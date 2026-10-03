package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
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

class VariableAdvancedFeaturePack : FeaturePack {
    override val id: String = "standard.variable.advanced"
    private val delegate = DefinitionFeaturePack(id, VariableAdvancedFeatures.definitions)
    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}

private object VariableAdvancedFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        action(
            "variable.move", "Move variable", "Move a runtime value to another variable and clear the source",
            listOf(variableField("source", "Source variable"), textField("destination", "Destination variable")),
        ) { feature, context ->
            val source = feature.config.string("source")
            val destination = feature.config.string("destination")
            if (source.isBlank() || destination.isBlank()) return@action variableNameEmpty()
            val value = context.variables.get(source) ?: ConfigValue.NullValue
            context.variables.set(destination, value)
            context.variables.set(source, ConfigValue.NullValue)
            ActionExecutionResult(true, value)
        },
        action(
            "variable.swap", "Swap variables", "Swap the values stored in two runtime variables",
            listOf(variableField("first", "First variable"), variableField("second", "Second variable")),
        ) { feature, context ->
            val first = feature.config.string("first")
            val second = feature.config.string("second")
            if (first.isBlank() || second.isBlank()) return@action variableNameEmpty()
            val left = context.variables.get(first) ?: ConfigValue.NullValue
            val right = context.variables.get(second) ?: ConfigValue.NullValue
            context.variables.set(first, right)
            context.variables.set(second, left)
            ActionExecutionResult(true, ConfigValue.ListValue(listOf(right, left)))
        },
        conditionalSet("variable.set_if_missing", "Set variable if missing", "Set a runtime variable only when it has never been assigned") {
            it == null
        },
        conditionalSet("variable.set_if_null", "Set variable if null", "Set a runtime variable when it is missing or contains null") {
            it == null || it == ConfigValue.NullValue
        },
        numericMutation("variable.decrement", "Decrement variable", "Subtract an amount from a numeric variable", 1.0) { current, amount -> current - amount },
        numericMutation("variable.multiply", "Multiply variable", "Multiply a numeric variable by an amount", 2.0) { current, amount -> current * amount },
        numericMutation("variable.divide", "Divide variable", "Divide a numeric variable by an amount", 2.0) { current, amount ->
            if (amount == 0.0) throw IllegalArgumentException()
            current / amount
        },
        numericMutation("variable.modulo", "Modulo variable", "Replace a numeric variable with its remainder", 2.0) { current, amount ->
            if (amount == 0.0) throw IllegalArgumentException()
            current % amount
        },
        textMutation("variable.text.append", "Append text", "Append text to a variable") { current, value -> current + value },
        textMutation("variable.text.prepend", "Prepend text", "Prepend text to a variable") { current, value -> value + current },
        action(
            "variable.list.append_all", "Append list", "Append every item from another list variable",
            listOf(variableField("name", "Target list"), variableField("other", "Source list")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.listAdvanced(name) ?: return@action notListAdvanced()
            val other = context.listAdvanced(feature.config.string("other")) ?: return@action notListAdvanced()
            context.storeAdvanced(name, ConfigValue.ListValue(current + other))
        },
        action(
            "variable.list.prepend_all", "Prepend list", "Insert every item from another list at the beginning of a list variable",
            listOf(variableField("name", "Target list"), variableField("other", "Source list")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.listAdvanced(name) ?: return@action notListAdvanced()
            val other = context.listAdvanced(feature.config.string("other")) ?: return@action notListAdvanced()
            context.storeAdvanced(name, ConfigValue.ListValue(other + current))
        },
        action(
            "variable.list.clear_items", "Clear list items", "Empty a list while preserving its list type",
            listOf(variableField("name", "List variable")),
        ) { feature, context ->
            val name = feature.config.string("name")
            if (context.listAdvanced(name) == null) return@action notListAdvanced()
            context.storeAdvanced(name, ConfigValue.ListValue(emptyList()))
        },
        action(
            "variable.object.remove_key", "Remove object key", "Remove a field from an object variable in place",
            listOf(variableField("name", "Object variable"), textField("key", "Key")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.objectAdvanced(name) ?: return@action notObjectAdvanced()
            val key = feature.config.string("key")
            if (key.isBlank()) return@action keyEmptyAdvanced()
            context.storeAdvanced(name, ConfigValue.ObjectValue(current - key))
        },
        action(
            "variable.object.merge", "Merge object variable", "Merge another object into a target object variable in place",
            listOf(variableField("name", "Target object"), variableField("other", "Source object")),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.objectAdvanced(name) ?: return@action notObjectAdvanced()
            val other = context.objectAdvanced(feature.config.string("other")) ?: return@action notObjectAdvanced()
            context.storeAdvanced(name, ConfigValue.ObjectValue(current + other))
        },
        action(
            "variable.object.rename_key", "Rename object key in place", "Rename a key inside an object variable",
            listOf(
                variableField("name", "Object variable"),
                textField("key", "Current key"),
                textField("newKey", "New key"),
            ),
        ) { feature, context ->
            val name = feature.config.string("name")
            val current = context.objectAdvanced(name) ?: return@action notObjectAdvanced()
            val key = feature.config.string("key")
            val newKey = feature.config.string("newKey")
            if (key.isBlank() || newKey.isBlank()) return@action keyEmptyAdvanced()
            val value = current[key] ?: return@action operationFailedAdvanced(feature)
            val updated = LinkedHashMap(current)
            updated.remove(key)
            updated[newKey] = value
            context.storeAdvanced(name, ConfigValue.ObjectValue(updated))
        },
        action(
            "variable.object.clear_fields", "Clear object fields", "Empty an object while preserving its object type",
            listOf(variableField("name", "Object variable")),
        ) { feature, context ->
            val name = feature.config.string("name")
            if (context.objectAdvanced(name) == null) return@action notObjectAdvanced()
            context.storeAdvanced(name, ConfigValue.ObjectValue(emptyMap()))
        },
        action(
            "variable.snapshot", "Snapshot variables", "Copy all current runtime variables into one object variable",
            listOf(textField("resultVariable", "Destination variable")),
        ) { feature, context ->
            context.storeAdvanced(feature.config.string("resultVariable"), ConfigValue.ObjectValue(context.variables.snapshot()))
        },
        action(
            "variable.names", "List variable names", "Return all current runtime variable names",
            listOf(textField("resultVariable", "Destination variable")),
        ) { feature, context ->
            val names = context.variables.snapshot().keys.sorted().map(ConfigValue::StringValue)
            context.storeAdvanced(feature.config.string("resultVariable"), ConfigValue.ListValue(names))
        },
        action(
            "variable.count", "Count variables", "Return the number of runtime variables currently present",
            listOf(textField("resultVariable", "Destination variable")),
        ) { feature, context ->
            context.storeAdvanced(
                feature.config.string("resultVariable"),
                ConfigValue.NumberValue(context.variables.snapshot().size.toDouble()),
            )
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
        mapOf("value" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val name = feature.config.string("name")
        if (name.isBlank()) return@action variableNameEmpty()
        val current = context.variables.get(name)
        if (!predicate(current)) return@action ActionExecutionResult(true, current ?: ConfigValue.NullValue)
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
        mapOf("amount" to FieldBehavior(defaultValue = ConfigValue.NumberValue(defaultAmount))),
    ) { feature, context ->
        val name = feature.config.string("name")
        val current = context.variables.get(name).asNumberAdvanced() ?: return@action operationFailedAdvanced(feature)
        val amount = feature.config["amount"].numberOrNull() ?: defaultAmount
        runCatching { operation(current, amount) }.fold(
            onSuccess = { context.storeAdvanced(name, ConfigValue.NumberValue(it)) },
            onFailure = { operationFailedAdvanced(feature) },
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
        mapOf("value" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val name = feature.config.string("name")
        if (name.isBlank()) return@action variableNameEmpty()
        val current = context.variables.get(name).asTextAdvanced()
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
    ),
) { feature, context -> block(feature, context) }

private fun variableField(key: String, label: String) = FieldSchema.Variable(key, label, true)
private fun textField(key: String, label: String) = FieldSchema.Text(key, label, true)

private fun FeatureExecutionContext.storeAdvanced(name: String, value: ConfigValue): ActionExecutionResult {
    if (name.isBlank()) return variableNameEmpty()
    variables.set(name, value)
    return ActionExecutionResult(true, value)
}

private fun FeatureExecutionContext.listAdvanced(name: String): List<ConfigValue>? =
    (variables.get(name) as? ConfigValue.ListValue)?.value

private fun FeatureExecutionContext.objectAdvanced(name: String): Map<String, ConfigValue>? =
    (variables.get(name) as? ConfigValue.ObjectValue)?.value

private fun ConfigValue?.asNumberAdvanced(): Double? = when (this) {
    is ConfigValue.NumberValue -> value
    is ConfigValue.StringValue -> value.toDoubleOrNull()
    else -> null
}

private fun ConfigValue?.asTextAdvanced(): String = when (this) {
    null, ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asTextAdvanced() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { (key, value) -> "$key=${value.asTextAdvanced()}" }
}

private fun variableNameEmpty() = ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
private fun notListAdvanced() = ActionExecutionResult(false, message = userText("feature.variable_not_list"))
private fun notObjectAdvanced() = ActionExecutionResult(false, message = userText("feature.variable_not_object"))
private fun keyEmptyAdvanced() = ActionExecutionResult(false, message = userText("feature.key_empty"))
private fun operationFailedAdvanced(feature: FeatureRef) =
    ActionExecutionResult(false, message = userText("feature.operation_failed", feature.id.value))
