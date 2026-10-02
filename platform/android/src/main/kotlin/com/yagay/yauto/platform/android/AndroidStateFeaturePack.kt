package com.yagay.yauto.platform.android

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.nfc.NfcManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.Settings
import com.yagay.yauto.core.logging.*
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.CancellationException

/** Live queries rather than cached event payloads: every dispatch sees current state. */
interface AndroidStateReader {
    fun screenOn(): Boolean
    fun networkTypes(): Set<String>
    fun charging(): Boolean
    fun batteryPercent(): Double?
    fun batteryTemperatureC(): Double?
    fun powerSave(): Boolean
    fun appInstalled(packageName: String): Boolean
    fun mediaVolumePercent(): Double?
    fun brightnessPercent(): Double?
    fun autoBrightness(): Boolean
    fun nfcEnabled(): Boolean
    fun locationEnabled(): Boolean
    fun autoRotate(): Boolean
    fun darkMode(): Boolean
    fun stayAwakeWhileCharging(): Boolean
    fun screenTimeoutMs(): Double?
    fun headsetConnected(): Boolean
}

class SystemAndroidStateReader(context: Context) : AndroidStateReader {
    private val context = context.applicationContext
    private val power get() = context.getSystemService(PowerManager::class.java)
    override fun screenOn() = power.isInteractive
    override fun powerSave() = power.isPowerSaveMode
    override fun charging() = context.getSystemService(BatteryManager::class.java).isCharging
    override fun batteryPercent(): Double? {
        val battery = batteryIntent() ?: return null
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0 && level <= scale) level * 100.0 / scale else null
    }
    override fun batteryTemperatureC(): Double? {
        val temperatureTenths = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?: return null
        return if (temperatureTenths == Int.MIN_VALUE) null else temperatureTenths / 10.0
    }
    override fun networkTypes(): Set<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return setOf("none")
        val caps = manager.getNetworkCapabilities(network) ?: return emptySet()
        return buildSet {
            add("connected")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("wifi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ethernet")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("vpn")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("bluetooth")
        }
    }
    @Suppress("DEPRECATION")
    override fun appInstalled(packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    override fun mediaVolumePercent(): Double? {
        val audio = context.getSystemService(AudioManager::class.java)
        val min = audio.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= min) return null
        val value = audio.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(min, max)
        return (value - min) * 100.0 / (max - min)
    }

    override fun brightnessPercent(): Double? = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            .coerceIn(0, 255) * 100.0 / 255.0
    }.getOrNull()

    override fun autoBrightness(): Boolean =
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        ) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    override fun nfcEnabled(): Boolean = runCatching {
        context.getSystemService(NfcManager::class.java)?.defaultAdapter?.isEnabled == true
    }.getOrDefault(false)

    override fun locationEnabled(): Boolean = runCatching {
        context.getSystemService(LocationManager::class.java).isLocationEnabled
    }.getOrDefault(false)

    override fun autoRotate(): Boolean = runCatching {
        Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    }.getOrDefault(false)

    override fun darkMode(): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    override fun stayAwakeWhileCharging(): Boolean = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != 0
    }.getOrDefault(false)

    override fun screenTimeoutMs(): Double? = runCatching {
        Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT).toDouble()
    }.getOrNull()

    override fun headsetConnected(): Boolean = runCatching {
        context.getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .any { it.yautoHeadsetCategory() != null }
    }.getOrDefault(false)

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}

class AndroidStateFeaturePack(private val reader: AndroidStateReader) : FeaturePack {
    constructor(context: Context) : this(SystemAndroidStateReader(context))
    override val id = "android.state"

