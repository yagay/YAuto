package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.UUID

class AndroidLsposedFeaturePack : FeaturePack {
    override val id: String = "android.lsposed"

    override fun install(registry: FeatureRegistry) {
        registerMethodHook(registry)
        registerDisableSession(registry)
        registerMethodCalled(registry)
        registerSystemOperation(registry)
        registerSensorsOff(registry)
    }

    private fun registerMethodHook(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.lsposed.hook.install_session"),
                FeatureKind.ACTION,
                "Install LSPosed method hook",
                "Install a session-only API-102 method hook in a scoped and currently running target app",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Target app / package", true),
                    FieldSchema.Text("className", "Class name", true),
                    FieldSchema.Choice("memberKind", "Hook target", options = listOf("method", "constructor")),
                    FieldSchema.Text("methodName", "Method name"),
                    FieldSchema.Number("parameterCount", "Parameter count (-1 = any overload)", min = -1.0, max = 64.0),
                    FieldSchema.Text("parameterTypes", "Parameter types (comma-separated Java names)"),
                    FieldSchema.Text("returnType", "Return type (Java name)"),
                    FieldSchema.Choice("lifecycle", "Observe lifecycle", true, listOf("before", "after")),
                    FieldSchema.Choice("mode", "Hook mode", true, listOf("observe", "replace")),
                    FieldSchema.Choice("replacementType", "Replacement type", options = listOf("null", "boolean", "int", "long", "float", "double", "string")),
                    FieldSchema.Text("replacementValue", "Replacement value"),
                    FieldSchema.Text("sessionId", "Session ID (blank = generated)"),
                    FieldSchema.Variable("resultVariable", "Store hook session object"),
                ),
                fieldBehaviors = mapOf(
                    "package" to FieldBehavior(supportsVariables = true),
                    "className" to FieldBehavior(supportsVariables = true),
                    "memberKind" to FieldBehavior(defaultValue = ConfigValue.StringValue("method")),
                    "methodName" to FieldBehavior(
                        supportsVariables = true,
                        visibleWhen = FieldRule.Equals("memberKind", ConfigValue.StringValue("method")),
                    ),
                    "replacementValue" to FieldBehavior(
                        supportsVariables = true,
                        visibleWhen = FieldRule.Equals("mode", ConfigValue.StringValue("replace")),
                    ),
                    "replacementType" to FieldBehavior(
                        visibleWhen = FieldRule.Equals("mode", ConfigValue.StringValue("replace")),
                    ),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED_HOOK),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("lsposed", "xposed", "method hook", "hook", "intercept", "ShortX"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val className = feature.config.string("className").resolveVariables(ctx.variables).trim()
            val methodName = feature.config.string("methodName").resolveVariables(ctx.variables).trim()
            val memberKind = feature.config.string("memberKind", "method")
            val parameterCount = (feature.config["parameterCount"].numberOrNull() ?: -1.0).toInt()
            val parameterTypes = feature.config.string("parameterTypes").resolveVariables(ctx.variables).trim()
            val returnType = feature.config.string("returnType").resolveVariables(ctx.variables).trim()
            val lifecycle = feature.config.string("lifecycle", "before")
            val mode = feature.config.string("mode", "observe")
            val replacementType = feature.config.string("replacementType", "null")
            val replacementValue = feature.config.string("replacementValue").resolveVariables(ctx.variables)
            val requestedSession = feature.config.string("sessionId").resolveVariables(ctx.variables).trim()
            val sessionId = requestedSession.ifBlank { "hook-" + UUID.randomUUID().toString().replace("-", "") }
            if (!PACKAGE_NAME.matches(pkg) || !CLASS_NAME.matches(className) ||
                memberKind !in setOf("method", "constructor") ||
                (memberKind == "method" && !METHOD_NAME.matches(methodName)) ||
                (memberKind == "constructor" && mode != "observe") ||
                parameterCount !in -1..64 || mode !in setOf("observe", "replace")
            ) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.lsposed_hook_input_invalid"))
            }
            val eventToken = UUID.randomUUID().toString() + UUID.randomUUID().toString()
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED_HOOK,
                    operationId = "lsposed.hook.install_session",
                    payload = mapOf(
                        "package" to ConfigValue.StringValue(pkg),
                        "className" to ConfigValue.StringValue(className),
                        "memberKind" to ConfigValue.StringValue(memberKind),
                        "methodName" to ConfigValue.StringValue(if (memberKind == "constructor") "<init>" else methodName),
                        "parameterCount" to ConfigValue.NumberValue(parameterCount.toDouble()),
                        "parameterTypes" to ConfigValue.StringValue(parameterTypes),
                        "returnType" to ConfigValue.StringValue(returnType),
                        "lifecycle" to ConfigValue.StringValue(lifecycle),
                        "mode" to ConfigValue.StringValue(mode),
                        "replacementType" to ConfigValue.StringValue(replacementType),
                        "replacementValue" to ConfigValue.StringValue(replacementValue),
                        "sessionId" to ConfigValue.StringValue(sessionId),
                        "eventToken" to ConfigValue.StringValue(eventToken),
                    ),
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val backend = result.value as? ConfigValue.ObjectValue
            val hookedCount = (backend?.value?.get("hookedCount") as? ConfigValue.NumberValue)?.value ?: 0.0
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "sessionId" to ConfigValue.StringValue(sessionId),
                    "package" to ConfigValue.StringValue(pkg),
                    "className" to ConfigValue.StringValue(className),
                    "methodName" to ConfigValue.StringValue(if (memberKind == "constructor") "<init>" else methodName),
                    "memberKind" to ConfigValue.StringValue(memberKind),
                    "hookedCount" to ConfigValue.NumberValue(hookedCount),
                    "mode" to ConfigValue.StringValue(mode),
                )
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDisableSession(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.lsposed.hook.disable_session"),
                FeatureKind.ACTION,
                "Disable LSPosed hook session",
                "Stop observing or replacing methods in a running scoped app without rebooting; the physical hook remains until the app process restarts",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Target app / package", true),
                    FieldSchema.Text("sessionId", "Session ID", true),
                ),
                fieldBehaviors = mapOf(
                    "package" to FieldBehavior(supportsVariables = true),
                    "sessionId" to FieldBehavior(supportsVariables = true),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED_HOOK),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("lsposed", "hook", "disable", "stop", "session", "ShortX"),
                ownerPackId = id,
            )
        ) { item, ctx ->
            val pkg = item.config.string("package").resolveVariables(ctx.variables).trim()
            val session = item.config.string("sessionId").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg) || !Regex("[A-Za-z0-9_.:-]{1,96}").matches(session)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.lsposed_hook_input_invalid"))
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED_HOOK,
                    operationId = "lsposed.hook.disable_session",
                    payload = mapOf(
                        "package" to ConfigValue.StringValue(pkg),
                        "sessionId" to ConfigValue.StringValue(session),
                    ),
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun registerMethodCalled(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.lsposed_method_called"),
                FeatureKind.EVENT,
                "LSPosed method called",
                "Run when an observed YAuto LSPosed hook session intercepts a target method call",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("sessionId", "Session ID"),
                    FieldSchema.AppPicker("package", "Target app / package"),
                    FieldSchema.Text("className", "Class name"),
                    FieldSchema.Text("methodName", "Method name"),
                    FieldSchema.Choice("lifecycle", "Lifecycle", options = listOf("any", "before", "after")),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("lsposed", "xposed", "method called", "hook event", "ShortX"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.lsposed_method_called") return@registerEvent false
            val sessionId = feature.config.string("sessionId").trim()
            if (sessionId.isNotBlank() && ctx.event.payload.string("sessionId") != sessionId) return@registerEvent false
            val pkg = feature.config.string("package").trim()
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            val className = feature.config.string("className").trim()
            if (className.isNotBlank() && ctx.event.payload.string("className") != className) return@registerEvent false
            val methodName = feature.config.string("methodName").trim()
            if (methodName.isNotBlank() && ctx.event.payload.string("methodName") != methodName) return@registerEvent false
            val lifecycle = feature.config.string("lifecycle", "any")
            lifecycle == "any" || ctx.event.payload.string("lifecycle") == lifecycle
        }
    }

    private fun registerSystemOperation(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.lsposed.system.operation"),
                FeatureKind.ACTION,
                "LSPosed system operation",
                "Run a fixed system_server operation with LSPosed first and Root/Shizuku fallback",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice(
                        "operation",
                        "System operation",
                        true,
                        listOf(
                            "sleep", "wake", "expand_notifications", "expand_quick_settings",
                            "collapse_panels", "reboot", "reboot_recovery", "reboot_bootloader", "shutdown",
                        ),
                    )
                ),
                capabilities = setOf(CapabilityIds.SYSTEM_UI),
                keywords = setOf("lsposed", "system server", "power", "status bar", "quick settings"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val operation = when (feature.config.string("operation")) {
                "sleep" -> com.yagay.yauto.core.capability.SystemOperations.SLEEP
                "wake" -> com.yagay.yauto.core.capability.SystemOperations.WAKE
                "expand_notifications" -> com.yagay.yauto.core.capability.SystemOperations.EXPAND_NOTIFICATIONS
                "expand_quick_settings" -> com.yagay.yauto.core.capability.SystemOperations.EXPAND_QUICK_SETTINGS
                "collapse_panels" -> com.yagay.yauto.core.capability.SystemOperations.COLLAPSE_PANELS
                "reboot" -> com.yagay.yauto.core.capability.SystemOperations.REBOOT
                "reboot_recovery" -> com.yagay.yauto.core.capability.SystemOperations.REBOOT_RECOVERY
                "reboot_bootloader" -> com.yagay.yauto.core.capability.SystemOperations.REBOOT_BOOTLOADER
                "shutdown" -> com.yagay.yauto.core.capability.SystemOperations.SHUTDOWN
                else -> return@registerAction ActionExecutionResult(false, message = userText("feature.lsposed_system_operation_invalid"))
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.SYSTEM_UI,
                    operationId = operation,
                    allowFallback = true,
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }


    private fun registerSensorsOff(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sensors_off.set"),
                FeatureKind.ACTION,
                "Set Sensors Off",
                "Enable or disable Android's system-wide Sensors Off privacy switch through the LSPosed system_server bridge",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Toggle("enabled", "Sensors Off enabled")),
                capabilities = setOf(CapabilityIds.SYSTEM_UI),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("sensors off", "sensor privacy", "lsposed", "shortx", "privacy"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val operation = if (feature.config.boolean("enabled", true)) {
                com.yagay.yauto.core.capability.SystemOperations.SENSORS_OFF_ENABLE
            } else {
                com.yagay.yauto.core.capability.SystemOperations.SENSORS_OFF_DISABLE
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.SYSTEM_UI,
                    operationId = operation,
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }

        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.SYSTEM_UI,
                    operationId = com.yagay.yauto.core.capability.SystemOperations.SENSORS_OFF_QUERY,
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            val enabled = (result.value as? ConfigValue.BooleanValue)?.value ?: return@ConditionEvaluator false
            enabled == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.sensors_off"),
            FeatureKind.STATE,
            "Sensors Off",
            "Check Android's system-wide Sensors Off privacy state through LSPosed",
            FeatureCategory.SYSTEM,
            fields = listOf(FieldSchema.Toggle("value", "Sensors Off enabled")),
            capabilities = setOf(CapabilityIds.SYSTEM_UI),
            accessRequirements = setOf(AccessRequirement.LSPOSED),
            keywords = setOf("sensors off", "sensor privacy", "lsposed", "shortx"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.sensors_off"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)+")
        val METHOD_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,127}")
    }
}
