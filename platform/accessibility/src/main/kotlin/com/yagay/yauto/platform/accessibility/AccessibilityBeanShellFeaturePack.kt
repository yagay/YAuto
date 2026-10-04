package com.yagay.yauto.platform.accessibility

import android.content.Context
import bsh.Interpreter
import bsh.Primitive
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * BeanShell Java automation compatible with the core shape of MacroDroid's Java Action.
 *
 * Imported source remains a compatibility node until the user explicitly upgrades it. This runtime
 * intentionally exposes Android Context and the live AccessibilityService because that is the
 * purpose of this advanced scripting feature.
 */
class AccessibilityBeanShellFeaturePack(
    context: Context,
) : FeaturePack {
    override val id: String = "accessibility.beanshell"
    private val appContext = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.beanshell.execute"), FeatureKind.ACTION,
                "Run Java / BeanShell",
                "Execute trusted BeanShell Java code with appContext, accessibility, magicText, variableSetter and globals bindings",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("script", "BeanShell Java code", true, multiline = true),
                    FieldSchema.Duration("timeoutMs", "Execution timeout"),
                    FieldSchema.Variable("resultVariable", "Store returned value"),
                    FieldSchema.Variable("consoleVariable", "Store console output"),
                ),
                keywords = setOf("java", "beanshell", "script", "macrodroid", "tasker"),
                ownerPackId = id,
                stability = com.yagay.yauto.core.model.Stability.EXPERIMENTAL,
            )
        ) { feature, ctx ->
            val script = feature.config.string("script")
            if (script.isBlank() || script.length > MAX_SCRIPT_LENGTH) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "invalid script"))
            }
            val timeoutMs = ((feature.config["timeoutMs"] as? ConfigValue.NumberValue)?.value ?: 10_000.0)
                .toLong().coerceIn(100L, 30_000L)
            val output = runBeanShell(script, timeoutMs, ctx.variables)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "BeanShell timeout or error"))

            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let {
                ctx.variables.set(it, output.value)
            }
            feature.config.string("consoleVariable").trim().takeIf { it.isNotBlank() }?.let {
                ctx.variables.set(it, ConfigValue.StringValue(output.console))
            }
            ActionExecutionResult(true, output.value)
        }
    }

    private suspend fun runBeanShell(
        script: String,
        timeoutMs: Long,
        variables: VariableAccess,
    ): BeanShellOutput? = withContext(Dispatchers.IO) {
        val consoleBytes = ByteArrayOutputStream()
        val console = PrintStream(consoleBytes, true, StandardCharsets.UTF_8.name())
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "YAutoBeanShell").apply { isDaemon = true }
        }
        val bridge = BeanShellVariableBridge(variables)
        val magicText = BeanShellMagicText(variables)
        val future = executor.submit<Any?> {
            Interpreter().apply {
                setOut(console)
                setErr(console)
                set("appContext", appContext)
                set("accessibility", YAutoAccessibilityService.current)
                set("magicText", magicText)
                set("variableSetter", bridge)
                set("variables", bridge)
                set("globals", BeanShellGlobals)
            }.eval(script)
        }
        try {
            val raw = future.get(timeoutMs, TimeUnit.MILLISECONDS)
            val value = javaToConfig(runCatching { Primitive.unwrap(raw) }.getOrDefault(raw))
            val text = consoleBytes.toString(StandardCharsets.UTF_8.name()).take(MAX_CONSOLE_LENGTH)
            BeanShellOutput(value, text)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Throwable) {
            null
        } finally {
            console.close()
            executor.shutdownNow()
        }
    }

    private data class BeanShellOutput(
        val value: ConfigValue,
        val console: String,
    )

    companion object {
        private const val MAX_SCRIPT_LENGTH = 100_000
        private const val MAX_CONSOLE_LENGTH = 64_000
    }
}

