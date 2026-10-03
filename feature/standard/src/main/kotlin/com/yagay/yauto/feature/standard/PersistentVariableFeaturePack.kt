package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class PersistentVariableFeaturePack(
    private val control: PersistentVariableControl,
) : FeaturePack {
    override val id: String = "standard.variable.persistent"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.global.set"), FeatureKind.ACTION,
                "Set persistent variable", "Store a typed variable in the YAuto workspace for future automation runs",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("name", "Variable name", true),
                    FieldSchema.Variable("sourceVariable", "Source runtime variable"),
                    FieldSchema.Choice("valueType", "Value type", options = listOf("text", "number", "boolean", "null")),
                    FieldSchema.Text("value", "Value", multiline = true),
                ),
                keywords = setOf("global", "persistent", "variable", "store", "workspace"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (name.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            val sourceName = feature.config.string("sourceVariable").trim()
            val value = if (sourceName.isNotBlank()) {
                ctx.variables.get(sourceName) ?: ConfigValue.NullValue
            } else {
                persistentConfiguredValue(feature, ctx.variables)
                    ?: return@registerAction ActionExecutionResult(false, message = userText("feature.invalid_persistent_value"))
            }
            val change = control.set(name, value)
            if (change.success) {
                ctx.variables.set(name, value)
                ActionExecutionResult(true, value)
            } else {
                ActionExecutionResult(false, message = userText("feature.persistent_variable_update_failed"))
            }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.global.get"), FeatureKind.ACTION,
                "Get persistent variable", "Load a persistent workspace variable into the current automation run",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("name", "Variable name", true),
                    FieldSchema.Variable("resultVariable", "Destination variable", true),
                ),
                keywords = setOf("global", "persistent", "variable", "load", "workspace"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            val destination = feature.config.string("resultVariable").trim()
            if (name.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            if (destination.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            val value = control.get(name) ?: ConfigValue.NullValue
            ctx.variables.set(destination, value)
            ActionExecutionResult(true, value)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.global.clear"), FeatureKind.ACTION,
                "Clear persistent variable", "Remove a persistent variable from the YAuto workspace",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Text("name", "Variable name", true)),
                keywords = setOf("global", "persistent", "variable", "remove", "workspace"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            if (name.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.variable_name_empty"))
            val change = control.clear(name)
            if (change.success) {
                ctx.variables.set(name, ConfigValue.NullValue)
                ActionExecutionResult(true, change.previous ?: ConfigValue.NullValue)
            } else {
                ActionExecutionResult(false, message = userText("feature.persistent_variable_clear_failed"))
            }
        }

        registry.registerCondition(persistentEqualsDescriptor(FeatureKind.CONDITION, "variable.global.equals", "Persistent variable equals")) { feature, ctx ->
            persistentEquals(feature, ctx)
        }
        registry.registerState(persistentEqualsDescriptor(FeatureKind.STATE, "variable.global.state.equals", "Persistent variable state equals")) { feature, ctx ->
            persistentEquals(feature, ctx)
        }
        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("variable.global.exists"), FeatureKind.CONDITION,
                "Persistent variable exists", "Check whether a persistent workspace variable exists",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Text("name", "Variable name", true)),
                keywords = setOf("global", "persistent", "variable", "exists"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
            name.isNotBlank() && control.get(name) != null
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("core.event.variable_changed"), FeatureKind.EVENT,
                "Persistent variable changed", "Run when a typed persistent workspace variable is created, changed, or removed",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("name", "Variable name"),
                    FieldSchema.Choice("change", "Change type", options = listOf("any", "created", "changed", "removed")),
                ),
                keywords = setOf("global", "persistent", "variable", "changed", "trigger"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "core.event.variable_changed") return@registerEvent false
            val expectedName = feature.config.string("name").resolveVariables(ctx.variables).trim()
            val expectedChange = feature.config.string("change", "any")
            (expectedName.isBlank() || ctx.event.payload.string("name") == expectedName) &&
                (expectedChange == "any" || ctx.event.payload.string("change") == expectedChange)
        }
    }

    private fun persistentEqualsDescriptor(kind: FeatureKind, featureId: String, title: String) = FeatureDescriptor(
        FeatureId(featureId), kind, title, "Compare a persistent workspace variable with a typed value",
        FeatureCategory.VARIABLE,
        fields = listOf(
            FieldSchema.Text("name", "Variable name", true),
            FieldSchema.Variable("sourceVariable", "Source runtime variable"),
            FieldSchema.Choice("valueType", "Value type", options = listOf("text", "number", "boolean", "null")),
            FieldSchema.Text("value", "Value", multiline = true),
        ),
        keywords = setOf("global", "persistent", "variable", "compare"),
        ownerPackId = id,
    )

    private suspend fun persistentEquals(feature: FeatureRef, ctx: FeatureExecutionContext): Boolean {
        val name = feature.config.string("name").resolveVariables(ctx.variables).trim()
        if (name.isBlank()) return false
        val actual = control.get(name) ?: return false
        val sourceName = feature.config.string("sourceVariable").trim()
        val expected = if (sourceName.isNotBlank()) {
            ctx.variables.get(sourceName) ?: ConfigValue.NullValue
        } else {
            persistentConfiguredValue(feature, ctx.variables) ?: return false
        }
        return actual == expected
    }
}

private fun persistentConfiguredValue(feature: FeatureRef, variables: VariableAccess): ConfigValue? {
    val raw = feature.config.string("value").resolveVariables(variables)
    return when (feature.config.string("valueType", "text")) {
        "number" -> raw.toDoubleOrNull()?.takeIf { it.isFinite() }?.let(ConfigValue::NumberValue)
        "boolean" -> when (raw.trim().lowercase()) {
            "true", "1", "yes", "on" -> ConfigValue.BooleanValue(true)
            "false", "0", "no", "off" -> ConfigValue.BooleanValue(false)
            else -> null
        }
        "null" -> ConfigValue.NullValue
        else -> ConfigValue.StringValue(raw)
    }
}
