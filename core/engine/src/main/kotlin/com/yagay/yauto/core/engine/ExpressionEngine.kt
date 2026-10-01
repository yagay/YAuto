package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.VariableAccess

interface ExpressionEngine {
    fun evaluateBoolean(expression: String, variables: VariableAccess): Boolean
    fun evaluateText(expression: String, variables: VariableAccess): String
}

class SimpleExpressionEngine : ExpressionEngine {
    override fun evaluateBoolean(expression: String, variables: VariableAccess): Boolean {
        val text = expression.trim()
        if (text.equals("true", true)) return true
        if (text.equals("false", true)) return false
        if ("&&" in text) return text.split("&&").all { evaluateBoolean(it, variables) }
        if ("||" in text) return text.split("||").any { evaluateBoolean(it, variables) }
        if (text.startsWith("!")) return !evaluateBoolean(text.drop(1), variables)
        val op = listOf("==", "!=", ">=", "<=", ">", "<").firstOrNull { it in text }
        if (op != null) {
            val (left, right) = text.split(op, limit = 2).map { it.trim() }
            val actual = variables.get(left)
            val expected = right.trim('"', '\'')
            return compare(actual, expected, op)
        }
        return truthy(variables.get(text))
    }

    override fun evaluateText(expression: String, variables: VariableAccess): String {
        val text = expression.trim()
        if (text.startsWith("${'$'}{") && text.endsWith("}")) return valueAsText(variables.get(text.drop(2).dropLast(1)))
        return variables.get(text)?.let(::valueAsText) ?: text.trim('"', '\'')
    }

    private fun compare(value: ConfigValue?, expected: String, op: String): Boolean {
        val leftNumber = (value as? ConfigValue.NumberValue)?.value ?: (value as? ConfigValue.StringValue)?.value?.toDoubleOrNull()
        val rightNumber = expected.toDoubleOrNull()
        if (leftNumber != null && rightNumber != null) return when (op) {
            "==" -> leftNumber == rightNumber; "!=" -> leftNumber != rightNumber
            ">" -> leftNumber > rightNumber; "<" -> leftNumber < rightNumber
            ">=" -> leftNumber >= rightNumber; "<=" -> leftNumber <= rightNumber
            else -> false
        }
        val equal = valueAsText(value) == expected
        return if (op == "!=") !equal else if (op == "==") equal else false
    }

    private fun truthy(value: ConfigValue?): Boolean = when (value) {
        is ConfigValue.BooleanValue -> value.value
        is ConfigValue.NumberValue -> value.value != 0.0
        is ConfigValue.StringValue -> value.value.isNotBlank() && !value.value.equals("false", true)
        else -> false
    }

    private fun valueAsText(value: ConfigValue?): String = when (value) {
        is ConfigValue.StringValue -> value.value
        is ConfigValue.NumberValue -> value.value.toString().removeSuffix(".0")
        is ConfigValue.BooleanValue -> value.value.toString()
        ConfigValue.NullValue, null -> "null"
        is ConfigValue.ListValue -> value.value.toString()
        is ConfigValue.ObjectValue -> value.value.toString()
    }
}