    override fun install(registry: FeatureRegistry) {
        booleanState(registry, "screen", "Screen on / off", FeatureCategory.DISPLAY, reader::screenOn)
        booleanState(registry, "charging", "Charging", FeatureCategory.DEVICE, reader::charging)
        booleanState(registry, "power_save", "Power saving mode", FeatureCategory.DEVICE, reader::powerSave)
        booleanState(registry, "nfc_enabled", "NFC enabled", FeatureCategory.DEVICE, reader::nfcEnabled)
        booleanState(registry, "location_enabled", "Location services enabled", FeatureCategory.DEVICE, reader::locationEnabled)
        booleanState(registry, "auto_rotate", "Auto-rotate enabled", FeatureCategory.DISPLAY, reader::autoRotate)
        booleanState(registry, "dark_mode", "Dark theme active", FeatureCategory.DISPLAY, reader::darkMode)
        booleanState(registry, "stay_awake_while_charging", "Stay awake while charging", FeatureCategory.DEVICE, reader::stayAwakeWhileCharging)
        booleanState(registry, "headset_connected", "Headset connected", FeatureCategory.AUDIO, reader::headsetConnected)
        state(registry, "network", "Network type", FeatureCategory.NETWORK,
            listOf(FieldSchema.Choice("type", "Network type", true, listOf("connected", "none", "wifi", "cellular", "ethernet", "vpn", "bluetooth")))) { config, _ ->
            config.string("type", "connected") in reader.networkTypes()
        }
        state(registry, "battery_level", "Battery level", FeatureCategory.DEVICE,
            listOf(FieldSchema.Number("min", "Minimum percent", min = 0.0, max = 100.0), FieldSchema.Number("max", "Maximum percent", min = 0.0, max = 100.0))) { config, _ ->
            val min = config["min"].numberOrNull() ?: 0.0
            val max = config["max"].numberOrNull() ?: 100.0
            require(min.isFinite() && max.isFinite() && min in 0.0..100.0 && max in min..100.0) { "Invalid battery range" }
            val level = reader.batteryPercent() ?: error("Battery level unavailable")
            level in min..max
        }
        state(registry, "battery_temperature", "Battery temperature", FeatureCategory.DEVICE,
            listOf(
                FieldSchema.Choice("operator", "Operator", true, listOf("<", "<=", "==", ">=", ">")),
                FieldSchema.Number("value", "Temperature (°C)", true, min = -50.0, max = 150.0),
            )) { config, _ ->
            compareNumber(
                reader.batteryTemperatureC() ?: error("Battery temperature unavailable"),
                config,
                min = -50.0,
                max = 150.0,
                equalityTolerance = 0.05,
            )
        }
        state(registry, "app_installed", "App installed", FeatureCategory.APP,
            listOf(FieldSchema.AppPicker("package", "App / package", true), FieldSchema.Toggle("value", "Installed"))) { config, ctx ->
            val pkg = config.string("package").resolveVariables(ctx.variables).trim()
            require(pkg.isNotEmpty()) { "Package is empty" }
            reader.appInstalled(pkg) == config.boolean("value", true)
        }
        numericState(registry, "media_volume", "Media volume", FeatureCategory.AUDIO, reader::mediaVolumePercent)
        state(registry, "brightness", "Screen brightness", FeatureCategory.DISPLAY,
            listOf(
                FieldSchema.Choice("mode", "Brightness mode", true, listOf("any", "manual", "auto")),
                FieldSchema.Toggle("compareLevel", "Compare brightness level"),
                FieldSchema.Choice("operator", "Operator", true, listOf("<", "<=", "==", ">=", ">")),
                FieldSchema.Number("value", "Brightness percent", min = 0.0, max = 100.0),
            )) { config, _ ->
            val mode = config.string("mode", "any")
            val auto = reader.autoBrightness()
            val modeMatches = when (mode) {
                "auto" -> auto
                "manual" -> !auto
                "any" -> true
                else -> false
            }
            if (!modeMatches) false
            else if (!config.boolean("compareLevel", true)) true
            else comparePercent(reader.brightnessPercent() ?: error("Brightness unavailable"), config)
        }
        state(registry, "screen_timeout", "Screen timeout", FeatureCategory.DISPLAY,
            listOf(
                FieldSchema.Duration("minMs", "Minimum timeout"),
                FieldSchema.Duration("maxMs", "Maximum timeout"),
            )) { config, _ ->
            val current = reader.screenTimeoutMs() ?: error("Screen timeout unavailable")
            val min = config["minMs"].numberOrNull() ?: 0.0
            val max = config["maxMs"].numberOrNull() ?: 86_400_000.0
            require(min.isFinite() && max.isFinite() && min >= 0.0 && max >= min) { "Invalid timeout range" }
            current in min..max
        }
    }

    private fun booleanState(registry: FeatureRegistry, key: String, title: String, category: FeatureCategory, query: () -> Boolean) =
        state(registry, key, title, category, listOf(FieldSchema.Toggle("value", "Enabled / on"))) { config, _ ->
            query() == config.boolean("value", true)
        }

    private fun numericState(registry: FeatureRegistry, key: String, title: String, category: FeatureCategory, query: () -> Double?) =
        state(registry, key, title, category,
            listOf(
                FieldSchema.Choice("operator", "Operator", true, listOf("<", "<=", "==", ">=", ">")),
                FieldSchema.Number("value", "Percent", true, min = 0.0, max = 100.0),
            )) { config, _ ->
            comparePercent(query() ?: error("$title unavailable"), config)
        }

    private fun comparePercent(actual: Double, config: ConfigMap): Boolean =
        compareNumber(actual, config, min = 0.0, max = 100.0, equalityTolerance = 0.5)

    private fun compareNumber(
        actual: Double,
        config: ConfigMap,
        min: Double,
        max: Double,
        equalityTolerance: Double,
    ): Boolean {
        val expected = config["value"].numberOrNull() ?: min
        require(expected.isFinite() && expected in min..max) { "Invalid numeric comparison value" }
        return when (config.string("operator", "==")) {
            "<" -> actual < expected
            "<=" -> actual <= expected
            "==" -> kotlin.math.abs(actual - expected) <= equalityTolerance
            ">=" -> actual >= expected
            ">" -> actual > expected
            else -> false
        }
    }

    private fun state(registry: FeatureRegistry, key: String, title: String, category: FeatureCategory,
        fields: List<FieldSchema>, evaluate: (ConfigMap, FeatureExecutionContext) -> Boolean) {
        val featureId = "android.state.$key"
        for (kind in listOf(FeatureKind.STATE, FeatureKind.CONDITION)) {
            val registeredId = if (kind == FeatureKind.STATE) featureId else "android.condition.$key"
            val evaluator = ConditionEvaluator { feature, ctx ->
                val start = System.nanoTime()
                var failure: Throwable? = null
                val matches = try { evaluate(feature.config, ctx) } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    failure = error
                    false
                }
                ctx.tracer.record(TraceEvent(ctx.executionId, kind = if (kind == FeatureKind.STATE) TraceKind.STATE else TraceKind.CONDITION,
                    level = if (failure == null) TraceLevel.DEBUG else TraceLevel.ERROR,
                    timestampEpochMs = System.currentTimeMillis(), message = failure?.message ?: "Android state evaluated",
                    nodeId = ctx.nodeId, featureId = registeredId, backendId = "android", success = failure == null,
                    durationMs = (System.nanoTime() - start) / 1_000_000,
                    attributes = mapOf("matched" to matches.toString(), "exception" to failure?.javaClass?.name.orEmpty())))
                matches
            }
            val descriptor = FeatureDescriptor(FeatureId(registeredId), kind, title, "Evaluate current Android state", category, fields = fields, ownerPackId = id)
            if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
        }
    }
}
