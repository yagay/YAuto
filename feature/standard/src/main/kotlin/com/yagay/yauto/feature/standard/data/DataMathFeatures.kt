package com.yagay.yauto.feature.standard.data

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round

internal object DataMathFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            dataDescriptor(
                "data.math.calculate",
                "Calculate numbers",
                "Apply a common arithmetic operation to two numbers and store the result",
                fields = listOf(
                    FieldSchema.Number("a", "First number", true),
                    FieldSchema.Choice(
                        "operation",
                        "Operation",
                        true,
                        listOf("add", "subtract", "multiply", "divide", "modulo", "power", "min", "max"),
                    ),
                    FieldSchema.Number("b", "Second number", true),
                    FieldSchema.Variable("resultVariable", "Store result in variable", true),
                ),
                keywords = setOf("math", "calculate", "add", "divide", "power"),
                behaviors = mapOf("operation" to FieldBehavior(defaultValue = ConfigValue.StringValue("add"))),
            )
        ) { feature, context ->
            val a = feature.number("a")
            val b = feature.number("b")
            val value = when (feature.config.string("operation", "add")) {
                "add" -> a + b
                "subtract" -> a - b
                "multiply" -> a * b
                "divide" -> if (b == 0.0) null else a / b
                "modulo" -> if (b == 0.0) null else a % b
                "power" -> a.pow(b)
                "min" -> minOf(a, b)
                "max" -> maxOf(a, b)
                else -> null
            }?.takeIf(Double::isFinite)
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.math_invalid_operation"))
            context.storeNumber(feature.destination(), value)
        },
        actionFeature(
            dataDescriptor(
                "data.math.round",
                "Round number",
                "Round, floor or ceil a number with optional decimal precision",
                fields = listOf(
                    FieldSchema.Number("value", "Number", true),
                    FieldSchema.Choice("mode", "Rounding mode", true, listOf("round", "floor", "ceil")),
                    FieldSchema.Number("decimals", "Decimal places", min = 0.0, max = 12.0),
                    FieldSchema.Variable("resultVariable", "Store result in variable", true),
                ),
                keywords = setOf("math", "round", "floor", "ceil", "decimal"),
                behaviors = mapOf(
                    "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("round")),
                    "decimals" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                ),
            )
        ) { feature, context ->
            val value = feature.number("value")
            val decimals = feature.number("decimals").toInt().coerceIn(0, 12)
            val factor = 10.0.pow(decimals)
            val scaled = value * factor
            val rounded = when (feature.config.string("mode", "round")) {
                "round" -> round(scaled)
                "floor" -> floor(scaled)
                "ceil" -> ceil(scaled)
                else -> return@actionFeature ActionExecutionResult(false, message = userText("feature.math_invalid_operation"))
            } / factor
            context.storeNumber(feature.destination(), rounded)
        },
        actionFeature(
            dataDescriptor(
                "data.math.clamp",
                "Clamp number",
                "Keep a number inside an inclusive minimum and maximum range",
                fields = listOf(
                    FieldSchema.Number("value", "Number", true),
                    FieldSchema.Number("min", "Minimum", true),
                    FieldSchema.Number("max", "Maximum", true),
                    FieldSchema.Variable("resultVariable", "Store result in variable", true),
                ),
                keywords = setOf("math", "clamp", "range", "limit"),
            )
        ) { feature, context ->
            val value = feature.number("value")
            val min = feature.number("min")
            val max = feature.number("max")
            if (max < min) {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.maximum_below_minimum"))
            }
            context.storeNumber(feature.destination(), value.coerceIn(min, max))
        },
    )
}
