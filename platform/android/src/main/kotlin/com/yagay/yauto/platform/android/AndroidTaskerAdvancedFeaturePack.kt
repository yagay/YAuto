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

    private fun pluginFields(): List<FieldSchema> = listOf(
        FieldSchema.AppPicker("package", "Plugin package", true),
        FieldSchema.Text("receiverClass", "Plugin receiver class", true),
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

    private fun javaToConfig(value: Any?): ConfigValue = when (value) {
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
        is DoubleArray -> ConfigValue.ListValue(value.map(ConfigValue::NumberValue))
        is BooleanArray -> ConfigValue.ListValue(value.map(ConfigValue::BooleanValue))
        else -> ConfigValue.StringValue(value.toString())
    }

    private companion object {
        val JAVA_IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
    }
}

internal fun localePluginRuleKey(feature: FeatureRef): String =
    feature.config.entries
        .filterKeys { it !in setOf("source.raw", "source.type", "source.importer", "tag", "timeoutMs") }
        .toSortedMap()
        .entries
        .joinToString(";") { it.key + "=" + it.value }
