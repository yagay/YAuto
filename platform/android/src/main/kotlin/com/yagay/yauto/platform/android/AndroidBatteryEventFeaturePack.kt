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

/** Detailed battery triggers derived from the existing ACTION_BATTERY_CHANGED runtime event. */
class AndroidBatteryEventFeaturePack : FeaturePack {
    override val id: String = "android.events.battery.detail"

    override fun install(registry: FeatureRegistry) {
        choiceEvent(
            registry,
            "android.event.battery_power_source_filtered",
            "Battery power source changed",
            "Trigger when the battery update reports the selected power source",
            "plugged",
            listOf("none", "ac", "usb", "wireless", "dock"),
            setOf("battery", "power", "charger", "usb", "wireless"),
        )
        choiceEvent(
            registry,
            "android.event.battery_status_filtered",
            "Battery charging status changed",
            "Trigger when the battery update reports the selected charging status",
            "status",
            listOf("charging", "discharging", "full", "not_charging", "unknown"),
            setOf("battery", "charging", "discharging", "full"),
        )
        choiceEvent(
            registry,
            "android.event.battery_health_filtered",
            "Battery health changed",
            "Trigger when the battery update reports the selected health state",
            "health",
            listOf("good", "overheat", "dead", "over_voltage", "failure", "cold", "unknown"),
            setOf("battery", "health", "temperature", "overheat", "cold"),
        )
        presentEvent(registry)
        voltageEvent(registry)
        profileEvent(registry)
    }

    private fun choiceEvent(
        registry: FeatureRegistry,
        featureId: String,
        title: String,
        description: String,
        payloadKey: String,
        options: List<String>,
        keywords: Set<String>,
    ) {
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = title,
                description = description,
                category = FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Choice("value", "Value", true, options)),
                keywords = keywords,
                ownerPackId = id,
            )
        ) { feature, context ->
            context.event.typeId == "android.event.battery_changed" &&
                context.event.payload.string(payloadKey) == feature.config.string("value", options.first())
        }
    }

    private fun presentEvent(registry: FeatureRegistry) {
        val featureId = "android.event.battery_present_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Battery presence changed",
                description = "Trigger on battery updates that match whether a physical battery is present",
                category = FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Choice("value", "Battery present", true, listOf("yes", "no"))),
                keywords = setOf("battery", "present", "hardware"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.battery_changed") return@registerEvent false
            val expected = feature.config.string("value", "yes") == "yes"
            context.event.payload.boolean("present") == expected
        }
    }

    private fun voltageEvent(registry: FeatureRegistry) {
        val featureId = "android.event.battery_voltage_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Battery voltage range",
                description = "Trigger when the reported battery voltage falls inside the configured millivolt range",
                category = FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("minMv", "Minimum millivolts", min = 0.0, max = 20_000.0),
                    FieldSchema.Number("maxMv", "Maximum millivolts", min = 0.0, max = 20_000.0),
                ),
                keywords = setOf("battery", "voltage", "millivolt"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.battery_changed") return@registerEvent false
            val actual = context.event.payload["voltageMv"].numberOrNull() ?: return@registerEvent false
            inRange(actual, feature.config, "minMv", "maxMv", 0.0, 20_000.0)
        }
    }

    private fun profileEvent(registry: FeatureRegistry) {
        val featureId = "android.event.battery_profile_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Filtered battery profile update",
                description = "Trigger on a battery update after combining charge, temperature, voltage, power-source, status, health and presence filters",
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
                keywords = setOf("battery", "profile", "charging", "temperature", "voltage", "health"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.battery_changed") return@registerEvent false
            val payload = context.event.payload

            val percent = payload["percent"].numberOrNull()
            if (!optionalRangeMatches(percent, feature.config, "minPercent", "maxPercent", 0.0, 100.0)) {
                return@registerEvent false
            }
            val temperature = payload["temperatureC"].numberOrNull()
            if (!optionalRangeMatches(temperature, feature.config, "minTemperatureC", "maxTemperatureC", -50.0, 150.0)) {
                return@registerEvent false
            }
            val voltage = payload["voltageMv"].numberOrNull()
            if (!optionalRangeMatches(voltage, feature.config, "minVoltageMv", "maxVoltageMv", 0.0, 20_000.0)) {
                return@registerEvent false
            }

            choiceMatches(feature.config.string("powerSource", "any"), payload.string("plugged")) &&
                choiceMatches(feature.config.string("status", "any"), payload.string("status")) &&
                choiceMatches(feature.config.string("health", "any"), payload.string("health")) &&
                triStateMatches(feature.config.string("present", "any"), payload.boolean("present"))
        }
    }

    private fun optionalRangeMatches(
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
        return actual != null && inRange(actual, config, minKey, maxKey, floor, ceiling)
    }

    private fun inRange(
        actual: Double,
        config: ConfigMap,
        minKey: String,
        maxKey: String,
        floor: Double,
        ceiling: Double,
    ): Boolean {
        val min = config[minKey].numberOrNull() ?: floor
        val max = config[maxKey].numberOrNull() ?: ceiling
        return min.isFinite() && max.isFinite() && min in floor..ceiling && max in floor..ceiling && min <= max && actual in min..max
    }

    private fun choiceMatches(expected: String, actual: String): Boolean = expected == "any" || expected == actual

    private fun triStateMatches(mode: String, actual: Boolean): Boolean = when (mode) {
        "yes" -> actual
        "no" -> !actual
        else -> true
    }
}

/** Compatibility name for the event payload; the mapping itself is owned by AndroidDeviceUtilityFeaturePack. */
internal fun batteryPluggedName(value: Int): String = chargingSourceName(value)
