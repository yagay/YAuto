package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AutomationControlFeaturePack(
    control: AutomationControl,
) : FeaturePack {
    override val id: String = "standard.automation.control"

    private val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            automationDescriptor(
                "core.automation.run",
                FeatureKind.ACTION,
                "Run automation",
                "Run another YAuto automation by stable ID or exact name",
                fields = listOf(
                    FieldSchema.Text("target", "Target automation", true),
                    FieldSchema.Toggle("allowDisabled", "Allow disabled automation"),
                    FieldSchema.Variable("resultVariable", "Store return value"),
                ),
                keywords = setOf("automation", "run", "invoke", "macro", "task"),
                behaviors = mapOf(
                    "target" to FieldBehavior(supportsVariables = true),
                    "allowDisabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false)),
                ),
            )
        ) { feature, context ->
            val target = feature.config.string("target").resolveVariables(context.variables)
            val allowDisabled = (feature.config["allowDisabled"] as? ConfigValue.BooleanValue)?.value ?: false
            val result = control.run(target, context.variables.snapshot(), allowDisabled)
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                    context.variables.set(it, result.value)
                }
            }
            result
        },
        actionFeature(
            automationDescriptor(
                "core.automation.set_enabled",
                FeatureKind.ACTION,
                "Set automation state",
                "Enable, disable, or toggle another YAuto automation",
                fields = listOf(
                    FieldSchema.Text("target", "Target automation", true),
                    FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle")),
                    FieldSchema.Variable("resultVariable", "Store enabled state"),
                ),
                keywords = setOf("automation", "enable", "disable", "toggle", "state"),
                behaviors = mapOf(
                    "target" to FieldBehavior(supportsVariables = true),
                    "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("toggle")),
                ),
            )
        ) { feature, context ->
            val target = feature.config.string("target").resolveVariables(context.variables)
            val mode = when (feature.config.string("mode", "toggle").lowercase()) {
                "enable" -> AutomationEnableMode.ENABLE
                "disable" -> AutomationEnableMode.DISABLE
                else -> AutomationEnableMode.TOGGLE
            }
            val result = control.setEnabled(target, mode)
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                    context.variables.set(it, result.value)
                }
            }
            result
        },
        actionFeature(
            automationDescriptor(
                "core.automation.cancel",
                FeatureKind.ACTION,
                "Cancel automation",
                "Cancel currently running executions of another YAuto automation",
                fields = listOf(
                    FieldSchema.Text("target", "Target automation", true),
                    FieldSchema.Variable("resultVariable", "Store whether anything was cancelled"),
                ),
                keywords = setOf("automation", "cancel", "stop", "running"),
                behaviors = mapOf("target" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val target = feature.config.string("target").resolveVariables(context.variables)
            val result = control.cancel(target)
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                    context.variables.set(it, result.value)
                }
            }
            result
        },
        actionFeature(
            automationDescriptor(
                "core.category.set_enabled",
                FeatureKind.ACTION,
                "Set automation category state",
                "Enable, disable, or toggle a named automation category",
                fields = listOf(
                    FieldSchema.Text("category", "Category", true),
                    FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "toggle")),
                    FieldSchema.Variable("resultVariable", "Store enabled state"),
                ),
                keywords = setOf("category", "group", "enable", "disable", "macrodroid"),
                behaviors = mapOf(
                    "category" to FieldBehavior(supportsVariables = true),
                    "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("toggle")),
                ),
            )
        ) { feature, context ->
            val category = feature.config.string("category").resolveVariables(context.variables)
            val mode = when (feature.config.string("mode", "toggle").lowercase()) {
                "enable" -> AutomationEnableMode.ENABLE
                "disable" -> AutomationEnableMode.DISABLE
                else -> AutomationEnableMode.TOGGLE
            }
            val result = control.setCategoryEnabled(category, mode)
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                    context.variables.set(it, result.value)
                }
            }
            result
        },
        conditionFeature(
            automationDescriptor(
                "core.category.enabled",
                FeatureKind.CONDITION,
                "Automation category enabled",
                "Check whether a named automation category is enabled",
                fields = listOf(
                    FieldSchema.Text("category", "Category", true),
                    FieldSchema.Toggle("value", "Enabled"),
                ),
                keywords = setOf("category", "enabled", "disabled", "macrodroid"),
                behaviors = mapOf("category" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val category = feature.config.string("category").resolveVariables(context.variables)
            val expected = (feature.config["value"] as? ConfigValue.BooleanValue)?.value ?: true
            control.isCategoryEnabled(category) == expected
        },
        conditionFeature(
            automationDescriptor(
                "core.automation.last_run",
                FeatureKind.CONDITION,
                "Automation last run",
                "Compare how long ago another automation last finished",
                fields = listOf(
                    FieldSchema.Text("target", "Target automation", true),
                    FieldSchema.Choice("operator", "Compare", true, listOf("within", "older_than", "never", "ever")),
                    FieldSchema.Duration("durationMs", "Duration"),
                ),
                keywords = setOf("last run", "last executed", "macro", "macrodroid"),
                behaviors = mapOf(
                    "target" to FieldBehavior(supportsVariables = true),
                    "operator" to FieldBehavior(defaultValue = ConfigValue.StringValue("within")),
                ),
            )
        ) { feature, context ->
            val target = feature.config.string("target").resolveVariables(context.variables)
            val last = control.lastRunEpochMs(target)
            when (feature.config.string("operator", "within")) {
                "never" -> last == null
                "ever" -> last != null
                "older_than" -> {
                    val duration = (feature.config["durationMs"] as? ConfigValue.NumberValue)?.value
                        ?.toLong()?.coerceAtLeast(0L) ?: 0L
                    last != null && System.currentTimeMillis() - last >= duration
                }
                else -> {
                    val duration = (feature.config["durationMs"] as? ConfigValue.NumberValue)?.value
                        ?.toLong()?.coerceAtLeast(0L) ?: 0L
                    last != null && System.currentTimeMillis() - last <= duration
                }
            }
        },
        eventFeature(
            automationDescriptor(
                "core.event.category_enabled_changed",
                FeatureKind.EVENT,
                "Automation category state changed",
                "Run when a YAuto automation category is enabled or disabled",
                fields = listOf(
                    FieldSchema.Text("category", "Category"),
                    FieldSchema.Choice("state", "State", options = listOf("any", "enabled", "disabled")),
                ),
                keywords = setOf("category", "enabled changed", "macrodroid"),
                behaviors = mapOf("category" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            if (context.event.typeId != "core.event.category_enabled_changed") return@eventFeature false
            val category = feature.config.string("category").resolveVariables(context.variables)
            val matchesCategory = category.isBlank() ||
                context.event.payload.string("category").equals(category, ignoreCase = true)
            val enabled = (context.event.payload["enabled"] as? ConfigValue.BooleanValue)?.value
            matchesCategory && when (feature.config.string("state", "any")) {
                "enabled" -> enabled == true
                "disabled" -> enabled == false
                else -> true
            }
        },
        eventFeature(
            automationDescriptor(
                "core.event.automation_enabled_changed",
                FeatureKind.EVENT,
                "Automation enabled state changed",
                "Run when another YAuto automation is enabled or disabled",
                fields = listOf(
                    FieldSchema.Text("target", "Target automation"),
                    FieldSchema.Choice("state", "State", options = listOf("any", "enabled", "disabled")),
                ),
                keywords = setOf("automation", "enabled", "disabled", "changed", "macrodroid"),
                behaviors = mapOf("target" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            if (context.event.typeId != "core.event.automation_enabled_changed") return@eventFeature false
            val target = feature.config.string("target").resolveVariables(context.variables).trim()
            val targetMatches = target.isBlank() ||
                context.event.payload.string("automationId") == target ||
                context.event.payload.string("automationName").equals(target, true)
            val enabled = (context.event.payload["enabled"] as? ConfigValue.BooleanValue)?.value
            targetMatches && when (feature.config.string("state", "any")) {
                "enabled" -> enabled == true
                "disabled" -> enabled == false
                else -> true
            }
        },
        conditionFeature(
            automationDescriptor(
                "core.automation.running",
                FeatureKind.CONDITION,
                "Automation running",
                "Check whether a YAuto automation currently has an active execution",
                fields = listOf(FieldSchema.Text("target", "Target automation", true)),
                keywords = setOf("automation", "running", "active", "macro", "task"),
                behaviors = mapOf("target" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val target = feature.config.string("target").resolveVariables(context.variables)
            control.isRunning(target) == true
        },
        conditionFeature(
            automationDescriptor(
                "core.automation.enabled",
                FeatureKind.CONDITION,
                "Automation enabled",
                "Check whether a YAuto automation is currently enabled",
                fields = listOf(FieldSchema.Text("target", "Target automation", true)),
                keywords = setOf("automation", "enabled", "disabled", "state"),
                behaviors = mapOf("target" to FieldBehavior(supportsVariables = true)),
            )
        ) { feature, context ->
            val target = feature.config.string("target").resolveVariables(context.variables)
            control.isEnabled(target) == true
        },
    )

    override fun install(registry: FeatureRegistry) {
        DefinitionFeaturePack(id, definitions).install(registry)
    }

    private fun automationDescriptor(
        featureId: String,
        kind: FeatureKind,
        title: String,
        description: String,
        fields: List<FieldSchema>,
        keywords: Set<String>,
        behaviors: Map<String, FieldBehavior> = emptyMap(),
    ) = FeatureDescriptor(
        id = FeatureId(featureId),
        kind = kind,
        title = title,
        description = description,
        category = FeatureCategory.FLOW,
        fields = fields,
        keywords = keywords,
        fieldBehaviors = behaviors,
    )
}
