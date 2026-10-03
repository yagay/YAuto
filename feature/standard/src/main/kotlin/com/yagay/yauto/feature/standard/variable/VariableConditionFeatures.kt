package com.yagay.yauto.feature.standard.variable

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

internal object VariableConditionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        conditionFeature(variableEqualsDescriptor(FeatureKind.CONDITION, "variable.equals", "Variable equals")) { feature, context ->
            variableEquals(feature, context)
        },
        stateFeature(variableEqualsDescriptor(FeatureKind.STATE, "variable.state.equals", "Variable state equals")) { feature, context ->
            variableEquals(feature, context)
        },
        conditionFeature(
            variableDescriptor(
                "variable.exists",
                "Variable exists",
                "Check that a variable is present and not null",
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
                kind = FeatureKind.CONDITION,
            )
        ) { feature, context ->
            val value = context.variables.get(feature.config.string("name"))
            value != null && value != ConfigValue.NullValue
        },
        conditionFeature(
            variableDescriptor(
                "variable.number.compare",
                "Compare number",
                "Compare a variable with a number",
                fields = listOf(
                    FieldSchema.Variable("name", "Variable", true),
                    FieldSchema.Choice("operator", "Operator", true, comparisonOperators),
                    FieldSchema.Number("value", "Value", true),
                ),
                behaviors = mapOf("operator" to FieldBehavior(defaultValue = ConfigValue.StringValue("=="))),
                kind = FeatureKind.CONDITION,
            )
        ) { feature, context ->
            val left = context.variables.get(feature.config.string("name")).asNumber()
            val right = feature.config["value"].asNumber()
            left != null && right != null && compareNumbers(left, right, feature.config.string("operator", "=="))
        },
        conditionFeature(
            variableDescriptor(
                "variable.list.size",
                "List size",
                "Compare the size of a list variable",
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Choice("operator", "Operator", true, comparisonOperators),
                    FieldSchema.Number("value", "Size", true, min = 0.0),
                ),
                behaviors = mapOf(
                    "operator" to FieldBehavior(defaultValue = ConfigValue.StringValue("==")),
                    "value" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                ),
                kind = FeatureKind.CONDITION,
            )
        ) { feature, context ->
            val size = (context.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value?.size?.toDouble()
            val right = feature.config["value"].asNumber()
            size != null && right != null && compareNumbers(size, right, feature.config.string("operator", "=="))
        },
    )

    private fun variableEqualsDescriptor(kind: FeatureKind, featureId: String, title: String) = variableDescriptor(
        featureId = featureId,
        title = title,
        description = "Compare a variable with a value",
        fields = listOf(
            FieldSchema.Variable("name", "Variable", true),
            FieldSchema.Text("value", "Value"),
        ),
        behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
        kind = kind,
    )
}
