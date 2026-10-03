package com.yagay.yauto.feature.standard.persistent

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.PersistentVariableControl
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.resolveVariables

internal fun persistentVariableActions(control: PersistentVariableControl): List<FeatureDefinition> = listOf(
    actionFeature(
        persistentDescriptor(
            "variable.global.set",
            FeatureKind.ACTION,
            "Set persistent variable",
            "Store a typed variable in the YAuto workspace for future automation runs",
            fields = persistentValueFields,
            keywords = setOf("global", "persistent", "variable", "store", "workspace"),
            behaviors = persistentValueBehaviors,
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        if (name.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
        }
        val sourceName = feature.config.string("sourceVariable").trim()
        val value = if (sourceName.isNotBlank()) {
            context.variables.get(sourceName) ?: ConfigValue.NullValue
        } else {
            persistentConfiguredValue(feature, context.variables)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.invalid_persistent_value"))
        }
        val change = control.set(name, value)
        if (change.success) {
            context.variables.set(name, value)
            ActionExecutionResult(true, value)
        } else {
            ActionExecutionResult(false, message = userText("feature.persistent_variable_update_failed"))
        }
    },
    actionFeature(
        persistentDescriptor(
            "variable.global.get",
            FeatureKind.ACTION,
            "Get persistent variable",
            "Load a persistent workspace variable into the current automation run",
            fields = listOf(
                FieldSchema.Text("name", "Variable name", true),
                FieldSchema.Variable("resultVariable", "Destination variable", true),
            ),
            keywords = setOf("global", "persistent", "variable", "load", "workspace"),
            behaviors = mapOf("name" to FieldBehavior(supportsVariables = true)),
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        val destination = feature.config.string("resultVariable").trim()
        if (name.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
        }
        if (destination.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
        }
        val value = control.get(name) ?: ConfigValue.NullValue
        context.variables.set(destination, value)
        ActionExecutionResult(true, value)
    },
    actionFeature(
        persistentDescriptor(
            "variable.global.clear",
            FeatureKind.ACTION,
            "Clear persistent variable",
            "Remove a persistent variable from the YAuto workspace",
            fields = listOf(FieldSchema.Text("name", "Variable name", true)),
            keywords = setOf("global", "persistent", "variable", "remove", "workspace"),
            behaviors = mapOf("name" to FieldBehavior(supportsVariables = true)),
        )
    ) { feature, context ->
        val name = feature.config.string("name").resolveVariables(context.variables).trim()
        if (name.isBlank()) {
            return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
        }
        val change = control.clear(name)
        if (change.success) {
            context.variables.set(name, ConfigValue.NullValue)
            ActionExecutionResult(true, change.previous ?: ConfigValue.NullValue)
        } else {
            ActionExecutionResult(false, message = userText("feature.persistent_variable_clear_failed"))
        }
    },
)
