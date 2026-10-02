package com.yagay.yauto.ui.editor

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.ui.design.R as TextR
import java.util.Locale

internal class FeatureTextResolver(private val context: Context) {
    private val locale: Locale
        get() = context.resources.configuration.locales[0] ?: Locale.getDefault()
    private val chinese: Boolean
        get() = locale.language.equals("zh", ignoreCase = true)

    fun title(descriptor: FeatureDescriptor): String =
        resource("feature_${resourceKey(descriptor.id.value)}_title")
            ?: phrase(descriptor.title)
            ?: if (chinese) genericTitle(descriptor) else descriptor.title

    fun description(descriptor: FeatureDescriptor): String =
        resource("feature_${resourceKey(descriptor.id.value)}_description")
            ?: if (chinese) context.getString(TextR.string.feature_generic_description_format, title(descriptor))
            else descriptor.description

    fun fieldLabel(descriptorId: String, field: FieldSchema): String =
        resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(field.key)}")
            ?: phrase(field.label)
            ?: if (chinese) context.getString(TextR.string.feature_generic_parameter) else field.label

    fun choiceOption(descriptorId: String, fieldKey: String, option: String): String =
        resource("feature_${resourceKey(descriptorId)}_field_${resourceKey(fieldKey)}_option_${resourceKey(option)}")
            ?: phrase(option)
            ?: if (chinese) context.getString(TextR.string.feature_generic_option) else option

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

    private fun phrase(text: String): String? {
        if (!chinese) return null
        return resource("feature_phrase_${resourceKey(text)}")
    }

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

internal fun resourceKey(value: String): String = value.lowercase(Locale.ROOT).map {
    if (it.isLetterOrDigit()) it else '_'
}.joinToString("").replace(Regex("_+"), "_").trim('_')
