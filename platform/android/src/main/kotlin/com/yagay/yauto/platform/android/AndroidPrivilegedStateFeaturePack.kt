package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

/** Privileged read-only states that can be used directly as rule constraints. */
class AndroidPrivilegedStateFeaturePack : FeaturePack {
    override val id: String = "android.privileged.states"

    override fun install(registry: FeatureRegistry) {
        registerSettingMatch(registry, FeatureKind.STATE, "android.state.setting_matches")
        registerSettingMatch(registry, FeatureKind.CONDITION, "android.condition.setting_matches")
        registerPropertyMatch(registry, FeatureKind.STATE, "android.state.system_property_matches")
        registerPropertyMatch(registry, FeatureKind.CONDITION, "android.condition.system_property_matches")
        registerBackendAvailable(registry, FeatureKind.STATE, "android.state.privileged_backend_available")
        registerBackendAvailable(registry, FeatureKind.CONDITION, "android.condition.privileged_backend_available")
        registerAppOpMode(registry, FeatureKind.STATE, "android.state.appop_mode")
        registerAppOpMode(registry, FeatureKind.CONDITION, "android.condition.appop_mode")
    }

    private fun registerSettingMatch(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Android setting matches", "Read a System, Global or Secure setting through Root/Shizuku and compare its value",
            FeatureCategory.SYSTEM,
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            fields = listOf(
                FieldSchema.Choice("namespace", "Settings namespace", true, listOf("system", "global", "secure")),
                FieldSchema.Text("key", "Setting key", true),
                FieldSchema.Choice("operator", "Comparison", true, listOf("equals", "not_equals", "contains", "not_contains", "regex")),
                FieldSchema.Text("expected", "Expected value", true),
            ),
            keywords = setOf("settings", "secure", "global", "system", "compare"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val command = settingsGetCommand(feature.config.string("namespace"), feature.config.string("key")) ?: return@ConditionEvaluator false
            val result = executePrivileged(command, ctx) ?: return@ConditionEvaluator false
            compareText(result, feature.config.string("operator", "equals"), feature.config.string("expected").resolveVariables(ctx.variables))
        }
        register(registry, descriptor, evaluator)
    }

    private fun registerPropertyMatch(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "System property matches", "Read an Android getprop value through Root/Shizuku and compare its text",
            FeatureCategory.SYSTEM,
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            fields = listOf(
                FieldSchema.Text("key", "Property key", true),
                FieldSchema.Choice("operator", "Comparison", true, listOf("equals", "not_equals", "contains", "not_contains", "regex")),
                FieldSchema.Text("expected", "Expected value", true),
            ),
            keywords = setOf("getprop", "property", "system property", "compare"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val key = feature.config.string("key").trim()
            if (!isValidPropertyName(key)) return@ConditionEvaluator false
            val result = executePrivileged("getprop $key", ctx) ?: return@ConditionEvaluator false
            compareText(result, feature.config.string("operator", "equals"), feature.config.string("expected").resolveVariables(ctx.variables))
        }
        register(registry, descriptor, evaluator)
    }

    private fun registerBackendAvailable(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Privileged backend available", "Check whether Root or Shizuku is currently usable for privileged shell operations",
            FeatureCategory.ADVANCED,
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            fields = listOf(
                FieldSchema.Choice("backend", "Backend", true, listOf("root", "shizuku")),
                FieldSchema.Toggle("value", "Available"),
            ),
            keywords = setOf("root", "shizuku", "backend", "available", "privileged"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val backend = feature.config.string("backend")
            if (backend !in setOf("root", "shizuku")) return@ConditionEvaluator false
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "android.privileged.backend.probe",
                    payload = mapOf("command" to ConfigValue.StringValue("true")),
                    allowFallback = false,
                    preferredBackendId = backend,
                )
            )
            result.success == feature.config.boolean("value", true)
        }
        register(registry, descriptor, evaluator)
    }

    private fun registerAppOpMode(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "App operation mode", "Check the current Android AppOps mode for an application operation",
            FeatureCategory.APP,
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            fields = listOf(
                FieldSchema.AppPicker("package", "App / package", true),
                FieldSchema.Text("operation", "App operation", true),
                FieldSchema.Choice("mode", "Expected mode", true, listOf("allow", "ignore", "deny", "default", "foreground", "errored")),
            ),
            keywords = setOf("appops", "permission", "operation", "mode", "package"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            val operation = feature.config.string("operation").trim()
            val command = appOpGetCommand(pkg, operation) ?: return@ConditionEvaluator false
            val output = executePrivileged(command, ctx) ?: return@ConditionEvaluator false
            parseAppOpMode(output) == feature.config.string("mode", "allow")
        }
        register(registry, descriptor, evaluator)
    }

    private fun register(registry: FeatureRegistry, descriptor: FeatureDescriptor, evaluator: ConditionEvaluator) {
        if (descriptor.kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private suspend fun executePrivileged(command: String, ctx: FeatureExecutionContext): String? {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "android.privileged.state.read",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        if (!result.success) return null
        return shellStdout(result.value).trimEnd('\r', '\n')
    }
}

internal fun compareText(actual: String, operator: String, expected: String): Boolean = when (operator) {
    "equals" -> actual == expected
    "not_equals" -> actual != expected
    "contains" -> actual.contains(expected)
    "not_contains" -> !actual.contains(expected)
    "regex" -> runCatching { Regex(expected).containsMatchIn(actual) }.getOrDefault(false)
    else -> false
}

internal fun parseAppOpMode(output: String): String? {
    if (output.contains("No operations", ignoreCase = true)) return "default"
    return Regex("(?i)(?:^|\\s|:)(allow|ignore|deny|default|foreground|errored)(?:$|[;\\s])")
        .find(output)?.groupValues?.getOrNull(1)?.lowercase()
}
