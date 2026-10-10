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
