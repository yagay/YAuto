package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue

/** Reusable time/date transformations without Android-specific dependencies. */
class TimeUtilityFeaturePack : FeaturePack {
    override val id: String = "standard.time.utility"

    override fun install(registry: FeatureRegistry) {
        registerNow(registry)
        registerFormat(registry)
        registerParse(registry)
        registerAdd(registry)
        registerDifference(registry)
        registerFields(registry)
        registerWaitUntil(registry)
        registerTimestampCompare(registry)
    }

    private fun registerNow(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("time.now"), FeatureKind.ACTION,
                "Get current time", "Store the current Unix epoch time in milliseconds",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store timestamp in variable", true)),
                keywords = setOf("time", "now", "timestamp", "epoch"), ownerPackId = id,
            )
        ) { feature, ctx ->
            storeNumber(feature.config.string("resultVariable"), System.currentTimeMillis().toDouble(), ctx)
        }
    }

    private fun registerFormat(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("time.format"), FeatureKind.ACTION,
                "Format timestamp", "Format an epoch-millisecond timestamp using a date-time pattern and time zone",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Timestamp (ms)", true),
                    FieldSchema.Text("pattern", "Date-time pattern", true),
                    FieldSchema.Text("zone", "Time zone (blank = system)"),
                    FieldSchema.Variable("resultVariable", "Store formatted text in variable", true),
                ), keywords = setOf("time", "date", "format", "timestamp", "timezone"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val timestamp = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val formatter = formatter(feature.config.string("pattern"), feature.config.string("zone"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_format"))
            val output = runCatching { formatter.format(Instant.ofEpochMilli(timestamp)) }.getOrNull()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_format"))
            storeText(feature.config.string("resultVariable"), output, ctx)
        }
    }

    private fun registerParse(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("time.parse"), FeatureKind.ACTION,
                "Parse date and time", "Parse text with a date-time pattern and time zone into epoch milliseconds",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Text("text", "Date-time text", true),
                    FieldSchema.Text("pattern", "Date-time pattern", true),
                    FieldSchema.Text("zone", "Time zone (blank = system)"),
                    FieldSchema.Variable("resultVariable", "Store timestamp in variable", true),
                ), keywords = setOf("time", "date", "parse", "timestamp", "timezone"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val zone = zone(feature.config.string("zone"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_zone"))
            val pattern = feature.config.string("pattern")
            val value = runCatching {
                val f = DateTimeFormatter.ofPattern(pattern)
                ZonedDateTime.parse(feature.config.string("text"), f.withZone(zone)).toInstant().toEpochMilli()
            }.recoverCatching {
                val f = DateTimeFormatter.ofPattern(pattern)
                java.time.LocalDateTime.parse(feature.config.string("text"), f).atZone(zone).toInstant().toEpochMilli()
            }.getOrNull() ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_parse_failed"))
            storeNumber(feature.config.string("resultVariable"), value.toDouble(), ctx)
        }
    }

    private fun registerAdd(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("time.add"), FeatureKind.ACTION,
                "Add to timestamp", "Add or subtract a duration unit from an epoch-millisecond timestamp",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Timestamp (ms)", true),
                    FieldSchema.Number("amount", "Amount", true),
                    FieldSchema.Choice("unit", "Unit", true, listOf("milliseconds", "seconds", "minutes", "hours", "days", "weeks")),
                    FieldSchema.Variable("resultVariable", "Store timestamp in variable", true),
                ), keywords = setOf("time", "add", "subtract", "duration", "timestamp"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val base = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val amount = feature.config["amount"].numberOrNull()?.toLong() ?: 0L
            val millis = durationToMillis(amount, feature.config.string("unit"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_unit"))
            val value = runCatching { Math.addExact(base, millis) }.getOrNull()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_overflow"))
            storeNumber(feature.config.string("resultVariable"), value.toDouble(), ctx)
        }
    }

    private fun registerDifference(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("time.difference"), FeatureKind.ACTION,
                "Timestamp difference", "Calculate the signed difference between two epoch-millisecond timestamps",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Number("fromMs", "From timestamp (ms)", true),
                    FieldSchema.Number("toMs", "To timestamp (ms)", true),
                    FieldSchema.Choice("unit", "Result unit", true, listOf("milliseconds", "seconds", "minutes", "hours", "days")),
                    FieldSchema.Variable("resultVariable", "Store difference in variable", true),
                ), keywords = setOf("time", "difference", "duration", "elapsed"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val from = feature.config["fromMs"].numberOrNull()?.toLong()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val to = feature.config["toMs"].numberOrNull()?.toLong()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val divisor = unitDivisor(feature.config.string("unit"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_unit"))
            storeNumber(feature.config.string("resultVariable"), (to - from).toDouble() / divisor, ctx)
        }
    }

    private fun registerFields(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("time.fields"), FeatureKind.ACTION,
                "Get date-time fields", "Convert a timestamp to local date/time fields in a selected time zone",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Timestamp (ms)", true),
                    FieldSchema.Text("zone", "Time zone (blank = system)"),
                    FieldSchema.Variable("resultVariable", "Store date-time object", true),
                ), keywords = setOf("time", "date", "year", "month", "weekday", "fields"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val timestamp = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val zone = zone(feature.config.string("zone"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_zone"))
            val dt = Instant.ofEpochMilli(timestamp).atZone(zone)
            val output = ConfigValue.ObjectValue(mapOf(
                "year" to ConfigValue.NumberValue(dt.year.toDouble()),
                "month" to ConfigValue.NumberValue(dt.monthValue.toDouble()),
                "day" to ConfigValue.NumberValue(dt.dayOfMonth.toDouble()),
                "weekday" to ConfigValue.NumberValue(dt.dayOfWeek.value.toDouble()),
                "hour" to ConfigValue.NumberValue(dt.hour.toDouble()),
                "minute" to ConfigValue.NumberValue(dt.minute.toDouble()),
                "second" to ConfigValue.NumberValue(dt.second.toDouble()),
                "dayOfYear" to ConfigValue.NumberValue(dt.dayOfYear.toDouble()),
                "zone" to ConfigValue.StringValue(zone.id),
            ))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerWaitUntil(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("time.wait_until"), FeatureKind.ACTION,
                "Wait until timestamp", "Pause the flow until the target epoch-millisecond time, with a safety limit",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Number("timestampMs", "Target timestamp (ms)", true),
                    FieldSchema.Duration("maxWaitMs", "Maximum wait"),
                ), keywords = setOf("time", "wait", "until", "delay", "schedule"), ownerPackId = id,
            )
        ) { feature, _ ->
            val target = feature.config["timestampMs"].numberOrNull()?.toLong()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.time_invalid_timestamp"))
            val maxWait = (feature.config["maxWaitMs"].numberOrNull()?.toLong() ?: 3_600_000L).coerceIn(0L, 86_400_000L)
            val remaining = (target - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining > maxWait) return@registerAction ActionExecutionResult(false, message = userText("feature.time_wait_exceeds_limit"))
            if (remaining > 0L) delay(remaining)
            ActionExecutionResult(true)
        }
    }

    private fun registerTimestampCompare(registry: FeatureRegistry) {
        val fields = listOf(
            FieldSchema.Number("leftMs", "Left timestamp (ms)", true),
            FieldSchema.Choice("operator", "Comparison", true, listOf("==", "!=", ">", ">=", "<", "<=")),
            FieldSchema.Number("rightMs", "Right timestamp (ms)", true),
        )
        registry.registerState(
            FeatureDescriptor(
                FeatureId("time.state.timestamp_compare"), FeatureKind.STATE,
                "Compare timestamps", "Compare two epoch-millisecond timestamps",
                FeatureCategory.SYSTEM, fields = fields, keywords = setOf("time", "timestamp", "compare"), ownerPackId = id,
            )
        ) { feature, _ -> compare(feature) }
        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("time.condition.timestamp_compare"), FeatureKind.CONDITION,
                "Compare timestamps", "Compare two epoch-millisecond timestamps",
                FeatureCategory.SYSTEM, fields = fields, keywords = setOf("time", "timestamp", "compare"), ownerPackId = id,
            )
        ) { feature, _ -> compare(feature) }
    }

    private fun compare(feature: com.yagay.yauto.core.model.FeatureRef): Boolean {
        val left = feature.config["leftMs"].numberOrNull() ?: return false
        val right = feature.config["rightMs"].numberOrNull() ?: return false
        return when (feature.config.string("operator", "==")) {
            "==" -> left == right
            "!=" -> left != right
            ">" -> left > right
            ">=" -> left >= right
            "<" -> left < right
            "<=" -> left <= right
            else -> false
        }
    }

    private fun formatter(pattern: String, zone: String): DateTimeFormatter? = runCatching {
        DateTimeFormatter.ofPattern(pattern).withZone(zone(zone) ?: error("invalid zone"))
    }.getOrNull()

    private fun zone(raw: String): ZoneId? = runCatching {
        if (raw.isBlank()) ZoneId.systemDefault() else ZoneId.of(raw.trim())
    }.getOrNull()

    private fun storeNumber(name: String, value: Double, ctx: FeatureExecutionContext): ActionExecutionResult {
        val output = ConfigValue.NumberValue(value)
        ctx.variables.set(name, output)
        return ActionExecutionResult(true, output)
    }

    private fun storeText(name: String, value: String, ctx: FeatureExecutionContext): ActionExecutionResult {
        val output = ConfigValue.StringValue(value)
        ctx.variables.set(name, output)
        return ActionExecutionResult(true, output)
    }
}

internal fun durationToMillis(amount: Long, unit: String): Long? = runCatching {
    when (unit) {
        "milliseconds" -> amount
        "seconds" -> Math.multiplyExact(amount, 1_000L)
        "minutes" -> Math.multiplyExact(amount, 60_000L)
        "hours" -> Math.multiplyExact(amount, 3_600_000L)
        "days" -> Math.multiplyExact(amount, 86_400_000L)
        "weeks" -> Math.multiplyExact(amount, 604_800_000L)
        else -> return null
    }
}.getOrNull()

internal fun unitDivisor(unit: String): Double? = when (unit) {
    "milliseconds" -> 1.0
    "seconds" -> 1_000.0
    "minutes" -> 60_000.0
    "hours" -> 3_600_000.0
    "days" -> 86_400_000.0
    else -> null
}
