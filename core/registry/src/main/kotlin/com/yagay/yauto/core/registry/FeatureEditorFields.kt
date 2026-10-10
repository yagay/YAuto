package com.yagay.yauto.core.registry

import com.yagay.yauto.core.model.ConfigValue

/** Single descriptor-driven visibility contract for all generic feature editors. */
fun FeatureDescriptor.visibleEditorFields(
    config: Map<String, ConfigValue>,
    showAdvanced: Boolean,
    excludedKeys: Set<String> = emptySet(),
): List<FieldSchema> = fields.filter { field ->
    field.key !in excludedKeys &&
        fieldBehavior(field.key).visibleWhen?.matches(config) != false &&
        (showAdvanced || !fieldBehavior(field.key).advanced)
}

// The same rule must govern rendering, saving and validating each feature field.
fun FeatureDescriptor.isEditorFieldActive(
    field: FieldSchema,
    config: Map<String, ConfigValue>,
): Boolean {
    val behavior = fieldBehavior(field.key)
    return behavior.visibleWhen?.matches(config) != false &&
        behavior.enabledWhen?.matches(config) != false
}

fun FeatureDescriptor.editableEditorFields(
    config: Map<String, ConfigValue>,
    excludedKeys: Set<String> = emptySet(),
): List<FieldSchema> = fields.filter { field ->
    field.key !in excludedKeys && isEditorFieldActive(field, config)
}
