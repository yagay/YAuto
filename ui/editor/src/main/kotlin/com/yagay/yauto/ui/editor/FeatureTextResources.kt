package com.yagay.yauto.ui.editor

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.normalizeMacroDroidPickerCategory
import com.yagay.yauto.ui.design.R as TextR
import java.util.Locale

internal data class FeatureDisplayText(
    val descriptor: FeatureDescriptor,
    val title: String,
    val description: String,
)

internal class FeatureTextResolver(private val context: Context) {
    private val locale: Locale
        get() = context.resources.configuration.locales[0] ?: Locale.getDefault()


    // The app bundles Simplified Chinese translations under values-zh-rCN.
    // Without this fallback, a zh-HK / zh-TW / plain zh locale would receive
    // English text even when the exact Chinese feature name is translated.
    // Only feature editor text is resolved through this context; app locale,
    // feature IDs and user settings remain unchanged.
    private val localizedContext: Context by lazy {
        if (locale.language == Locale.CHINESE.language && locale.country != Locale.CHINA.country) {
            val config = Configuration(context.resources.configuration)
            config.setLocale(Locale.SIMPLIFIED_CHINESE)
            context.createConfigurationContext(config)
        } else {
            context
        }
    }

    private val resourceIdCache = HashMap<String, Int>()
    private val titleCache = HashMap<String, String>()
    private val descriptionCache = HashMap<String, String>()
    private val fieldLabelCache = HashMap<String, String>()
    private val choiceOptionCache = HashMap<String, String>()
    private val searchTextCache = HashMap<String, String>()

    fun title(descriptor: FeatureDescriptor): String =
        titleCache.getOrPut(descriptor.id.value) {
            resource("feature_display_${resourceKey(descriptor.id.value)}_title")
                ?: resource("macro_feature_${resourceKey(descriptor.id.value)}_title")
                ?: resource("shortx_feature_${resourceKey(descriptor.id.value)}_title")
                ?: resource("feature_${resourceKey(descriptor.id.value)}_title")
                ?: sourceAlignedFeatureTitle(descriptor.id.value)?.let(localizedContext::getString)
                ?: phrase(descriptor.title)
                ?: genericTitle(descriptor)
        }

    /** A mode parameter label, not a new or numbered feature title. */
    fun implementationLabel(featureId: String): String? =
        resource("feature_variant_${resourceKey(featureId)}")

    fun description(descriptor: FeatureDescriptor): String =
        descriptionCache.getOrPut(descriptor.id.value) {
            resource("feature_${resourceKey(descriptor.id.value)}_description")
                ?: phrase(descriptor.description)
                ?: localizedContext.getString(TextR.string.feature_generic_description_format, title(descriptor))
        }

    fun displayText(descriptor: FeatureDescriptor): FeatureDisplayText =
        FeatureDisplayText(descriptor, title(descriptor), description(descriptor))

    fun fieldLabel(descriptorId: String, field: FieldSchema): String =
        fieldLabelCache.getOrPut("$descriptorId|${field.key}") {
            resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(field.key)}")
                ?: phrase(field.label)
                ?: localizedContext.getString(TextR.string.feature_generic_parameter)
        }

    fun choiceOption(descriptorId: String, fieldKey: String, option: String): String =
        choiceOptionCache.getOrPut("$descriptorId|$fieldKey|$option") {
            resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(fieldKey)}_option_${resourceKey(option)}")
                ?: phrase(option)
                ?: localizedContext.getString(TextR.string.feature_generic_option)
        }

    fun searchText(descriptor: FeatureDescriptor): String =
        searchTextCache.getOrPut(descriptor.id.value) {
            buildList {
                add(title(descriptor))
                add(description(descriptor))
                add(descriptor.title)
                add(descriptor.description)
                add(descriptor.id.value)
                val semanticCategory = catalogCategory(normalizeMacroDroidPickerCategory(descriptor.kind, descriptor.pickerCategory), descriptor.kind)
                add(localizedContext.getString(semanticCategory.titleRes))
                add(localizedContext.getString(semanticCategory.subtitleRes))
                addAll(descriptor.keywords)
                descriptor.fields.forEach { field ->
                    add(fieldLabel(descriptor.id.value, field))
                    if (field is FieldSchema.Choice) {
                        field.options.forEach { add(choiceOption(descriptor.id.value, field.key, it)) }
                    }
                }
            }.joinToString("\n")
        }

    fun matches(descriptor: FeatureDescriptor, query: String): Boolean =
        query.isBlank() || searchText(descriptor).contains(query, ignoreCase = true)

    private fun phrase(text: String): String? =
        resource("feature_phrase_${resourceKey(text)}")

    private fun genericTitle(descriptor: FeatureDescriptor): String {
        // A category-only fallback gave *every* untranslated action in that
        // category the same visible name (e.g. "Device feature").
        // Preserve a meaningful source title until its exact localized entry
        // is reviewed; never collapse unrelated feature IDs into that label.
        val sourceName = descriptiveFallbackName(descriptor.title, descriptor.id.value)
        if (locale.language != Locale.CHINESE.language) return sourceName
        val category = catalogCategory(
            normalizeMacroDroidPickerCategory(descriptor.kind, descriptor.pickerCategory),
            descriptor.kind,
        )
        return localizedContext.getString(
            TextR.string.feature_generic_distinct_title_format,
            localizedContext.getString(category.titleRes),
            sourceName,
        )
    }

    private fun resource(name: String): String? {
        val id = resourceIdCache.getOrPut(name) {
            localizedContext.resources.getIdentifier(name, "string", localizedContext.packageName)
        }
        return if (id != 0) localizedContext.getString(id) else null
    }
}

/**
 * Never replace all unknown titles with a category placeholder.
 * Use the authored descriptor label, or a readable stable ID when blank.
 * Localized resource translations and source-approved names still take precedence.
 */
internal fun descriptiveFallbackName(authoredTitle: String, featureId: String): String {
    val candidate = authoredTitle.trim()
    if (candidate.isNotEmpty()) return candidate
    return featureId.replace('.', ' ').replace('_', ' ').trim().ifEmpty { featureId }
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
