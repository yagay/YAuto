package com.yagay.yauto.feature.standard.persistent

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.PersistentVariableControl
import com.yagay.yauto.core.registry.conditionFeature
import com.yagay.yauto.core.registry.eventFeature
import com.yagay.yauto.core.registry.stateFeature

internal fun persistentVariablePredicates(control: PersistentVariableControl): List<FeatureDefinition> = listOf(
    conditionFeature(
        persistentEqualsDescriptor(
            FeatureKind.CONDITION,
            "variable.global.equals",
            "Persistent variable equals",
        )
    ) { feature, context -> persistentEquals(control, feature, context) },
    stateFeature(
        persistentEqualsDescriptor(
            FeatureKind.STATE,
            "variable.global.state.equals",
            "Persistent variable state equals",
        )
    ) { feature, context -> persistentEquals(control, feature, context) },
    conditionFeature(
        persistentDescriptor(
            "variable.global.exists",
            FeatureKind.CONDITION,
            "Persistent variable exists",
            "Check whether a persistent workspace variable exists",
            fields = listOf(FieldSchema.Text("name", "Variable name", true)),
            keywords = setOf("global", "persistent", "variable", "exists"),
            behaviors = mapOf("name" to FieldBehavior(supportsVariables = true)),
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        name.isNotBlank() && control.get(name) != null
    },
    eventFeature(
        persistentDescriptor(
            "core.event.variable_changed",
            FeatureKind.EVENT,
            "Persistent variable changed",
            "Run when a typed persistent workspace variable is created, changed, or removed",
            fields = listOf(
                FieldSchema.Text("name", "Variable name"),
                FieldSchema.Choice("change", "Change type", options = listOf("any", "created", "changed", "removed")),
            ),
            keywords = setOf("global", "persistent", "variable", "changed", "trigger"),
            behaviors = mapOf(
                "name" to FieldBehavior(supportsVariables = true),
                "change" to FieldBehavior(defaultValue = ConfigValue.StringValue("any")),
            ),
        )
    ) { feature, context ->
        if (context.event.typeId != "core.event.variable_changed") return@eventFeature false
        val expectedName = feature.config.string("name").resolveVariables(context.variables).trim()
        val expectedChange = feature.config.string("change", "any")
        (expectedName.isBlank() || context.event.payload.string("name") == expectedName) &&
            (expectedChange == "any" || context.event.payload.string("change") == expectedChange)
    },
)

private fun persistentEqualsDescriptor(kind: FeatureKind, id: String, title: String) = persistentDescriptor(
    id = id,
    kind = kind,
    title = title,
    description = "Compare a persistent workspace variable with a typed value",
    fields = persistentValueFields,
    keywords = setOf("global", "persistent", "variable", "compare"),
    behaviors = persistentValueBehaviors,
)

private suspend fun persistentEquals(
    control: PersistentVariableControl,
    feature: FeatureRef,
    context: FeatureExecutionContext,
): Boolean {
    val name = feature.config.string("name").resolveVariables(context.variables).trim()
    if (name.isBlank()) return false
    val actual = control.get(name) ?: return false
    val sourceName = feature.config.string("sourceVariable").trim()
    val expected = if (sourceName.isNotBlank()) {
        context.variables.get(sourceName) ?: ConfigValue.NullValue
    } else {
        persistentConfiguredValue(feature, context.variables) ?: return false
    }
    return actual == expected
}
