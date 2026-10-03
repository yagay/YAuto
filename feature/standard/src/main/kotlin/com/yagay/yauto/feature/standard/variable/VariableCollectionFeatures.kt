package com.yagay.yauto.feature.standard.variable

import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*

internal object VariableCollectionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            variableDescriptor(
                "variable.list.append",
                "Append to list",
                "Append a value to a list variable",
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Text("value", "Value", true),
                ),
                keywords = setOf("array", "list", "append"),
                behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = (context.variables.get(name) as? ConfigValue.ListValue)?.value.orEmpty()
            val value = (feature.config["value"] ?: ConfigValue.StringValue("")).resolveVariables(context.variables)
            val output = ConfigValue.ListValue(current + value)
            context.variables.set(name, output)
            ActionExecutionResult(true, output)
        },
        actionFeature(
            variableDescriptor(
                "variable.list.get",
                "Get list item",
                "Read a list item by zero-based index",
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Number("index", "Index", true, min = 0.0),
                    FieldSchema.Text("resultVariable", "Destination variable", true),
                ),
                behaviors = mapOf("index" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0))),
            )
        ) { feature, context ->
            val values = (context.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value
            if (values == null) {
                ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            } else {
                val index = feature.config["index"].asNumber()?.toInt() ?: 0
                val value = values.getOrNull(index)
                if (value == null) {
                    ActionExecutionResult(false, message = userText("feature.list_index_out_of_bounds"))
                } else {
                    context.variables.set(feature.config.string("resultVariable"), value)
                    ActionExecutionResult(true, value)
                }
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.list.remove",
                "Remove list item",
                "Remove a list item by zero-based index",
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Number("index", "Index", true, min = 0.0),
                ),
                behaviors = mapOf("index" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0))),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            val values = (context.variables.get(name) as? ConfigValue.ListValue)?.value?.toMutableList()
            if (values == null) {
                ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            } else {
                val index = feature.config["index"].asNumber()?.toInt() ?: 0
                if (index !in values.indices) {
                    ActionExecutionResult(false, message = userText("feature.list_index_out_of_bounds"))
                } else {
                    values.removeAt(index)
                    val output = ConfigValue.ListValue(values)
                    context.variables.set(name, output)
                    ActionExecutionResult(true, output)
                }
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.object.put",
                "Set object field",
                "Set a key on an object/map variable",
                fields = listOf(
                    FieldSchema.Variable("name", "Object variable", true),
                    FieldSchema.Text("key", "Key", true),
                    FieldSchema.Text("value", "Value"),
                ),
                keywords = setOf("map", "object", "dictionary"),
                behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val name = feature.config.string("name")
            val key = feature.config.string("key")
            if (key.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.key_empty"))
            } else {
                val current = (context.variables.get(name) as? ConfigValue.ObjectValue)?.value.orEmpty()
                val value = (feature.config["value"] ?: ConfigValue.NullValue).resolveVariables(context.variables)
                val output = ConfigValue.ObjectValue(current + (key to value))
                context.variables.set(name, output)
                ActionExecutionResult(true, output)
            }
        },
        actionFeature(
            variableDescriptor(
                "variable.object.get",
                "Get object field",
                "Read a key from an object/map variable",
                fields = listOf(
                    FieldSchema.Variable("name", "Object variable", true),
                    FieldSchema.Text("key", "Key", true),
                    FieldSchema.Text("resultVariable", "Destination variable", true),
                ),
            )
        ) { feature, context ->
            val objectValue = (context.variables.get(feature.config.string("name")) as? ConfigValue.ObjectValue)?.value
            if (objectValue == null) {
                ActionExecutionResult(false, message = userText("feature.variable_not_object"))
            } else {
                val value = objectValue[feature.config.string("key")] ?: ConfigValue.NullValue
                context.variables.set(feature.config.string("resultVariable"), value)
                ActionExecutionResult(true, value)
            }
        },
    )
}
