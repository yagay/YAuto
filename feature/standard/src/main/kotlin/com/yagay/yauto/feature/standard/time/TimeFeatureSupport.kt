package com.yagay.yauto.feature.standard.time

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun timeDescriptor(
    id: String,
    kind: FeatureKind,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    keywords: Set<String>,
    behaviors: Map<String, FieldBehavior> = emptyMap(),
): FeatureDescriptor = FeatureDescriptor(
    id = FeatureId(id),
    kind = kind,
    title = title,
    description = description,
    category = FeatureCategory.SYSTEM,
    fields = fields,
    keywords = keywords,
    fieldBehaviors = behaviors,
)

internal fun FeatureExecutionContext.storeTimeNumber(name: String, value: Double): ActionExecutionResult {
    val output = ConfigValue.NumberValue(value)
    variables.set(name, output)
    return ActionExecutionResult(true, output)
}

internal fun FeatureExecutionContext.storeTimeText(name: String, value: String): ActionExecutionResult {
    val output = ConfigValue.StringValue(value)
    variables.set(name, output)
    return ActionExecutionResult(true, output)
}

internal fun timeZone(raw: String): ZoneId? = runCatching {
    if (raw.isBlank()) ZoneId.systemDefault() else ZoneId.of(raw.trim())
}.getOrNull()

internal fun timeFormatter(pattern: String, zone: String): DateTimeFormatter? = runCatching {
    DateTimeFormatter.ofPattern(pattern).withZone(timeZone(zone) ?: error("invalid zone"))
}.getOrNull()

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
