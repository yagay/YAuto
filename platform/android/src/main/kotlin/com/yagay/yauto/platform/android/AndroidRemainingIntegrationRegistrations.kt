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


internal fun AndroidRemainingParityFeaturePack.registerAudioAndTorch(registry: FeatureRegistry) {
        resultAction(registry, "android.audio.devices.query", "Query audio devices", "Return current input and output audio devices", FeatureCategory.AUDIO) {
            ConfigValue.ListValue(audio.getDevices(AudioManager.GET_DEVICES_ALL).map { device ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "id" to ConfigValue.NumberValue(device.id.toDouble()),
                        "type" to ConfigValue.NumberValue(device.type.toDouble()),
                        "productName" to ConfigValue.StringValue(device.productName?.toString().orEmpty()),
                        "source" to ConfigValue.BooleanValue(device.isSource),
                        "sink" to ConfigValue.BooleanValue(device.isSink),
                        "sampleRates" to ConfigValue.ListValue(device.sampleRates.map { ConfigValue.NumberValue(it.toDouble()) }),
                        "channelCounts" to ConfigValue.ListValue(device.channelCounts.map { ConfigValue.NumberValue(it.toDouble()) }),
                    )
                )
            })
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.torch.state.query"), FeatureKind.ACTION,
                "Query torch state", "Return observed torch state for all camera flash units",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store torch map", true)),
                keywords = setOf("torch", "flashlight", "state", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx -> store(feature, ctx, torch.value()) }

        val evaluator = ConditionEvaluator { feature, _ ->
            val cameraId = feature.config.string("cameraId").trim()
            val actual = if (cameraId.isBlank()) torch.anyEnabled() else torch.enabled(cameraId)
            actual == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.torch_on"), FeatureKind.STATE,
            "Torch enabled", "Check observed flashlight/torch state",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Text("cameraId", "Camera ID"), FieldSchema.Toggle("value", "Torch on")),
            keywords = setOf("torch", "flashlight", "state", "shortx"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.torch_on"), kind = FeatureKind.CONDITION), evaluator)
    }

internal fun AndroidRemainingParityFeaturePack.registerSoundLevel(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.sound_level.measure"), FeatureKind.ACTION,
                "Measure sound level", "Sample the microphone and return RMS, peak and relative dBFS level",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Duration("durationMs", "Measurement duration"),
                    FieldSchema.Choice("sampleRate", "Sample rate", options = listOf("8000", "16000", "44100")),
                    FieldSchema.Variable("resultVariable", "Store level object", true),
                ),
                accessRequirements = setOf(AccessRequirement.RECORD_AUDIO),
                keywords = setOf("sound level", "noise", "microphone", "db", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.microphone_permission_required"))
            }
            val durationMs = (feature.config["durationMs"].numberOrNull() ?: 1_000.0).toLong().coerceIn(100L, 10_000L)
            val sampleRate = feature.config.string("sampleRate", "16000").toIntOrNull()?.takeIf { it in setOf(8000, 16000, 44100) } ?: 16000
            val output = withContext(Dispatchers.IO) {
                runCatching { measureSoundLevel(sampleRate, durationMs) }.getOrNull()
            } ?: return@registerAction ActionExecutionResult(false, message = userText("feature.audio_measurement_failed"))
            store(feature, ctx, output)
        }
    }

    @Suppress("MissingPermission")
internal fun AndroidRemainingParityFeaturePack.measureSoundLevel(sampleRate: Int, durationMs: Long): ConfigValue.ObjectValue {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 2)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(min * 2)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("AudioRecord initialization failed")
        }
        val buffer = ShortArray(min)
        var sumSquares = 0.0
        var peak = 0
        var count = 0L
        val end = android.os.SystemClock.elapsedRealtime() + durationMs
        try {
            recorder.startRecording()
            while (android.os.SystemClock.elapsedRealtime() < end) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (index in 0 until read) {
                    val value = buffer[index].toInt()
                    val abs = kotlin.math.abs(value)
                    if (abs > peak) peak = abs
                    sumSquares += value.toDouble() * value.toDouble()
                }
                count += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        val rms = if (count > 0) sqrt(sumSquares / count) else 0.0
        val dbfs = if (rms > 0.0) 20.0 * log10(rms / Short.MAX_VALUE.toDouble()) else -120.0
        return ConfigValue.ObjectValue(
            mapOf(
                "rms" to ConfigValue.NumberValue(rms),
                "peak" to ConfigValue.NumberValue(peak.toDouble()),
                "dbfs" to ConfigValue.NumberValue(dbfs.coerceAtLeast(-120.0)),
                "sampleCount" to ConfigValue.NumberValue(count.toDouble()),
                "sampleRate" to ConfigValue.NumberValue(sampleRate.toDouble()),
            )
        )
    }

