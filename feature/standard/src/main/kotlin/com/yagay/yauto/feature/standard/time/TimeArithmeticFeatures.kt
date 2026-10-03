package com.yagay.yauto.feature.standard.time

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature

internal object TimeArithmeticFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            timeDescriptor(
                "time.add",
                FeatureKind.ACTION,
                "Add to timestamp",
                "Add or subtract a duration unit from an epoch-millisecond timestamp",
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Timestamp (ms)", true),
                    FieldSchema.Number("amount", "Amount", true),
                    FieldSchema.Choice(
                        "unit",
                        "Unit",
                        true,
                        listOf("milliseconds", "seconds", "minutes", "hours", "days", "weeks"),
                    ),
                    FieldSchema.Variable("resultVariable", "Store timestamp in variable", true),
                ),
                keywords = setOf("time", "add", "subtract", "duration", "timestamp"),
                behaviors = mapOf(
                    "unit" to FieldBehavior(defaultValue = ConfigValue.StringValue("milliseconds")),
                ),
            )
        ) { feature, context ->
            val base = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val amount = feature.config["amount"].numberOrNull()?.toLong() ?: 0L
            val millis = durationToMillis(amount, feature.config.string("unit"))
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_unit"))
            val value = runCatching { Math.addExact(base, millis) }.getOrNull()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_overflow"))
            context.storeTimeNumber(feature.config.string("resultVariable"), value.toDouble())
        },
        actionFeature(
            timeDescriptor(
                "time.difference",
                FeatureKind.ACTION,
                "Timestamp difference",
                "Calculate the signed difference between two epoch-millisecond timestamps",
                fields = listOf(
                    FieldSchema.Number("fromMs", "From timestamp (ms)", true),
                    FieldSchema.Number("toMs", "To timestamp (ms)", true),
                    FieldSchema.Choice(
                        "unit",
                        "Result unit",
                        true,
                        listOf("milliseconds", "seconds", "minutes", "hours", "days"),
                    ),
                    FieldSchema.Variable("resultVariable", "Store difference in variable", true),
                ),
                keywords = setOf("time", "difference", "duration", "elapsed"),
                behaviors = mapOf(
                    "unit" to FieldBehavior(defaultValue = ConfigValue.StringValue("milliseconds")),
                ),
            )
        ) { feature, context ->
            val from = feature.config["fromMs"].numberOrNull()?.toLong()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val to = feature.config["toMs"].numberOrNull()?.toLong()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val divisor = unitDivisor(feature.config.string("unit"))
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_unit"))
            context.storeTimeNumber(feature.config.string("resultVariable"), (to - from).toDouble() / divisor)
        },
    )
}
