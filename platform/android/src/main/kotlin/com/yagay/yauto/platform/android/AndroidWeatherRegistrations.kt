package com.yagay.yauto.platform.android

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.CancellationSignal
import android.os.LocaleList
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt



/** Weather event, action, forecast and condition registrations. */
internal fun AndroidRemainingParityFeaturePack.registerWeatherEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.weather_changed"), FeatureKind.EVENT,
                "Weather condition update",
                "Run when configured weather data updates and matches temperature, wind, humidity, condition or wind-direction filters",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                    FieldSchema.Duration("intervalMs", "Refresh interval"),
                    FieldSchema.Choice("metric", "Weather metric", true, listOf("any_update", "temperature", "wind_speed", "humidity", "condition", "wind_direction")),
                    FieldSchema.Choice("operator", "Comparison", options = listOf("any", "above", "below")),
                    FieldSchema.Number("value", "Threshold"),
                    FieldSchema.Choice("condition", "Weather condition", options = listOf("any", "clear", "cloudy", "rain", "thunder", "snow")),
                    FieldSchema.Number("directionMin", "Wind direction minimum °", min = 0.0, max = 360.0),
                    FieldSchema.Number("directionMax", "Wind direction maximum °", min = 0.0, max = 360.0),
                ),
                keywords = setOf("weather trigger", "temperature", "wind", "humidity", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.weather_changed") return@registerEvent false
            val lat = feature.config["latitude"].numberOrNull()
            val lon = feature.config["longitude"].numberOrNull()
            if (lat != null && kotlin.math.abs(ctx.event.payload["latitude"].numberOrNull().orZero() - lat) > 0.001) return@registerEvent false
            if (lon != null && kotlin.math.abs(ctx.event.payload["longitude"].numberOrNull().orZero() - lon) > 0.001) return@registerEvent false
            when (feature.config.string("metric", "any_update")) {
                "temperature" -> weatherCompare(ctx.event.payload["temperatureC"].numberOrNull(), feature)
                "wind_speed" -> weatherCompare(ctx.event.payload["windSpeedKmh"].numberOrNull(), feature)
                "humidity" -> weatherCompare(ctx.event.payload["humidityPercent"].numberOrNull(), feature)
                "condition" -> {
                    val wanted = feature.config.string("condition", "any")
                    wanted == "any" || ctx.event.payload.string("condition") == wanted
                }
                "wind_direction" -> {
                    val value = ctx.event.payload["windDirectionDeg"].numberOrNull() ?: return@registerEvent false
                    val min = feature.config["directionMin"].numberOrNull() ?: 0.0
                    val max = feature.config["directionMax"].numberOrNull() ?: 360.0
                    if (min <= max) value in min..max else value >= min || value <= max
                }
                else -> true
            }
        }
    }

internal fun AndroidRemainingParityFeaturePack.weatherCompare(actual: Double?, feature: com.yagay.yauto.core.model.FeatureRef): Boolean {
        actual ?: return false
        val threshold = feature.config["value"].numberOrNull() ?: return false
        return when (feature.config.string("operator", "any")) {
            "above" -> actual >= threshold
            "below" -> actual <= threshold
            else -> true
        }
    }

internal fun AndroidRemainingParityFeaturePack.registerWeather(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.weather.current"), FeatureKind.ACTION,
                "Get current weather", "Query current weather for coordinates using Open-Meteo without an API key",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                    FieldSchema.Variable("resultVariable", "Store weather object", true),
                ),
                keywords = setOf("weather", "temperature", "wind", "forecast", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val lat = feature.config["latitude"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val lon = feature.config["longitude"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val output = withContext(Dispatchers.IO) { fetchWeather(lat, lon) }
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.weather_query_failed"))
            store(feature, ctx, output)
        }
    }

internal fun AndroidRemainingParityFeaturePack.fetchWeather(latitude: Double, longitude: Double): ConfigValue.ObjectValue? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=" + latitude +
                "&longitude=" + longitude +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m&timezone=auto"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current = root.optJSONObject("current") ?: return@runCatching null
            ConfigValue.ObjectValue(
                mapOf(
                    "latitude" to ConfigValue.NumberValue(root.optDouble("latitude", latitude)),
                    "longitude" to ConfigValue.NumberValue(root.optDouble("longitude", longitude)),
                    "timezone" to ConfigValue.StringValue(root.optString("timezone")),
                    "time" to ConfigValue.StringValue(current.optString("time")),
                    "temperatureC" to ConfigValue.NumberValue(current.optDouble("temperature_2m", Double.NaN)),
                    "apparentTemperatureC" to ConfigValue.NumberValue(current.optDouble("apparent_temperature", Double.NaN)),
                    "humidityPercent" to ConfigValue.NumberValue(current.optDouble("relative_humidity_2m", Double.NaN)),
                    "precipitationMm" to ConfigValue.NumberValue(current.optDouble("precipitation", 0.0)),
                    "weatherCode" to ConfigValue.NumberValue(current.optDouble("weather_code", -1.0)),
                    "windSpeedKmh" to ConfigValue.NumberValue(current.optDouble("wind_speed_10m", 0.0)),
                    "windDirectionDeg" to ConfigValue.NumberValue(current.optDouble("wind_direction_10m", 0.0)),
                )
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

