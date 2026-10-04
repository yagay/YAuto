package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

class AndroidShortXTimeFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.shortx.time_features"
    private val calendar = ChinaHolidayCalendar(context.applicationContext)

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.alarm_time"), FeatureKind.EVENT,
                "Alarm time",
                "Run at a selected local wall-clock time on selected weekdays",
                FeatureCategory.CORE,
                fields = listOf(
                    FieldSchema.Text("time", "Time HH:mm[:ss]", true),
                    FieldSchema.Text("days", "Days (MON,TUE… or blank for all)"),
                ),
                keywords = setOf("alarm", "time", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.alarm_time" &&
                ctx.event.payload.string("ruleKey") == shortXTimeRuleKey(feature)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.fixed_in_period"), FeatureKind.EVENT,
                "Fixed interval in time period",
                "Run repeatedly at a fixed interval while local time is inside a selected period",
                FeatureCategory.CORE,
                fields = listOf(
                    FieldSchema.Text("start", "Start HH:mm[:ss]", true),
                    FieldSchema.Text("end", "End HH:mm[:ss]", true),
                    FieldSchema.Duration("intervalMs", "Interval", true),
                    FieldSchema.Text("days", "Days (MON,TUE… or blank for all)"),
                ),
                keywords = setOf("fixed interval", "period", "schedule", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.fixed_in_period" &&
                ctx.event.payload.string("ruleKey") == shortXTimeRuleKey(feature)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.random_in_period"), FeatureKind.EVENT,
                "Random time in period",
                "Run once per matching day at a deterministic random local time inside a selected period",
                FeatureCategory.CORE,
                fields = listOf(
                    FieldSchema.Text("start", "Start HH:mm[:ss]", true),
                    FieldSchema.Text("end", "End HH:mm[:ss]", true),
                    FieldSchema.Text("days", "Days (MON,TUE… or blank for all)"),
                ),
                keywords = setOf("random time", "period", "schedule", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.random_in_period" &&
                ctx.event.payload.string("ruleKey") == shortXTimeRuleKey(feature)
        }

        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("android.condition.calendar_day_type"), FeatureKind.CONDITION,
                "Calendar day type",
                "Match any day, workday, holiday, weekend or China transfer-workday",
                FeatureCategory.CORE,
                fields = listOf(
                    FieldSchema.Choice(
                        "dayType", "Day type", true,
                        listOf("any", "workday", "holiday", "weekend", "transfer_workday"),
                    ),
                    FieldSchema.Text("date", "Date yyyy-MM-dd (blank = today)"),
                ),
                fieldBehaviors = mapOf("date" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("calendar day", "holiday", "workday", "transfer workday", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val raw = feature.config.string("date").resolveVariables(ctx.variables).trim()
            val date = if (raw.isBlank()) LocalDate.now()
            else runCatching { LocalDate.parse(raw) }.getOrNull() ?: return@registerCondition false
            when (feature.config.string("dayType", "any")) {
                "any" -> true
                "weekend" -> date.dayOfWeek in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                "transfer_workday" -> calendar.classify(date) == ChinaHolidayCalendar.DayType.TRANSFER_WORKDAY
                "holiday" -> calendar.classify(date) == ChinaHolidayCalendar.DayType.HOLIDAY
                "workday" -> calendar.classify(date) == ChinaHolidayCalendar.DayType.WORKDAY
                else -> false
            }
        }

        registry.registerCondition(
            FeatureDescriptor(
                FeatureId("core.condition.delay"), FeatureKind.CONDITION,
                "Delay condition",
                "Wait for the configured duration, then allow the condition chain to continue",
                FeatureCategory.CORE,
                fields = listOf(FieldSchema.Duration("durationMs", "Delay", true)),
                keywords = setOf("delay", "condition delay", "shortx"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val duration = (feature.config["durationMs"].numberOrNull() ?: 0.0).toLong()
                .coerceIn(0L, 86_400_000L)
            delay(duration)
            true
        }
    }
}

private class ChinaHolidayCalendar(private val context: Context) {
    enum class DayType { WORKDAY, HOLIDAY, TRANSFER_WORKDAY }

    private val cacheFile = File(context.cacheDir, "shortx-holidayAPI.json")
    private val prefs = context.getSharedPreferences("shortx_holiday_calendar", Context.MODE_PRIVATE)

    suspend fun classify(date: LocalDate): DayType = withContext(Dispatchers.IO) {
        val root = loadCalendar()
        if (root != null) {
            val years = root.optJSONObject("Years")
            val entries = years?.optJSONArray(date.year.toString())
            if (entries != null) {
                for (index in 0 until entries.length()) {
                    val item = entries.optJSONObject(index) ?: continue
                    val comp = item.optJSONArray("CompDays")
                    if (comp != null) {
                        for (i in 0 until comp.length()) {
                            if (comp.optString(i) == date.toString()) return@withContext DayType.TRANSFER_WORKDAY
                        }
                    }
                    val start = runCatching { LocalDate.parse(item.optString("StartDate")) }.getOrNull()
                    val end = runCatching { LocalDate.parse(item.optString("EndDate")) }.getOrNull()
                    if (start != null && end != null && !date.isBefore(start) && !date.isAfter(end)) {
                        return@withContext DayType.HOLIDAY
                    }
                }
            }
        }
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) DayType.HOLIDAY
        else DayType.WORKDAY
    }

    private fun loadCalendar(): JSONObject? {
        val now = System.currentTimeMillis()
        val lastFetch = prefs.getLong("last_fetch", 0L)
        if (cacheFile.isFile && now - lastFetch < CACHE_MS) {
            runCatching { return JSONObject(cacheFile.readText()) }
        }
        val fetched = fetch()
        if (fetched != null) {
            runCatching {
                cacheFile.parentFile?.mkdirs()
                cacheFile.writeText(fetched)
                prefs.edit().putLong("last_fetch", now).apply()
            }
            return runCatching { JSONObject(fetched) }.getOrNull()
        }
        return if (cacheFile.isFile) runCatching { JSONObject(cacheFile.readText()) }.getOrNull() else null
    }

    private fun fetch(): String? = runCatching {
        val connection = URL(CALENDAR_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", context.packageName)
        try {
            if (connection.responseCode !in 200..299) null
            else connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    companion object {
        private const val CALENDAR_URL =
            "https://raw.githubusercontent.com/lanceliao/china-holiday-calender/master/holidayAPI.json"
        private const val CACHE_MS = 7L * 24L * 60L * 60L * 1_000L
    }
}
