package com.yagay.yauto.platform.android

import android.content.ComponentName
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Scoped CustomTile label customization; stock tiles and icons are untouched. */
class AndroidShortXTileLabelFeaturePack : FeaturePack {
    override val id = "android.shortx.tile_label"
    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.qs_tile.custom_label"),
                FeatureKind.ACTION,
                "Override external Quick Settings tile label",
                "Change or restore one third-party custom tile label through scoped SystemUI LSPosed",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("component", "Tile service component (package/class)", true),
                    FieldSchema.Text("label", "Replacement tile label"),
                    FieldSchema.Choice("operation", "Operation", options = listOf("set", "clear")),
                ),
                fieldBehaviors = mapOf(
                    "component" to FieldBehavior(supportsVariables = true),
                    "label" to FieldBehavior(supportsVariables = true,
                        visibleWhen = FieldRule.NotEquals("operation", ConfigValue.StringValue("clear"))),
                    "operation" to FieldBehavior(defaultValue = ConfigValue.StringValue("set")),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                capabilities = setOf(CapabilityIds.LSPOSED),
                implementationOptions = listOf(FeatureImplementationOption(
                    "lsposed", setOf(AccessRequirement.LSPOSED)
                )),
                keywords = setOf("ShortX", "LSPosed", "SystemUI", "QS", "custom tile", "label"),
                ownerPackId = id,
            )
        ) { item, ctx ->
            val raw = item.config.string("component").resolveVariables(ctx.variables).trim()
            val component = ComponentName.unflattenFromString(raw)
                ?: return@registerAction ActionExecutionResult(false, message = userText("shortx.tile.invalid_component"))
            val operation = item.config.string("operation", "set")
            val label = item.config.string("label").resolveVariables(ctx.variables).trim()
            if (operation !in setOf("set", "clear") ||
                (operation == "set" && (label.isEmpty() || label.length > 64 || '\n' in label))) {
                return@registerAction ActionExecutionResult(false, message = userText("shortx.tile.invalid_operation"))
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED,
                    operationId = if (operation == "clear") "qs_tile.label.clear" else "qs_tile.label.set",
                    payload = mapOf(
                        "component" to ConfigValue.StringValue(component.flattenToString()),
                        "label" to ConfigValue.StringValue(label),
                    ),
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }
}
