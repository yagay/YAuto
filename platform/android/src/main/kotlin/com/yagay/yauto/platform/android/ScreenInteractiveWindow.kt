package com.yagay.yauto.platform.android

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager

/**
 * Windowed screen-interaction usage. Unlike uptime, this counts observed on/off transitions.
 * Return unknown rather than assuming a complete history on OEM devices.
 */
internal fun readScreenOnTimeWindow(usage: UsageStatsManager, startMs: Long, endMs: Long): Long? {
    if (endMs <= startMs) return null
    val stream = usage.queryEvents(startMs, endMs)
    val event = UsageEvents.Event()
    val records = mutableListOf<ScreenInteractionTransition>()
    while (stream.hasNextEvent()) {
        stream.getNextEvent(event)
        when (event.eventType) {
            UsageEvents.Event.SCREEN_INTERACTIVE ->
                records.add(ScreenInteractionTransition(event.timeStamp, true))
            UsageEvents.Event.SCREEN_NON_INTERACTIVE ->
                records.add(ScreenInteractionTransition(event.timeStamp, false))
        }
    }
    return calculateScreenOnTimeMs(startMs, endMs, records)
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
