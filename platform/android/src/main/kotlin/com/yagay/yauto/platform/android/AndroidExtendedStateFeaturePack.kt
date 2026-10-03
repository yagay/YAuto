package com.yagay.yauto.platform.android

import android.app.AlarmManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.text.format.DateFormat
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/** Additional live Android constraints kept separate from the legacy state-reader contract. */
interface AndroidExtendedStateReader {
    fun deviceIdle(): Boolean
    fun keyguardLocked(): Boolean
    fun deviceSecure(): Boolean
    fun musicActive(): Boolean
    fun microphoneMuted(): Boolean
    fun speakerphoneOn(): Boolean
    fun uses24HourClock(): Boolean
    fun nextAlarmSet(): Boolean
    fun networkValidated(): Boolean
    fun networkMetered(): Boolean
    fun networkRoaming(): Boolean
    fun networkInternet(): Boolean
    fun networkRestricted(): Boolean
    fun networkSuspended(): Boolean
    fun orientation(): String
    fun ringerMode(): String
    fun batteryPresent(): Boolean
    fun batteryPlugged(): String
    fun batteryStatus(): String
    fun batteryHealth(): String
    fun batteryVoltageMv(): Double?
}

class SystemAndroidExtendedStateReader(context: Context) : AndroidExtendedStateReader {
    private val context = context.applicationContext

    override fun deviceIdle(): Boolean =
        context.getSystemService(PowerManager::class.java).isDeviceIdleMode

    override fun keyguardLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    override fun deviceSecure(): Boolean =
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    override fun musicActive(): Boolean =
        context.getSystemService(AudioManager::class.java).isMusicActive

    override fun microphoneMuted(): Boolean =
        context.getSystemService(AudioManager::class.java).isMicrophoneMute

    @Suppress("DEPRECATION")
    override fun speakerphoneOn(): Boolean =
        context.getSystemService(AudioManager::class.java).isSpeakerphoneOn

    override fun uses24HourClock(): Boolean = DateFormat.is24HourFormat(context)

    override fun nextAlarmSet(): Boolean =
        context.getSystemService(AlarmManager::class.java).nextAlarmClock != null

    override fun networkValidated(): Boolean = networkCapabilities()
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true

    override fun networkMetered(): Boolean =
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

    override fun networkRoaming(): Boolean {
        val caps = networkCapabilities() ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
    }

    override fun networkInternet(): Boolean = networkCapabilities()
        ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

    override fun networkRestricted(): Boolean {
        val caps = networkCapabilities() ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    }

    override fun networkSuspended(): Boolean {
        val caps = networkCapabilities() ?: return false
        return !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
    }

    override fun orientation(): String = when (context.resources.configuration.orientation) {
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        else -> "undefined"
    }

    override fun ringerMode(): String = when (context.getSystemService(AudioManager::class.java).ringerMode) {
        AudioManager.RINGER_MODE_SILENT -> "silent"
        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
        AudioManager.RINGER_MODE_NORMAL -> "normal"
        else -> "unknown"
    }

    override fun batteryPresent(): Boolean = batteryIntent()
        ?.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false) == true

    override fun batteryPlugged(): String = when (batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
        BatteryManager.BATTERY_PLUGGED_AC -> "ac"
        BatteryManager.BATTERY_PLUGGED_USB -> "usb"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        else -> "none"
    }

    override fun batteryStatus(): String = when (
        batteryIntent()?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
    ) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    override fun batteryHealth(): String = when (
        batteryIntent()?.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
    ) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
        BatteryManager.BATTERY_HEALTH_COLD -> "cold"
        else -> "unknown"
    }

    override fun batteryVoltageMv(): Double? {
        val value = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE) ?: return null
        return value.takeIf { it != Int.MIN_VALUE }?.toDouble()
    }

    private fun networkCapabilities(): NetworkCapabilities? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.activeNetwork?.let(manager::getNetworkCapabilities)
    }

    private fun batteryIntent(): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}

class AndroidExtendedStateFeaturePack(private val reader: AndroidExtendedStateReader) : FeaturePack {
    constructor(context: Context) : this(SystemAndroidExtendedStateReader(context))

    override val id: String = "android.state.extended"

