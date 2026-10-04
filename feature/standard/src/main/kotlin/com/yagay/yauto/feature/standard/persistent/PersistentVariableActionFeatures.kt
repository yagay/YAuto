package com.yagay.yauto.feature.standard.persistent

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.PersistentVariableControl
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.resolveVariables

internal fun persistentVariableActions(control: PersistentVariableControl): List<FeatureDefinition> = listOf(
    actionFeature(
        persistentDescriptor(
            "variable.global.set",
            FeatureKind.ACTION,
            "Set persistent variable",
            "Store a typed variable in the YAuto workspace for future automation runs",
            fields = persistentValueFields,
            keywords = setOf("global", "persistent", "variable", "store", "workspace"),
            behaviors = persistentValueBehaviors,
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        if (name.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
        }
        val sourceName = feature.config.string("sourceVariable").trim()
        val value = if (sourceName.isNotBlank()) {
            context.variables.get(sourceName) ?: ConfigValue.NullValue
        } else {
            persistentConfiguredValue(feature, context.variables)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.invalid_persistent_value"))
        }
        val change = control.set(name, value)
        if (change.success) {
            context.variables.set(name, value)
            ActionExecutionResult(true, value)
        } else {
            ActionExecutionResult(false, message = userText("feature.persistent_variable_update_failed"))
        }
    },
    actionFeature(
        persistentDescriptor(
            "variable.global.get",
            FeatureKind.ACTION,
            "Get persistent variable",
            "Load a persistent workspace variable into the current automation run",
            fields = listOf(
                FieldSchema.Text("name", "Variable name", true),
                FieldSchema.Variable("resultVariable", "Destination variable", true),
            ),
            keywords = setOf("global", "persistent", "variable", "load", "workspace"),
            behaviors = mapOf("name" to FieldBehavior(supportsVariables = true)),
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        val destination = feature.config.string("resultVariable").trim()
        if (name.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
        }
        if (destination.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
        }
        val value = control.get(name) ?: ConfigValue.NullValue
        context.variables.set(destination, value)
        ActionExecutionResult(true, value)
    },
    actionFeature(
        persistentDescriptor(
            "variable.global.clear",
            FeatureKind.ACTION,
            "Clear persistent variable",
            "Remove a persistent variable from the YAuto workspace",
            fields = listOf(FieldSchema.Text("name", "Variable name", true)),
            keywords = setOf("global", "persistent", "variable", "remove", "workspace"),
            behaviors = mapOf("name" to FieldBehavior(supportsVariables = true)),
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        if (name.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
        }
        val change = control.clear(name)
        if (change.success) {
            context.variables.set(name, ConfigValue.NullValue)
            ActionExecutionResult(true, change.previous ?: ConfigValue.NullValue)
        } else {
            ActionExecutionResult(false, message = userText("feature.persistent_variable_clear_failed"))
        }
    },
    actionFeature(
        persistentDescriptor(
            "variable.global.mutate",
            FeatureKind.ACTION,
            "Modify persistent variable",
            "Apply ShortX-compatible numeric, boolean, text or list mutations to a persistent variable",
            fields = listOf(
                FieldSchema.Text("name", "Variable name", true),
                FieldSchema.Choice(
                    "operation",
                    "Operation",
                    true,
                    listOf(
                        "override", "plus_one", "minus_one", "plus_delta", "minus_delta", "invert",
                        "append_first", "append_last", "delete_value", "delete_first", "delete_last",
                        "remove_at_index", "reverse", "shuffle", "clear",
                    ),
                ),
                FieldSchema.Variable("sourceVariable", "Source runtime variable"),
                FieldSchema.Choice("valueType", "Value type", options = listOf("text", "number", "boolean", "null")),
                FieldSchema.Text("value", "Value", multiline = true),
                FieldSchema.Number("index", "List/text index", min = 0.0),
            ),
            keywords = setOf("global", "persistent", "variable", "modify", "shortx", "list", "increment"),
            behaviors = mapOf(
                "name" to FieldBehavior(supportsVariables = true),
                "value" to FieldBehavior(supportsVariables = true),
                "operation" to FieldBehavior(defaultValue = ConfigValue.StringValue("override")),
                "valueType" to FieldBehavior(defaultValue = ConfigValue.StringValue("text")),
            ),
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        if (name.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
        }
        val current = control.get(name) ?: ConfigValue.NullValue
        val sourceName = feature.config.string("sourceVariable").trim()
        val operand = if (sourceName.isNotBlank()) {
            context.variables.get(sourceName) ?: ConfigValue.NullValue
        } else {
            persistentConfiguredValue(feature, context.variables) ?: ConfigValue.NullValue
        }
        val index = (feature.config["index"] as? ConfigValue.NumberValue)?.value?.toInt()?.coerceAtLeast(0) ?: 0
        val updated = mutatePersistentValue(
            current = current,
            operation = feature.config.string("operation", "override"),
            operand = operand,
            index = index,
        ) ?: return@actionFeature ActionExecutionResult(
            false,
            message = userText("feature.operation_failed", "Unsupported variable mutation"),
        )
        val change = control.set(name, updated)
        if (change.success) {
            context.variables.set(name, updated)
            ActionExecutionResult(true, updated)
        } else {
            ActionExecutionResult(false, message = userText("feature.persistent_variable_update_failed"))
        }
    },
)

internal fun mutatePersistentValue(
    current: ConfigValue,
    operation: String,
    operand: ConfigValue,
    index: Int = 0,
): ConfigValue? = when (operation) {
    "override" -> operand
    "plus_one" -> (current as? ConfigValue.NumberValue)?.let { ConfigValue.NumberValue(it.value + 1.0) }
    "minus_one" -> (current as? ConfigValue.NumberValue)?.let { ConfigValue.NumberValue(it.value - 1.0) }
    "plus_delta" -> numericPersistentMutation(current, operand) { a, b -> a + b }
    "minus_delta" -> numericPersistentMutation(current, operand) { a, b -> a - b }
    "invert" -> when (current) {
        is ConfigValue.BooleanValue -> ConfigValue.BooleanValue(!current.value)
        is ConfigValue.NumberValue -> ConfigValue.NumberValue(-current.value)
        else -> null
    }
    "append_first" -> appendPersistentValue(current, operand, first = true)
    "append_last" -> appendPersistentValue(current, operand, first = false)
    "delete_value" -> when (current) {
        is ConfigValue.ListValue -> ConfigValue.ListValue(current.value.filterNot { it == operand })
        is ConfigValue.StringValue -> ConfigValue.StringValue(current.value.replace(persistentText(operand), ""))
        else -> null
    }
    "delete_first" -> when (current) {
        is ConfigValue.ListValue -> ConfigValue.ListValue(current.value.drop(1))
        is ConfigValue.StringValue -> ConfigValue.StringValue(current.value.drop(1))
        else -> null
    }
    "delete_last" -> when (current) {
        is ConfigValue.ListValue -> ConfigValue.ListValue(current.value.dropLast(1))
        is ConfigValue.StringValue -> ConfigValue.StringValue(current.value.dropLast(1))
        else -> null
    }
    "remove_at_index" -> when (current) {
        is ConfigValue.ListValue -> if (index in current.value.indices) {
            ConfigValue.ListValue(current.value.filterIndexed { i, _ -> i != index })
        } else current
        is ConfigValue.StringValue -> if (index in current.value.indices) {
            ConfigValue.StringValue(current.value.removeRange(index, index + 1))
        } else current
        else -> null
    }
    "reverse" -> when (current) {
        is ConfigValue.ListValue -> ConfigValue.ListValue(current.value.reversed())
        is ConfigValue.StringValue -> ConfigValue.StringValue(current.value.reversed())
        else -> null
    }
    "shuffle" -> when (current) {
        is ConfigValue.ListValue -> ConfigValue.ListValue(current.value.shuffled())
        is ConfigValue.StringValue -> ConfigValue.StringValue(current.value.toList().shuffled().joinToString(""))
        else -> null
    }
    "clear" -> when (current) {
        is ConfigValue.ListValue -> ConfigValue.ListValue(emptyList())
        is ConfigValue.ObjectValue -> ConfigValue.ObjectValue(emptyMap())
        is ConfigValue.StringValue -> ConfigValue.StringValue("")
        is ConfigValue.NumberValue -> ConfigValue.NumberValue(0.0)
        is ConfigValue.BooleanValue -> ConfigValue.BooleanValue(false)
        ConfigValue.NullValue -> ConfigValue.NullValue
    }
    else -> null
}

private fun numericPersistentMutation(
    current: ConfigValue,
    operand: ConfigValue,
    transform: (Double, Double) -> Double,
): ConfigValue? {
    val left = (current as? ConfigValue.NumberValue)?.value ?: return null
    val right = (operand as? ConfigValue.NumberValue)?.value ?: return null
    val value = transform(left, right)
    return value.takeIf { it.isFinite() }?.let(ConfigValue::NumberValue)
}

private fun appendPersistentValue(current: ConfigValue, operand: ConfigValue, first: Boolean): ConfigValue? = when (current) {
    is ConfigValue.ListValue -> {
        val additions = (operand as? ConfigValue.ListValue)?.value ?: listOf(operand)
        ConfigValue.ListValue(if (first) additions + current.value else current.value + additions)
    }
    is ConfigValue.StringValue -> {
        val text = persistentText(operand)
        ConfigValue.StringValue(if (first) text + current.value else current.value + text)
    }
    else -> null
}

private fun persistentText(value: ConfigValue): String = when (value) {
    is ConfigValue.StringValue -> value.value
    is ConfigValue.NumberValue -> value.value.toString()
    is ConfigValue.BooleanValue -> value.value.toString()
    ConfigValue.NullValue -> ""
    is ConfigValue.ListValue -> value.value.joinToString(",") { persistentText(it) }
    is ConfigValue.ObjectValue -> value.value.entries.joinToString(",") { (key, item) -> key + "=" + persistentText(item) }
}
