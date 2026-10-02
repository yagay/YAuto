package com.yagay.yauto.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.yagay.yauto.core.registry.FeatureDescriptor

@Composable
internal fun localizedFeatureTitle(descriptor: FeatureDescriptor): String = localizedFeatureText(
    key = "feature_${resourceKey(descriptor.id.value)}_title",
    fallback = descriptor.title,
)

@Composable
internal fun localizedFeatureDescriptionShared(descriptor: FeatureDescriptor): String = localizedFeatureText(
    key = "feature_${resourceKey(descriptor.id.value)}_description",
    fallback = descriptor.description,
)

@Composable
internal fun localizedFieldLabelShared(descriptorId: String, fieldKey: String, fallback: String): String = localizedFeatureText(
    key = "feature_${resourceKey(descriptorId)}_field_${resourceKey(fieldKey)}",
    fallback = fallback,
)

@Composable
internal fun localizedChoiceOptionShared(
    descriptorId: String,
    fieldKey: String,
    option: String,
): String = localizedFeatureText(
    key = "feature_${resourceKey(descriptorId)}_field_${resourceKey(fieldKey)}_option_${resourceKey(option)}",
    fallback = option,
)

@Composable
private fun localizedFeatureText(key: String, fallback: String): String {
    val context = LocalContext.current
    val id = remember(key, context.packageName) {
        context.resources.getIdentifier(key, "string", context.packageName)
    }
    return if (id != 0) stringResource(id) else fallback
}

private fun resourceKey(value: String): String = value.lowercase().map {
    if (it.isLetterOrDigit()) it else '_'
}.joinToString("").replace(Regex("_+"), "_").trim('_')
