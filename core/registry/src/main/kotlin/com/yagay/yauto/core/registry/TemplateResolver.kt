package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue

private val yAutoVariable = Regex("\\$\\{([^}]+)}")
private val taskerVariable = Regex("%[A-Za-z][A-Za-z0-9_]*(?:\\[[^]]+])?")

fun String.resolveVariables(variables: VariableAccess): String {
    var result = yAutoVariable.replace(this) { match ->
        variables.get(match.groupValues[1])?.asTemplateText() ?: match.value
    }
    result = taskerVariable.replace(result) { match ->
        variables.get(match.value)?.asTemplateText()
            ?: variables.get(match.value.removePrefix("%"))?.asTemplateText()
            ?: match.value
    }
    return result
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
