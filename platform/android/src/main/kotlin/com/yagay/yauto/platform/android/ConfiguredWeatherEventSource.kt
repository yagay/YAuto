package com.yagay.yauto.platform.android

import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ConfiguredWeatherEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.weather.configured"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private val lastPoll = ConcurrentHashMap<String, Long>()
    private val signatures = ConcurrentHashMap<String, String>()

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        job = scope.launch {
            while (isActive && started.get()) {
                val rules = loadRules()
                val activeKeys = rules.map { it.key }.toSet()
                lastPoll.keys.removeIf { it !in activeKeys }
                signatures.keys.removeIf { it !in activeKeys }
                val now = System.currentTimeMillis()
                rules.forEach { rule ->
                    val previousAt = lastPoll[rule.key] ?: 0L
                    if (now - previousAt < rule.intervalMs) return@forEach
                    lastPoll[rule.key] = now
                    val payload = fetch(rule) ?: return@forEach
                    val signature = weatherSignature(payload)
                    val previous = signatures.put(rule.key, signature)
                    if (previous == null || previous != signature) {
                        emitter.emit(
                            RuntimeEvent(
                                typeId = "android.event.weather_changed",
                                payload = payload,
                                source = id,
                            )
                        )
                    }
                }
                delay(60_000L)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        job?.cancel()
        job = null
        emitter = null
        lastPoll.clear()
        signatures.clear()
        scope.cancel()
    }

    private suspend fun loadRules(): List<WeatherRule> = runCatching {
        workspace.load().automations.asSequence()
            .filter { it.enabled }
            .flatMap { it.activation.events.asSequence() }
            .filter { it.typeId == "android.event.weather_changed" }
            .mapNotNull(::toRule)
            .distinctBy { it.key }
            .toList()
    }.getOrDefault(emptyList())

    private fun toRule(feature: FeatureRef): WeatherRule? {
        val latitude = feature.config["latitude"].numberOrNull() ?: return null
        val longitude = feature.config["longitude"].numberOrNull() ?: return null
        if (!latitude.isFinite() || !longitude.isFinite() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        val interval = feature.config.long("intervalMs", 900_000L).coerceIn(300_000L, 86_400_000L)
        val roundedLat = "%.4f".format(java.util.Locale.ROOT, latitude)
        val roundedLon = "%.4f".format(java.util.Locale.ROOT, longitude)
        return WeatherRule(
            key = "$roundedLat,$roundedLon,$interval",
            latitude = latitude,
            longitude = longitude,
            intervalMs = interval,
        )
    }

    private fun fetch(rule: WeatherRule): Map<String, ConfigValue>? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=" + rule.latitude +
                "&longitude=" + rule.longitude +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m&timezone=auto"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", context.packageName)
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current = root.optJSONObject("current") ?: return@runCatching null
            val code = current.optInt("weather_code", -1)
            mapOf(
                "latitude" to ConfigValue.NumberValue(root.optDouble("latitude", rule.latitude)),
                "longitude" to ConfigValue.NumberValue(root.optDouble("longitude", rule.longitude)),
                "timezone" to ConfigValue.StringValue(root.optString("timezone")),
                "time" to ConfigValue.StringValue(current.optString("time")),
                "temperatureC" to ConfigValue.NumberValue(current.optDouble("temperature_2m", Double.NaN)),
                "apparentTemperatureC" to ConfigValue.NumberValue(current.optDouble("apparent_temperature", Double.NaN)),
                "humidityPercent" to ConfigValue.NumberValue(current.optDouble("relative_humidity_2m", Double.NaN)),
                "precipitationMm" to ConfigValue.NumberValue(current.optDouble("precipitation", 0.0)),
                "weatherCode" to ConfigValue.NumberValue(code.toDouble()),
                "condition" to ConfigValue.StringValue(weatherCondition(code)),
                "windSpeedKmh" to ConfigValue.NumberValue(current.optDouble("wind_speed_10m", 0.0)),
                "windDirectionDeg" to ConfigValue.NumberValue(current.optDouble("wind_direction_10m", 0.0)),
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun weatherSignature(payload: Map<String, ConfigValue>): String =
        listOf(
            payload["temperatureC"],
            payload["apparentTemperatureC"],
            payload["humidityPercent"],
            payload["precipitationMm"],
            payload["weatherCode"],
            payload["windSpeedKmh"],
            payload["windDirectionDeg"],
        ).joinToString("|")

    private data class WeatherRule(
        val key: String,
        val latitude: Double,
        val longitude: Double,
        val intervalMs: Long,
    )
}

internal fun weatherCondition(code: Int): String = when (code) {
    0, 1 -> "clear"
    2, 3, 45, 48 -> "cloudy"
    51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82 -> "rain"
    71, 73, 75, 77, 85, 86 -> "snow"
    95, 96, 99 -> "thunder"
    else -> "unknown"
}
