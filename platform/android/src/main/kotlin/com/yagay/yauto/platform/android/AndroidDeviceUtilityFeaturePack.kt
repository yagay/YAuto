package com.yagay.yauto.platform.android

import android.app.ActivityManager
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateFormat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.TimeZone

class AndroidDeviceUtilityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.device.utility"
    private val context = context.applicationContext
    private val clipboard = context.applicationContext.getSystemService(ClipboardManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerClipboardClear(registry)
        registerClipboardState(registry, FeatureKind.STATE, "android.state.clipboard_content")
        registerClipboardState(registry, FeatureKind.CONDITION, "android.condition.clipboard_content")
        registerBatteryInfo(registry)
        registerChargingSource(registry, FeatureKind.STATE, "android.state.charging_source")
        registerChargingSource(registry, FeatureKind.CONDITION, "android.condition.charging_source")
        registerMemoryInfo(registry)
        registerDisplayMetrics(registry)
        registerUptime(registry)
        registerThermal(registry)
        registerLocaleInfo(registry)
    }

    private fun registerClipboardClear(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.clipboard.clear"), FeatureKind.ACTION,
                "Clear clipboard", "Clear the current Android primary clipboard clip",
                FeatureCategory.DEVICE,
                keywords = setOf("clipboard", "clear", "privacy"), ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                clipboard.clearPrimaryClip()
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerClipboardState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Clipboard has content", "Check whether Android currently exposes a non-empty primary clipboard clip",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Toggle("value", "Has content")),
            keywords = setOf("clipboard", "content", "text"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val hasContent = runCatching {
                val clip = clipboard.primaryClip
                clip != null && clip.itemCount > 0 && clip.getItemAt(0).coerceToText(context).isNotEmpty()
            }.getOrDefault(false)
            hasContent == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerBatteryInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.battery.info"), FeatureKind.ACTION,
                "Get battery information", "Read battery level, health, temperature, voltage, charging source and hardware counters",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store battery object", true)),
                keywords = setOf("battery", "health", "temperature", "voltage", "capacity"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val intent = batteryIntent() ?: return@registerAction ActionExecutionResult(false, message = userText("feature.battery_unavailable"))
            val manager = context.getSystemService(BatteryManager::class.java)
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val percent = if (level >= 0 && scale > 0) level * 100.0 / scale else -1.0
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "percent" to ConfigValue.NumberValue(percent),
                    "status" to ConfigValue.StringValue(batteryStatusName(intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN))),
                    "health" to ConfigValue.StringValue(batteryHealthName(intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN))),
                    "temperatureC" to ConfigValue.NumberValue(intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0),
                    "voltageMv" to ConfigValue.NumberValue(intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0).toDouble()),
                    "technology" to ConfigValue.StringValue(intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty()),
                    "present" to ConfigValue.BooleanValue(intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true)),
                    "plugged" to ConfigValue.StringValue(chargingSourceName(intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0))),
                    "capacityPercent" to batteryProperty(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)),
                    "chargeCounterUah" to batteryProperty(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)),
                    "currentNowUa" to batteryProperty(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)),
                    "currentAverageUa" to batteryProperty(manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)),
                    "energyCounterNwh" to batteryLongProperty(manager.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerChargingSource(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Charging source", "Match the current Android battery charging source",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Choice("source", "Charging source", true, listOf("none", "ac", "usb", "wireless", "dock", "any_charging"))),
            keywords = setOf("charging", "usb", "wireless", "dock", "power"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val actual = batteryIntent()?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
            matchesChargingSource(feature.config.string("source", "any_charging"), actual)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerMemoryInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.memory.info"), FeatureKind.ACTION,
                "Get memory information", "Read Android system memory totals and low-memory status into an object variable",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store memory object", true)),
                keywords = setOf("memory", "ram", "free memory", "low memory"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val info = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "totalBytes" to ConfigValue.NumberValue(info.totalMem.toDouble()),
                    "availableBytes" to ConfigValue.NumberValue(info.availMem.toDouble()),
                    "thresholdBytes" to ConfigValue.NumberValue(info.threshold.toDouble()),
                    "lowMemory" to ConfigValue.BooleanValue(info.lowMemory),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDisplayMetrics(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.display.metrics"), FeatureKind.ACTION,
                "Get display metrics", "Read current application display pixel and density metrics into an object variable",
                FeatureCategory.DISPLAY,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store display object", true)),
                keywords = setOf("display", "resolution", "dpi", "density", "screen"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val metrics = context.resources.displayMetrics
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "widthPixels" to ConfigValue.NumberValue(metrics.widthPixels.toDouble()),
                    "heightPixels" to ConfigValue.NumberValue(metrics.heightPixels.toDouble()),
                    "density" to ConfigValue.NumberValue(metrics.density.toDouble()),
                    "densityDpi" to ConfigValue.NumberValue(metrics.densityDpi.toDouble()),
                    "scaledDensity" to ConfigValue.NumberValue(metrics.scaledDensity.toDouble()),
                    "xdpi" to ConfigValue.NumberValue(metrics.xdpi.toDouble()),
                    "ydpi" to ConfigValue.NumberValue(metrics.ydpi.toDouble()),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerUptime(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.device.uptime"), FeatureKind.ACTION,
                "Get device uptime", "Read elapsed realtime, awake uptime and estimated boot epoch time",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store uptime object", true)),
                keywords = setOf("uptime", "boot", "elapsed realtime", "device"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val elapsed = SystemClock.elapsedRealtime()
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "elapsedRealtimeMs" to ConfigValue.NumberValue(elapsed.toDouble()),
                    "uptimeMs" to ConfigValue.NumberValue(SystemClock.uptimeMillis().toDouble()),
                    "bootEpochMs" to ConfigValue.NumberValue((System.currentTimeMillis() - elapsed).toDouble()),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerThermal(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.device.thermal_status"), FeatureKind.ACTION,
                "Get thermal status", "Read Android PowerManager thermal status into an object variable",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store thermal object", true)),
                keywords = setOf("thermal", "temperature", "heat", "power"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val status = context.getSystemService(PowerManager::class.java).currentThermalStatus
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "status" to ConfigValue.NumberValue(status.toDouble()),
                    "name" to ConfigValue.StringValue(thermalStatusName(status)),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerLocaleInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.locale.info"), FeatureKind.ACTION,
                "Get locale and time-zone information", "Read configured Android locales, time zone and 12/24-hour preference",
                FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store locale object", true)),
                keywords = setOf("locale", "language", "time zone", "24 hour"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val locales = context.resources.configuration.locales
            val tags = (0 until locales.size()).map { ConfigValue.StringValue(locales[it].toLanguageTag()) }
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "locales" to ConfigValue.ListValue(tags),
                    "timeZone" to ConfigValue.StringValue(TimeZone.getDefault().id),
                    "is24Hour" to ConfigValue.BooleanValue(DateFormat.is24HourFormat(context)),
                )
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun batteryIntent(): Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
}

internal fun chargingSourceName(plugged: Int): String = when (plugged) {
    BatteryManager.BATTERY_PLUGGED_AC -> "ac"
    BatteryManager.BATTERY_PLUGGED_USB -> "usb"
    BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
    BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
    else -> "none"
}

internal fun matchesChargingSource(expected: String, plugged: Int): Boolean {
    val actual = chargingSourceName(plugged)
    return when (expected) {
        "any_charging" -> actual != "none"
        "none", "ac", "usb", "wireless", "dock" -> actual == expected
        else -> false
    }
}

internal fun batteryStatusName(status: Int): String = when (status) {
    BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
    BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
    BatteryManager.BATTERY_STATUS_FULL -> "full"
    BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
    else -> "unknown"
}

internal fun batteryHealthName(health: Int): String = when (health) {
    BatteryManager.BATTERY_HEALTH_GOOD -> "good"
    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
    BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
    BatteryManager.BATTERY_HEALTH_COLD -> "cold"
    else -> "unknown"
}

internal fun thermalStatusName(status: Int): String = when (status) {
    PowerManager.THERMAL_STATUS_NONE -> "none"
    PowerManager.THERMAL_STATUS_LIGHT -> "light"
    PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
    PowerManager.THERMAL_STATUS_SEVERE -> "severe"
    PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
    PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
    PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
    else -> "unknown"
}

private fun batteryProperty(value: Int): ConfigValue =
    if (value == Int.MIN_VALUE) ConfigValue.NullValue else ConfigValue.NumberValue(value.toDouble())

private fun batteryLongProperty(value: Long): ConfigValue =
    if (value == Long.MIN_VALUE) ConfigValue.NullValue else ConfigValue.NumberValue(value.toDouble())
