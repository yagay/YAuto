package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.engine.SimpleExpressionEngine
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Lightweight deterministic expressions for automation rules.
 *
 * This intentionally does not pretend to be Java or MVEL. It provides the common portable subset
 * needed by imported automations while retaining explicit JavaScript / source-compatibility nodes
 * for expressions that require a richer runtime.
 */
class ExpressionFeaturePack : FeaturePack {
    override val id: String = "standard.expression"
    private val engine = SimpleExpressionEngine()

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("expression.boolean.evaluate"), FeatureKind.ACTION,
                "Evaluate boolean expression",
                "Evaluate comparisons and boolean operators against current YAuto variables",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("expression", "Expression", true, multiline = true),
                    FieldSchema.Variable("resultVariable", "Store boolean result", true),
                ),
                fieldBehaviors = mapOf("expression" to FieldBehavior(supportsVariables = false)),
                keywords = setOf("expression", "boolean", "mvel", "condition", "tasker", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val expression = feature.config.string("expression")
            val output = runCatching { ConfigValue.BooleanValue(engine.evaluateBoolean(expression, ctx.variables)) }
                .getOrElse {
                    return@registerAction ActionExecutionResult(
                        false,
                        message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                    )
                }
            val target = feature.config.string("resultVariable").trim()
            if (target.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.result_variable_empty"))
            ctx.variables.set(target, output)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("expression.text.evaluate"), FeatureKind.ACTION,
                "Evaluate text expression",
                "Resolve a variable or literal text expression and store the resulting text",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Text("expression", "Expression", true, multiline = true),
                    FieldSchema.Variable("resultVariable", "Store text result", true),
                ),
                keywords = setOf("expression", "text", "mvel", "value", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = runCatching {
                ConfigValue.StringValue(engine.evaluateText(feature.config.string("expression"), ctx.variables))
            }.getOrElse {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                )
            }
            val target = feature.config.string("resultVariable").trim()
            if (target.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.result_variable_empty"))
            ctx.variables.set(target, output)
            ActionExecutionResult(true, output)
        }

        val descriptor = FeatureDescriptor(
            FeatureId("expression.condition"), FeatureKind.CONDITION,
            "Expression condition",
            "Evaluate a portable boolean expression against current YAuto variables",
            FeatureCategory.SCRIPT,
            fields = listOf(FieldSchema.Text("expression", "Expression", true, multiline = true)),
            keywords = setOf("expression", "mvel", "condition", "tasker", "shortx"),
            ownerPackId = id,
        )
        registry.registerCondition(descriptor) { feature, ctx ->
            runCatching { engine.evaluateBoolean(feature.config.string("expression"), ctx.variables) }.getOrDefault(false)
        }
        registry.registerState(
            descriptor.copy(id = FeatureId("expression.state"), kind = FeatureKind.STATE)
        ) { feature, ctx ->
            runCatching { engine.evaluateBoolean(feature.config.string("expression"), ctx.variables) }.getOrDefault(false)
        }
    }
}
