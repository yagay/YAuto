package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*

class VariableFeaturePack : FeaturePack {
    override val id: String = "standard.variable"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.set"), FeatureKind.ACTION, "Set variable", "Set a runtime variable",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Text("name", "Variable name", true), FieldSchema.Text("value", "Value")),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) ActionExecutionResult(false, message = "Variable name is empty")
            else {
                ctx.variables.set(name, feature.config["value"] ?: ConfigValue.NullValue)
                ActionExecutionResult(true)
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.clear"), FeatureKind.ACTION, "Clear variable", "Clear a runtime variable",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) ActionExecutionResult(false, message = "Variable name is empty")
            else {
                ctx.variables.set(name, ConfigValue.NullValue)
                ActionExecutionResult(true)
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.increment"), FeatureKind.ACTION, "Increment variable", "Add a number to a variable",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true), FieldSchema.Number("amount", "Amount")),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) return@registerAction ActionExecutionResult(false, message = "Variable name is empty")
            val current = ctx.variables.get(name).asNumber() ?: 0.0
            val amount = feature.config["amount"].asNumber() ?: 1.0
            val value = ConfigValue.NumberValue(current + amount)
            ctx.variables.set(name, value)
            ActionExecutionResult(true, value)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.toggle"), FeatureKind.ACTION, "Toggle variable", "Invert a boolean variable",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) return@registerAction ActionExecutionResult(false, message = "Variable name is empty")
            val value = ConfigValue.BooleanValue(!(ctx.variables.get(name).booleanOrNull() ?: false))
            ctx.variables.set(name, value)
            ActionExecutionResult(true, value)
        }

        registry.registerCondition(variableEqualsDescriptor(FeatureKind.CONDITION, "variable.equals", "Variable equals")) { feature, ctx ->
            variableEquals(feature, ctx)
        }
        registry.registerState(variableEqualsDescriptor(FeatureKind.STATE, "variable.state.equals", "Variable state equals")) { feature, ctx ->
            variableEquals(feature, ctx)
        }

        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("variable.exists"), FeatureKind.CONDITION, "Variable exists", "Check that a variable is present and not null",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val value = ctx.variables.get(feature.config.string("name"))
            value != null && value != ConfigValue.NullValue
        }

        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("variable.number.compare"), FeatureKind.CONDITION, "Compare number", "Compare a variable with a number",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Variable("name", "Variable", true),
                    FieldSchema.Choice("operator", "Operator", true, listOf("==", "!=", ">", ">=", "<", "<=")),
                    FieldSchema.Number("value", "Value", true),
                ),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val left = ctx.variables.get(feature.config.string("name")).asNumber() ?: return@registerCondition false
            val right = feature.config["value"].asNumber() ?: return@registerCondition false
            when (feature.config.string("operator", "==")) {
                "==" -> left == right
                "!=" -> left != right
                ">" -> left > right
                ">=" -> left >= right
                "<" -> left < right
                "<=" -> left <= right
                else -> false
            }
        }
    }

    private fun variableEqualsDescriptor(kind: FeatureKind, featureId: String, title: String) = FeatureDescriptor(
        FeatureId(featureId), kind, title, "Compare a variable with a value", FeatureCategory.VARIABLE,
        fields = listOf(FieldSchema.Variable("name", "Variable", true), FieldSchema.Text("value", "Value")),
        ownerPackId = id,
    )

    private fun variableEquals(feature: FeatureRef, ctx: FeatureExecutionContext): Boolean {
        val actual = ctx.variables.get(feature.config.string("name"))
        val expected = feature.config["value"]
        return if (expected != null) actual == expected else actual.asText() == feature.config.string("value")
    }
}

private fun ConfigValue?.asNumber(): Double? = when (this) {
    is ConfigValue.NumberValue -> value
    is ConfigValue.StringValue -> value.toDoubleOrNull()
    else -> null
}

private fun ConfigValue?.asText(): String = when (this) {
    null, ConfigValue.NullValue -> "null"
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.toString()
    is ConfigValue.ObjectValue -> value.toString()
}