    override fun install(registry: FeatureRegistry) {
        booleanPair(registry, "device_idle", "Device idle / Doze", FeatureCategory.DEVICE, reader::deviceIdle)
        booleanPair(registry, "keyguard_locked", "Keyguard locked", FeatureCategory.DEVICE, reader::keyguardLocked)
        booleanPair(registry, "device_secure", "Secure lock configured", FeatureCategory.DEVICE, reader::deviceSecure)
        booleanPair(registry, "music_active", "Music playback active", FeatureCategory.AUDIO, reader::musicActive)
        booleanPair(registry, "microphone_muted", "Microphone muted", FeatureCategory.AUDIO, reader::microphoneMuted)
        booleanPair(registry, "speakerphone_on", "Speakerphone enabled", FeatureCategory.AUDIO, reader::speakerphoneOn)
        booleanPair(registry, "clock_24_hour", "24-hour clock enabled", FeatureCategory.SYSTEM, reader::uses24HourClock)
        booleanPair(registry, "next_alarm_set", "Next alarm is set", FeatureCategory.SYSTEM, reader::nextAlarmSet)
        booleanPair(registry, "network_validated", "Network validated", FeatureCategory.NETWORK, reader::networkValidated)
        booleanPair(registry, "network_metered", "Metered network", FeatureCategory.NETWORK, reader::networkMetered)
        booleanPair(registry, "network_roaming", "Roaming network", FeatureCategory.NETWORK, reader::networkRoaming)
        booleanPair(registry, "network_internet", "Network has internet capability", FeatureCategory.NETWORK, reader::networkInternet)
        booleanPair(registry, "network_restricted", "Restricted network", FeatureCategory.NETWORK, reader::networkRestricted)
        booleanPair(registry, "network_suspended", "Suspended network", FeatureCategory.NETWORK, reader::networkSuspended)
        choicePair(
            registry,
            "orientation",
            "Device orientation",
            FeatureCategory.DISPLAY,
            listOf("portrait", "landscape", "undefined"),
            reader::orientation,
        )
        choicePair(
            registry,
            "ringer_mode",
            "Ringer mode",
            FeatureCategory.AUDIO,
            listOf("normal", "vibrate", "silent", "unknown"),
            reader::ringerMode,
        )
        booleanPair(registry, "battery_present", "Battery present", FeatureCategory.DEVICE, reader::batteryPresent)
        choicePair(
            registry,
            "battery_plugged",
            "Battery power source",
            FeatureCategory.DEVICE,
            listOf("none", "ac", "usb", "wireless", "dock"),
            reader::batteryPlugged,
        )
        choicePair(
            registry,
            "battery_status",
            "Battery charging status",
            FeatureCategory.DEVICE,
            listOf("charging", "discharging", "full", "not_charging", "unknown"),
            reader::batteryStatus,
        )
        choicePair(
            registry,
            "battery_health",
            "Battery health status",
            FeatureCategory.DEVICE,
            listOf("good", "overheat", "dead", "over_voltage", "failure", "cold", "unknown"),
            reader::batteryHealth,
        )
        numericPair(
            registry,
            "battery_voltage",
            "Battery voltage",
            FeatureCategory.DEVICE,
            "Millivolts",
            0.0,
            20_000.0,
            reader::batteryVoltageMv,
        )
    }

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        query: () -> Boolean,
    ) = pair(
        registry,
        key,
        title,
        category,
        listOf(FieldSchema.Toggle("value", "Enabled / active")),
    ) { config, _ -> query() == config.boolean("value", true) }

    private fun choicePair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        options: List<String>,
        query: () -> String,
    ) = pair(
        registry,
        key,
        title,
        category,
        listOf(FieldSchema.Choice("value", "Value", true, options)),
    ) { config, _ -> query() == config.string("value", options.first()) }

    private fun numericPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        valueLabel: String,
        min: Double,
        max: Double,
        query: () -> Double?,
    ) = pair(
        registry,
        key,
        title,
        category,
        listOf(
            FieldSchema.Choice("operator", "Operator", true, listOf("<", "<=", "==", ">=", ">")),
            FieldSchema.Number("value", valueLabel, true, min = min, max = max),
        ),
    ) { config, _ ->
        val actual = query() ?: return@pair false
        val expected = config["value"].numberOrNull() ?: min
        if (!expected.isFinite() || expected !in min..max) return@pair false
        when (config.string("operator", "==")) {
            "<" -> actual < expected
            "<=" -> actual <= expected
            "==" -> actual == expected
            ">=" -> actual >= expected
            ">" -> actual > expected
            else -> false
        }
    }

    private fun pair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        evaluate: (ConfigMap, FeatureExecutionContext) -> Boolean,
    ) {
        register(registry, FeatureKind.STATE, "android.state.$key", title, category, fields, evaluate)
        register(registry, FeatureKind.CONDITION, "android.condition.$key", title, category, fields, evaluate)
    }

    private fun register(
        registry: FeatureRegistry,
        kind: FeatureKind,
        featureId: String,
        title: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        evaluate: (ConfigMap, FeatureExecutionContext) -> Boolean,
    ) {
        val descriptor = FeatureDescriptor(
            id = FeatureId(featureId),
            kind = kind,
            title = title,
            description = "Evaluate the current Android runtime state",
            category = category,
            fields = fields,
            keywords = setOf("state", "condition", "constraint", "android"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, context ->
            runCatching { evaluate(feature.config, context) }.getOrDefault(false)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator)
        else registry.registerCondition(descriptor, evaluator)
    }
}
