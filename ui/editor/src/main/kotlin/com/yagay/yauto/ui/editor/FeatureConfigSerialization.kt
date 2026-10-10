package com.yagay.yauto.ui.editor

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.UserHandle
import android.os.UserManager
import android.provider.CalendarContract
import android.telephony.SubscriptionManager
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.ui.design.CapabilityBadge
import com.yagay.yauto.ui.design.R as TextR
import com.yagay.yauto.ui.design.localizedList
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Generic configuration screen driven entirely by [FeatureDescriptor]. */

/**
 * Serialize only fields applicable to the currently selected mode. Retain unknown imported
 * config keys for forward compatibility, but do not leak hidden/disabled schema fields when
 * the user changes an operation's settings.
 */
internal fun buildEditedFeatureConfig(
    descriptor: FeatureDescriptor,
    initial: FeatureRef?,
    initialWithDefaults: FeatureRef,
    values: Map<String, String>,
    locale: Locale,
    hardwareIdentityConfig: Map<String, ConfigValue> = emptyMap(),
): Map<String, ConfigValue> {
    val typedValues = valuesAsConfig(descriptor.fields, values, locale)
    val config = initial?.config.orEmpty().toMutableMap()
    // Old steps keep their saved route until edited; saving migrates to auto.
    config.remove(FEATURE_METHOD_CONFIG_KEY)
    config.remove(FEATURE_BACKEND_CONFIG_KEY)
    descriptor.fields.forEach { field ->
        val prior = config.remove(field.key)
        val behavior = descriptor.fieldBehavior(field.key)
        if (behavior.visibleWhen?.matches(typedValues) == false ||
            behavior.enabledWhen?.matches(typedValues) == false
        ) return@forEach

        val raw = values[field.key].orEmpty()
        if (prior != null && raw == editorConfigValueText(initialWithDefaults.config[field.key], locale)) {
            config[field.key] = prior
        } else {
            parseFieldValue(field, raw, locale)?.let { config[field.key] = it }
        }
    }
    config.keys.filter { it.startsWith("hardwareIdentity") }.forEach(config::remove)
    config.putAll(hardwareIdentityConfig)
    return config
}

private fun valuesAsConfig(
    fields: List<FieldSchema>,
    values: Map<String, String>,
    locale: Locale,
): Map<String, ConfigValue> = buildMap {
    fields.forEach { field ->
        parseFieldValue(field, values[field.key].orEmpty(), locale)?.let { put(field.key, it) }
    }
}

private fun parseFieldValue(field: FieldSchema, raw: String, locale: Locale): ConfigValue? = when (field) {
    is FieldSchema.Toggle -> ConfigValue.BooleanValue(raw.toBooleanStrictOrNull() ?: false)
    is FieldSchema.Number, is FieldSchema.Duration -> parseLocalizedDouble(raw, locale)?.let(ConfigValue::NumberValue)
    else -> if (raw.isNotEmpty() || field.required) ConfigValue.StringValue(raw) else null
}

internal fun fieldValid(field: FieldSchema, raw: String, locale: Locale): Boolean = when (field) {
    is FieldSchema.Number -> (raw.isBlank() && !field.required) || parseLocalizedDouble(raw, locale)?.let { number ->
        number.isFinite() && (field.min?.let { number >= it } ?: true) && (field.max?.let { number <= it } ?: true)
    } == true
    is FieldSchema.Duration -> (raw.isBlank() && !field.required) || parseLocalizedDouble(raw, locale)?.let {
        it.isFinite() && it >= 0
    } == true
    is FieldSchema.Choice -> (raw.isBlank() && !field.required) || raw in field.options
    is FieldSchema.Toggle -> true
    else -> !field.required || raw.isNotBlank()
}

