package com.yagay.yauto

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.platform.xposed.SystemBridgeProtocol

/** Intentional narrow subset: no arbitrary Root or shell execution inside SystemUI. */
internal class ShortXSystemUiShellFeaturePack : FeaturePack {
    override val id = "android.shortx.systemui_query"
    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("systemui.shell.query"), FeatureKind.ACTION,
                userText("shortx.systemui.title"),
                userText("shortx.systemui.detail"),
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("command", userText("shortx.systemui.command"), true),
                    FieldSchema.Variable("resultVariable", "Store output"),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED),
                keywords = setOf("systemui", "shell", "getprop", "read only", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val cmd = feature.config.string("command").trim()
            if (cmd != "id" && !cmd.matches(Regex("getprop [A-Za-z0-9_.-]{1,128}"))) {
                return@registerAction ActionExecutionResult(false)
            }
            val result = ctx.capabilities.execute(CapabilityRequest(
                capability = CapabilityIds.LSPOSED,
                operationId = SystemBridgeProtocol.STATUS_SHELL_QUERY,
                payload = mapOf("command" to ConfigValue.StringValue(cmd)),
                preferredBackendId = "lsposed",
            ))
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }
                    ?.let { ctx.variables.set(it, result.value ?: ConfigValue.StringValue("")) }
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }
}
