package com.yagay.yauto.platform.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import android.text.format.DateFormat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.resolveVariables
import java.util.TimeZone

/** Native environment/data helpers that complement the existing device, state and resource packs. */
class AndroidNativeEnvironmentFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.native_environment"
    private val context = context.applicationContext
    private val clipboard = this.context.getSystemService(ClipboardManager::class.java)
    private val battery = this.context.getSystemService(BatteryManager::class.java)
    private val resolver = this.context.contentResolver

    override fun install(registry: FeatureRegistry) {
        registerClipboardRead(registry)
        registerClipboardWrite(registry)

        numericPair(
            registry,
            key = "battery_current",
            title = "Battery current",
            description = "Match the instantaneous battery current reported by Android in microamps",
            minAllowed = -20_000_000.0,
            maxAllowed = 20_000_000.0,
            keywords = setOf("battery", "current", "microamp", "charging"),
        ) { batteryIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }

        numericPair(
            registry,
            key = "battery_charge_counter",
            title = "Battery charge counter",
            description = "Match the remaining battery charge counter reported by Android in microamp-hours",
            minAllowed = 0.0,
            maxAllowed = 100_000_000.0,
            keywords = setOf("battery", "charge counter", "capacity", "uah"),
        ) { batteryIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }

        numericPair(
            registry,
            key = "battery_energy_counter",
            title = "Battery energy counter",
            description = "Match the remaining battery energy counter reported by Android in nanowatt-hours",
            minAllowed = 0.0,
            maxAllowed = 1_000_000_000_000.0,
            keywords = setOf("battery", "energy counter", "nwh", "capacity"),
        ) { batteryLongPropertyOrNull(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER) }

        booleanPair(
            registry,
            key = "clock_24h",
            title = "24-hour clock",
            description = "Check whether Android is currently configured to use 24-hour time",
            category = FeatureCategory.SYSTEM,
            keywords = setOf("clock", "24 hour", "time format"),
        ) { DateFormat.is24HourFormat(context) }

        textContainsPair(
            registry,
            key = "timezone",
            title = "Time zone",
            description = "Match the current Android time-zone ID",
            fieldKey = "zoneContains",
            fieldLabel = "Time-zone ID contains",
            keywords = setOf("timezone", "time zone", "region", "clock"),
        ) { TimeZone.getDefault().id }

        textContainsPair(
            registry,
            key = "locale",
            title = "System locale",
            description = "Match any configured Android locale language tag",
            fieldKey = "languageTagContains",
            fieldLabel = "Language tag contains",
            keywords = setOf("locale", "language", "region", "language tag"),
        ) {
            val locales = context.resources.configuration.locales
            (0 until locales.size()).joinToString(",") { locales[it].toLanguageTag() }
        }

        booleanPair(
            registry,
            key = "developer_options",
            title = "Developer options enabled",
            description = "Check whether Android developer options are enabled",
            category = FeatureCategory.SYSTEM,
            keywords = setOf("developer options", "development settings", "debug"),
        ) { Settings.Global.getInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1 }

        booleanPair(
            registry,
            key = "adb_enabled",
            title = "ADB enabled",
            description = "Check whether Android Debug Bridge is enabled in system settings",
            category = FeatureCategory.SYSTEM,
            keywords = setOf("adb", "usb debugging", "wireless debugging", "developer"),
        ) { Settings.Global.getInt(resolver, "adb_enabled", 0) == 1 }
    }

    private fun registerClipboardRead(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.read"), FeatureKind.ACTION,
                "Read clipboard text", "Read Android's current primary clipboard text into a YAuto variable when platform privacy rules allow access",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store clipboard text", true)),
                keywords = setOf("clipboard", "paste", "read", "text"), ownerPackId = id,
            )
        ) { feature, ctx ->
            runCatching {
                val clip = clipboard.primaryClip
                val text = if (clip != null && clip.itemCount > 0) {
                    clip.getItemAt(0).coerceToText(context)?.toString().orEmpty()
                } else ""
                val output = ConfigValue.StringValue(text)
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun registerClipboardWrite(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.write"), FeatureKind.ACTION,
                "Write clipboard text", "Replace Android's primary clipboard with text after resolving YAuto variables",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("text", "Clipboard text", true, multiline = true),
                    FieldSchema.Text("label", "Clipboard label"),
                ),
                keywords = setOf("clipboard", "copy", "write", "text"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val label = feature.config.string("label").resolveVariables(ctx.variables).ifBlank { "YAuto" }
            runCatching {
                clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
                ActionExecutionResult(true, ConfigValue.StringValue(text))
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun numericPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        minAllowed: Double,
        maxAllowed: Double,
        keywords: Set<String>,
        query: () -> Double?,
    ) {
        pair(
            registry = registry,
            key = key,
            title = title,
            description = description,
            category = FeatureCategory.DEVICE,
            fields = listOf(
                FieldSchema.Number("min", "Minimum value", min = minAllowed, max = maxAllowed),
                FieldSchema.Number("max", "Maximum value", min = minAllowed, max = maxAllowed),
            ),
            keywords = keywords,
        ) { feature ->
            val actual = query() ?: return@pair false
            val min = feature.config["min"].numberOrNull()
            val max = feature.config["max"].numberOrNull()
            matchesBoundedNumber(actual, min, max, minAllowed, maxAllowed)
        }
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        keywords: Set<String>,
        query: () -> Boolean,
    ) {
        pair(
            registry, key, title, description, category,
            listOf(FieldSchema.Toggle("value", "Enabled / true")), keywords,
        ) { feature -> query() == feature.config.boolean("value", true) }
    }

    private fun textContainsPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        fieldKey: String,
        fieldLabel: String,
        keywords: Set<String>,
        query: () -> String,
    ) {
        pair(
            registry, key, title, description, FeatureCategory.SYSTEM,
            listOf(FieldSchema.Text(fieldKey, fieldLabel, true)), keywords,
        ) { feature ->
            val expected = feature.config.string(fieldKey).trim()
            expected.isNotEmpty() && query().contains(expected, ignoreCase = true)
        }
    }

    private fun pair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        evaluate: (com.yagay.yauto.core.model.FeatureRef) -> Boolean,
    ) {
        val evaluator = ConditionEvaluator { feature, _ -> runCatching { evaluate(feature) }.getOrDefault(false) }
        val state = FeatureDescriptor(
            FeatureId("android.state.$key"), FeatureKind.STATE,
            title, description, category,
            fields = fields, keywords = keywords, ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.$key"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun batteryIntProperty(propertyId: Int): Double? {
        val value = battery.getIntProperty(propertyId)
        return value.takeUnless { it == Int.MIN_VALUE }?.toDouble()
    }

    private fun batteryLongPropertyOrNull(propertyId: Int): Double? {
        val value = battery.getLongProperty(propertyId)
        return value.takeUnless { it == Long.MIN_VALUE }?.toDouble()
    }
}

internal fun matchesBoundedNumber(
    actual: Double,
    min: Double?,
    max: Double?,
    allowedMin: Double,
    allowedMax: Double,
): Boolean {
    if (!actual.isFinite() || actual !in allowedMin..allowedMax) return false
    val safeMin = min?.takeIf { it.isFinite() } ?: allowedMin
    val safeMax = max?.takeIf { it.isFinite() } ?: allowedMax
    if (safeMin !in allowedMin..allowedMax || safeMax !in safeMin..allowedMax) return false
    return actual in safeMin..safeMax
}
