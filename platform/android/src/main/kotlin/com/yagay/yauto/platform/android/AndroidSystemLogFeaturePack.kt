package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidSystemLogFeaturePack : FeaturePack {
    override val id: String = "android.system_log"

    override fun install(registry: FeatureRegistry) {
        registerQuery(registry)
        registerClear(registry)
        registerDumpsys(registry)
        registerTrigger(registry)
    }

    private fun registerQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.logcat.query"), FeatureKind.ACTION,
                "Query system log", "Read recent logcat entries through an authorized privileged shell backend",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Number("lines", "Maximum lines", min = 1.0, max = 5000.0),
                    FieldSchema.Text("tag", "Tag filter"),
                    FieldSchema.Choice("priority", "Minimum priority", true, listOf("V", "D", "I", "W", "E", "F")),
                    FieldSchema.Text("textContains", "Text contains"),
                    FieldSchema.Variable("resultVariable", "Store log text", true),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("logcat", "system log", "debug", "logs", "diagnostics"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val lines = ((feature.config["lines"] as? ConfigValue.NumberValue)?.value ?: 300.0).toInt().coerceIn(1, 5000)
            val tag = feature.config.string("tag").resolveVariables(ctx.variables).trim()
            val priority = feature.config.string("priority", "V").takeIf { it in setOf("V", "D", "I", "W", "E", "F") } ?: "V"
            val text = feature.config.string("textContains").resolveVariables(ctx.variables)
            val selector = if (tag.isBlank()) "*:$priority" else "${safeLogcatTag(tag)}:$priority *:S"
            val grep = if (text.isBlank()) "" else " | grep -F -- ${shellQuote(text)}"
            val command = "logcat -d -v threadtime -t $lines ${shellQuote(selector)}$grep"
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = feature.typeId,
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(shellStdout(result.value))
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerClear(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.logcat.clear"), FeatureKind.ACTION,
                "Clear system log", "Clear logcat buffers through an authorized privileged shell backend",
                FeatureCategory.ADVANCED,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("logcat", "clear", "logs"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = feature.typeId,
                    payload = mapOf("command" to ConfigValue.StringValue("logcat -c")),
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private fun registerDumpsys(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.dumpsys.query"), FeatureKind.ACTION,
                "Query Android service dump", "Run dumpsys for a selected Android service with optional safe arguments",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("service", "Service name", true),
                    FieldSchema.Text("arguments", "Arguments"),
                    FieldSchema.Variable("resultVariable", "Store dump text", true),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("dumpsys", "system service", "diagnostics", "dump"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val service = feature.config.string("service").trim()
            val args = feature.config.string("arguments").resolveVariables(ctx.variables).trim()
            if (!Regex("[A-Za-z0-9_.:-]{1,80}").matches(service) || args.contains('\n') || args.contains('\r') || args.contains('\u0000')) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.dumpsys_input_invalid"))
            }
            val command = "dumpsys ${shellQuote(service)}" + if (args.isBlank()) "" else " $args"
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = feature.typeId,
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val output = ConfigValue.StringValue(shellStdout(result.value))
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerTrigger(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.system_log_entry"), FeatureKind.EVENT,
                "System log entry", "Run when the configured logcat watcher receives a matching line",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Choice("priority", "Priority", true, listOf("any", "V", "D", "I", "W", "E", "F")),
                    FieldSchema.Text("tagContains", "Tag contains"),
                    FieldSchema.Text("textContains", "Text contains"),
                    FieldSchema.Text("regex", "Message regex"),
                ),
                accessRequirements = setOf(AccessRequirement.ROOT),
                keywords = setOf("logcat", "trigger", "system log", "entry", "debug"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.system_log_entry") return@registerEvent false
            val priority = feature.config.string("priority", "any")
            if (priority != "any" && ctx.event.payload.string("priority") != priority) return@registerEvent false
            val tag = feature.config.string("tagContains")
            if (tag.isNotBlank() && !ctx.event.payload.string("tag").contains(tag, ignoreCase = true)) return@registerEvent false
            val text = feature.config.string("textContains")
            val message = ctx.event.payload.string("message")
            if (text.isNotBlank() && !message.contains(text, ignoreCase = true)) return@registerEvent false
            val regex = feature.config.string("regex")
            regex.isBlank() || runCatching { Regex(regex).containsMatchIn(message) }.getOrDefault(false)
        }
    }
}

internal fun safeLogcatTag(raw: String): String =
    raw.filter { it.isLetterOrDigit() || it in "._-:" }.take(64).ifBlank { "*" }
