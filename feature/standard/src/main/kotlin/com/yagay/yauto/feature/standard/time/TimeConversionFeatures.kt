package com.yagay.yauto.feature.standard.time

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object TimeConversionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            timeDescriptor(
                "time.now",
                FeatureKind.ACTION,
                "Get current time",
                "Store the current Unix epoch time in milliseconds",
                fields = listOf(FieldSchema.Variable("resultVariable", "Store timestamp in variable", true)),
                keywords = setOf("time", "now", "timestamp", "epoch"),
            )
        ) { feature, context ->
            context.storeTimeNumber(feature.config.string("resultVariable"), System.currentTimeMillis().toDouble())
        },
        actionFeature(
            timeDescriptor(
                "time.format",
                FeatureKind.ACTION,
                "Format timestamp",
                "Format an epoch-millisecond timestamp using a date-time pattern and time zone",
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Timestamp (ms)", true),
                    FieldSchema.Text("pattern", "Date-time pattern", true),
                    FieldSchema.Text("zone", "Time zone (blank = system)"),
                    FieldSchema.Variable("resultVariable", "Store formatted text in variable", true),
                ),
                keywords = setOf("time", "date", "format", "timestamp", "timezone"),
            )
        ) { feature, context ->
            val timestamp = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val formatter = timeFormatter(feature.config.string("pattern"), feature.config.string("zone"))
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_format"))
            val output = runCatching { formatter.format(Instant.ofEpochMilli(timestamp)) }.getOrNull()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_format"))
            context.storeTimeText(feature.config.string("resultVariable"), output)
        },
        actionFeature(
            timeDescriptor(
                "time.parse",
                FeatureKind.ACTION,
                "Parse date and time",
                "Parse text with a date-time pattern and time zone into epoch milliseconds",
                fields = listOf(
                    FieldSchema.Text("text", "Date-time text", true),
                    FieldSchema.Text("pattern", "Date-time pattern", true),
                    FieldSchema.Text("zone", "Time zone (blank = system)"),
                    FieldSchema.Variable("resultVariable", "Store timestamp in variable", true),
                ),
                keywords = setOf("time", "date", "parse", "timestamp", "timezone"),
            )
        ) { feature, context ->
            val zone = timeZone(feature.config.string("zone"))
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_zone"))
            val pattern = feature.config.string("pattern")
            val text = feature.config.string("text")
            val value = runCatching {
                val formatter = DateTimeFormatter.ofPattern(pattern)
                ZonedDateTime.parse(text, formatter.withZone(zone)).toInstant().toEpochMilli()
            }.recoverCatching {
                val formatter = DateTimeFormatter.ofPattern(pattern)
                LocalDateTime.parse(text, formatter).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_parse_failed"))
            context.storeTimeNumber(feature.config.string("resultVariable"), value.toDouble())
        },
        actionFeature(
            timeDescriptor(
                "time.fields",
                FeatureKind.ACTION,
                "Get date-time fields",
                "Convert a timestamp to local date/time fields in a selected time zone",
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Timestamp (ms)", true),
                    FieldSchema.Text("zone", "Time zone (blank = system)"),
                    FieldSchema.Variable("resultVariable", "Store date-time object", true),
                ),
                keywords = setOf("time", "date", "year", "month", "weekday", "fields"),
            )
        ) { feature, context ->
            val timestamp = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val zone = timeZone(feature.config.string("zone"))
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.time_invalid_zone"))
            val dateTime = Instant.ofEpochMilli(timestamp).atZone(zone)
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "year" to ConfigValue.NumberValue(dateTime.year.toDouble()),
                    "month" to ConfigValue.NumberValue(dateTime.monthValue.toDouble()),
                    "day" to ConfigValue.NumberValue(dateTime.dayOfMonth.toDouble()),
                    "weekday" to ConfigValue.NumberValue(dateTime.dayOfWeek.value.toDouble()),
                    "hour" to ConfigValue.NumberValue(dateTime.hour.toDouble()),
                    "minute" to ConfigValue.NumberValue(dateTime.minute.toDouble()),
                    "second" to ConfigValue.NumberValue(dateTime.second.toDouble()),
                    "dayOfYear" to ConfigValue.NumberValue(dateTime.dayOfYear.toDouble()),
                    "zone" to ConfigValue.StringValue(zone.id),
                ),
            )
            context.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        },
    )
}
