package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/**
 * Canonical battery trigger. Historical detailed battery triggers are aliases whose old config
 * keys are migrated into this single filter schema.
 */
class AndroidBatteryEventFeaturePack : FeaturePack {
    override val id: String = "android.events.battery.detail"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId("android.event.battery_changed"),
                kind = FeatureKind.EVENT,
                title = "Battery changed",
                description = "Run when Android reports a battery update and optionally filter level, temperature, voltage, power source, charging status, health or presence",
                category = FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("minPercent", "Minimum percent", min = 0.0, max = 100.0),
                    FieldSchema.Number("maxPercent", "Maximum percent", min = 0.0, max = 100.0),
                    FieldSchema.Number("minTemperatureC", "Minimum temperature (°C)", min = -50.0, max = 150.0),
                    FieldSchema.Number("maxTemperatureC", "Maximum temperature (°C)", min = -50.0, max = 150.0),
                    FieldSchema.Number("minVoltageMv", "Minimum millivolts", min = 0.0, max = 20_000.0),
                    FieldSchema.Number("maxVoltageMv", "Maximum millivolts", min = 0.0, max = 20_000.0),
                    FieldSchema.Choice("powerSource", "Power source", options = listOf("any", "none", "ac", "usb", "wireless", "dock")),
                    FieldSchema.Choice("status", "Charging status", options = listOf("any", "charging", "discharging", "full", "not_charging", "unknown")),
                    FieldSchema.Choice("health", "Battery health", options = listOf("any", "good", "overheat", "dead", "over_voltage", "failure", "cold", "unknown")),
                    FieldSchema.Choice("present", "Battery present", options = listOf("any", "yes", "no")),
                ),
                keywords = setOf("battery", "profile", "level", "charging", "temperature", "voltage", "health", "power"),
                ownerPackId = id,
                aliases = setOf(
                    "android.event.battery_power_source_filtered",
                    "android.event.battery_status_filtered",
                    "android.event.battery_health_filtered",
                    "android.event.battery_present_filtered",
                    "android.event.battery_voltage_filtered",
                    "android.event.battery_profile_filtered",
                ),
                aliasConfigKeyRenames = mapOf(
                    "android.event.battery_power_source_filtered" to mapOf("value" to "powerSource"),
                    "android.event.battery_status_filtered" to mapOf("value" to "status"),
                    "android.event.battery_health_filtered" to mapOf("value" to "health"),
                    "android.event.battery_present_filtered" to mapOf("value" to "present"),
                    "android.event.battery_voltage_filtered" to mapOf(
                        "minMv" to "minVoltageMv",
                        "maxMv" to "maxVoltageMv",
                    ),
                ),
            )
        ) { feature, context ->
            context.event.typeId == "android.event.battery_changed" &&
                batteryProfileMatches(feature.config, context.event.payload)
        }
    }
}

internal fun batteryProfileMatches(config: ConfigMap, payload: ConfigMap): Boolean {
    val percent = payload["percent"].numberOrNull()
    if (!optionalBatteryRangeMatches(percent, config, "minPercent", "maxPercent", 0.0, 100.0)) return false

    val temperature = payload["temperatureC"].numberOrNull()
    if (!optionalBatteryRangeMatches(temperature, config, "minTemperatureC", "maxTemperatureC", -50.0, 150.0)) return false

    val voltage = payload["voltageMv"].numberOrNull()
    if (!optionalBatteryRangeMatches(voltage, config, "minVoltageMv", "maxVoltageMv", 0.0, 20_000.0)) return false

    return batteryChoiceMatches(config.string("powerSource", "any"), payload.string("plugged")) &&
        batteryChoiceMatches(config.string("status", "any"), payload.string("status")) &&
        batteryChoiceMatches(config.string("health", "any"), payload.string("health")) &&
        batteryTriStateMatches(config.string("present", "any"), payload.boolean("present"))
}

private fun optionalBatteryRangeMatches(
    actual: Double?,
    config: ConfigMap,
    minKey: String,
    maxKey: String,
    floor: Double,
    ceiling: Double,
): Boolean {
    val hasMin = config[minKey].numberOrNull() != null
    val hasMax = config[maxKey].numberOrNull() != null
    if (!hasMin && !hasMax) return true
    if (actual == null) return false
    val min = config[minKey].numberOrNull() ?: floor
    val max = config[maxKey].numberOrNull() ?: ceiling
    return min.isFinite() && max.isFinite() &&
        min in floor..ceiling && max in floor..ceiling &&
        min <= max && actual in min..max
}

private fun batteryChoiceMatches(expected: String, actual: String): Boolean =
    expected == "any" || expected == actual

private fun batteryTriStateMatches(mode: String, actual: Boolean): Boolean = when (mode) {
    "yes" -> actual
    "no" -> !actual
    else -> true
}

/** Compatibility name for the event payload; the mapping itself is owned by AndroidDeviceUtilityFeaturePack. */
internal fun batteryPluggedName(value: Int): String = chargingSourceName(value)
