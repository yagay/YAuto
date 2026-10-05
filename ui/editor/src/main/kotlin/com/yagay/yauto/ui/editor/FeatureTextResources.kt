package com.yagay.yauto.ui.editor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.ui.design.R as TextR
import java.util.Locale

internal class FeatureTextResolver(private val context: Context) {
    private val locale: Locale
        get() = context.resources.configuration.locales[0] ?: Locale.getDefault()

    fun title(descriptor: FeatureDescriptor): String =
        resource("feature_${resourceKey(descriptor.id.value)}_title")
            ?: phrase(descriptor.title)
            ?: descriptor.title.takeIf { it.isNotBlank() }
            ?: genericTitle(descriptor)

    fun description(descriptor: FeatureDescriptor): String =
        resource("feature_${resourceKey(descriptor.id.value)}_description")
            ?: phrase(descriptor.description)
            ?: descriptor.description.takeIf { it.isNotBlank() }
            ?: context.getString(TextR.string.feature_generic_description_format, title(descriptor))

    fun fieldLabel(descriptorId: String, field: FieldSchema): String =
        resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(field.key)}")
            ?: phrase(field.label)
            ?: field.label.takeIf { it.isNotBlank() }
            ?: context.getString(TextR.string.feature_generic_parameter)

    fun choiceOption(descriptorId: String, fieldKey: String, option: String): String =
        resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(fieldKey)}_option_${resourceKey(option)}")
            ?: phrase(option)
            ?: option.takeIf { it.isNotBlank() }
            ?: context.getString(TextR.string.feature_generic_option)

    fun matches(descriptor: FeatureDescriptor, query: String): Boolean {
        if (query.isBlank()) return true
        return buildList {
            add(title(descriptor))
            add(description(descriptor))
            add(descriptor.title)
            add(descriptor.description)
            add(descriptor.id.value)
            addAll(descriptor.keywords)
            descriptor.fields.forEach { field ->
                add(fieldLabel(descriptor.id.value, field))
                if (field is FieldSchema.Choice) {
                    field.options.forEach { add(choiceOption(descriptor.id.value, field.key, it)) }
                }
            }
        }.any { it.contains(query, ignoreCase = true) }
    }

    private fun phrase(text: String): String? =
        resource("feature_phrase_${resourceKey(text)}")

    private fun genericTitle(descriptor: FeatureDescriptor): String {
        val category = when (descriptor.category) {
            FeatureCategory.CORE -> context.getString(TextR.string.category_core)
            FeatureCategory.APP -> context.getString(TextR.string.category_app)
            FeatureCategory.DEVICE -> context.getString(TextR.string.category_device)
            FeatureCategory.NETWORK -> context.getString(TextR.string.category_network)
            FeatureCategory.DISPLAY -> context.getString(TextR.string.category_display)
            FeatureCategory.AUDIO -> context.getString(TextR.string.category_audio)
            FeatureCategory.NOTIFICATION -> context.getString(TextR.string.category_notification)
            FeatureCategory.FILE -> context.getString(TextR.string.category_file)
            FeatureCategory.VARIABLE -> context.getString(TextR.string.category_variable)
            FeatureCategory.FLOW -> context.getString(TextR.string.category_flow)
            FeatureCategory.UI_AUTOMATION -> context.getString(TextR.string.category_ui_automation)
            FeatureCategory.SYSTEM -> context.getString(TextR.string.category_system)
            FeatureCategory.SCRIPT -> context.getString(TextR.string.category_script)
            FeatureCategory.ADVANCED, FeatureCategory.COMPATIBILITY -> context.getString(TextR.string.category_advanced)
        }
        return context.getString(TextR.string.feature_generic_title_format, category)
    }

    private fun resource(name: String): String? {
        val id = context.resources.getIdentifier(name, "string", context.packageName)
        return if (id != 0) context.getString(id) else null
    }
}

@Composable
internal fun rememberFeatureTextResolver(): FeatureTextResolver {
    val context = LocalContext.current
    val localeTag = context.resources.configuration.locales[0]?.toLanguageTag().orEmpty()
    return remember(context, localeTag) { FeatureTextResolver(context) }
}

internal val localizedFeatureTitle: @Composable (FeatureDescriptor) -> String = { descriptor ->
    rememberFeatureTextResolver().title(descriptor)
}

@Composable
internal fun localizedFeatureDescriptionShared(descriptor: FeatureDescriptor): String =
    rememberFeatureTextResolver().description(descriptor)

@Composable
internal fun localizedFieldLabelShared(descriptorId: String, field: FieldSchema): String =
    rememberFeatureTextResolver().fieldLabel(descriptorId, field)

@Composable
internal fun localizedChoiceOptionShared(descriptorId: String, fieldKey: String, option: String): String =
    rememberFeatureTextResolver().choiceOption(descriptorId, fieldKey, option)

/**
 * Map.Entry.value is an open getter, so Kotlin cannot smart-cast repeated `entry.value` accesses.
 * The feature-summary branches already verify StringValue before reading this editor-only property.
 */
internal val ConfigValue.value: String
    get() = (this as? ConfigValue.StringValue)?.value.orEmpty()

internal fun resourceKey(value: String): String {
    val normalized = StringBuilder(value.length)
    var previousWasSeparator = false
    value.lowercase(Locale.ROOT).forEach { character ->
        if (character.isLetterOrDigit()) {
            normalized.append(character)
            previousWasSeparator = false
        } else if (normalized.isNotEmpty() && !previousWasSeparator) {
            normalized.append('_')
            previousWasSeparator = true
        }
    }
    while (normalized.isNotEmpty() && normalized.last() == '_') {
        normalized.setLength(normalized.length - 1)
    }
    if (normalized.isNotEmpty()) return normalized.toString()

    // Pure-symbol values such as ==, !=, >= and <= previously collapsed to the same empty key.
    // Encode their Unicode code points so every machine option has a deterministic resource key.
    val encoded = StringBuilder("symbol_")
    value.forEachIndexed { index, character ->
        if (index > 0) encoded.append('_')
        encoded.append(character.code.toString(16))
    }
    return encoded.toString()
}
