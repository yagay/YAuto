package com.yagay.yauto.feature.standard.variable

import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*

internal object VariableScalarFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            variableDescriptor(
                "variable.set",
                "Set variable",
                "Set a runtime variable",
                fields = listOf(
                    FieldSchema.Text("name", "Variable name", true),
                    FieldSchema.Text("value", "Value"),
                ),
                behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                context.variables.set(
                    name,
                    (feature.config["value"] ?: ConfigValue.NullValue).resolveVariables(context.variables),
                )
                ActionExecutionResult(true)
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.clear",
                "Clear variable",
                "Clear a runtime variable",
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                context.variables.set(name, ConfigValue.NullValue)
                ActionExecutionResult(true)
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.copy",
                "Copy variable",
                "Copy one runtime variable to another",
                fields = listOf(
                    FieldSchema.Variable("source", "Source variable", true),
                    FieldSchema.Text("destination", "Destination variable", true),
                ),
            )
        ) { feature, context ->
            val destination = feature.config.string("destination")
            if (destination.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            } else {
                val value = context.variables.get(feature.config.string("source")) ?: ConfigValue.NullValue
                context.variables.set(destination, value)
                ActionExecutionResult(true, value)
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.increment",
                "Increment variable",
                "Add a number to a variable",
                fields = listOf(
                    FieldSchema.Variable("name", "Variable", true),
                    FieldSchema.Number("amount", "Amount"),
                ),
                behaviors = mapOf("amount" to FieldBehavior(defaultValue = ConfigValue.NumberValue(1.0))),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                val current = context.variables.get(name).asNumber() ?: 0.0
                val amount = feature.config["amount"].asNumber() ?: 1.0
                val value = ConfigValue.NumberValue(current + amount)
                context.variables.set(name, value)
                ActionExecutionResult(true, value)
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.toggle",
                "Toggle variable",
                "Invert a boolean variable",
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                val value = ConfigValue.BooleanValue(!(context.variables.get(name).booleanOrNull() ?: false))
                context.variables.set(name, value)
                ActionExecutionResult(true, value)
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.text.replace",
                "Replace text",
                "Replace literal or regular-expression matches in text",
                fields = listOf(
                    FieldSchema.Variable("name", "Text variable", true),
                    FieldSchema.Text("find", "Find", true),
                    FieldSchema.Text("replacement", "Replacement"),
                    FieldSchema.Toggle("regex", "Regular expression"),
                ),
                keywords = setOf("text", "replace", "regex"),
                behaviors = mapOf(
                    "find" to FieldBehavior(supportsVariables = true),
                    "replacement" to FieldBehavior(supportsVariables = true),
                    "regex" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false)),
                ),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            val source = context.variables.get(name).asText()
            val find = feature.config.string("find").resolveVariables(context.variables)
            val replacement = feature.config.string("replacement").resolveVariables(context.variables)
            runCatching {
                if (feature.config.boolean("regex")) Regex(find).replace(source, replacement)
                else source.replace(find, replacement)
            }.fold(
                onSuccess = { outputText ->
                    val output = ConfigValue.StringValue(outputText)
                    context.variables.set(name, output)
                    ActionExecutionResult(true, output)
                },
                onFailure = { error ->
                    ActionExecutionResult(
                        false,
                        message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName),
                    )
                },
            )
        },
    )
}
