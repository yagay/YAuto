package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.resolveVariables

/** Runtime-variable mutation helpers that complement the basic set/copy/increment actions. */
class VariableAdvancedFeaturePack : FeaturePack {
    override val id: String = "standard.variable.advanced"

    private val delegate = DefinitionFeaturePack(id, VariableAdvancedFeatures.definitions)

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}

private object VariableAdvancedFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        action(
            "variable.move",
            "Move variable",
            "Move a runtime value to another variable and clear the source",
            listOf(variableField("source", "Source variable"), textField("destination", "Destination variable")),
        ) { feature, context ->
            val source = feature.config.string("source")
            val destination = feature.config.string("destination")
            if (source.isBlank() || destination.isBlank()) return@action fail("Variable name is empty")
            val value = context.variables.get(source) ?: ConfigValue.NullValue
            context.variables.set(destination, value)
            context.variables.set(source, ConfigValue.NullValue)
            ActionExecutionResult(true, value)
        },
        action(
            "variable.swap",
            "Swap variables",
            "Swap the values stored in two runtime variables",
            listOf(variableField("first", "First variable"), variableField("second", "Second variable")),
        ) { feature, context ->
            val first = feature.config.string("first")
            val second = feature.config.string("second")
            if (first.isBlank() || second.isBlank()) return@action fail("Variable name is empty")
            val left = context.variables.get(first) ?: ConfigValue.NullValue
            val right = context.variables.get(second) ?: ConfigValue.NullValue
            context.variables.set(first, right)
            context.variables.set(second, left)
            ActionExecutionResult(true, ConfigValue.ListValue(listOf(right, left)))
        },
        conditionalSet(
            "variable.set_if_missing",
            "Set variable if missing",
            "Set a runtime variable only when it has never been assigned",
        ) { current -> current == null },
        conditionalSet(
            "variable.set_if_null",
            "Set variable if null",
            "Set a runtime variable when it is missing or contains null",
        ) { current -> current == null || current == ConfigValue.NullValue },
        numericMutation("variable.decrement", "Decrement variable", "Subtract an amount from a numeric variable", 1.0) { current, amount -> current - amount },
        numericMutation("variable.multiply", "Multiply variable", "Multiply a numeric variable by an amount", 2.0) { current, amount -> current * amount },
        numericMutation("variable.divide", "Divide variable", "Divide a numeric variable by an amount", 2.0) { current, amount ->
            require(amount != 0.0) { "Cannot divide by zero" }
            current / amount
        },
        numericMutation("variable.modulo", "Modulo variable", "Replace a numeric variable with its remainder", 2.0) { current, amount ->
            require(amount != 0.0) { "Cannot divide by zero" }
            current % amount
        },
        textMutation("variable.text.append", "Append text", "Append text to a variable") { current, value -> current + value },
        textMutation("variable.text.prepend", "Prepend text", "Prepend text to a variable") { current, value -> value + current },
        action(
            "variable.list.append_all",
            "Append list",
            "Append every item from another list variable",
            listOf(variableField("name", "Target list"), variableField("other", "Source list")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.list(name) ?: return@action fail("Target variable is not a list")
            val other = context.list(feature.config.string("other")) ?: return@action fail("Source variable is not a list")
            context.storeAdvanced(name, ConfigValue.ListValue(current + other))
        },
        action(
            "variable.list.prepend_all",
            "Prepend list",
            "Insert every item from another list at the beginning of a list variable",
            listOf(variableField("name", "Target list"), variableField("other", "Source list")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.list(name) ?: return@action fail("Target variable is not a list")
            val other = context.list(feature.config.string("other")) ?: return@action fail("Source variable is not a list")
            context.storeAdvanced(name, ConfigValue.ListValue(other + current))
        },
        action(
            "variable.list.clear_items",
            "Clear list items",
            "Empty a list while preserving its list type",
            listOf(variableField("name", "List variable")),
        ) { feature, context ->
            val name = feature.config.string("name")
            if (context.list(name) == null) return@action fail("Selected variable is not a list")
            context.storeAdvanced(name, ConfigValue.ListValue(emptyList()))
        },
        action(
            "variable.object.remove_key",
            "Remove object key",
            "Remove a field from an object variable in place",
            listOf(variableField("name", "Object variable"), textField("key", "Key")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.obj(name) ?: return@action fail("Selected variable is not an object")
            context.storeAdvanced(name, ConfigValue.ObjectValue(current - feature.config.string("key")))
        },
        action(
            "variable.object.merge",
            "Merge object variable",
            "Merge another object into a target object variable in place",
            listOf(variableField("name", "Target object"), variableField("other", "Source object")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.obj(name) ?: return@action fail("Target variable is not an object")
            val other = context.obj(feature.config.string("other")) ?: return@action fail("Source variable is not an object")
            context.storeAdvanced(name, ConfigValue.ObjectValue(current + other))
        },
        action(
            "variable.object.rename_key",
            "Rename object key in place",
            "Rename a key inside an object variable",
            listOf(
                variableField("name", "Object variable"),
                textField("key", "Current key"),
                textField("newKey", "New key"),
            ),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.obj(name) ?: return@action fail("Selected variable is not an object")
            val key = feature.config.string("key")
            val value = current[key] ?: return@action fail("Object key does not exist")
            val updated = LinkedHashMap(current)
            updated.remove(key)
            updated[feature.config.string("newKey")] = value
            context.storeAdvanced(name, ConfigValue.ObjectValue(updated))
        },
        action(
            "variable.object.clear_fields",
            "Clear object fields",
            "Empty an object while preserving its object type",
            listOf(variableField("name", "Object variable")),
        ) { feature, context ->
            val name = feature.config.string("name")
            if (context.obj(name) == null) return@action fail("Selected variable is not an object")
            context.storeAdvanced(name, ConfigValue.ObjectValue(emptyMap()))
        },
        action(
            "variable.snapshot",
            "Snapshot variables",
            "Copy all current runtime variables into one object variable",
            listOf(textField("resultVariable", "Destination variable")),
        ) { feature, context ->
            context.storeAdvanced(feature.config.string("resultVariable"), ConfigValue.ObjectValue(context.variables.snapshot()))
        },
        action(
            "variable.names",
            "List variable names",
            "Return all current runtime variable names",
            listOf(textField("resultVariable", "Destination variable")),
        ) { feature, context ->
            val names = context.variables.snapshot().keys.sorted().map(ConfigValue::StringValue)
            context.storeAdvanced(feature.config.string("resultVariable"), ConfigValue.ListValue(names))
        },
        action(
            "variable.count",
            "Count variables",
            "Return the number of runtime variables currently present",
            listOf(textField("resultVariable", "Destination variable")),
        ) { feature, context ->
            val count = context.variables.snapshot().size.toDouble()
            context.storeAdvanced(feature.config.string("resultVariable"), ConfigValue.NumberValue(count))
        },
    )

    private fun conditionalSet(
        id: String,
        title: String,
        description: String,
        predicate: (ConfigValue?) -> Boolean,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        listOf(textField("name", "Variable name"), FieldSchema.Text("value", "Value")),
        behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val name = feature.config.string("name")
        if (name.isBlank()) return@action fail("Variable name is empty")
        val current = context.variables.get(name)
        if (!predicate(current)) return@action ActionExecutionResult(true, current ?: ConfigValue.NullValue, "Existing value kept")
        val value = (feature.config["value"] ?: ConfigValue.NullValue).resolveVariables(context.variables)
        context.storeAdvanced(name, value)
    }

    private fun numericMutation(
        id: String,
        title: String,
        description: String,
        defaultAmount: Double,
        operation: (Double, Double) -> Double,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        listOf(variableField("name", "Numeric variable"), FieldSchema.Number("amount", "Amount", true)),
        behaviors = mapOf("amount" to FieldBehavior(defaultValue = ConfigValue.NumberValue(defaultAmount))),
    ) { feature, context ->
        val name = feature.config.string("name")
        val current = context.variables.get(name).asNumber() ?: return@action fail("Selected variable is not numeric")
        val amount = feature.config["amount"].numberOrNull() ?: defaultAmount
        runCatching { operation(current, amount) }.fold(
            onSuccess = { context.storeAdvanced(name, ConfigValue.NumberValue(it)) },
            onFailure = { fail(it.message ?: "Numeric operation failed") },
        )
    }

    private fun textMutation(
        id: String,
        title: String,
        description: String,
        operation: (String, String) -> String,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        listOf(variableField("name", "Text variable"), FieldSchema.Text("value", "Text")),
        behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val name = feature.config.string("name")
        val current = context.variables.get(name).asText()
        val value = feature.config.string("value").resolveVariables(context.variables)
        context.storeAdvanced(name, ConfigValue.StringValue(operation(current, value)))
    }
}

private fun action(
    id: String,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    behaviors: Map<String, FieldBehavior> = emptyMap(),
    block: suspend (FeatureRef, FeatureExecutionContext) -> ActionExecutionResult,
): FeatureDefinition = actionFeature(
    FeatureDescriptor(
        id = FeatureId(id),
        kind = FeatureKind.ACTION,
        title = title,
        description = description,
        category = FeatureCategory.VARIABLE,
        fields = fields,
        fieldBehaviors = behaviors,
        keywords = setOf("variable", "runtime", "list", "object", "mutation"),
    )
) { feature, context -> block(feature, context) }

private fun variableField(key: String, label: String) = FieldSchema.Variable(key, label, true)
private fun textField(key: String, label: String) = FieldSchema.Text(key, label, true)

private fun FeatureExecutionContext.storeAdvanced(name: String, value: ConfigValue): ActionExecutionResult {
    if (name.isBlank()) return fail("Variable name is empty")
    variables.set(name, value)
    return ActionExecutionResult(true, value)
}

private fun FeatureExecutionContext.list(name: String): List<ConfigValue>? =
    (variables.get(name) as? ConfigValue.ListValue)?.value

private fun FeatureExecutionContext.obj(name: String): Map<String, ConfigValue>? =
    (variables.get(name) as? ConfigValue.ObjectValue)?.value

private fun ConfigValue?.asNumber(): Double? = when (this) {
    is ConfigValue.NumberValue -> value
    is ConfigValue.StringValue -> value.toDoubleOrNull()
    else -> null
}

private fun ConfigValue?.asText(): String = when (this) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { (key, value) -> "$key=${value.asText()}" }
}

private fun fail(message: String) = ActionExecutionResult(false, message = message)