internal fun AndroidRemainingParityFeaturePack.registerSensorPrivacy(registry: FeatureRegistry) {
        val manager = context.getSystemService(SensorPrivacyManager::class.java)
        sensorPrivacyPair(registry, manager, SensorPrivacyManager.Sensors.MICROPHONE, "microphone", "Microphone privacy blocked")
        sensorPrivacyPair(registry, manager, SensorPrivacyManager.Sensors.CAMERA, "camera", "Camera privacy blocked")
    }

internal fun AndroidRemainingParityFeaturePack.registerConfigurationAndSimEvents(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.configuration_changed"), FeatureKind.EVENT,
                "Android configuration changed", "Run when Android configuration changes",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("orientation", "Orientation", options = listOf("any", "portrait", "landscape", "square", "undefined")),
                ),
                keywords = setOf("configuration", "orientation", "font scale", "ui mode"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.configuration_changed") return@registerEvent false
            val wanted = feature.config.string("orientation", "any")
            wanted == "any" || ctx.event.payload.string("orientation") == wanted
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.orientation_changed"), FeatureKind.EVENT,
                "Device orientation changed", "Run when Android reports portrait/landscape configuration changes",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Choice("orientation", "Orientation", options = listOf("any", "portrait", "landscape", "square", "undefined")),
                ),
                keywords = setOf("orientation", "portrait", "landscape", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.orientation_changed") return@registerEvent false
            val wanted = feature.config.string("orientation", "any")
            wanted == "any" || ctx.event.payload.string("orientation") == wanted
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sim_subscription_changed"), FeatureKind.EVENT,
                "SIM subscription changed", "Run when active SIMs or Android default data/SMS/voice subscriptions change",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("change", "Change type", options = listOf("any", "active_set", "default_data", "default_sms", "default_voice")),
                    FieldSchema.Number("subscriptionId", "Subscription ID (-1 = any)", min = -1.0),
                ),
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("sim", "subscription", "default data", "sim changed", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sim_subscription_changed") return@registerEvent false
            val wantedChange = feature.config.string("change", "any")
            val changeMatches = when (wantedChange) {
                "active_set" -> ctx.event.payload.boolean("activeSetChanged")
                "default_data" -> ctx.event.payload.boolean("defaultDataChanged")
                "default_sms" -> ctx.event.payload.boolean("defaultSmsChanged")
                "default_voice" -> ctx.event.payload.boolean("defaultVoiceChanged")
                else -> true
            }
            if (!changeMatches) return@registerEvent false
            val wantedId = feature.config["subscriptionId"].numberOrNull()?.toInt() ?: -1
            if (wantedId < 0) return@registerEvent true
            val ids = (ctx.event.payload["activeIds"] as? ConfigValue.ListValue)?.value.orEmpty()
                .mapNotNull { (it as? ConfigValue.NumberValue)?.value?.toInt() }
            wantedId in ids ||
                (ctx.event.payload["defaultDataId"] as? ConfigValue.NumberValue)?.value?.toInt() == wantedId ||
                (ctx.event.payload["defaultSmsId"] as? ConfigValue.NumberValue)?.value?.toInt() == wantedId ||
                (ctx.event.payload["defaultVoiceId"] as? ConfigValue.NumberValue)?.value?.toInt() == wantedId
        }
    }

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

