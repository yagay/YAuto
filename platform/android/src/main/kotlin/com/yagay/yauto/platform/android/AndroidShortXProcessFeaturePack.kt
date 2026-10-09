package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Starts actual Android application processes through ActivityManagerInternal in system_server.
 * This does NOT launch Activities or pretend that a shell-created process is Android app-managed.
 * The system may kill a process again if it has no active components.
 */
class AndroidShortXProcessFeaturePack : FeaturePack {
    override val id = "android.shortx.process_start"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.process.start"), FeatureKind.ACTION,
                "Start application process",
                "Ask Android ActivityManager to create app processes without opening an Activity; processes may be reclaimed",
                FeatureCategory.APP,
                capabilities = setOf(CapabilityIds.LSPOSED),
                accessRequirements = setOf(AccessRequirement.LSPOSED),
                implementationOptions = listOf(FeatureImplementationOption("lsposed", setOf(AccessRequirement.LSPOSED))),
                fields = listOf(
                    FieldSchema.Text("packages", "Package names (one per line)", multiline = true),
                    FieldSchema.Text("packageSets", "Package-set variable names (one per line)", multiline = true),
                    FieldSchema.Number("userId", "Android user ID", min = 0.0, max = 99.0),
                    FieldSchema.Variable("resultVariable", "Store request summary"),
                ),
                fieldBehaviors = mapOf(
                    "packages" to FieldBehavior(supportsVariables = true),
                    "packageSets" to FieldBehavior(supportsVariables = true),
                    "userId" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                ),
                keywords = setOf("shortx", "start process", "zygote", "process list", "package sets"),
                ownerPackId = id,
            )
        ) { feature, context ->
            val idNumber = feature.config["userId"].numberOrNull() ?: 0.0
            if (!idNumber.isFinite() || idNumber.toInt().toDouble() != idNumber || idNumber !in 0.0..99.0) {
                return@registerAction ActionExecutionResult(false, message = userText("shortx.process.invalid_user_id"))
            }
            val names = mutableListOf<String>()
            names += feature.config.string("packages").resolveVariables(context.variables)
                .lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            val setNames = feature.config.string("packageSets").resolveVariables(context.variables)
                .lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            for (setName in setNames) {
                val item = context.variables.get(setName) as? ConfigValue.ListValue
                    ?: return@registerAction ActionExecutionResult(false, message = userText("shortx.process.missing_package_set", setName))
                names += item.value.mapNotNull { (it as? ConfigValue.StringValue)?.value }
            }
            val packages = names.distinct()
            if (packages.isEmpty() || packages.size > 24 || packages.any { !processPackageNameValid(it) }) {
                return@registerAction ActionExecutionResult(false, message = userText("shortx.process.invalid_packages"))
            }
            var accepted = 0
            val errors = mutableListOf<String>()
            for (pkg in packages) {
                val response = context.capabilities.execute(
                    CapabilityRequest(
                        capability = CapabilityIds.LSPOSED,
                        operationId = "app_process.start",
                        payload = mapOf(
                            "package" to ConfigValue.StringValue(pkg),
                            "userId" to ConfigValue.NumberValue(idNumber),
                        ),
                        preferredBackendId = "lsposed",
                        allowFallback = false,
                    )
                )
                if (response.success) accepted++ else errors += "$pkg: ${response.message ?: "unsupported"}"
            }
            val summary = ConfigValue.ObjectValue(mapOf(
                "requested" to ConfigValue.NumberValue(packages.size.toDouble()),
                "accepted" to ConfigValue.NumberValue(accepted.toDouble()),
                "failed" to ConfigValue.NumberValue((packages.size - accepted).toDouble()),
            ))
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                context.variables.set(it, summary)
            }
            ActionExecutionResult(accepted == packages.size, summary, errors.take(4).joinToString("; ").ifBlank { null })
        }
    }
}

internal fun processPackageNameValid(value: String) =
    value.length in 3..180 && Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+").matches(value)
