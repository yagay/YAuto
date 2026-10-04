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
import org.mvel2.MVEL

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
        registerMvel(registry)
        registerPluginAction(registry)
        registerPluginCondition(registry)
        registerPluginEvent(registry)
        registerPluginScan(registry)
        registerAdbWifi(registry)
        registerMatter(registry)
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



    private fun registerMatter(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.matter.light"),
                FeatureKind.ACTION,
                "Matter light control",
                "Control a Matter light with chip-tool: on/off/toggle, brightness and RGB/XY color",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("deviceId", "Matter node ID", true),
                    FieldSchema.Number("endpointId", "Endpoint ID", min = 0.0, max = 65535.0),
                    FieldSchema.Choice("set", "Power", options = listOf("unchanged", "on", "off", "toggle")),
                    FieldSchema.Number("brightness", "Brightness %", min = 0.0, max = 100.0),
                    FieldSchema.Text("color", "Color #RRGGBB"),
                    FieldSchema.Text("binaryPath", "chip-tool binary"),
                    FieldSchema.Variable("resultVariable", "Store command output"),
                ),
                capabilities = setOf(com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("matter", "light", "chip tool", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val node = matterNode(feature.config.string("deviceId").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val endpoint = (feature.config["endpointId"].numberOrNull() ?: 1.0).toInt().coerceIn(0, 65535)
            val binary = matterBinary(feature.config.string("binaryPath").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val commands = mutableListOf<String>()
            when (feature.config.string("set", "unchanged")) {
                "on" -> commands += shellArg(binary) + " onoff on " + node + " " + endpoint
                "off" -> commands += shellArg(binary) + " onoff off " + node + " " + endpoint
                "toggle" -> commands += shellArg(binary) + " onoff toggle " + node + " " + endpoint
            }
            feature.config["brightness"].numberOrNull()?.let { percent ->
                val level = (percent.coerceIn(0.0, 100.0) * 254.0 / 100.0).toInt().coerceIn(0, 254)
                commands += shellArg(binary) + " levelcontrol move-to-level " + level + " 0 0 0 " + node + " " + endpoint
            }
            val color = feature.config.string("color").trim()
            if (color.isNotBlank()) {
                val rgb = parseRgb(color) ?: return@registerAction ActionExecutionResult(false)
                val xy = rgbToMatterXy(rgb.first, rgb.second, rgb.third)
                commands += shellArg(binary) + " colorcontrol move-to-color " + xy.first + " " + xy.second +
                    " 0 0 0 " + node + " " + endpoint
            }
            if (commands.isEmpty()) return@registerAction ActionExecutionResult(false)
            val result = privilegedShell(ctx, commands.joinToString(" && "))
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, result.value)
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }

        val matterStateEvaluator = ConditionEvaluator { feature, ctx ->
            val node = matterNode(feature.config.string("deviceId").resolveVariables(ctx.variables))
                ?: return@ConditionEvaluator false
            val endpoint = (feature.config["endpointId"].numberOrNull() ?: 1.0).toInt().coerceIn(0, 65535)
            val binary = matterBinary(feature.config.string("binaryPath").resolveVariables(ctx.variables))
                ?: return@ConditionEvaluator false
            val result = privilegedShell(
                ctx,
                shellArg(binary) + " onoff read on-off " + node + " " + endpoint,
            )
            if (!result.success) return@ConditionEvaluator false
            val stdout = capabilityStdout(result)
            val on = Regex("""(?i)(?:OnOff|on-off|value)\s*[:=]\s*(?:true|1|0x01)""").containsMatchIn(stdout)
            when (feature.config.string("expected", "on")) {
                "off" -> !on
                else -> on
            }
        }
        val matterState = FeatureDescriptor(
            FeatureId("android.condition.matter_light"),
            FeatureKind.CONDITION,
            "Matter light state",
            "Read a Matter OnOff cluster with chip-tool and compare the current state",
            FeatureCategory.DEVICE,
            fields = listOf(
                FieldSchema.Text("deviceId", "Matter node ID", true),
                FieldSchema.Number("endpointId", "Endpoint ID", min = 0.0, max = 65535.0),
                FieldSchema.Choice("expected", "Expected", true, listOf("on", "off")),
                FieldSchema.Text("binaryPath", "chip-tool binary"),
            ),
            capabilities = setOf(com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            keywords = setOf("matter", "light state", "chip tool", "tasker"),
            ownerPackId = id,
        )
        registry.registerCondition(matterState, matterStateEvaluator)
        registry.registerState(
            matterState.copy(id = FeatureId("android.state.matter_light"), kind = FeatureKind.STATE),
            matterStateEvaluator,
        )

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.matter.command"),
                FeatureKind.ACTION,
                "Matter cluster command",
                "Run a generic chip-tool cluster command against a Matter node",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("cluster", "Cluster", true),
                    FieldSchema.Text("command", "Command", true),
                    FieldSchema.Text("arguments", "Arguments before node/endpoint"),
                    FieldSchema.Text("deviceId", "Matter node ID", true),
                    FieldSchema.Number("endpointId", "Endpoint ID", min = 0.0, max = 65535.0),
                    FieldSchema.Text("binaryPath", "chip-tool binary"),
                    FieldSchema.Variable("resultVariable", "Store command output"),
                ),
                fieldBehaviors = mapOf(
                    "cluster" to FieldBehavior(supportsVariables = true),
                    "command" to FieldBehavior(supportsVariables = true),
                    "arguments" to FieldBehavior(supportsVariables = true),
                ),
                capabilities = setOf(com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("matter", "cluster", "command", "chip tool"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val cluster = feature.config.string("cluster").resolveVariables(ctx.variables).trim()
            val command = feature.config.string("command").resolveVariables(ctx.variables).trim()
            val args = feature.config.string("arguments").resolveVariables(ctx.variables).trim()
            if (!MATTER_TOKEN.matches(cluster) || !MATTER_TOKEN.matches(command)) {
                return@registerAction ActionExecutionResult(false)
            }
            val safeArgs = args.takeIf(String::isNotBlank)?.let(::safeMatterArguments)
                ?: if (args.isBlank()) "" else return@registerAction ActionExecutionResult(false)
            val node = matterNode(feature.config.string("deviceId").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val endpoint = (feature.config["endpointId"].numberOrNull() ?: 1.0).toInt().coerceIn(0, 65535)
            val binary = matterBinary(feature.config.string("binaryPath").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val shell = buildString {
                append(shellArg(binary)).append(' ')
                append(cluster).append(' ').append(command).append(' ')
                if (safeArgs.isNotBlank()) append(safeArgs).append(' ')
                append(node).append(' ').append(endpoint)
            }
            val result = privilegedShell(ctx, shell)
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, result.value)
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.matter.commission"),
                FeatureKind.ACTION,
                "Commission Matter device",
                "Commission a Matter device using chip-tool pairing code, on-network or BLE/Wi-Fi",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("method", "Method", true, listOf("code", "onnetwork", "ble_wifi")),
                    FieldSchema.Text("deviceId", "New node ID", true),
                    FieldSchema.Text("setupCode", "QR/manual pairing code"),
                    FieldSchema.Number("pinCode", "Setup PIN", min = 0.0),
                    FieldSchema.Number("discriminator", "Discriminator", min = 0.0, max = 4095.0),
                    FieldSchema.Text("ssid", "Wi-Fi SSID"),
                    FieldSchema.Text("password", "Wi-Fi password"),
                    FieldSchema.Text("binaryPath", "chip-tool binary"),
                    FieldSchema.Variable("resultVariable", "Store pairing output"),
                ),
                capabilities = setOf(com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("matter", "commission", "pair", "chip tool", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val node = matterNode(feature.config.string("deviceId").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val binary = matterBinary(feature.config.string("binaryPath").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val command = when (feature.config.string("method", "code")) {
                "code" -> {
                    val code = feature.config.string("setupCode").resolveVariables(ctx.variables).trim()
                    if (!MATTER_SETUP_CODE.matches(code)) return@registerAction ActionExecutionResult(false)
                    shellArg(binary) + " pairing code " + node + " " + shellArg(code)
                }
                "onnetwork" -> {
                    val pin = feature.config["pinCode"].numberOrNull()?.toLong()
                        ?: return@registerAction ActionExecutionResult(false)
                    shellArg(binary) + " pairing onnetwork " + node + " " + pin.coerceAtLeast(0L)
                }
                "ble_wifi" -> {
                    val ssid = feature.config.string("ssid").resolveVariables(ctx.variables)
                    val password = feature.config.string("password").resolveVariables(ctx.variables)
                    val pin = feature.config["pinCode"].numberOrNull()?.toLong()
                        ?: return@registerAction ActionExecutionResult(false)
                    val discriminator = feature.config["discriminator"].numberOrNull()?.toInt()
                        ?: return@registerAction ActionExecutionResult(false)
                    shellArg(binary) + " pairing ble-wifi " + node + " " + shellArg(ssid) + " " +
                        shellArg(password) + " " + pin.coerceAtLeast(0L) + " " + discriminator.coerceIn(0, 4095)
                }
                else -> return@registerAction ActionExecutionResult(false)
            }
            val result = privilegedShell(ctx, command)
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, result.value)
            }
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }

    private suspend fun privilegedShell(
        ctx: FeatureExecutionContext,
        command: String,
    ): com.yagay.yauto.core.capability.CapabilityResult =
        ctx.capabilities.execute(
            com.yagay.yauto.core.capability.CapabilityRequest(
                capability = com.yagay.yauto.core.capability.CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )

    private fun capabilityStdout(result: com.yagay.yauto.core.capability.CapabilityResult): String =
        ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()

    private fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    private fun matterBinary(raw: String): String? {
        val value = raw.trim().ifBlank { "chip-tool" }
        return value.takeIf { MATTER_BINARY.matches(it) }
    }

    private fun matterNode(raw: String): String? {
        val value = raw.trim()
        return value.takeIf { MATTER_NODE.matches(it) }
    }

    private fun safeMatterArguments(raw: String): String? {
        val tokens = raw.split(Regex("""\s+""")).filter(String::isNotBlank)
        if (tokens.size > 32 || tokens.any { !MATTER_ARGUMENT.matches(it) }) return null
        return tokens.joinToString(" ") { shellArg(it) }
    }

    private fun parseRgb(raw: String): Triple<Int, Int, Int>? {
        val match = Regex("""^#?([0-9A-Fa-f]{6})$""").matchEntire(raw.trim()) ?: return null
        val value = match.groupValues[1].toInt(16)
        return Triple((value shr 16) and 0xff, (value shr 8) and 0xff, value and 0xff)
    }

    private fun rgbToMatterXy(r: Int, g: Int, b: Int): Pair<Int, Int> {
        fun linear(v: Int): Double {
            val n = v / 255.0
            return if (n > 0.04045) Math.pow((n + 0.055) / 1.055, 2.4) else n / 12.92
        }
        val rr = linear(r)
        val gg = linear(g)
        val bb = linear(b)
        val x = rr * 0.664511 + gg * 0.154324 + bb * 0.162028
        val y = rr * 0.283881 + gg * 0.668433 + bb * 0.047685
        val z = rr * 0.000088 + gg * 0.072310 + bb * 0.986039
        val sum = x + y + z
        if (sum <= 0.0) return 0 to 0
        return ((x / sum) * 65535.0).toInt().coerceIn(0, 65535) to
            ((y / sum) * 65535.0).toInt().coerceIn(0, 65535)
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
        val MATTER_BINARY = Regex("(?:chip-tool|/[A-Za-z0-9_./-]{1,240})")
        val MATTER_NODE = Regex("(?:0[xX][0-9A-Fa-f]{1,16}|[0-9]{1,20})")
        val MATTER_TOKEN = Regex("[A-Za-z0-9_-]{1,80}")
        val MATTER_ARGUMENT = Regex("[A-Za-z0-9_+.,:/=@%#-]{1,256}")
        val MATTER_SETUP_CODE = Regex("[0-9A-Za-z+:/.-]{4,512}")
    }
}

internal fun localePluginRuleKey(feature: FeatureRef): String =
    feature.config.entries
        .filterKeys { it !in setOf("source.raw", "source.type", "source.importer", "tag", "timeoutMs") }
        .toSortedMap()
        .entries
        .joinToString(";") { it.key + "=" + it.value }
