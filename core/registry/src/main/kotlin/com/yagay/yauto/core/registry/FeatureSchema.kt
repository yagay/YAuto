package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue

/**
 * Optional behaviour attached to a descriptor field.
 *
 * FieldSchema describes the value shape; FieldBehavior describes how the generic editor should
 * present it. Keeping those concerns separate lets existing features remain source-compatible while
 * future features gain defaults, conditional UI and variable support without custom Compose code.
 */
data class FieldBehavior(
    val defaultValue: ConfigValue? = null,
    val visibleWhen: FieldRule? = null,
    val enabledWhen: FieldRule? = null,
    val advanced: Boolean = false,
    val supportsVariables: Boolean = false,
    val help: String? = null,
)

sealed interface FieldRule {
    val fieldKey: String

    data class Equals(
        override val fieldKey: String,
        val expected: ConfigValue,
    ) : FieldRule

    data class NotEquals(
        override val fieldKey: String,
        val expected: ConfigValue,
    ) : FieldRule

    data class Present(override val fieldKey: String) : FieldRule
    data class Truthy(override val fieldKey: String) : FieldRule
}

fun FeatureDescriptor.fieldBehavior(key: String): FieldBehavior = fieldBehaviors[key] ?: FieldBehavior()

fun FieldRule.matches(values: Map<String, ConfigValue>): Boolean {
    val current = values[fieldKey]
    return when (this) {
        is FieldRule.Equals -> current == expected
        is FieldRule.NotEquals -> current != expected
        is FieldRule.Present -> current != null && current != ConfigValue.NullValue && !current.isBlankLike()
        is FieldRule.Truthy -> current.isTruthy()
    }
}

private fun ConfigValue?.isTruthy(): Boolean = when (this) {
    is ConfigValue.BooleanValue -> value
    is ConfigValue.NumberValue -> value != 0.0
    is ConfigValue.StringValue -> value.equals("true", ignoreCase = true) || value == "1"
    else -> false
}

private fun ConfigValue.isBlankLike(): Boolean = when (this) {
    is ConfigValue.StringValue -> value.isBlank()
    ConfigValue.NullValue -> true
    else -> false
}