class BeanShellVariableBridge(
    private val variables: VariableAccess,
) {
    fun get(name: String): Any? = configToJava(variables.get(name))
    fun names(): Array<String> = variables.snapshot().keys.sorted().toTypedArray()

    fun setString(name: String, value: String): Boolean = set(name, ConfigValue.StringValue(value))
    fun setInt(name: String, value: Long): Boolean = set(name, ConfigValue.NumberValue(value.toDouble()))
    fun setDecimal(name: String, value: Double): Boolean = set(name, ConfigValue.NumberValue(value))
    fun setBoolean(name: String, value: Boolean): Boolean = set(name, ConfigValue.BooleanValue(value))
    fun setColor(name: String, value: String): Boolean = setString(name, value)
    fun setColor(name: String, value: Int): Boolean = setInt(name, value.toLong())

    fun setArray(name: String, json: String): Boolean {
        val value = runCatching { jsonToConfig(JSONArray(json)) }.getOrNull() ?: return false
        return set(name, value)
    }

    fun setDict(name: String, json: String): Boolean {
        val value = runCatching { jsonToConfig(JSONObject(json)) }.getOrNull() ?: return false
        return set(name, value)
    }

    fun setValue(name: String, value: Any?): Boolean = set(name, javaToConfig(value))

    private fun set(name: String, value: ConfigValue): Boolean {
        if (name.isBlank()) return false
        variables.set(name, value)
        return true
    }
}

class BeanShellMagicText(
    private val variables: VariableAccess,
) {
    fun evaluateString(text: String): String = text.resolveVariables(variables)
    fun evaluateInt(text: String): Long = evaluateString(text).trim().toLongOrNull() ?: 0L
    fun evaluateDecimal(text: String): Double = evaluateString(text).trim().toDoubleOrNull() ?: 0.0
    fun evaluateBoolean(text: String): Boolean {
        val value = evaluateString(text).trim()
        return value.equals("true", ignoreCase = true) || value == "1"
    }
}

object BeanShellGlobals {
    private val values = ConcurrentHashMap<String, Any?>()

    @JvmStatic fun get(name: String): Any? = values[name]
    @JvmStatic fun set(name: String, value: Any?): Any? {
        if (name.isBlank()) return value
        if (value == null) values.remove(name) else values[name] = value
        return value
    }
    @JvmStatic fun remove(name: String): Any? = values.remove(name)
    @JvmStatic fun contains(name: String): Boolean = values.containsKey(name)
    @JvmStatic fun clear() = values.clear()
    @JvmStatic fun keys(): Array<String> = values.keys.sorted().toTypedArray()
}

private fun configToJava(value: ConfigValue?): Any? = when (value) {
    null, ConfigValue.NullValue -> null
    is ConfigValue.StringValue -> value.value
    is ConfigValue.NumberValue -> value.value
    is ConfigValue.BooleanValue -> value.value
    is ConfigValue.ListValue -> value.value.map(::configToJava)
    is ConfigValue.ObjectValue -> value.value.mapValues { configToJava(it.value) }
}

private fun javaToConfig(value: Any?, depth: Int = 0): ConfigValue {
    if (depth >= 16) return ConfigValue.StringValue(value?.toString().orEmpty())
    return when (value) {
        null -> ConfigValue.NullValue
        is ConfigValue -> value
        is Boolean -> ConfigValue.BooleanValue(value)
        is Number -> ConfigValue.NumberValue(value.toDouble())
        is CharSequence -> ConfigValue.StringValue(value.toString())
        is Map<*, *> -> ConfigValue.ObjectValue(
            value.entries.associate { (key, item) -> key.toString() to javaToConfig(item, depth + 1) }
        )
        is Iterable<*> -> ConfigValue.ListValue(value.map { javaToConfig(it, depth + 1) })
        is Array<*> -> ConfigValue.ListValue(value.map { javaToConfig(it, depth + 1) })
        is BooleanArray -> ConfigValue.ListValue(value.map { ConfigValue.BooleanValue(it) })
        is IntArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is LongArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is FloatArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it.toDouble()) })
        is DoubleArray -> ConfigValue.ListValue(value.map { ConfigValue.NumberValue(it) })
        else -> ConfigValue.StringValue(value.toString())
    }
}

private fun jsonToConfig(value: Any?, depth: Int = 0): ConfigValue {
    if (depth >= 16) return ConfigValue.StringValue(value?.toString().orEmpty())
    return when (value) {
        null, JSONObject.NULL -> ConfigValue.NullValue
        is Boolean -> ConfigValue.BooleanValue(value)
        is Number -> ConfigValue.NumberValue(value.toDouble())
        is String -> ConfigValue.StringValue(value)
        is JSONArray -> ConfigValue.ListValue((0 until value.length()).map { jsonToConfig(value.opt(it), depth + 1) })
        is JSONObject -> ConfigValue.ObjectValue(
            value.keys().asSequence().associateWith { key -> jsonToConfig(value.opt(key), depth + 1) }
        )
        else -> ConfigValue.StringValue(value.toString())
    }
}
