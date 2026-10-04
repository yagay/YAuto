package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.absoluteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConfiguredShortXTimeEventSource(
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.shortx.time"
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private val emittedKeys = ConcurrentHashMap<String, String>()

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        job = scope.launch {
            while (isActive && started.get()) {
                val events = runCatching {
                    workspace.load().automations.asSequence()
                        .filter { it.enabled }
                        .flatMap { it.activation.events.asSequence() }
                        .filter {
                            it.typeId == "android.event.alarm_time" ||
                                it.typeId == "android.event.fixed_in_period" ||
                                it.typeId == "android.event.random_in_period"
                        }
                        .toList()
                }.getOrDefault(emptyList())
                val now = ZonedDateTime.now()
                events.forEach { feature ->
                    when (feature.typeId) {
                        "android.event.alarm_time" -> evaluateAlarm(feature, now)
                        "android.event.fixed_in_period" -> evaluateFixed(feature, now)
                        "android.event.random_in_period" -> evaluateRandom(feature, now)
                    }
                }
                val activeKeys = events.map(::eventKey).toSet()
                emittedKeys.keys.removeIf { it !in activeKeys }
                delay(1_000L)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        job?.cancel()
        job = null
        emitter = null
        emittedKeys.clear()
        scope.cancel()
    }

    private fun evaluateAlarm(feature: FeatureRef, now: ZonedDateTime) {
        if (!dayMatches(feature.config.string("days"), now.dayOfWeek)) return
        val target = parseTime(feature.config.string("time")) ?: return
        if (now.hour != target.hour || now.minute != target.minute || now.second !in target.second..(target.second + 1).coerceAtMost(59)) return
        emitOnce(feature, now.toLocalDate().toString() + ":" + target, mapOf(
            "time" to ConfigValue.StringValue(target.toString()),
            "date" to ConfigValue.StringValue(now.toLocalDate().toString()),
        ))
    }

    private fun evaluateFixed(feature: FeatureRef, now: ZonedDateTime) {
        if (!dayMatches(feature.config.string("days"), now.dayOfWeek)) return
        val start = parseTime(feature.config.string("start")) ?: return
        val end = parseTime(feature.config.string("end")) ?: return
        val intervalMs = feature.config.long("intervalMs", 60_000L).coerceIn(1_000L, 86_400_000L)
        val local = now.toLocalTime()
        if (!inWindow(local, start, end)) return

        val elapsedMs = elapsedSinceWindowStart(local, start, end)
        val slot = elapsedMs / intervalMs
        val slotStartMs = slot * intervalMs
        if (elapsedMs - slotStartMs > 1_500L) return
        emitOnce(feature, now.toLocalDate().toString() + ":" + slot, mapOf(
            "slot" to ConfigValue.NumberValue(slot.toDouble()),
            "elapsedMs" to ConfigValue.NumberValue(elapsedMs.toDouble()),
        ))
    }

    private fun evaluateRandom(feature: FeatureRef, now: ZonedDateTime) {
        if (!dayMatches(feature.config.string("days"), now.dayOfWeek)) return
        val start = parseTime(feature.config.string("start")) ?: return
        val end = parseTime(feature.config.string("end")) ?: return
        val date = now.toLocalDate()
        val windowMs = windowDurationMs(start, end)
        if (windowMs <= 0L) return
        val hash = (eventKey(feature) + ":" + date).hashCode().toLong().absoluteValue
        val offsetMs = hash % windowMs
        val target = addMillis(start, offsetMs)
        val local = now.toLocalTime()
        if (!inWindow(local, target, addMillis(target, 2_000L))) return
        emitOnce(feature, date.toString(), mapOf(
            "targetTime" to ConfigValue.StringValue(target.toString()),
            "date" to ConfigValue.StringValue(date.toString()),
        ))
    }

    private fun emitOnce(feature: FeatureRef, marker: String, payload: Map<String, ConfigValue>) {
        val key = eventKey(feature)
        if (emittedKeys.put(key, marker) == marker) return
        emitter?.emit(RuntimeEvent(feature.typeId, payload, source = id))
    }

    private fun eventKey(feature: FeatureRef): String =
        feature.typeId + "|" + feature.config.entries.sortedBy { it.key }.joinToString(";") { it.key + "=" + it.value }

    private fun parseTime(raw: String): LocalTime? = runCatching {
        when (raw.trim().count { it == ':' }) {
            1 -> LocalTime.parse(raw.trim() + ":00")
            2 -> LocalTime.parse(raw.trim())
            else -> null
        }
    }.getOrNull()

    private fun dayMatches(raw: String, day: DayOfWeek): Boolean {
        if (raw.isBlank() || raw.equals("any", true)) return true
        val names = raw.split(',', ';', '|', ' ').map(String::trim).filter(String::isNotEmpty).map(String::uppercase).toSet()
        val aliases = setOf(day.name, day.name.take(3), day.value.toString())
        return names.any { it in aliases }
    }

    private fun inWindow(value: LocalTime, start: LocalTime, end: LocalTime): Boolean =
        if (end >= start) !value.isBefore(start) && value.isBefore(end)
        else !value.isBefore(start) || value.isBefore(end)

    private fun elapsedSinceWindowStart(value: LocalTime, start: LocalTime, end: LocalTime): Long {
        val base = Duration.between(start, value).toMillis()
        return if (base >= 0L) base else base + 86_400_000L
    }

    private fun windowDurationMs(start: LocalTime, end: LocalTime): Long {
        val direct = Duration.between(start, end).toMillis()
        return if (direct > 0L) direct else direct + 86_400_000L
    }

    private fun addMillis(time: LocalTime, millis: Long): LocalTime =
        time.plusNanos((millis % 86_400_000L) * 1_000_000L)
}
