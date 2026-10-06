package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef

/**
 * Optional behaviour attached to a descriptor field.
 *
 * FieldSchema describes the value shape; FieldBehavior describes how the generic editor should
 * present it. Keeping those concerns separate lets existing features remain source-compatible while
 * future features gain defaults, conditional UI and variable support without custom Compose code.
 */
enum class ComponentPickerKind { ACTIVITY, SERVICE, RECEIVER, PROVIDER, QUICK_SETTINGS_TILE }

data class FieldPickerOption(
    val value: String,
    val label: String = value,
)

sealed interface FieldPickerSource {
    data object InstalledApp : FieldPickerSource

    data class Component(
        val packageFieldKey: String? = null,
        val kinds: Set<ComponentPickerKind> = ComponentPickerKind.entries.toSet(),
    ) : FieldPickerSource

    data class Permission(val packageFieldKey: String = "package") : FieldPickerSource
    data object Subscription : FieldPickerSource
    data object Camera : FieldPickerSource
    data object AndroidUser : FieldPickerSource
    data object TimeZone : FieldPickerSource
    data object Locale : FieldPickerSource
    data object Calendar : FieldPickerSource
    data class Options(val options: List<FieldPickerOption>) : FieldPickerSource
}

data class FieldBehavior(
    val defaultValue: ConfigValue? = null,
    val visibleWhen: FieldRule? = null,
    val enabledWhen: FieldRule? = null,
    val advanced: Boolean = false,
    val supportsVariables: Boolean = false,
    val help: String? = null,
    val picker: FieldPickerSource? = null,
    val allowManualInput: Boolean = true,
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

/** Applies descriptor defaults without overwriting values explicitly stored by the user. */
fun FeatureDescriptor.applyDefaults(feature: FeatureRef): FeatureRef {
    if (fieldBehaviors.none { (_, behavior) -> behavior.defaultValue != null }) return feature
    val config = buildMap {
        fields.forEach { field -> fieldBehaviors[field.key]?.defaultValue?.let { put(field.key, it) } }
        putAll(feature.config)
    }
    return if (config == feature.config) feature else feature.copy(config = config)
}

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
