package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/** Workspace-driven continuous location events. Actual subscriptions are owned by ConfiguredLocationEventSource. */
class AndroidLocationEventFeaturePack : FeaturePack {
    override val id: String = "android.location.events"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.location_update"),
                FeatureKind.EVENT,
                "Location update",
                "Run when Android provides a location update that satisfies provider, accuracy, distance and interval filters",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("provider", "Provider", options = listOf("any", "gps", "network", "passive")),
                    FieldSchema.Number("maxAccuracyMeters", "Maximum accuracy radius (meters, 0 = any)", min = 0.0, max = 100_000.0),
                    FieldSchema.Number("minimumDistanceMeters", "Minimum movement (meters)", min = 0.0, max = 1_000_000.0),
                    FieldSchema.Duration("minimumIntervalMs", "Minimum time between updates"),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("location", "gps", "continuous", "position", "movement", "accuracy"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.location_update" &&
                ctx.event.payload.string("subscription") == locationSubscriptionKey(feature)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.geofence_transition"),
                FeatureKind.EVENT,
                "Geofence transition",
                "Run when the device enters or exits a configured circular location radius",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                    FieldSchema.Number("radiusMeters", "Radius meters", true, min = 1.0, max = 1_000_000.0),
                    FieldSchema.Choice("transition", "Transition", true, listOf("any", "enter", "exit")),
                    FieldSchema.Choice("provider", "Provider", options = listOf("any", "gps", "network", "passive")),
                    FieldSchema.Number("maxAccuracyMeters", "Maximum accuracy radius (meters, 0 = any)", min = 0.0, max = 100_000.0),
                    FieldSchema.Number("minimumDistanceMeters", "Location subscription movement (meters)", min = 0.0, max = 10_000.0),
                    FieldSchema.Duration("minimumIntervalMs", "Location subscription interval"),
                    FieldSchema.Toggle("emitInitial", "Emit current inside/outside state after subscription starts"),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("location", "geofence", "enter", "exit", "radius", "gps"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.geofence_transition") return@registerEvent false
            if (ctx.event.payload.string("subscription") != locationSubscriptionKey(feature)) return@registerEvent false
            val expected = feature.config.string("transition", "any")
            expected == "any" || ctx.event.payload.string("transition") == expected
        }
    }
}

internal fun locationSubscriptionKey(feature: FeatureRef): String = when (feature.typeId) {
    "android.event.geofence_transition" -> listOf(
        feature.typeId,
        feature.config["latitude"].numberOrNull()?.toString().orEmpty(),
        feature.config["longitude"].numberOrNull()?.toString().orEmpty(),
        feature.config["radiusMeters"].numberOrNull()?.toString().orEmpty(),
        feature.config.string("transition", "any"),
        feature.config.string("provider", "any"),
        feature.config["maxAccuracyMeters"].numberOrNull()?.toString() ?: "0.0",
        feature.config["minimumDistanceMeters"].numberOrNull()?.toString() ?: "25.0",
        feature.config.long("minimumIntervalMs", 15_000L).toString(),
        feature.config.boolean("emitInitial").toString(),
    ).joinToString("|")

    else -> listOf(
        feature.typeId,
        feature.config.string("provider", "any"),
        feature.config["maxAccuracyMeters"].numberOrNull()?.toString() ?: "0.0",
        feature.config["minimumDistanceMeters"].numberOrNull()?.toString() ?: "10.0",
        feature.config.long("minimumIntervalMs", 30_000L).toString(),
    ).joinToString("|")
}
