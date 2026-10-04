package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.engine.SimpleExpressionEngine
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

internal object ExpressionFeatures {
    private val engine = SimpleExpressionEngine()

    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
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
            )
        ) { feature, ctx ->
            val output = runCatching {
                ConfigValue.BooleanValue(engine.evaluateBoolean(feature.config.string("expression"), ctx.variables))
            }.getOrElse {
                return@actionFeature ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                )
            }
            storeExpressionResult(feature, ctx, output)
        },
        actionFeature(
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
            )
        ) { feature, ctx ->
            val output = runCatching {
                ConfigValue.StringValue(engine.evaluateText(feature.config.string("expression"), ctx.variables))
            }.getOrElse {
                return@actionFeature ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                )
            }
            storeExpressionResult(feature, ctx, output)
        },
        conditionFeature(
            FeatureDescriptor(
                FeatureId("expression.condition"), FeatureKind.CONDITION,
                "Expression condition",
                "Evaluate a portable boolean expression against current YAuto variables",
                FeatureCategory.SCRIPT,
                fields = listOf(FieldSchema.Text("expression", "Expression", true, multiline = true)),
                keywords = setOf("expression", "mvel", "condition", "tasker", "shortx"),
            )
        ) { feature, ctx ->
            runCatching { engine.evaluateBoolean(feature.config.string("expression"), ctx.variables) }.getOrDefault(false)
        },
        stateFeature(
            FeatureDescriptor(
                FeatureId("expression.state"), FeatureKind.STATE,
                "Expression condition",
                "Evaluate a portable boolean expression against current YAuto variables",
                FeatureCategory.SCRIPT,
                fields = listOf(FieldSchema.Text("expression", "Expression", true, multiline = true)),
                keywords = setOf("expression", "mvel", "state", "tasker", "shortx"),
            )
        ) { feature, ctx ->
            runCatching { engine.evaluateBoolean(feature.config.string("expression"), ctx.variables) }.getOrDefault(false)
        },
    )
}

private fun storeExpressionResult(
    feature: com.yagay.yauto.core.model.FeatureRef,
    ctx: FeatureExecutionContext,
    value: ConfigValue,
): ActionExecutionResult {
    val target = feature.config.string("resultVariable").trim()
    if (target.isBlank()) return ActionExecutionResult(false, message = userText("feature.result_variable_empty"))
    ctx.variables.set(target, value)
    return ActionExecutionResult(true, value)
}
