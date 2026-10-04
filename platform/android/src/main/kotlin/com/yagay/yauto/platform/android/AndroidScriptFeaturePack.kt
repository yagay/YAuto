package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.EvaluatorException
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import java.util.concurrent.TimeUnit

/** Sandboxed JavaScript automation without exposing Android/Java classes to scripts. */
class AndroidScriptFeaturePack : FeaturePack {
    override val id: String = "android.script"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.javascript.execute"), FeatureKind.ACTION,
                "Run JavaScript", "Execute sandboxed JavaScript and store its returned value",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("script", "JavaScript", true, multiline = true),
                    FieldSchema.Text("input", "Input text"),
                    FieldSchema.Duration("timeoutMs", "Execution timeout"),
                    FieldSchema.Variable("resultVariable", "Store script result"),
                ),
                fieldBehaviors = mapOf(
                    "script" to FieldBehavior(supportsVariables = true),
                    "input" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("javascript", "script", "expression", "code", "Tasker"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val script = feature.config.string("script").resolveVariables(ctx.variables)
            if (script.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.script_empty"))
            val input = feature.config.string("input").resolveVariables(ctx.variables)
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 2_000.0).toLong().coerceIn(50L, 30_000L)
            val result = runCatching { executeJavaScript(script, input, timeout) }
                .getOrElse { return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result) }
            ActionExecutionResult(true, result)
        }

        val evaluator = ConditionEvaluator { feature, ctx ->
            val expression = feature.config.string("expression").resolveVariables(ctx.variables)
            if (expression.isBlank()) return@ConditionEvaluator false
            val input = feature.config.string("input").resolveVariables(ctx.variables)
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 1_000.0).toLong().coerceIn(50L, 30_000L)
            when (val result = runCatching { executeJavaScript("Boolean(" + expression + ")", input, timeout) }.getOrNull()) {
                is ConfigValue.BooleanValue -> result.value
                is ConfigValue.NumberValue -> result.value != 0.0
                is ConfigValue.StringValue -> result.value.equals("true", ignoreCase = true)
                else -> false
            }
        }
        val condition = FeatureDescriptor(
            FeatureId("script.javascript.condition"), FeatureKind.CONDITION,
            "JavaScript condition", "Evaluate a sandboxed JavaScript expression as a condition",
            FeatureCategory.SCRIPT,
            fields = listOf(
                FieldSchema.Text("expression", "Boolean expression", true, multiline = true),
                FieldSchema.Text("input", "Input text"),
                FieldSchema.Duration("timeoutMs", "Execution timeout"),
            ),
            fieldBehaviors = mapOf(
                "expression" to FieldBehavior(supportsVariables = true),
                "input" to FieldBehavior(supportsVariables = true),
            ),
            keywords = setOf("javascript", "condition", "expression", "script"), ownerPackId = id,
        )
        registry.registerCondition(condition, evaluator)
        registry.registerState(condition.copy(id = FeatureId("script.javascript.state"), kind = FeatureKind.STATE), evaluator)
    }
}

internal fun executeJavaScript(script: String, input: String, timeoutMs: Long): ConfigValue {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    val factory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            instructionObserverThreshold = 10_000
            classShutter = ClassShutter { false }
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.nanoTime() >= deadline) throw EvaluatorException("JavaScript execution timed out")
        }
    }
    return factory.call { cx ->
        val scope = cx.initSafeStandardObjects()
        ScriptableObject.putProperty(scope, "input", input)
        val raw = cx.evaluateString(scope, script, "YAuto", 1, null)
        rhinoToConfig(raw)
    }
}

private fun rhinoToConfig(value: Any?): ConfigValue = when (value) {
    null, Undefined.instance -> ConfigValue.NullValue
    is Boolean -> ConfigValue.BooleanValue(value)
    is Number -> ConfigValue.NumberValue(value.toDouble())
    is CharSequence -> ConfigValue.StringValue(value.toString())
    is NativeArray -> ConfigValue.ListValue((0 until value.length.toInt()).map { index -> rhinoToConfig(value.get(index, value)) })
    is NativeObject -> ConfigValue.ObjectValue(value.ids.associate { id ->
        val key = id.toString()
        key to rhinoToConfig(value.get(key, value))
    })
    is Scriptable -> ConfigValue.StringValue(Context.toString(value))
    else -> ConfigValue.StringValue(value.toString())
}
