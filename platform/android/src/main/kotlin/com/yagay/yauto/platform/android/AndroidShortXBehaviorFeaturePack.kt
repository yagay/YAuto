package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.platform.xposed.SystemBridgeProtocol

class AndroidShortXBehaviorFeaturePack : FeaturePack {
    override val id: String = "android.shortx_behaviors"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                id = FeatureId("android.shortx.compat.behavior.set"),
                kind = FeatureKind.ACTION,
                title = "Set ShortX compatibility bridge",
                description = "Enable or disable a YAuto-scoped LSPosed compatibility behavior",
                category = FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Choice(
                        "behavior",
                        "Compatibility behavior",
                        true,
                        listOf("accessibility_access", "clipboard_access", "permission_bridge"),
                    ),
                    FieldSchema.Toggle("enabled", "Enabled"),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                implementationOptions = listOf(
                    FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true)
                ),
                keywords = setOf("shortx", "lsposed", "accessibility", "clipboard", "permission", "bridge"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val behavior = feature.config.string("behavior")
            if (behavior !in ALLOWED_BEHAVIORS) {
                return@registerAction ActionExecutionResult(false)
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED,
                    operationId = SystemBridgeProtocol.SHORTX_BEHAVIOR_SET,
                    payload = mapOf(
                        "behavior" to ConfigValue.StringValue(behavior),
                        "enabled" to ConfigValue.BooleanValue(feature.config.boolean("enabled")),
                    ),
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private companion object {
        val ALLOWED_BEHAVIORS = setOf(
            SystemBridgeProtocol.SHORTX_BEHAVIOR_ACCESSIBILITY,
            SystemBridgeProtocol.SHORTX_BEHAVIOR_CLIPBOARD,
            SystemBridgeProtocol.SHORTX_BEHAVIOR_PERMISSION,
        )
    }
}
