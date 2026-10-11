package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.model.string
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact-date overrides from official UK government / holiday-cn annual releases. */
internal object WorkdayCalendarStore {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("yauto_workday_calendar", Context.MODE_PRIVATE)
    fun status(context: Context, region: String, date: LocalDate): Boolean {
        val current = prefs(context).getString("dates_$region", "{}").orEmpty()
        val overrides = runCatching { JSONObject(current) }.getOrDefault(JSONObject())
        val key = date.toString()
        return if (overrides.has(key)) !overrides.optBoolean(key)
        else date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY
    }
    fun manual(context: Context, region: String, date: LocalDate, off: Boolean) {
        require(region in setOf("UK", "CN"))
        val key = "dates_$region"
        val json = runCatching { JSONObject(prefs(context).getString(key, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        json.put(date.toString(), off)
        check(prefs(context).edit().putString(key, json.toString()).commit())
    }
    fun clear(context: Context, region: String, date: LocalDate) {
        require(region in setOf("UK", "CN"))
        val key = "dates_$region"
        val json = JSONObject(prefs(context).getString(key, "{}"))
        json.remove(date.toString())
        check(prefs(context).edit().putString(key, json.toString()).commit())
    }
    fun count(context: Context, region: String): Int =
        runCatching { JSONObject(prefs(context).getString("dates_$region", "{}")).length() }
            .getOrDefault(0)

    suspend fun refresh(context: Context, region: String, year: Int): Int = withContext(Dispatchers.IO) {
        require(region in setOf("UK", "CN") && year in 2000..2100)
        val url = when (region) {
            "UK" -> "https://www.gov.uk/bank-holidays.json"
            else -> "https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json"
        }
        val conn = URL(url).openConnection() as HttpsURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 12_000
        conn.readTimeout = 15_000
        try {
            require(conn.responseCode == 200) { "Holiday calendar HTTP ${conn.responseCode}" }
            val content = conn.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val length = input.read(chunk)
                    if (length < 0) break
                    require(output.size() + length <= 1_500_000)
                    output.write(chunk, 0, length)
                }
                output.toString("UTF-8")
            }
            val document = JSONObject(content)
            val dates = JSONObject()
            if (region == "UK") {
                // Government data covers all UK regions; union provides a safe
                // broad holiday indicator. Regional-only differences are noted in UI.
                for (scope in listOf("england-and-wales", "scotland", "northern-ireland")) {
                    val events = document.optJSONObject(scope)?.optJSONArray("events") ?: continue
                    for (i in 0 until events.length()) {
                        val date = LocalDate.parse(events.getJSONObject(i).getString("date"))
                        if (date.year == year) dates.put(date.toString(), true)
                    }
                }
            } else {
                val entries = document.getJSONArray("days")
                for (i in 0 until entries.length()) {
                    val day = entries.getJSONObject(i)
                    val date = LocalDate.parse(day.getString("date"))
                    if (date.year == year) dates.put(date.toString(), day.getBoolean("isOffDay"))
                }
            }
            require(dates.length() > 0) { "No holiday data for selected year" }
            val key = "dates_$region"
            val merged = JSONObject(prefs(context).getString(key, "{}"))
            val keys = dates.keys()
            while (keys.hasNext()) {
                val date = keys.next()
                merged.put(date, dates.getBoolean(date))
            }
            check(prefs(context).edit().putString(key, merged.toString()).commit())
            dates.length()
        } finally { conn.disconnect() }
    }
}

internal class ShortXWorkdayFeaturePack(context: Context) : FeaturePack {
    private val context = context.applicationContext
    override val id: String = "android.shortx.workday"
    override fun install(registry: FeatureRegistry) {
        val description = FeatureDescriptor(
            id = FeatureId("android.state.shortx_workday"),
            kind = FeatureKind.STATE,
            title = "Workday / statutory holiday",
            description = "Check whether today is a workday using cached UK or China holiday calendars",
            category = FeatureCategory.TIME,
            fields = listOf(
                FieldSchema.Choice("region", "Calendar", true, listOf("UK", "CN")),
                FieldSchema.Choice("expected", "Day status", true, listOf("workday", "offday")),
            ),
            keywords = setOf("workday", "holiday", "weekend", "calendar", "shortx"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val region = feature.config.string("region", "UK")
            val working = WorkdayCalendarStore.status(context, region, LocalDate.now())
            working == (feature.config.string("expected", "workday") == "workday")
        }
        registry.registerState(description, evaluator)
        registry.registerCondition(description.copy(
            id = FeatureId("android.condition.shortx_workday"),
            kind = FeatureKind.CONDITION,
        ), evaluator)
    }
}
