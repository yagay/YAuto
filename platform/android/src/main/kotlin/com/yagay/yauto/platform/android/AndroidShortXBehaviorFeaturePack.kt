package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

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
                    operationId = SHORTX_BEHAVIOR_SET,
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
        registry.registerAction(
            FeatureDescriptor(
                id = FeatureId("android.shortx.compat.package_behavior.set"),
                kind = FeatureKind.ACTION,
                title = "Set ShortX app compatibility behavior",
                description = "Enable or disable a ShortX-compatible LSPosed behavior inside one scoped app process",
                category = FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Target app / package", required = true),
                    FieldSchema.Choice(
                        "behavior",
                        "App compatibility behavior",
                        true,
                        listOf("rendernode_guard"),
                    ),
                    FieldSchema.Toggle("enabled", "Enabled"),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED_HOOK),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                implementationOptions = listOf(
                    FeatureImplementationOption(
                        "lsposed",
                        setOf(AccessRequirement.LSPOSED),
                        restartRequired = true,
                    )
                ),
                keywords = setOf("shortx", "lsposed", "rendernode", "animator", "crash guard"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val targetPackage = feature.config.string("package").trim()
            val behavior = feature.config.string("behavior")
            if (targetPackage.isBlank() || behavior !in ALLOWED_PACKAGE_BEHAVIORS) {
                return@registerAction ActionExecutionResult(false)
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED_HOOK,
                    operationId = SHORTX_PACKAGE_BEHAVIOR_SET,
                    payload = mapOf(
                        "package" to ConfigValue.StringValue(targetPackage),
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
        const val SHORTX_BEHAVIOR_SET = "shortx.behavior.set"
        const val SHORTX_BEHAVIOR_ACCESSIBILITY = "accessibility_access"
        const val SHORTX_BEHAVIOR_CLIPBOARD = "clipboard_access"
        const val SHORTX_BEHAVIOR_PERMISSION = "permission_bridge"
        const val SHORTX_PACKAGE_BEHAVIOR_SET = "shortx.package_behavior.set"
        const val SHORTX_PACKAGE_BEHAVIOR_RENDERNODE_GUARD = "rendernode_guard"

        val ALLOWED_BEHAVIORS = setOf(
            SHORTX_BEHAVIOR_ACCESSIBILITY,
            SHORTX_BEHAVIOR_CLIPBOARD,
            SHORTX_BEHAVIOR_PERMISSION,
        )
        val ALLOWED_PACKAGE_BEHAVIORS = setOf(
            SHORTX_PACKAGE_BEHAVIOR_RENDERNODE_GUARD,
        )
    }
}
