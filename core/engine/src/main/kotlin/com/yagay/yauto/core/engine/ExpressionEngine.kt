package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.VariableAccess

interface ExpressionEngine {
    fun evaluateBoolean(expression: String, variables: VariableAccess): Boolean
    fun evaluateText(expression: String, variables: VariableAccess): String
}

class SimpleExpressionEngine : ExpressionEngine {
    override fun evaluateBoolean(expression: String, variables: VariableAccess): Boolean {
        require(expression.length <= 16_000) { "Expression is too long" }
        return evaluateBoolean(expression, variables, 0)
    }

    private fun evaluateBoolean(expression: String, variables: VariableAccess, depth: Int): Boolean {
        require(depth < 64) { "Expression nesting limit exceeded" }
        val text = expression.trim()
        if (text.equals("true", true)) return true
        if (text.equals("false", true)) return false
        val or = splitOutsideGroups(text, "||")
        if (or.size > 1) return or.any { evaluateBoolean(it, variables, depth + 1) }
        val and = splitOutsideGroups(text, "&&")
        if (and.size > 1) return and.all { evaluateBoolean(it, variables, depth + 1) }
        if (text.startsWith("!") && !text.startsWith("!=")) return !evaluateBoolean(text.drop(1), variables, depth + 1)
        if (text.startsWith("(") && text.endsWith(")")) return evaluateBoolean(text.drop(1).dropLast(1), variables, depth + 1)
        val op = listOf("==", "!=", ">=", "<=", ">", "<").firstOrNull { splitOutsideGroups(text, it).size > 1 }
        if (op != null) {
            val operands = splitOutsideGroups(text, op)
            require(operands.size == 2) { "Expected two comparison operands" }
            val (left, right) = operands.map { it.trim() }
            val actual = variables.get(left)
            val expected = right.trim('"', '\'')
            return compare(actual, expected, op)
        }
        return truthy(variables.get(text))
    }

    /** Logical operators inside quoted strings or parentheses are data, not separators. */
    private fun splitOutsideGroups(text: String, operator: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        var index = 0
        var nesting = 0
        var quote: Char? = null
        while (index < text.length) {
            val character = text[index]
            if (quote != null) {
                if (character == '\\') { index += 2; continue }
                if (character == quote) quote = null
            } else when (character) {
                '\'', '"' -> quote = character
                '(' -> { nesting++; require(nesting < 64) { "Expression nesting limit exceeded" } }
                ')' -> { nesting--; require(nesting >= 0) { "Unbalanced expression parentheses" } }
                else -> if (nesting == 0 && text.startsWith(operator, index)) {
                    parts += text.substring(start, index)
                    index += operator.length
                    start = index
                    continue
                }
            }
            index++
        }
        require(nesting == 0 && quote == null) { "Unbalanced expression parentheses or quotes" }
        parts += text.substring(start)
        require(parts.all { it.isNotBlank() }) { "Missing expression operand" }
        return parts
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
