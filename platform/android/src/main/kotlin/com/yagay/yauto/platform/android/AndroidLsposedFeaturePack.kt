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
        registerCrashGuard(registry)
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
                    FieldSchema.Toggle("captureValues", "Capture primitive arguments and return values (sensitive; off by default)"),
                    FieldSchema.Number("parameterCount", "Parameter count (-1 = any overload)", min = -1.0, max = 64.0),
                    FieldSchema.Text("parameterTypes", "Parameter types (comma-separated Java names)"),
                    FieldSchema.Text("returnType", "Return type (Java name)"),
                    FieldSchema.Choice("lifecycle", "Observe lifecycle", true, listOf("before", "after")),
                    FieldSchema.Choice("mode", "Hook mode", true, listOf("observe", "replace", "override_result")),
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
                        visibleWhen = FieldRule.NotEquals("mode", ConfigValue.StringValue("observe")),
                    ),
                    "replacementType" to FieldBehavior(
                        visibleWhen = FieldRule.NotEquals("mode", ConfigValue.StringValue("observe")),
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
            val captureValues = feature.config.boolean("captureValues", false)
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
                parameterCount !in -1..64 || mode !in setOf("observe", "replace", "override_result")
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
                        "captureValues" to ConfigValue.BooleanValue(captureValues),
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
                "Manage LSPosed hook session",
                "Disable a Hook session or query whether its running scoped process has active hooks",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Target app / package", true),
                    FieldSchema.Text("sessionId", "Session ID", true),
                    FieldSchema.Choice("operation", "Session operation", options = listOf("disable", "query")),
                    FieldSchema.Variable("resultVariable", "Store session status"),
                ),
                fieldBehaviors = mapOf(
                    "package" to FieldBehavior(supportsVariables = true),
                    "sessionId" to FieldBehavior(supportsVariables = true),
                    "operation" to FieldBehavior(defaultValue = ConfigValue.StringValue("disable")),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED_HOOK),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("lsposed", "hook", "disable", "stop", "session", "ShortX"),
                ownerPackId = id,
            )
        ) { item, ctx ->
            val pkg = item.config.string("package").resolveVariables(ctx.variables).trim()
            val session = item.config.string("sessionId").resolveVariables(ctx.variables).trim()
            val operation = item.config.string("operation", "disable")
            if (!PACKAGE_NAME.matches(pkg) || !Regex("[A-Za-z0-9_.:-]{1,96}").matches(session) ||
                operation !in setOf("disable", "query")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.lsposed_hook_input_invalid"))
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED_HOOK,
                    operationId = if (operation == "query") "lsposed.hook.query_session"
                        else "lsposed.hook.disable_session",
                    payload = mapOf(
                        "package" to ConfigValue.StringValue(pkg),
                        "sessionId" to ConfigValue.StringValue(session),
                    ),
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            if (result.success) {
                item.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                    ctx.variables.set(it, result.value)
                }
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun registerCrashGuard(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.lsposed.hook.crash_guard"),
                FeatureKind.ACTION,
                "LSPosed Hook crash protection",
                "Query or reset automatic Hook safe mode after repeated Java process crashes; restart the app to re-enable hooks",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Target app / package", true),
                    FieldSchema.Choice("operation", "Operation", true, listOf("status", "reset")),
                    FieldSchema.Variable("resultVariable", "Store crash protection details"),
                ),
                fieldBehaviors = mapOf(
                    "package" to FieldBehavior(supportsVariables = true),
                    "operation" to FieldBehavior(defaultValue = ConfigValue.StringValue("status")),
                ),
                capabilities = setOf(CapabilityIds.LSPOSED_HOOK),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                implementationOptions = listOf(
                    FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED))
                ),
                keywords = setOf("lsposed", "hook", "crash", "safe mode", "ShortX"),
                ownerPackId = id,
            )
        ) { item, ctx ->
            val pkg = item.config.string("package").resolveVariables(ctx.variables).trim()
            val mode = item.config.string("operation", "status")
            if (!PACKAGE_NAME.matches(pkg) || mode !in setOf("status", "reset")) {
                return@registerAction ActionExecutionResult(false,
                    message = userText("feature.lsposed_hook_input_invalid"))
            }
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.LSPOSED_HOOK,
                    operationId = if (mode == "reset") "lsposed.hook.crash_guard.reset"
                        else "lsposed.hook.crash_guard.status",
                    payload = mapOf("package" to ConfigValue.StringValue(pkg)),
                    preferredBackendId = "lsposed",
                    allowFallback = false,
                )
            )
            if (result.success) item.config.string("resultVariable").trim()
                .takeIf(String::isNotBlank)?.let { ctx.variables.set(it, result.value) }
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
                    FieldSchema.Text("arg0Contains", "First argument contains"),
                    FieldSchema.Text("resultContains", "Return value contains"),
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
            val argFilter = feature.config.string("arg0Contains").trim()
            val resultFilter = feature.config.string("resultContains").trim()
            (lifecycle == "any" || ctx.event.payload.string("lifecycle") == lifecycle) &&
                (argFilter.isEmpty() || ctx.event.payload.string("arg0").contains(argFilter, ignoreCase = true)) &&
                (resultFilter.isEmpty() || ctx.event.payload.string("result").contains(resultFilter, ignoreCase = true))
        }
    }

    private fun registerSystemOperation(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.lsposed.system.operation"),
                FeatureKind.ACTION,
                "System controls",
                "Switch between MacroDroid Accessibility methods and ShortX LSPosed/Root/Shizuku methods",
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
                capabilities = setOf(CapabilityIds.ACCESSIBILITY, CapabilityIds.SYSTEM_UI),
                implementationOptions = listOf(
                    FeatureImplementationOption("accessibility", setOf(AccessRequirement.ACCESSIBILITY)),
                    FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED), restartRequired = true),
                    FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                    FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                ),
                keywords = setOf("macrodroid", "shortx", "accessibility", "lsposed", "root", "system controls", "power", "quick settings"),
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
            val accessibleAction = when (operation) {
                com.yagay.yauto.core.capability.SystemOperations.SLEEP -> "lock_screen"
                com.yagay.yauto.core.capability.SystemOperations.EXPAND_NOTIFICATIONS -> "notifications"
                com.yagay.yauto.core.capability.SystemOperations.EXPAND_QUICK_SETTINGS -> "quick_settings"
                else -> null
            }
            val result = routeFeatureMethod(
                method = feature.preferredMethod(),
                macroSupported = accessibleAction != null,
                macrodroid = {
                    ctx.executeCapability(feature.typeId, CapabilityRequest(
                        capability = CapabilityIds.ACCESSIBILITY,
                        operationId = "accessibility.global_action",
                        payload = mapOf("action" to ConfigValue.StringValue(accessibleAction.orEmpty())),
                        preferredBackendId = "accessibility",
                        allowFallback = false,
                    ))
                },
                shortx = {
                    val backend = feature.preferredBackendId()
                    ctx.executeCapability(feature.typeId, CapabilityRequest(
                        capability = CapabilityIds.SYSTEM_UI,
                        operationId = operation,
                        preferredBackendId = backend,
                        allowFallback = backend == null,
                    ))
                },
                succeeded = { it.success },
            )
            ActionExecutionResult(
                result?.success == true,
                result?.value ?: ConfigValue.NullValue,
                result?.message ?: if (result == null)
                    userText("feature.dual_method_no_macro", feature.config.string("operation"))
                else null,
            )
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
