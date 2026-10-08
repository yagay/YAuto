package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.UUID

class AndroidLsposedLifecycleFeaturePack : FeaturePack {
    override val id: String = "android.lsposed_lifecycle"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.lsposed.lifecycle.install"), FeatureKind.ACTION,
                "Install app lifecycle hooks", "Install predefined LSPosed Activity/Application lifecycle observers in a scoped running app",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Target app / package", true),
                    FieldSchema.Choice("scope", "Observe", true, listOf("all", "activity", "process")),
                    FieldSchema.Text("sessionPrefix", "Session prefix"),
                    FieldSchema.Variable("resultVariable", "Store installed sessions"),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                capabilities = setOf(CapabilityIds.LSPOSED_HOOK),
                keywords = setOf("lsposed", "activity lifecycle", "process start", "window", "hook"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").resolveVariables(ctx.variables).trim()
            if (!PACKAGE_NAME.matches(pkg)) return@registerAction ActionExecutionResult(false, message = userText("feature.lsposed_hook_input_invalid"))
            val scope = feature.config.string("scope", "all")
            val prefix = feature.config.string("sessionPrefix").resolveVariables(ctx.variables).trim().ifBlank { "lifecycle" }
            val specs = buildList {
                if (scope in setOf("all", "activity")) {
                    add(HookSpec("android.app.Activity", "onStart", 0, "activity_start"))
                    add(HookSpec("android.app.Activity", "onStop", 0, "activity_stop"))
                    add(HookSpec("android.app.Activity", "onDestroy", 0, "activity_destroy"))
                }
                if (scope in setOf("all", "process")) {
                    add(HookSpec("android.app.Application", "onCreate", 0, "process_start"))
                }
            }
            if (specs.isEmpty()) return@registerAction ActionExecutionResult(false)
            val installed = ArrayList<ConfigValue>()
            for (spec in specs) {
                val sessionId = "${prefix.take(40)}-${spec.key}-" + UUID.randomUUID().toString().take(8)
                val token = UUID.randomUUID().toString() + UUID.randomUUID().toString()
                val result = ctx.capabilities.execute(
                    CapabilityRequest(
                        capability = CapabilityIds.LSPOSED_HOOK,
                        operationId = "lsposed.hook.install_session",
                        payload = mapOf(
                            "package" to ConfigValue.StringValue(pkg),
                            "className" to ConfigValue.StringValue(spec.className),
                            "methodName" to ConfigValue.StringValue(spec.methodName),
                            "parameterCount" to ConfigValue.NumberValue(spec.parameterCount.toDouble()),
                            "parameterTypes" to ConfigValue.StringValue(""),
                            "returnType" to ConfigValue.StringValue("void"),
                            "lifecycle" to ConfigValue.StringValue("before"),
                            "mode" to ConfigValue.StringValue("observe"),
                            "replacementType" to ConfigValue.StringValue("null"),
                            "replacementValue" to ConfigValue.StringValue(""),
                            "sessionId" to ConfigValue.StringValue(sessionId),
                            "eventToken" to ConfigValue.StringValue(token),
                        ),
                        preferredBackendId = "lsposed",
                        allowFallback = false,
                    )
                )
                if (!result.success) continue
                installed += ConfigValue.ObjectValue(
                    mapOf(
                        "sessionId" to ConfigValue.StringValue(sessionId),
                        "kind" to ConfigValue.StringValue(spec.key),
                    )
                )
            }
            val output = ConfigValue.ListValue(installed)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(installed.isNotEmpty(), output)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.activity_lifecycle"), FeatureKind.EVENT,
                "Activity lifecycle", "Run when an LSPosed-observed Activity starts, stops or is destroyed",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "Target app / package"),
                    FieldSchema.Text("classContains", "Activity class contains"),
                    FieldSchema.Choice("lifecycle", "Lifecycle", true, listOf("any", "started", "stopped", "destroyed")),
                ),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("activity", "lifecycle", "started", "stopped", "destroyed", "lsposed"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.activity_lifecycle") return@registerEvent false
            val pkg = feature.config.string("package")
            if (pkg.isNotBlank() && ctx.event.payload.string("package") != pkg) return@registerEvent false
            val cls = feature.config.string("classContains")
            if (cls.isNotBlank() && !ctx.event.payload.string("className").contains(cls, true)) return@registerEvent false
            val lifecycle = feature.config.string("lifecycle", "any")
            lifecycle == "any" || ctx.event.payload.string("lifecycle") == lifecycle
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.app_process_started"), FeatureKind.EVENT,
                "App process started", "Run when an LSPosed-observed application process calls Application.onCreate",
                FeatureCategory.APP,
                fields = listOf(FieldSchema.AppPicker("package", "Target app / package")),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                keywords = setOf("process", "started", "application", "lsposed"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.app_process_started") return@registerEvent false
            val pkg = feature.config.string("package")
            pkg.isBlank() || ctx.event.payload.string("package") == pkg
        }
    }

    private data class HookSpec(val className: String, val methodName: String, val parameterCount: Int, val key: String)

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
