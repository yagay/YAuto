package com.yagay.yauto.platform.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Read-only screen-interactive time using Android UsageStats events.
 * This is not CPU uptime or the configured screen-off timeout.
 *
 * Screen transitions can be incomplete on OEM builds or when history has been pruned.
 * In that case the feature deliberately fails instead of inventing a duration.
 */
class AndroidScreenOnTimeFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.screen_on_time"
    private val context = context.applicationContext
    private val usage = this.context.getSystemService(UsageStatsManager::class.java)

    override fun install(registry: FeatureRegistry) {
        val timeFields = listOf(
            FieldSchema.Choice("window", "Measurement window", true, listOf("today", "last_24_hours")),
        )
        val requirements = setOf(AccessRequirement.USAGE_STATS)
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.screen_on_time.get"), FeatureKind.ACTION,
                "Get screen-on time",
                "Read the approximate amount of time the screen was interactive today or in the last 24 hours",
                FeatureCategory.DEVICE,
                fields = timeFields + FieldSchema.Variable("resultVariable", "Store duration in milliseconds", true),
                accessRequirements = requirements,
                keywords = setOf("shortx", "screen on time", "screen usage", "screen interactive", "usage stats"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val variable = feature.config.string("resultVariable").trim()
            if (variable.isEmpty()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
            }
            if (!isUsageStatsAccessGranted(context)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.screen_on_time_usage_required"))
            }
            val duration = withContext(Dispatchers.IO) { readScreenTime(feature.config.string("window", "today")) }
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.screen_on_time_data_unavailable"))
            val value = ConfigValue.NumberValue(duration.toDouble())
            ctx.variables.set(variable, value)
            ActionExecutionResult(true, value)
        }

        val comparisonFields = timeFields + listOf(
            FieldSchema.Number("minMinutes", "Minimum screen-on minutes", min = 0.0, max = 1440.0),
            FieldSchema.Number("maxMinutes", "Maximum screen-on minutes", min = 0.0, max = 1440.0),
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            if (!isUsageStatsAccessGranted(context)) return@ConditionEvaluator false
            val duration = withContext(Dispatchers.IO) { readScreenTime(feature.config.string("window", "today")) }
                ?: return@ConditionEvaluator false
            val minimum = feature.config["minMinutes"].numberOrNull() ?: 0.0
            val maximum = feature.config["maxMinutes"].numberOrNull() ?: 1440.0
            minimum.isFinite() && maximum.isFinite() &&
                minimum >= 0.0 && maximum <= 1440.0 && minimum <= maximum &&
                duration.toDouble() / 60_000.0 in minimum..maximum
        }
        val descriptor = FeatureDescriptor(
            FeatureId("android.state.screen_on_time"), FeatureKind.STATE,
            "Screen-on time range",
            "Check whether the measured interactive screen time lies inside a configured minute range",
            FeatureCategory.DEVICE,
            fields = comparisonFields,
            accessRequirements = requirements,
            keywords = setOf("shortx", "macrodroid", "screen on time", "screen usage", "duration"),
            ownerPackId = id,
        )
        registry.registerState(descriptor, evaluator)
        registry.registerCondition(
            descriptor.copy(id = FeatureId("android.condition.screen_on_time"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun readScreenTime(window: String): Long? = runCatching {
        val end = System.currentTimeMillis()
        val start = when (window) {
            "today" -> LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            "last_24_hours" -> end - 86_400_000L
            else -> return null
        }
        val records = mutableListOf<ScreenInteractionTransition>()
        val stream = usage.queryEvents(start, end)
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.SCREEN_INTERACTIVE ->
                    records.add(ScreenInteractionTransition(event.timeStamp, true))
                UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                    records.add(ScreenInteractionTransition(event.timeStamp, false))
            }
        }
        calculateScreenOnTimeMs(start, end, records)
    }.getOrNull()
}

/** An observed transition, not a guessed screen state. */
internal data class ScreenInteractionTransition(val epochMs: Long, val interactive: Boolean)

/**
 * Only infer the initial state when a first transition is present.
 * The first SCREEN_NON_INTERACTIVE implies the display was interactive before it;
 * the first SCREEN_INTERACTIVE implies it was non-interactive before it.
 * If the event history is empty, the caller must treat the result as unknown.
 */
internal fun calculateScreenOnTimeMs(
    startMs: Long,
    endMs: Long,
    transitions: List<ScreenInteractionTransition>,
): Long? {
    if (endMs <= startMs) return null
    val ordered = transitions.filter { it.epochMs in startMs..endMs }
        .sortedBy { it.epochMs }
    if (ordered.isEmpty()) return null
    var screenOn = !ordered.first().interactive
    var previous = startMs
    var duration = 0L
    for (transition in ordered) {
        if (screenOn) duration += transition.epochMs - previous
        screenOn = transition.interactive
        previous = transition.epochMs
    }
    if (screenOn) duration += endMs - previous
    return duration.coerceIn(0L, endMs - startMs)
}
