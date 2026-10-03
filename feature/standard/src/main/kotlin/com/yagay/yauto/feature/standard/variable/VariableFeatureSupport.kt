package com.yagay.yauto.feature.standard.variable

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

internal val comparisonOperators = listOf("==", "!=", ">", ">=", "<", "<=")

internal fun variableDescriptor(
    featureId: String,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    keywords: Set<String> = emptySet(),
    behaviors: Map<String, FieldBehavior> = emptyMap(),
    kind: FeatureKind = FeatureKind.ACTION,
): FeatureDescriptor = FeatureDescriptor(
    id = FeatureId(featureId),
    kind = kind,
    title = title,
    description = description,
    category = FeatureCategory.VARIABLE,
    fields = fields,
    keywords = keywords,
    fieldBehaviors = behaviors,
)

internal fun compareNumbers(left: Double, right: Double, operator: String): Boolean = when (operator) {
    "==" -> left == right
    "!=" -> left != right
    ">" -> left > right
    ">=" -> left >= right
    "<" -> left < right
    "<=" -> left <= right
    else -> false
}

internal fun ConfigValue?.asNumber(): Double? = when (this) {
    is ConfigValue.NumberValue -> value
    is ConfigValue.StringValue -> value.toDoubleOrNull()
    else -> null
}

internal fun ConfigValue?.asText(): String = when (this) {
    null, ConfigValue.NullValue -> "null"
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.asText()}" }
}

internal fun variableEquals(feature: FeatureRef, context: FeatureExecutionContext): Boolean {
    val actual = context.variables.get(feature.config.string("name"))
    val expected = feature.config["value"]?.resolveVariables(context.variables)
    return if (expected != null) actual == expected
    else actual.asText() == feature.config.string("value").resolveVariables(context.variables)
}
