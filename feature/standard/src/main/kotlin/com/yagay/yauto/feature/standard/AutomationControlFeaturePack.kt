package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AutomationControlFeaturePack(
    private val control: AutomationControl,
) : FeaturePack {
    override val id: String = "standard.automation.control"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("core.automation.run"), FeatureKind.ACTION,
                "Run automation", "Run another YAuto automation by stable ID or exact name",
                FeatureCategory.FLOW,
                fields = listOf(
                    FieldSchema.Text("target", "Target automation", true),
                    FieldSchema.Toggle("allowDisabled", "Allow disabled automation"),
                    FieldSchema.Variable("resultVariable", "Store return value"),
                ),
                keywords = setOf("automation", "run", "invoke", "macro", "task"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val target = feature.config.string("target").resolveVariables(ctx.variables)
            val allowDisabled = (feature.config["allowDisabled"] as? com.yagay.yauto.core.model.ConfigValue.BooleanValue)?.value ?: false
            val result = control.run(target, ctx.variables.snapshot(), allowDisabled)
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let {
                    ctx.variables.set(it, result.value)
                }
            }
            result
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("core.automation.set_enabled"), FeatureKind.ACTION,
                "Set automation state", "Enable, disable, or toggle another YAuto automation",
                FeatureCategory.FLOW,
                fields = listOf(
                    FieldSchema.Text("target", "Target automation", true),
                    FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle")),
                    FieldSchema.Variable("resultVariable", "Store enabled state"),
                ),
                keywords = setOf("automation", "enable", "disable", "toggle", "state"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val target = feature.config.string("target").resolveVariables(ctx.variables)
            val mode = when (feature.config.string("mode", "toggle").lowercase()) {
                "enable" -> AutomationEnableMode.ENABLE
                "disable" -> AutomationEnableMode.DISABLE
                else -> AutomationEnableMode.TOGGLE
            }
            val result = control.setEnabled(target, mode)
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let {
                    ctx.variables.set(it, result.value)
                }
            }
            result
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("core.automation.cancel"), FeatureKind.ACTION,
                "Cancel automation", "Cancel currently running executions of another YAuto automation",
                FeatureCategory.FLOW,
                fields = listOf(
                    FieldSchema.Text("target", "Target automation", true),
                    FieldSchema.Variable("resultVariable", "Store whether anything was cancelled"),
                ),
                keywords = setOf("automation", "cancel", "stop", "running"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val target = feature.config.string("target").resolveVariables(ctx.variables)
            val result = control.cancel(target)
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let {
                    ctx.variables.set(it, result.value)
                }
            }
            result
        }

        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("core.automation.enabled"), FeatureKind.CONDITION,
                "Automation enabled", "Check whether a YAuto automation is currently enabled",
                FeatureCategory.FLOW,
                fields = listOf(FieldSchema.Text("target", "Target automation", true)),
                keywords = setOf("automation", "enabled", "disabled", "state"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val target = feature.config.string("target").resolveVariables(ctx.variables)
            control.isEnabled(target) == true
        }
    }
}
