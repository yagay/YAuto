package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Narrow, system_server-backed status icon support. Unlike notification icons,
 * this requests a real StatusBarManager icon slot through the LSPosed system bridge.
 * It intentionally does not accept arbitrary resources or Android-owned icon slots.
 */
class AndroidStatusIconFeaturePack : FeaturePack {
    override val id = "android.status_icon"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.status_icon.control"), FeatureKind.ACTION,
                "Control system status bar icon",
                "Create or remove a YAuto-owned status bar icon using an Android built-in icon through LSPosed",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("mode", "Operation", true, listOf("show", "remove")),
                    FieldSchema.Text("slot", "YAuto icon slot (letters, digits, underscore)", true),
                    FieldSchema.Choice("icon", "Built-in icon", options = listOf("info", "warning", "lock", "upload", "save")),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                implementationOptions = listOf(FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED))),
                keywords = setOf("status bar", "system icon", "shortx", "lsposed"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val slot = feature.config.string("slot").resolveVariables(ctx.variables).trim()
            if (!statusSlotValid(slot)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.status_icon_bad_slot"))
            }
            val mode = feature.config.string("mode", "show")
            if (mode !in setOf("show", "remove")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.status_icon_bad_operation"))
            }
            val icon = feature.config.string("icon", "info")
            if (mode == "show" && icon !in STATUS_ICON_CHOICES) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.status_icon_bad_operation"))
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED,
                    operationId = if (mode == "show") "status_icon.set" else "status_icon.remove",
                    payload = mapOf(
                        "slot" to ConfigValue.StringValue(slot),
                        "icon" to ConfigValue.StringValue(icon),
                    ),
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }
}

internal val STATUS_ICON_CHOICES = setOf("info", "warning", "lock", "upload", "save")
internal fun statusSlotValid(raw: String): Boolean = Regex("[a-z][a-z0-9_]{0,23}").matches(raw)
