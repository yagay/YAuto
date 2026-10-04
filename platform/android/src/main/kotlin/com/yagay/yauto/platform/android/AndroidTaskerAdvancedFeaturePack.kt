package com.yagay.yauto.platform.android

import android.content.Context
import bsh.Interpreter
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mvel2.MVEL

class AndroidTaskerAdvancedFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.tasker.advanced"
    private val context = context.applicationContext
    private val plugins = LocalePluginHost(this.context)
    private val scriptExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "YAuto-Script").apply { isDaemon = true }
    }

    override fun install(registry: FeatureRegistry) {
        registerBeanShell(registry)
        registerMvel(registry)
        registerPluginAction(registry)
        registerPluginCondition(registry)
        registerPluginEvent(registry)
        registerPluginScan(registry)
        registerAdbWifi(registry)
    }

    private fun registerBeanShell(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.beanshell.execute"),
                FeatureKind.ACTION,
                "Run Java / BeanShell",
                "Execute Tasker/MacroDroid-style Java code with Android context and YAuto variables",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("script", "Java / BeanShell code", true, multiline = true),
                    FieldSchema.Duration("timeoutMs", "Execution timeout"),
                    FieldSchema.Variable("resultVariable", "Store returned value"),
                ),
                fieldBehaviors = mapOf("script" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("java", "beanshell", "java code", "tasker", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val script = feature.config.string("script").resolveVariables(ctx.variables)
            if (script.isBlank()) return@registerAction ActionExecutionResult(false)
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 5_000.0)
                .toLong().coerceIn(100L, 60_000L)
            val variables = ctx.variables.snapshot()
            val future = scriptExecutor.submit(Callable {
                val interpreter = Interpreter()
                interpreter.set("context", context)
                interpreter.set("yautoContext", context)
                interpreter.set("tasker", TaskerScriptHelper(ctx, context))
                interpreter.set("variables", variables.mapValues { configToJava(it.value) }.toMutableMap())
                variables.forEach { (name, value) ->
                    if (JAVA_IDENTIFIER.matches(name)) {
                        runCatching { interpreter.set(name, configToJava(value)) }
                    }
                }
                interpreter.eval(script)
            })
            val result = runCatching {
                withContext(Dispatchers.IO) { future.get(timeout, TimeUnit.MILLISECONDS) }
            }.onFailure { future.cancel(true) }
            if (result.isFailure) {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText(
                        "feature.operation_failed",
                        result.exceptionOrNull()?.message ?: "BeanShell",
                    ),
                )
            }
            val output = javaToConfig(result.getOrNull())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerMvel(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.mvel.execute"),
                FeatureKind.ACTION,
                "Run MVEL",
                "Execute an MVEL expression/script using YAuto variables",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("script", "MVEL", true, multiline = true),
                    FieldSchema.Duration("timeoutMs", "Execution timeout"),
                    FieldSchema.Variable("resultVariable", "Store returned value"),
                ),
                fieldBehaviors = mapOf("script" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("mvel", "script", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val script = feature.config.string("script").resolveVariables(ctx.variables)
            if (script.isBlank()) return@registerAction ActionExecutionResult(false)
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 3_000.0)
                .toLong().coerceIn(100L, 60_000L)
            val vars = mvelVariables(ctx.variables.snapshot())
            val future = scriptExecutor.submit(Callable { MVEL.eval(script, vars) })
            val result = runCatching {
                withContext(Dispatchers.IO) { future.get(timeout, TimeUnit.MILLISECONDS) }
            }.onFailure { future.cancel(true) }
            if (result.isFailure) {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText(
                        "feature.operation_failed",
                        result.exceptionOrNull()?.message ?: "MVEL",
                    ),
                )
            }
            val output = javaToConfig(result.getOrNull())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        }

        val evaluator = ConditionEvaluator { feature, ctx ->
            val expression = feature.config.string("expression").resolveVariables(ctx.variables)
            if (expression.isBlank()) return@ConditionEvaluator false
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 1_500.0)
                .toLong().coerceIn(100L, 30_000L)
            val future = scriptExecutor.submit(Callable {
                MVEL.eval(expression, mvelVariables(ctx.variables.snapshot()))
            })
            val result = runCatching {
                withContext(Dispatchers.IO) { future.get(timeout, TimeUnit.MILLISECONDS) }
            }.onFailure { future.cancel(true) }.getOrNull()
            when (result) {
                is Boolean -> result
                is Number -> result.toDouble() != 0.0
                is String -> result.equals("true", true)
                else -> false
            }
        }
        val descriptor = FeatureDescriptor(
            FeatureId("script.mvel.condition"),
            FeatureKind.CONDITION,
            "MVEL condition",
            "Evaluate an MVEL expression as a boolean condition",
            FeatureCategory.SCRIPT,
            fields = listOf(
                FieldSchema.Text("expression", "MVEL expression", true, multiline = true),
                FieldSchema.Duration("timeoutMs", "Execution timeout"),
            ),
            fieldBehaviors = mapOf("expression" to FieldBehavior(supportsVariables = true)),
            keywords = setOf("mvel", "condition", "shortx"),
            ownerPackId = id,
        )
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(id = FeatureId("script.mvel.state"), kind = FeatureKind.STATE),
            evaluator,
        )
    }

    private fun registerPluginAction(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.plugin.locale.action"),
                FeatureKind.ACTION,
                "Run Tasker / Locale plugin",
                "Fire a standard Locale-compatible plugin setting/action",
                FeatureCategory.COMPATIBILITY,
                fields = pluginFields() + listOf(
                    FieldSchema.Toggle("ordered", "Wait for plugin broadcast completion"),
                    FieldSchema.Variable("resultVariable", "Store plugin result metadata"),
                ),
                keywords = setOf("tasker plugin", "locale plugin", "plugin action", "autoinput", "autonotification"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val result = plugins.fireSetting(
                packageName = feature.config.string("package"),
                receiverClass = feature.config.string("receiverClass"),
                bundleJson = feature.config.string("bundleJson").resolveVariables(ctx.variables),
                ordered = (feature.config["ordered"] as? ConfigValue.BooleanValue)?.value ?: true,
                timeoutMs = pluginTimeout(feature),
            )
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "completed" to ConfigValue.BooleanValue(result.completed),
                    "resultCode" to ConfigValue.NumberValue(result.resultCode.toDouble()),
                    "extras" to plugins.bundleAsConfig(result.extras),
                )
            )
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(result.completed, output)
        }
    }

    private fun registerPluginCondition(registry: FeatureRegistry) {
        val evaluator = ConditionEvaluator { feature, ctx ->
            val result = plugins.queryCondition(
                packageName = feature.config.string("package"),
                receiverClass = feature.config.string("receiverClass"),
                bundleJson = feature.config.string("bundleJson").resolveVariables(ctx.variables),
                timeoutMs = pluginTimeout(feature),
            )
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, plugins.bundleAsConfig(result.extras))
            }
            result.completed && result.resultCode == LocalePluginProtocol.RESULT_SATISFIED
        }
        val descriptor = FeatureDescriptor(
            FeatureId("android.plugin.locale.condition"),
            FeatureKind.CONDITION,
            "Tasker / Locale plugin condition",
            "Query a standard Locale-compatible plugin condition",
            FeatureCategory.COMPATIBILITY,
            fields = pluginFields() + listOf(
                FieldSchema.Variable("resultVariable", "Store plugin result variables"),
            ),
            keywords = setOf("tasker plugin", "locale plugin", "plugin state", "condition"),
            ownerPackId = id,
        )
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(
                id = FeatureId("android.plugin.locale.state"),
                kind = FeatureKind.STATE,
            ),
            evaluator,
        )
    }

    private fun registerPluginEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.plugin_locale"),
                FeatureKind.EVENT,
                "Tasker / Locale plugin event",
                "Run when a configured Locale/Tasker event plugin requests a host query and reports SATISFIED",
                FeatureCategory.COMPATIBILITY,
                fields = pluginFields(),
                keywords = setOf("tasker plugin", "locale plugin", "plugin event", "request query"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.plugin_locale" &&
                ctx.event.payload.string("ruleKey") == localePluginRuleKey(feature)
        }
    }

    private fun registerPluginScan(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.plugin.locale.scan"),
                FeatureKind.ACTION,
                "Scan Tasker / Locale plugins",
                "List installed Locale-compatible action and condition receivers",
                FeatureCategory.COMPATIBILITY,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store plugin list", true)),
                keywords = setOf("tasker plugin", "locale", "scan plugins"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ListValue(
                plugins.scan().map { item ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "package" to ConfigValue.StringValue(item.packageName),
                            "receiverClass" to ConfigValue.StringValue(item.className),
                            "kind" to ConfigValue.StringValue(item.kind),
                            "label" to ConfigValue.StringValue(item.label),
                        )
                    )
                }
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }


    private fun registerAdbWifi(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.adb_wifi.command"),
                FeatureKind.ACTION,
                "ADB Wi-Fi / privileged shell command",
                "Run a Tasker-style privileged command using Root or Shizuku; optionally target an already-paired local adb client",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("command", "Shell command", true, multiline = true),
                    FieldSchema.Choice("transport", "Transport", options = listOf("privileged", "adb")),
                    FieldSchema.Text("adbTarget", "ADB host:port"),
                    FieldSchema.Duration("timeoutMs", "Timeout"),
                    FieldSchema.Variable("resultVariable", "Store command result"),
                ),
                fieldBehaviors = mapOf(
                    "command" to FieldBehavior(supportsVariables = true),
                    "adbTarget" to FieldBehavior(
                        visibleWhen = FieldRule.Equals("transport", ConfigValue.StringValue("adb")),
                        supportsVariables = true,
                    ),
                ),
                capabilities = setOf(com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = listOf(
                    FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                    FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
                ),
                keywords = setOf("adb wifi", "wireless debugging", "shell", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = feature.config.string("command").resolveVariables(ctx.variables).trim()
            if (command.isBlank()) return@registerAction ActionExecutionResult(false)
            val shellCommand = if (feature.config.string("transport", "privileged") == "adb") {
                val target = feature.config.string("adbTarget").resolveVariables(ctx.variables).trim()
                if (!ADB_TARGET.matches(target)) return@registerAction ActionExecutionResult(false)
                "adb -s " + shellArg(target) + " shell " + shellArg(command)
            } else command
            val result = ctx.capabilities.execute(
                com.yagay.yauto.core.capability.CapabilityRequest(
                    capability = com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "system.shell.execute",
                    payload = mapOf("command" to ConfigValue.StringValue(shellCommand)),
                )
            )
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, result.value)
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }

        val evaluator = ConditionEvaluator { _, ctx ->
            val result = ctx.capabilities.execute(
                com.yagay.yauto.core.capability.CapabilityRequest(
                    capability = com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "system.shell.execute",
                    payload = mapOf(
                        "command" to ConfigValue.StringValue(
                            "settings get global adb_wifi_enabled 2>/dev/null; getprop service.adb.tls.port; getprop service.adb.tcp.port"
                        )
                    ),
                )
            )
            if (!result.success) return@ConditionEvaluator false
            val stdout = ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()
            stdout.lineSequence().any {
                val value = it.trim()
                value == "1" || value.toIntOrNull()?.let { port -> port > 0 } == true
            }
        }
        val descriptor = FeatureDescriptor(
            FeatureId("android.condition.adb_wifi_available"),
            FeatureKind.CONDITION,
            "ADB Wi-Fi available",
            "Check whether wireless debugging / adb TCP is enabled on this Android device",
            FeatureCategory.SYSTEM,
            capabilities = setOf(com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = listOf(
                FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
                FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
            ),
            keywords = setOf("adb wifi", "wireless debugging", "tasker"),
            ownerPackId = id,
        )
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(id = FeatureId("android.state.adb_wifi_available"), kind = FeatureKind.STATE),
            evaluator,
        )
    }

    private fun shellArg(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private class TaskerScriptHelper(
        private val execution: FeatureExecutionContext,
        private val context: Context,
    ) {
        private val javaVariables = linkedMapOf<String, Any?>()

        fun getVariable(name: String): String? =
            execution.variables.get(normalizeVariable(name))?.let(::configAsString)

        fun setVariable(name: String, value: Any?) {
            val normalized = normalizeVariable(name)
            if (normalized.isBlank()) return
            execution.variables.set(normalized, javaToConfigStatic(value))
        }

        fun setVariable(name: String, value: Any?, structureVariable: Boolean) {
            setVariable(name, value)
        }

        fun getJavaVariable(name: String): Any? = javaVariables[name]

        fun setJavaVariable(name: String, value: Any?) {
            if (name.isNotBlank()) javaVariables[name] = value
        }

        fun clearGlobalJavaVariables() {
            javaVariables.clear()
        }

        fun log(message: Any?) {
            android.util.Log.i("YAuto-JavaCode", message?.toString().orEmpty())
        }

        fun getPackageName(): String = context.packageName

        private fun normalizeVariable(name: String): String = name.trim().removePrefix("%")

        private fun configAsString(value: ConfigValue): String = when (value) {
            ConfigValue.NullValue -> ""
            is ConfigValue.StringValue -> value.value
            is ConfigValue.NumberValue -> value.value.toString().removeSuffix(".0")
            is ConfigValue.BooleanValue -> value.value.toString()
            is ConfigValue.ListValue -> value.value.joinToString(",") { configAsString(it) }
            is ConfigValue.ObjectValue -> value.value.toString()
        }
    }

    private fun pluginFields(): List<FieldSchema> = listOf(
        FieldSchema.AppPicker("package", "Plugin package", true),
        FieldSchema.Text("receiverClass", "Plugin receiver class (optional)"),
        FieldSchema.Text("bundleJson", "Plugin Bundle as JSON", multiline = true),
        FieldSchema.Duration("timeoutMs", "Timeout"),
    )

    private fun pluginTimeout(feature: FeatureRef): Long =
        (feature.config["timeoutMs"].numberOrNull() ?: 10_000.0)
            .toLong().coerceIn(100L, 120_000L)

    private fun mvelVariables(values: Map<String, ConfigValue>): MutableMap<String, Any?> =
        values.mapValuesTo(linkedMapOf()) { configToJava(it.value) }.apply {
            put("context", context)
            put("yautoContext", context)
        }

    private fun configToJava(value: ConfigValue): Any? = when (value) {
        ConfigValue.NullValue -> null
        is ConfigValue.StringValue -> value.value
        is ConfigValue.NumberValue -> value.value
        is ConfigValue.BooleanValue -> value.value
        is ConfigValue.ListValue -> value.value.map(::configToJava).toMutableList()
        is ConfigValue.ObjectValue -> value.value.mapValues { configToJava(it.value) }.toMutableMap()
    }

    private fun javaToConfig(value: Any?): ConfigValue = javaToConfigStatic(value)

    private fun javaToConfigStatic(value: Any?): ConfigValue = when (value) {
        null -> ConfigValue.NullValue
        is ConfigValue -> value
        is Boolean -> ConfigValue.BooleanValue(value)
        is Number -> ConfigValue.NumberValue(value.toDouble())
        is CharSequence -> ConfigValue.StringValue(value.toString())
        is Map<*, *> -> ConfigValue.ObjectValue(
            value.entries.associate { (key, item) -> key.toString() to javaToConfig(item) }
        )
        is Iterable<*> -> ConfigValue.ListValue(value.map(::javaToConfig))
        is Array<*> -> ConfigValue.ListValue(value.map(::javaToConfig))
        is IntArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is LongArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is FloatArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is DoubleArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it) })
        is BooleanArray -> ConfigValue.ListValue(value.map { ConfigValue.BooleanValue(it) })
        else -> ConfigValue.StringValue(value.toString())
    }

    private companion object {
        val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        val ADB_TARGET = Regex("(?:127\\.0\\.0\\.1|localhost|[0-9A-Fa-f:.]+):[0-9]{1,5}")
    }
}

internal fun localePluginRuleKey(feature: FeatureRef): String =
    feature.config.entries
        .filterKeys { it !in setOf("source.raw", "source.type", "source.importer", "tag", "timeoutMs") }
        .toSortedMap()
        .entries
        .joinToString(";") { it.key + "=" + it.value }
