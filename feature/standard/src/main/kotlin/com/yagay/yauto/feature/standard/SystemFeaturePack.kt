package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.SystemOperations
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class SystemFeaturePack : FeaturePack {
    override val id: String = "standard.system"

    private val definitions: List<FeatureDefinition> = buildList {
        mapOf(
            SystemOperations.SLEEP to "Turn screen off",
            SystemOperations.EXPAND_NOTIFICATIONS to "Expand notifications",
            SystemOperations.COLLAPSE_PANELS to "Collapse system panels",
        ).forEach { (operation, title) ->
            add(
                actionFeature(
                    FeatureDescriptor(
                        id = FeatureId(operation),
                        kind = FeatureKind.ACTION,
                        title = title,
                        description = "Use an authorized system backend",
                        category = FeatureCategory.SYSTEM,
                        capabilities = setOf(CapabilityIds.SYSTEM_UI),
                    )
                ) { feature, context ->
                    val result = context.executeCapability(
                        feature.typeId,
                        CapabilityRequest(
                            capability = CapabilityIds.SYSTEM_UI,
                            operationId = operation,
                            preferredBackendId = feature.preferredBackendId(),
                        ),
                    )
                    ActionExecutionResult(result.success, result.value, result.message)
                },
            )
        }

        add(
            actionFeature(
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
                    fieldBehaviors = mapOf(
                        "command" to FieldBehavior(supportsVariables = true),
                    ),
                )
            ) { feature, context ->
                val command = feature.config.string("command").resolveVariables(context.variables)
                if (command.isBlank()) {
                    return@actionFeature ActionExecutionResult(false, message = userText("capability.shell_empty"))
                }
                val result = context.executeCapability(
                    featureId = feature.typeId,
                    request = CapabilityRequest(
                        capability = CapabilityIds.PRIVILEGED_SHELL,
                        operationId = "system.shell.execute",
                        payload = mapOf("command" to ConfigValue.StringValue(command)),
                        preferredBackendId = feature.preferredBackendId(),
                    ),
                )
                feature.config.string("resultVariable").takeIf(String::isNotBlank)?.let {
                    context.variables.set(it, result.value)
                }
                ActionExecutionResult(result.success, result.value, result.message)
            },
        )
    }

    override fun install(registry: FeatureRegistry) {
        DefinitionFeaturePack(id, definitions).install(registry)
    }
}
