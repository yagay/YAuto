package com.yagay.yauto.platform.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
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

class AndroidTaskerAdvancedFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.tasker.advanced"
    private val context = context.applicationContext
    private val plugins = LocalePluginHost(this.context)
    private val javaFunctions = TaskerJavaFunctionExecutor(this.context)
    private val scriptExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "YAuto-Script").apply { isDaemon = true }
    }

    override fun install(registry: FeatureRegistry) {
        registerBeanShell(registry)
        registerJavaFunction(registry)
        registerJavaObject(registry)
        registerPluginAction(registry)
        registerPluginCondition(registry)
        registerPluginEvent(registry)
        registerPluginScan(registry)
        registerAdbWifi(registry)
    }

    private fun registerBeanShell(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.tasker.beanshell.execute"),
                FeatureKind.ACTION,
                "Run Tasker BeanShell",
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
                interpreter.set("tasker", ScriptTaskerBridge(ctx))
                interpreter.set("variables", variables.mapValues { configToJava(it.value) }.toMutableMap())
                variables.forEach { (name, value) ->
                    if (JAVA_IDENTIFIER.matches(name)) {
                        runCatching { interpreter.set(name, configToJava(value)) }
                    }
                }
                val value = interpreter.eval(script)
                runCatching {
                    interpreter.nameSpace.variableNames.orEmpty().forEach { name ->
                        if (
                            name !in setOf("context", "yautoContext", "variables", "tasker") &&
                            JAVA_IDENTIFIER.matches(name)
                        ) {
                            val candidate = interpreter.get(name)
                            if (
                                candidate != null &&
                                candidate !is String &&
                                candidate !is Number &&
                                candidate !is Boolean &&
                                candidate !is Char
                            ) {
                                TaskerJavaObjectStore.put(ctx.executionId, name, candidate)
                            }
                        }
                    }
                }
                value
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

    private fun registerJavaFunction(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.java_function"),
                FeatureKind.ACTION,
                "Tasker Java Function",
                "Invoke an Android/Java method while preserving Java object references across actions in the same execution",
                FeatureCategory.SCRIPT,
                fields = buildList {
                    add(FieldSchema.Text("resultTarget", "Result variable / Java object name"))
                    add(FieldSchema.Text("target", "Object or class", true))
                    add(FieldSchema.Text("signature", "Function signature", true))
                    repeat(10) { index -> add(FieldSchema.Text("arg" + index, "Argument " + (index + 1))) }
                },
                fieldBehaviors = buildMap {
                    put("resultTarget", FieldBehavior(supportsVariables = true))
                    put("target", FieldBehavior(supportsVariables = true))
                    put("signature", FieldBehavior(supportsVariables = true))
                    repeat(10) { index ->
                        put(
                            "arg" + index,
                            FieldBehavior(supportsVariables = true, advanced = index >= 4),
                        )
                    }
                },
                keywords = setOf("java function", "java object", "reflection", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx -> javaFunctions.execute(feature, ctx) }
    }

    private fun registerJavaObject(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.java_object.manage"),
                FeatureKind.ACTION,
                "Tasker Java Object",
                "Delete a Java object or clear global Java objects kept by YAuto",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Choice("operation", "Operation", true, listOf("delete", "clear_globals", "clear_locals")),
                    FieldSchema.Text("name", "Java object name"),
                ),
                keywords = setOf("java object", "delete java object", "global java", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val ok = when (feature.config.string("operation", "delete")) {
                "delete" -> TaskerJavaObjectStore.remove(ctx.executionId, feature.config.string("name"))
                "clear_globals" -> {
                    TaskerJavaObjectStore.clearGlobals()
                    true
                }
                "clear_locals" -> {
                    TaskerJavaObjectStore.clear(ctx.executionId)
                    true
                }
                else -> false
            }
            ActionExecutionResult(ok)
        }
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

    private inner class ScriptTaskerBridge(
        private val ctx: FeatureExecutionContext,
    ) {
        fun getVariable(name: String): String =
            ctx.variables.get(normalizeTaskerVariable(name))?.let(::configText).orEmpty()

        fun setVariable(name: String, value: Any?) {
            ctx.variables.set(normalizeTaskerVariable(name), javaToConfig(value))
        }

        fun getJavaVariable(name: String): Any? =
            TaskerJavaObjectStore.get(ctx.executionId, name.trim())

        fun setJavaVariable(name: String, value: Any?) {
            TaskerJavaObjectStore.put(ctx.executionId, name.trim(), value)
        }

        fun showToast(text: Any?) {
            val message = text?.toString().orEmpty()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        fun log(text: Any?) {
            android.util.Log.i("YAuto-JavaCode", text?.toString().orEmpty())
        }

        fun logAndToast(text: Any?) {
            log(text)
            showToast(text)
        }

        fun clearGlobalJavaVariables() {
            TaskerJavaObjectStore.clearGlobals()
        }

        fun removeJavaVariable(name: String): Boolean =
            TaskerJavaObjectStore.remove(ctx.executionId, name.trim())

        fun getPackageName(): String = context.packageName
    }

    private fun normalizeTaskerVariable(name: String): String {
        val value = name.trim()
        return if (value.startsWith("%")) value else "%" + value
    }

    private fun configText(value: ConfigValue): String = when (value) {
        ConfigValue.NullValue -> ""
        is ConfigValue.StringValue -> value.value
        is ConfigValue.NumberValue -> value.value.toString().removeSuffix(".0")
        is ConfigValue.BooleanValue -> value.value.toString()
        is ConfigValue.ListValue -> value.value.joinToString(",") { configText(it) }
        is ConfigValue.ObjectValue -> value.value.entries.joinToString(",") {
            it.key + "=" + configText(it.value)
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
    feature.config
        .filterKeys { it !in setOf("source.raw", "source.type", "source.importer", "tag", "timeoutMs") }
        .toSortedMap()
        .entries
        .joinToString(";") { it.key + "=" + it.value }
