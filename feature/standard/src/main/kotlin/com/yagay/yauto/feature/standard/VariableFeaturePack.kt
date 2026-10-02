package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*

class VariableFeaturePack : FeaturePack {
    override val id: String = "standard.variable"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.set"), FeatureKind.ACTION, "Set variable", "Set a runtime variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Text("name", "Variable name", true), FieldSchema.Text("value", "Value")), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) ActionExecutionResult(false, message = "Variable name is empty")
            else { ctx.variables.set(name, (feature.config["value"] ?: ConfigValue.NullValue).resolveVariables(ctx.variables)); ActionExecutionResult(true) }
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.clear"), FeatureKind.ACTION, "Clear variable", "Clear a runtime variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true)), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) ActionExecutionResult(false, message = "Variable name is empty") else { ctx.variables.set(name, ConfigValue.NullValue); ActionExecutionResult(true) }
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.copy"), FeatureKind.ACTION, "Copy variable", "Copy one runtime variable to another", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("source", "Source variable", true), FieldSchema.Text("destination", "Destination variable", true)), ownerPackId = id)
        ) { feature, ctx ->
            val source = feature.config.string("source"); val destination = feature.config.string("destination")
            val value = ctx.variables.get(source) ?: ConfigValue.NullValue
            if (destination.isBlank()) ActionExecutionResult(false, message = "Destination variable is empty")
            else { ctx.variables.set(destination, value); ActionExecutionResult(true, value) }
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.increment"), FeatureKind.ACTION, "Increment variable", "Add a number to a variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true), FieldSchema.Number("amount", "Amount")), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) return@registerAction ActionExecutionResult(false, message = "Variable name is empty")
            val current = ctx.variables.get(name).asNumber() ?: 0.0
            val amount = feature.config["amount"].asNumber() ?: 1.0
            val value = ConfigValue.NumberValue(current + amount)
            ctx.variables.set(name, value); ActionExecutionResult(true, value)
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.toggle"), FeatureKind.ACTION, "Toggle variable", "Invert a boolean variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true)), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name")
            if (name.isBlank()) return@registerAction ActionExecutionResult(false, message = "Variable name is empty")
            val value = ConfigValue.BooleanValue(!(ctx.variables.get(name).booleanOrNull() ?: false))
            ctx.variables.set(name, value); ActionExecutionResult(true, value)
        }

        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.list.append"), FeatureKind.ACTION, "Append to list", "Append a value to a list variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "List variable", true), FieldSchema.Text("value", "Value", true)), keywords = setOf("array", "list", "append"), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name")
            val current = (ctx.variables.get(name) as? ConfigValue.ListValue)?.value.orEmpty()
            val value = (feature.config["value"] ?: ConfigValue.StringValue("")).resolveVariables(ctx.variables)
            val output = ConfigValue.ListValue(current + value); ctx.variables.set(name, output); ActionExecutionResult(true, output)
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.list.get"), FeatureKind.ACTION, "Get list item", "Read a list item by zero-based index", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "List variable", true), FieldSchema.Number("index", "Index", true, min = 0.0), FieldSchema.Text("resultVariable", "Destination variable", true)), ownerPackId = id)
        ) { feature, ctx ->
            val values = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value
                ?: return@registerAction ActionExecutionResult(false, message = "Variable is not a list")
            val index = feature.config["index"].asNumber()?.toInt() ?: 0
            val value = values.getOrNull(index) ?: return@registerAction ActionExecutionResult(false, message = "List index out of bounds")
            ctx.variables.set(feature.config.string("resultVariable"), value); ActionExecutionResult(true, value)
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.list.remove"), FeatureKind.ACTION, "Remove list item", "Remove a list item by zero-based index", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "List variable", true), FieldSchema.Number("index", "Index", true, min = 0.0)), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name")
            val values = (ctx.variables.get(name) as? ConfigValue.ListValue)?.value?.toMutableList()
                ?: return@registerAction ActionExecutionResult(false, message = "Variable is not a list")
            val index = feature.config["index"].asNumber()?.toInt() ?: 0
            if (index !in values.indices) return@registerAction ActionExecutionResult(false, message = "List index out of bounds")
            values.removeAt(index); val output = ConfigValue.ListValue(values); ctx.variables.set(name, output); ActionExecutionResult(true, output)
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.object.put"), FeatureKind.ACTION, "Set object field", "Set a key on an object/map variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Object variable", true), FieldSchema.Text("key", "Key", true), FieldSchema.Text("value", "Value")), keywords = setOf("map", "object", "dictionary"), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name"); val key = feature.config.string("key")
            if (key.isBlank()) return@registerAction ActionExecutionResult(false, message = "Key is empty")
            val current = (ctx.variables.get(name) as? ConfigValue.ObjectValue)?.value.orEmpty()
            val value = (feature.config["value"] ?: ConfigValue.NullValue).resolveVariables(ctx.variables)
            val output = ConfigValue.ObjectValue(current + (key to value)); ctx.variables.set(name, output); ActionExecutionResult(true, output)
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.object.get"), FeatureKind.ACTION, "Get object field", "Read a key from an object/map variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Object variable", true), FieldSchema.Text("key", "Key", true), FieldSchema.Text("resultVariable", "Destination variable", true)), ownerPackId = id)
        ) { feature, ctx ->
            val objectValue = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ObjectValue)?.value
                ?: return@registerAction ActionExecutionResult(false, message = "Variable is not an object")
            val value = objectValue[feature.config.string("key")] ?: ConfigValue.NullValue
            ctx.variables.set(feature.config.string("resultVariable"), value); ActionExecutionResult(true, value)
        }
        registry.registerAction(
            FeatureDescriptor(FeatureId("variable.text.replace"), FeatureKind.ACTION, "Replace text", "Replace literal or regular-expression matches in text", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Text variable", true), FieldSchema.Text("find", "Find", true), FieldSchema.Text("replacement", "Replacement"), FieldSchema.Toggle("regex", "Regular expression")), keywords = setOf("text", "replace", "regex"), ownerPackId = id)
        ) { feature, ctx ->
            val name = feature.config.string("name"); val source = ctx.variables.get(name).asText()
            val find = feature.config.string("find").resolveVariables(ctx.variables); val replacement = feature.config.string("replacement").resolveVariables(ctx.variables)
            val outputText = runCatching { if (feature.config.boolean("regex")) Regex(find).replace(source, replacement) else source.replace(find, replacement) }
                .getOrElse { return@registerAction ActionExecutionResult(false, message = it.message) }
            val output = ConfigValue.StringValue(outputText); ctx.variables.set(name, output); ActionExecutionResult(true, output)
        }

        registry.registerCondition(variableEqualsDescriptor(FeatureKind.CONDITION, "variable.equals", "Variable equals")) { feature, ctx -> variableEquals(feature, ctx) }
        registry.registerState(variableEqualsDescriptor(FeatureKind.STATE, "variable.state.equals", "Variable state equals")) { feature, ctx -> variableEquals(feature, ctx) }
        registry.registerCondition(
            FeatureDescriptor(FeatureId("variable.exists"), FeatureKind.CONDITION, "Variable exists", "Check that a variable is present and not null", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true)), ownerPackId = id)
        ) { feature, ctx -> val value = ctx.variables.get(feature.config.string("name")); value != null && value != ConfigValue.NullValue }
        registry.registerCondition(
            FeatureDescriptor(FeatureId("variable.number.compare"), FeatureKind.CONDITION, "Compare number", "Compare a variable with a number", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "Variable", true), FieldSchema.Choice("operator", "Operator", true, listOf("==", "!=", ">", ">=", "<", "<=")), FieldSchema.Number("value", "Value", true)), ownerPackId = id)
        ) { feature, ctx ->
            val left = ctx.variables.get(feature.config.string("name")).asNumber() ?: return@registerCondition false
            val right = feature.config["value"].asNumber() ?: return@registerCondition false
            when (feature.config.string("operator", "==")) { "==" -> left == right; "!=" -> left != right; ">" -> left > right; ">=" -> left >= right; "<" -> left < right; "<=" -> left <= right; else -> false }
        }
        registry.registerCondition(
            FeatureDescriptor(FeatureId("variable.list.size"), FeatureKind.CONDITION, "List size", "Compare the size of a list variable", FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "List variable", true), FieldSchema.Choice("operator", "Operator", true, listOf("==", "!=", ">", ">=", "<", "<=")), FieldSchema.Number("value", "Size", true, min = 0.0)), ownerPackId = id)
        ) { feature, ctx ->
            val size = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value?.size?.toDouble() ?: return@registerCondition false
            val right = feature.config["value"].asNumber() ?: 0.0
            when (feature.config.string("operator", "==")) { "==" -> size == right; "!=" -> size != right; ">" -> size > right; ">=" -> size >= right; "<" -> size < right; "<=" -> size <= right; else -> false }
        }
    }

    private fun variableEqualsDescriptor(kind: FeatureKind, featureId: String, title: String) = FeatureDescriptor(
        FeatureId(featureId), kind, title, "Compare a variable with a value", FeatureCategory.VARIABLE,
        fields = listOf(FieldSchema.Variable("name", "Variable", true), FieldSchema.Text("value", "Value")), ownerPackId = id)

    private fun variableEquals(feature: FeatureRef, ctx: FeatureExecutionContext): Boolean {
        val actual = ctx.variables.get(feature.config.string("name")); val expected = feature.config["value"]?.resolveVariables(ctx.variables)
        return if (expected != null) actual == expected else actual.asText() == feature.config.string("value").resolveVariables(ctx.variables)
    }
}

private fun ConfigValue?.asNumber(): Double? = when (this) { is ConfigValue.NumberValue -> value; is ConfigValue.StringValue -> value.toDoubleOrNull(); else -> null }
private fun ConfigValue?.asText(): String = when (this) {
    null, ConfigValue.NullValue -> "null"
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.asText()}" }
}
