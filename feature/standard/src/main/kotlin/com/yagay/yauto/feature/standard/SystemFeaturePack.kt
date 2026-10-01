package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class SystemFeaturePack : FeaturePack {
    override val id: String = "standard.system"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                id = FeatureId("system.shell.execute"),
                kind = FeatureKind.ACTION,
                title = "Run shell command",
                description = "Execute a command through the best available privileged shell backend",
                category = FeatureCategory.SCRIPT,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                fields = listOf(
                    FieldSchema.Text("command", "Command", true, true),
                    FieldSchema.Variable("resultVariable", "Store result in variable"),
                ),
                keywords = setOf("root", "shell", "adb", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = feature.config.string("command").resolveVariables(ctx.variables)
            if (command.isBlank()) return@registerAction ActionExecutionResult(false, message = "Shell command is empty")
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "system.shell.execute",
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            val variable = feature.config.string("resultVariable")
            if (variable.isNotBlank()) ctx.variables.set(variable, result.value)
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }
}
