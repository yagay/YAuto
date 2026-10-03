package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*

class VariableFeaturePack : FeaturePack {
    override val id: String = "standard.variable"

    private val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            descriptor(
                "variable.set",
                "Set variable",
                "Set a runtime variable",
                fields = listOf(
                    FieldSchema.Text("name", "Variable name", true),
                    FieldSchema.Text("value", "Value"),
                ),
                behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                ctx.variables.set(name, (feature.config["value"] ?: ConfigValue.NullValue).resolveVariables(ctx.variables))
                ActionExecutionResult(true)
            }
        },
        actionFeature(
            descriptor(
                "variable.clear",
                "Clear variable",
                "Clear a runtime variable",
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                ctx.variables.set(name, ConfigValue.NullValue)
                ActionExecutionResult(true)
            }
        },
        actionFeature(
            descriptor(
                "variable.copy",
                "Copy variable",
                "Copy one runtime variable to another",
                fields = listOf(
                    FieldSchema.Variable("source", "Source variable", true),
                    FieldSchema.Text("destination", "Destination variable", true),
                ),
            )
        ) { feature, ctx ->
            val destination = feature.config.string("destination")
            if (destination.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            } else {
                val value = ctx.variables.get(feature.config.string("source")) ?: ConfigValue.NullValue
                ctx.variables.set(destination, value)
                ActionExecutionResult(true, value)
            }
        },
        actionFeature(
            descriptor(
                "variable.increment",
                "Increment variable",
                "Add a number to a variable",
                fields = listOf(
                    FieldSchema.Variable("name", "Variable", true),
                    FieldSchema.Number("amount", "Amount"),
                ),
                behaviors = mapOf("amount" to FieldBehavior(defaultValue = ConfigValue.NumberValue(1.0))),
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                val current = ctx.variables.get(name).asNumber() ?: 0.0
                val amount = feature.config["amount"].asNumber() ?: 1.0
                val value = ConfigValue.NumberValue(current + amount)
                ctx.variables.set(name, value)
                ActionExecutionResult(true, value)
            }
        },
        actionFeature(
            descriptor(
                "variable.toggle",
                "Toggle variable",
                "Invert a boolean variable",
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            } else {
                val value = ConfigValue.BooleanValue(!(ctx.variables.get(name).booleanOrNull() ?: false))
                ctx.variables.set(name, value)
                ActionExecutionResult(true, value)
            }
        },
        actionFeature(
            descriptor(
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
        ) { feature, ctx ->
            val name = feature.config.string("name")
            val current = (ctx.variables.get(name) as? ConfigValue.ListValue)?.value.orEmpty()
            val value = (feature.config["value"] ?: ConfigValue.StringValue("")).resolveVariables(ctx.variables)
            val output = ConfigValue.ListValue(current + value)
            ctx.variables.set(name, output)
            ActionExecutionResult(true, output)
        },
        actionFeature(
            descriptor(
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
        ) { feature, ctx ->
            val values = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value
            if (values == null) {
                ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            } else {
                val index = feature.config["index"].asNumber()?.toInt() ?: 0
                val value = values.getOrNull(index)
                if (value == null) {
                    ActionExecutionResult(false, message = userText("feature.list_index_out_of_bounds"))
                } else {
                    ctx.variables.set(feature.config.string("resultVariable"), value)
                    ActionExecutionResult(true, value)
                }
            }
        },
        actionFeature(
            descriptor(
                "variable.list.remove",
                "Remove list item",
                "Remove a list item by zero-based index",
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Number("index", "Index", true, min = 0.0),
                ),
                behaviors = mapOf("index" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0))),
            )
        ) { feature, ctx ->
            val name = feature.config.string("name")
            val values = (ctx.variables.get(name) as? ConfigValue.ListValue)?.value?.toMutableList()
            if (values == null) {
                ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            } else {
                val index = feature.config["index"].asNumber()?.toInt() ?: 0
                if (index !in values.indices) {
                    ActionExecutionResult(false, message = userText("feature.list_index_out_of_bounds"))
                } else {
                    values.removeAt(index)
                    val output = ConfigValue.ListValue(values)
                    ctx.variables.set(name, output)
                    ActionExecutionResult(true, output)
                }
            }
        },
        actionFeature(
            descriptor(
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
        ) { feature, ctx ->
            val name = feature.config.string("name")
            val key = feature.config.string("key")
            if (key.isBlank()) {
                ActionExecutionResult(false, message = userText("feature.key_empty"))
            } else {
                val current = (ctx.variables.get(name) as? ConfigValue.ObjectValue)?.value.orEmpty()
                val value = (feature.config["value"] ?: ConfigValue.NullValue).resolveVariables(ctx.variables)
                val output = ConfigValue.ObjectValue(current + (key to value))
                ctx.variables.set(name, output)
                ActionExecutionResult(true, output)
            }
        },
        actionFeature(
            descriptor(
                "variable.object.get",
                "Get object field",
                "Read a key from an object/map variable",
                fields = listOf(
                    FieldSchema.Variable("name", "Object variable", true),
                    FieldSchema.Text("key", "Key", true),
                    FieldSchema.Text("resultVariable", "Destination variable", true),
                ),
            )
        ) { feature, ctx ->
            val objectValue = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ObjectValue)?.value
            if (objectValue == null) {
                ActionExecutionResult(false, message = userText("feature.variable_not_object"))
            } else {
                val value = objectValue[feature.config.string("key")] ?: ConfigValue.NullValue
                ctx.variables.set(feature.config.string("resultVariable"), value)
                ActionExecutionResult(true, value)
            }
        },
        actionFeature(
            descriptor(
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
        ) { feature, ctx ->
            val name = feature.config.string("name")
            val source = ctx.variables.get(name).asText()
            val find = feature.config.string("find").resolveVariables(ctx.variables)
            val replacement = feature.config.string("replacement").resolveVariables(ctx.variables)
            runCatching {
                if (feature.config.boolean("regex")) Regex(find).replace(source, replacement)
                else source.replace(find, replacement)
            }.fold(
                onSuccess = { outputText ->
                    val output = ConfigValue.StringValue(outputText)
                    ctx.variables.set(name, output)
                    ActionExecutionResult(true, output)
                },
                onFailure = { error ->
                    ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
                },
            )
        },
        conditionFeature(variableEqualsDescriptor(FeatureKind.CONDITION, "variable.equals", "Variable equals")) { feature, ctx ->
            variableEquals(feature, ctx)
        },
        stateFeature(variableEqualsDescriptor(FeatureKind.STATE, "variable.state.equals", "Variable state equals")) { feature, ctx ->
            variableEquals(feature, ctx)
        },
        conditionFeature(
            descriptor(
                "variable.exists",
                "Variable exists",
                "Check that a variable is present and not null",
                fields = listOf(FieldSchema.Variable("name", "Variable", true)),
                kind = FeatureKind.CONDITION,
            )
        ) { feature, ctx ->
            val value = ctx.variables.get(feature.config.string("name"))
            value != null && value != ConfigValue.NullValue
        },
        conditionFeature(
            descriptor(
                "variable.number.compare",
                "Compare number",
                "Compare a variable with a number",
                fields = listOf(
                    FieldSchema.Variable("name", "Variable", true),
                    FieldSchema.Choice("operator", "Operator", true, COMPARISON_OPERATORS),
                    FieldSchema.Number("value", "Value", true),
                ),
                behaviors = mapOf("operator" to FieldBehavior(defaultValue = ConfigValue.StringValue("=="))),
                kind = FeatureKind.CONDITION,
            )
        ) { feature, ctx ->
            val left = ctx.variables.get(feature.config.string("name")).asNumber()
            val right = feature.config["value"].asNumber()
            left != null && right != null && compare(left, right, feature.config.string("operator", "=="))
        },
        conditionFeature(
            descriptor(
                "variable.list.size",
                "List size",
                "Compare the size of a list variable",
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Choice("operator", "Operator", true, COMPARISON_OPERATORS),
                    FieldSchema.Number("value", "Size", true, min = 0.0),
                ),
                behaviors = mapOf(
                    "operator" to FieldBehavior(defaultValue = ConfigValue.StringValue("==")),
                    "value" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                ),
                kind = FeatureKind.CONDITION,
            )
        ) { feature, ctx ->
            val size = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value?.size?.toDouble()
            val right = feature.config["value"].asNumber()
            size != null && right != null && compare(size, right, feature.config.string("operator", "=="))
        },
    )

    override fun install(registry: FeatureRegistry) {
        DefinitionFeaturePack(id, definitions).install(registry)
    }

    private fun descriptor(
        featureId: String,
        title: String,
        description: String,
        fields: List<FieldSchema>,
        keywords: Set<String> = emptySet(),
        behaviors: Map<String, FieldBehavior> = emptyMap(),
        kind: FeatureKind = FeatureKind.ACTION,
    ) = FeatureDescriptor(
        id = FeatureId(featureId),
        kind = kind,
        title = title,
        description = description,
        category = FeatureCategory.VARIABLE,
        fields = fields,
        keywords = keywords,
        fieldBehaviors = behaviors,
    )

    private fun variableEqualsDescriptor(kind: FeatureKind, featureId: String, title: String) = descriptor(
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

    private fun variableEquals(feature: FeatureRef, ctx: FeatureExecutionContext): Boolean {
        val actual = ctx.variables.get(feature.config.string("name"))
        val expected = feature.config["value"]?.resolveVariables(ctx.variables)
        return if (expected != null) actual == expected
        else actual.asText() == feature.config.string("value").resolveVariables(ctx.variables)
    }

    companion object {
        private val COMPARISON_OPERATORS = listOf("==", "!=", ">", ">=", "<", "<=")
    }
}

private fun compare(left: Double, right: Double, operator: String): Boolean = when (operator) {
    "==" -> left == right
    "!=" -> left != right
    ">" -> left > right
    ">=" -> left >= right
    "<" -> left < right
    "<=" -> left <= right
    else -> false
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
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.asText()}" }
}
