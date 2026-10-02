package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/** Standard Android organizer actions that delegate to the user's installed alarm/calendar apps. */
class AndroidOrganizerFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.organizer"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerAlarm(registry)
        registerTimer(registry)
        registerCalendarEvent(registry)
    }

    private fun registerAlarm(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.alarm.set"), FeatureKind.ACTION,
                "Set alarm", "Create an alarm in the user's alarm clock application",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("hour", "Hour (0-23)", true, min = 0.0, max = 23.0),
                    FieldSchema.Number("minute", "Minute (0-59)", true, min = 0.0, max = 59.0),
                    FieldSchema.Text("label", "Alarm label"),
                    FieldSchema.Toggle("skipUi", "Create without showing alarm app"),
                ),
                keywords = setOf("alarm", "clock", "wake", "time"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val time = validatedAlarmTime(feature.config["hour"].numberOrNull(), feature.config["minute"].numberOrNull())
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.alarm_time_invalid"))
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, time.first)
                putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                putExtra(AlarmClock.EXTRA_MESSAGE, feature.config.string("label").resolveVariables(ctx.variables))
                putExtra(AlarmClock.EXTRA_SKIP_UI, feature.config.boolean("skipUi"))
            }
            startExternalActivity(intent)
        }
    }

    private fun registerTimer(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.timer.set"), FeatureKind.ACTION,
                "Set timer", "Create a countdown timer in the user's clock application",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Number("durationSeconds", "Duration seconds", true, min = 1.0, max = 604800.0),
                    FieldSchema.Text("label", "Timer label"),
                    FieldSchema.Toggle("skipUi", "Create without showing clock app"),
                ),
                keywords = setOf("timer", "countdown", "clock", "delay"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val seconds = validatedTimerSeconds(feature.config["durationSeconds"].numberOrNull())
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.timer_duration_invalid"))
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, feature.config.string("label").resolveVariables(ctx.variables))
                putExtra(AlarmClock.EXTRA_SKIP_UI, feature.config.boolean("skipUi"))
            }
            startExternalActivity(intent)
        }
    }

    private fun registerCalendarEvent(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.calendar.event.add"), FeatureKind.ACTION,
                "Add calendar event", "Open the calendar editor for an event relative to the current time",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Text("title", "Event title", true),
                    FieldSchema.Text("description", "Description", multiline = true),
                    FieldSchema.Text("location", "Location"),
                    FieldSchema.Number("startDelayMinutes", "Start after minutes", min = 0.0, max = 525600.0),
                    FieldSchema.Number("durationMinutes", "Duration minutes", min = 1.0, max = 10080.0),
                ),
                keywords = setOf("calendar", "event", "appointment", "schedule"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val window = calendarWindow(
                nowEpochMs = System.currentTimeMillis(),
                startDelayMinutes = feature.config["startDelayMinutes"].numberOrNull() ?: 0.0,
                durationMinutes = feature.config["durationMinutes"].numberOrNull() ?: 60.0,
            ) ?: return@registerAction ActionExecutionResult(false, message = userText("feature.calendar_window_invalid"))
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, feature.config.string("title").resolveVariables(ctx.variables))
                putExtra(CalendarContract.Events.DESCRIPTION, feature.config.string("description").resolveVariables(ctx.variables))
                putExtra(CalendarContract.Events.EVENT_LOCATION, feature.config.string("location").resolveVariables(ctx.variables))
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, window.first)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, window.second)
            }
            startExternalActivity(intent)
        }
    }

    private fun startExternalActivity(intent: Intent): ActionExecutionResult = runCatching {
        val launch = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch.resolveActivity(context.packageManager) == null) {
            return@runCatching ActionExecutionResult(false, message = userText("feature.no_compatible_app"))
        }
        context.startActivity(launch)
        ActionExecutionResult(true)
    }.getOrElse {
        ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
    }
}

internal fun validatedAlarmTime(hour: Double?, minute: Double?): Pair<Int, Int>? {
    if (hour == null || minute == null || !hour.isFinite() || !minute.isFinite()) return null
    if (hour % 1.0 != 0.0 || minute % 1.0 != 0.0) return null
    val h = hour.toInt()
    val m = minute.toInt()
    return if (h in 0..23 && m in 0..59) h to m else null
}

internal fun validatedTimerSeconds(seconds: Double?): Int? {
    if (seconds == null || !seconds.isFinite() || seconds % 1.0 != 0.0) return null
    val value = seconds.toLong()
    return if (value in 1L..604800L) value.toInt() else null
}

internal fun calendarWindow(nowEpochMs: Long, startDelayMinutes: Double, durationMinutes: Double): Pair<Long, Long>? {
    if (!startDelayMinutes.isFinite() || !durationMinutes.isFinite()) return null
    if (startDelayMinutes !in 0.0..525600.0 || durationMinutes !in 1.0..10080.0) return null
    val startOffset = (startDelayMinutes * 60_000.0).toLong()
    val duration = (durationMinutes * 60_000.0).toLong()
    val start = runCatching { Math.addExact(nowEpochMs, startOffset) }.getOrNull() ?: return null
    val end = runCatching { Math.addExact(start, duration) }.getOrNull() ?: return null
    return start to end
}
