package com.yagay.yauto.feature.standard.data

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.random.Random

internal object DataRandomFeatures {
    val standardDefinitions: List<FeatureDefinition> = listOf(
        actionFeature(
            dataDescriptor(
                "data.random.number",
                "Random number",
                "Generate an inclusive random integer",
                fields = listOf(
                    FieldSchema.Number("min", "Minimum", true),
                    FieldSchema.Number("max", "Maximum", true),
                    FieldSchema.Variable("resultVariable", "Store number in variable", true),
                ),
                keywords = setOf("random", "number"),
            )
        ) { feature, context ->
            val min = feature.number("min").toLong()
            val max = feature.number("max").toLong()
            if (max < min) {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.maximum_below_minimum"))
            }
            val number = when {
                max == min -> min
                max == Long.MAX_VALUE -> Random.nextLong(min, max)
                else -> Random.nextLong(min, max + 1)
            }
            context.storeNumber(feature.destination(), number.toDouble())
        },
        actionFeature(
            dataDescriptor(
                "data.uuid",
                "Generate UUID",
                "Generate a random UUID string",
                fields = listOf(FieldSchema.Variable("resultVariable", "Store UUID in variable", true)),
                keywords = setOf("uuid", "random", "id"),
            )
        ) { feature, context ->
            context.storeText(feature.destination(), UUID.randomUUID().toString())
        },
    )

    val utilityDefinitions: List<FeatureDefinition> = listOf(
        actionFeature(
            dataDescriptor(
                "data.random.decimal",
                "Random decimal",
                "Generate a random decimal number between an inclusive minimum and maximum",
                fields = listOf(
                    FieldSchema.Number("min", "Minimum", true),
                    FieldSchema.Number("max", "Maximum", true),
                    FieldSchema.Number("decimals", "Decimal places", min = 0.0, max = 12.0),
                    FieldSchema.Variable("resultVariable", "Store number in variable", true),
                ),
                keywords = setOf("random", "decimal", "number"),
                behaviors = mapOf("decimals" to FieldBehavior(defaultValue = ConfigValue.NumberValue(2.0))),
            )
        ) { feature, context ->
            val min = feature.number("min")
            val max = feature.number("max", 1.0)
            if (max < min) {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.maximum_below_minimum"))
            }
            val decimals = feature.number("decimals", 2.0).toInt().coerceIn(0, 12)
            val raw = if (max == min) min else Random.nextDouble(min, max)
            val value = BigDecimal.valueOf(raw)
                .setScale(decimals, RoundingMode.HALF_UP)
                .toDouble()
                .coerceIn(min, max)
            context.storeNumber(feature.destination(), value)
        },
    )
}
