package com.yagay.yauto.platform.accessibility

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mvel2.MVEL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class AccessibilityMvelFeaturePack(
    context: Context,
) : FeaturePack {
    override val id: String = "accessibility.mvel"
    private val appContext = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("script.mvel.execute"), FeatureKind.ACTION,
                "Run MVEL",
                "Execute an MVEL expression/script with Android context and YAuto variable bindings",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("expression", "MVEL code", true, multiline = true),
                    FieldSchema.Duration("timeoutMs", "Execution timeout"),
                    FieldSchema.Variable("resultVariable", "Store returned value"),
                ),
                keywords = setOf("mvel", "shortx", "script", "expression"),
                ownerPackId = id,
                stability = com.yagay.yauto.core.model.Stability.EXPERIMENTAL,
            )
        ) { feature, ctx ->
            val expression = feature.config.string("expression")
            val timeout = ((feature.config["timeoutMs"] as? ConfigValue.NumberValue)?.value ?: 10_000.0)
                .toLong().coerceIn(100L, 30_000L)
            val raw = evaluate(expression, timeout, ctx.variables)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.mvel_failed"))
            val output = javaToConfig(raw)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }

        val condition = FeatureDescriptor(
            FeatureId("script.mvel.condition"), FeatureKind.CONDITION,
            "MVEL condition",
            "Evaluate an MVEL expression as a boolean condition",
            FeatureCategory.SCRIPT,
            fields = listOf(
                FieldSchema.Text("expression", "MVEL expression", true, multiline = true),
                FieldSchema.Duration("timeoutMs", "Execution timeout"),
            ),
            keywords = setOf("mvel", "shortx", "condition", "expression"),
            ownerPackId = id,
            stability = com.yagay.yauto.core.model.Stability.EXPERIMENTAL,
        )
        registry.registerCondition(condition) { feature, ctx ->
            val raw = evaluate(
                feature.config.string("expression"),
                ((feature.config["timeoutMs"] as? ConfigValue.NumberValue)?.value ?: 5_000.0).toLong().coerceIn(100L, 30_000L),
                ctx.variables,
            )
            truthy(raw)
        }
        registry.registerState(
            condition.copy(id = FeatureId("script.mvel.state"), kind = FeatureKind.STATE)
        ) { feature, ctx ->
            val raw = evaluate(
                feature.config.string("expression"),
                ((feature.config["timeoutMs"] as? ConfigValue.NumberValue)?.value ?: 5_000.0).toLong().coerceIn(100L, 30_000L),
                ctx.variables,
            )
            truthy(raw)
        }
    }

    private suspend fun evaluate(
        expression: String,
        timeoutMs: Long,
        variables: VariableAccess,
    ): Any? = withContext(Dispatchers.IO) {
        if (expression.isBlank() || expression.length > 100_000) return@withContext null
        val bindings = HashMap<String, Any?>()
        variables.snapshot().forEach { (name, value) -> bindings[name] = configToJava(value) }
        bindings["appContext"] = appContext
        bindings["accessibility"] = YAutoAccessibilityService.current
        bindings["variableSetter"] = BeanShellVariableBridge(variables)
        bindings["globals"] = BeanShellGlobals
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "YAutoMVEL").apply { isDaemon = true }
        }
        val future = executor.submit<Any?> { MVEL.eval(expression, bindings) }
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            null
        } catch (_: Throwable) {
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        is Boolean -> value
        is Number -> value.toDouble() != 0.0
        is CharSequence -> value.isNotBlank() && !value.toString().equals("false", ignoreCase = true)
        else -> value != null
    }
}
