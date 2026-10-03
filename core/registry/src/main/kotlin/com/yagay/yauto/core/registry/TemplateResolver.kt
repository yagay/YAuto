package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue

private val yAutoVariable = Regex("\\$\\{([^}]+)}")

/** Resolves YAuto-native ${name} templates only. */
fun String.resolveVariables(variables: VariableAccess): String =
    yAutoVariable.replace(this) { match ->
        variables.get(match.groupValues[1])?.asTemplateText() ?: match.value
    }

fun ConfigValue.resolveVariables(variables: VariableAccess): ConfigValue = when (this) {
    ConfigValue.NullValue -> this
    is ConfigValue.StringValue -> ConfigValue.StringValue(value.resolveVariables(variables))
    is ConfigValue.NumberValue -> this
    is ConfigValue.BooleanValue -> this
    is ConfigValue.ListValue -> ConfigValue.ListValue(value.map { it.resolveVariables(variables) })
    is ConfigValue.ObjectValue -> ConfigValue.ObjectValue(value.mapValues { (_, item) -> item.resolveVariables(variables) })
}

private fun ConfigValue.asTemplateText(): String = when (this) {
    ConfigValue.NullValue -> "null"
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asTemplateText() }
    is ConfigValue.ObjectValue -> value.toString()
}
