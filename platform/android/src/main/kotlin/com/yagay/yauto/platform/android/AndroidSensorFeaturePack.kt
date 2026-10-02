package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidSensorFeaturePack : FeaturePack {
    override val id = "android.sensors"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sensor_value"), FeatureKind.EVENT,
                "Sensor value", "Run when a selected hardware sensor value satisfies a comparison",
                FeatureCategory.DEVICE,
                fields = listOf(
                    sensorField(),
                    FieldSchema.Choice("axis", "Value", true, listOf("value", "x", "y", "z", "magnitude")),
                    FieldSchema.Choice("operator", "Operator", true, listOf(">", ">=", "<", "<=", "==", "!=")),
                    FieldSchema.Number("threshold", "Threshold", true),
                    FieldSchema.Duration("minimumIntervalMs", "Minimum time between matches"),
                ),
                keywords = setOf("sensor", "light", "proximity", "accelerometer", "gyroscope", "传感器"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sensor_value") return@registerEvent false
            val subscription = ctx.event.payload.string("subscription")
            if (subscription.isNotBlank() && subscription != sensorSubscriptionKey(feature)) return@registerEvent false
            if (ctx.event.payload.string("sensor") != feature.config.string("sensor", "light")) return@registerEvent false
            val axis = feature.config.string("axis", "value")
            val actual = ctx.event.payload[axis].numberOrNull() ?: return@registerEvent false
            val threshold = feature.config["threshold"].numberOrNull() ?: return@registerEvent false
            sensorComparison(actual, threshold, feature.config.string("operator", ">="))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.shake"), FeatureKind.EVENT,
                "Shake device", "Run when accelerometer linear magnitude exceeds a threshold",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("threshold", "Shake threshold", min = 1.0, max = 40.0),
                    FieldSchema.Duration("cooldownMs", "Minimum time between shakes"),
                ),
                keywords = setOf("shake", "accelerometer", "motion", "摇一摇"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.shake") return@registerEvent false
            val subscription = ctx.event.payload.string("subscription")
            if (subscription.isNotBlank() && subscription != sensorSubscriptionKey(feature)) return@registerEvent false
            val actual = ctx.event.payload["linearMagnitude"].numberOrNull() ?: return@registerEvent false
            actual >= (feature.config["threshold"].numberOrNull() ?: 5.0)
        }
    }

    private fun sensorField() = FieldSchema.Choice(
        "sensor", "Sensor", true,
        listOf("accelerometer", "gyroscope", "light", "proximity", "magnetic_field", "pressure")
    )
}

internal fun sensorComparison(left: Double, right: Double, operator: String): Boolean = when (operator) {
    ">" -> left > right
    ">=" -> left >= right
    "<" -> left < right
    "<=" -> left <= right
    "==" -> left == right
    "!=" -> left != right
    else -> false
}

internal fun sensorSubscriptionKey(feature: FeatureRef): String = when (feature.typeId) {
    "android.event.shake" -> listOf(
        feature.typeId,
        feature.config["threshold"].numberOrNull()?.toString() ?: "5.0",
        feature.config.long("cooldownMs", 1_000).toString(),
    ).joinToString("|")

    else -> listOf(
        feature.typeId,
        feature.config.string("sensor", "light"),
        feature.config.string("axis", "value"),
        feature.config.string("operator", ">="),
        feature.config["threshold"].numberOrNull()?.toString() ?: "0.0",
        feature.config.long("minimumIntervalMs", 500).toString(),
    ).joinToString("|")
}
