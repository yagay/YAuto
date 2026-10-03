package com.yagay.yauto.feature.standard.data

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema

internal fun dataDescriptor(
    id: String,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    keywords: Set<String> = emptySet(),
    behaviors: Map<String, FieldBehavior> = emptyMap(),
): FeatureDescriptor = FeatureDescriptor(
    id = FeatureId(id),
    kind = FeatureKind.ACTION,
    title = title,
    description = description,
    category = FeatureCategory.VARIABLE,
    fields = fields,
    keywords = keywords,
    fieldBehaviors = behaviors,
)

internal fun variableTextBehavior(default: ConfigValue? = null): FieldBehavior =
    FieldBehavior(defaultValue = default, supportsVariables = true)

internal fun FeatureExecutionContext.storeValue(name: String, value: ConfigValue): ActionExecutionResult {
    variables.set(name, value)
    return ActionExecutionResult(true, value)
}

internal fun FeatureExecutionContext.storeText(name: String, value: String): ActionExecutionResult =
    storeValue(name, ConfigValue.StringValue(value))

internal fun FeatureExecutionContext.storeNumber(name: String, value: Double): ActionExecutionResult =
    storeValue(name, ConfigValue.NumberValue(value))

internal fun FeatureRef.number(key: String, default: Double = 0.0): Double =
    config[key].numberOrNull() ?: default

internal fun FeatureRef.destination(): String = config.string("resultVariable")

internal fun ConfigValue.asDataText(): String = when (this) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asDataText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.asDataText()}" }
}
