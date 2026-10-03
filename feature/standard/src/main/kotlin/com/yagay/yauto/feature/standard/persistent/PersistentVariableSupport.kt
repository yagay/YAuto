package com.yagay.yauto.feature.standard.persistent

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.VariableAccess
import com.yagay.yauto.core.registry.resolveVariables

internal fun persistentDescriptor(
    id: String,
    kind: FeatureKind,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    keywords: Set<String>,
    behaviors: Map<String, FieldBehavior> = emptyMap(),
): FeatureDescriptor = FeatureDescriptor(
    id = FeatureId(id),
    kind = kind,
    title = title,
    description = description,
    category = FeatureCategory.VARIABLE,
    fields = fields,
    keywords = keywords,
    fieldBehaviors = behaviors,
)

internal val persistentValueFields: List<FieldSchema> = listOf(
    FieldSchema.Text("name", "Variable name", true),
    FieldSchema.Variable("sourceVariable", "Source runtime variable"),
    FieldSchema.Choice("valueType", "Value type", options = listOf("text", "number", "boolean", "null")),
    FieldSchema.Text("value", "Value", multiline = true),
)

internal val persistentValueBehaviors: Map<String, FieldBehavior> = mapOf(
    "name" to FieldBehavior(supportsVariables = true),
    "valueType" to FieldBehavior(defaultValue = ConfigValue.StringValue("text")),
    "value" to FieldBehavior(supportsVariables = true),
)

internal fun persistentConfiguredValue(feature: FeatureRef, variables: VariableAccess): ConfigValue? {
    val raw = feature.config.string("value").resolveVariables(variables)
    return when (feature.config.string("valueType", "text")) {
        "number" -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(ConfigValue::NumberValue)
        "boolean" -> when (raw.trim().lowercase()) {
            "true", "1", "yes", "on" -> ConfigValue.BooleanValue(true)
            "false", "0", "no", "off" -> ConfigValue.BooleanValue(false)
            else -> null
        }
        "null" -> ConfigValue.NullValue
        else -> ConfigValue.StringValue(raw)
    }
}