internal fun AndroidRemainingParityFeaturePack.registerExternalIntegration(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.external.intent.invoke"), FeatureKind.ACTION,
                "Invoke external integration", "Start an Activity, Service or Broadcast in another app with simple string extras",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Choice("mode", "Mode", true, listOf("broadcast", "activity", "service")),
                    FieldSchema.Text("action", "Intent action", true),
                    FieldSchema.Text("package", "Target package"),
                    FieldSchema.Text("component", "Component package/class"),
                    FieldSchema.Text("dataUri", "Data URI"),
                    FieldSchema.Text("extras", "Extras, key=value per line", multiline = true),
                ),
                keywords = setOf("plugin", "intent", "tasker plugin", "external app", "broadcast", "service"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val action = feature.config.string("action").resolveVariables(ctx.variables).trim()
            if (action.isBlank()) return@registerAction ActionExecutionResult(false)
            val intent = Intent(action).apply {
                feature.config.string("package").resolveVariables(ctx.variables).trim().takeIf { it.isNotBlank() }?.let(::setPackage)
                feature.config.string("component").resolveVariables(ctx.variables).trim().takeIf { it.isNotBlank() }
                    ?.let(ComponentName::unflattenFromString)?.let(::setComponent)
                feature.config.string("dataUri").resolveVariables(ctx.variables).trim().takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
                parseExtras(feature.config.string("extras").resolveVariables(ctx.variables)).forEach { (key, value) -> putExtra(key, value) }
            }
            runCatching {
                when (feature.config.string("mode", "broadcast")) {
                    "activity" -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "service" -> context.startService(intent)
                    else -> context.sendBroadcast(intent)
                }
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wireguard.tunnel.set"), FeatureKind.ACTION,
                "Set WireGuard tunnel", "Ask the official WireGuard Android app to bring a named tunnel up or down",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("tunnel", "Tunnel name", true),
                    FieldSchema.Toggle("enabled", "Tunnel up"),
                ),
                keywords = setOf("wireguard", "vpn", "tunnel", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val tunnel = feature.config.string("tunnel").resolveVariables(ctx.variables).trim()
            if (tunnel.isBlank()) return@registerAction ActionExecutionResult(false)
            val action = if (feature.config.boolean("enabled", true)) {
                "com.wireguard.android.action.SET_TUNNEL_UP"
            } else {
                "com.wireguard.android.action.SET_TUNNEL_DOWN"
            }
            runCatching {
                context.sendBroadcast(Intent(action).setPackage("com.wireguard.android").putExtra("tunnel", tunnel))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }


internal fun AndroidRemainingParityFeaturePack.registerRuntimeStates(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.mobile_data_changed"), FeatureKind.EVENT,
                "Mobile data changed", "Run when Android's global mobile-data setting changes",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Choice("state", "State", options = listOf("any", "enabled", "disabled"))),
                keywords = setOf("mobile data", "cellular data", "changed", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.mobile_data_changed") return@registerEvent false
            when (feature.config.string("state", "any")) {
                "enabled" -> ctx.event.payload.boolean("enabled")
                "disabled" -> !ctx.event.payload.boolean("enabled")
                else -> true
            }
        }

        val serviceFields = listOf(
            FieldSchema.AppPicker("package", "App / package", true),
            FieldSchema.Text("service", "Service class contains"),
            FieldSchema.Toggle("value", "Running"),
        )
        val serviceEvaluator = ConditionEvaluator { feature, ctx ->
            val pkg = feature.config.string("package").trim()
            if (!pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) return@ConditionEvaluator false
            val service = feature.config.string("service").trim()
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "system.shell.execute",
                    payload = mapOf(
                        "command" to ConfigValue.StringValue("dumpsys activity services " + shellQuote(pkg)),
                        "timeoutMs" to ConfigValue.NumberValue(5_000.0),
                    ),
                )
            )
            val stdout = ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()
            val running = result.success &&
                stdout.contains(pkg, ignoreCase = false) &&
                (service.isBlank() || stdout.contains(service, ignoreCase = true))
            running == feature.config.boolean("value", true)
        }
        val serviceState = FeatureDescriptor(
            FeatureId("android.state.service_running"), FeatureKind.STATE,
            "Android service running",
            "Check a package/service in ActivityManager using Root or Shizuku dumpsys",
            FeatureCategory.APP,
            fields = serviceFields,
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            keywords = setOf("service running", "background service", "dumpsys", "shortx", "root", "shizuku"),
            ownerPackId = id,
        )
        registry.registerState(serviceState, serviceEvaluator)
        registry.registerCondition(
            serviceState.copy(id = FeatureId("android.condition.service_running"), kind = FeatureKind.CONDITION),
            serviceEvaluator,
        )
    }

