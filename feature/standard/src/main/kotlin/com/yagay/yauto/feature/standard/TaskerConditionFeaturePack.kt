package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlin.math.abs

/**
 * Executes Tasker's exported ConditionList operator semantics without requiring Tasker at runtime.
 * Variable names (including Tasker's %name form) are kept verbatim by the importer.
 */
class TaskerConditionFeaturePack : FeaturePack {
    override val id: String = "standard.tasker_condition"

    override fun install(registry: FeatureRegistry) {
        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("tasker.condition.compare"),
                FeatureKind.CONDITION,
                "Tasker condition",
                "Evaluate a Tasker-compatible string, pattern, regex, numeric, parity or set/unset condition",
                FeatureCategory.COMPATIBILITY,
                fields = listOf(
                    FieldSchema.Text("lhs", "Left operand", true),
                    FieldSchema.Number("operator", "Tasker operator code", true, min = 0.0, max = 13.0),
                    FieldSchema.Text("rhs", "Right operand"),
                ),
                keywords = setOf("tasker", "condition", "if", "wait until", "variable"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val lhsRaw = feature.config.string("lhs")
            val rhsRaw = feature.config.string("rhs")
            val operator = (feature.config["operator"] as? ConfigValue.NumberValue)?.value?.toInt()
                ?: return@registerCondition false
            evaluateTaskerCondition(lhsRaw, operator, rhsRaw, ctx.variables)
        }
    }
}

private fun evaluateTaskerCondition(
    lhsRaw: String,
    operator: Int,
    rhsRaw: String,
    variables: VariableAccess,
): Boolean {
    val lhsValue = taskerOperand(lhsRaw, variables)
    val rhsValue = taskerOperand(rhsRaw, variables)
    return when (operator) {
        0 -> lhsValue == rhsValue
        1 -> lhsValue != rhsValue
        2 -> taskerSimplePattern(rhsValue).matches(lhsValue)
        3 -> !taskerSimplePattern(rhsValue).matches(lhsValue)
        4 -> runCatching { Regex(rhsValue).containsMatchIn(lhsValue) }.getOrDefault(false)
        5 -> !runCatching { Regex(rhsValue).containsMatchIn(lhsValue) }.getOrDefault(false)
        6 -> taskerNumber(lhsValue)?.let { left -> taskerNumber(rhsValue)?.let { left < it } } ?: false
        7 -> taskerNumber(lhsValue)?.let { left -> taskerNumber(rhsValue)?.let { left > it } } ?: false
        8 -> taskerNumber(lhsValue)?.let { left -> taskerNumber(rhsValue)?.let { left == it } }
            ?: (lhsValue.isEmpty() && rhsValue.isEmpty())
        9 -> taskerNumber(lhsValue)?.let { left -> taskerNumber(rhsValue)?.let { left != it } }
            ?: (lhsValue.isEmpty() != rhsValue.isEmpty())
        10 -> taskerNumber(lhsValue)?.let { abs(it % 2.0) < 1e-12 } ?: false
        11 -> taskerNumber(lhsValue)?.let { abs(it % 2.0) >= 1e-12 } ?: false
        12 -> taskerVariableIsSet(lhsRaw, variables)
        13 -> !taskerVariableIsSet(lhsRaw, variables)
        else -> false
    }
}

private fun taskerOperand(raw: String, variables: VariableAccess): String {
    if (raw.startsWith("%")) {
        val value = variables.get(raw)
        if (value != null && value != ConfigValue.NullValue) return taskerValueText(value)
    }
    return raw
}

private fun taskerVariableIsSet(raw: String, variables: VariableAccess): Boolean {
    if (!raw.startsWith("%")) return raw.isNotEmpty()
    val value = variables.get(raw) ?: return false
    return value != ConfigValue.NullValue && taskerValueText(value).isNotEmpty()
}

private fun taskerValueText(value: ConfigValue): String = when (value) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value.value
    is ConfigValue.NumberValue -> value.value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.value.toString()
    is ConfigValue.ListValue -> value.value.joinToString(",") { taskerValueText(it) }
    is ConfigValue.ObjectValue -> value.value.toString()
}

private fun taskerNumber(value: String): Double? = value.trim().toDoubleOrNull()

private fun taskerSimplePattern(pattern: String): Regex {
    val source = buildString {
        append("^")
        pattern.forEach { char ->
            when (char) {
                '*' -> append(".*")
                '+' -> append(".+")
                else -> append(Regex.escape(char.toString()))
            }
        }
        append("$")
    }
    return Regex(source, RegexOption.IGNORE_CASE)
}
