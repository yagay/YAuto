package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.registry.*
import kotlin.math.*

/** Low-power radius matching from Android's latest known location; it does not start continuous GPS tracking. */
class AndroidLocationRadiusFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.location.radius"
    private val context = context.applicationContext
    private val location = context.applicationContext.getSystemService(LocationManager::class.java)

    override fun install(registry: FeatureRegistry) {
        register(registry, FeatureKind.STATE, "android.state.location_radius")
        register(registry, FeatureKind.CONDITION, "android.condition.location_radius")
    }

    private fun register(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Location radius", "Check whether the latest known device location is inside or outside a radius",
            FeatureCategory.LOCATION,
            fields = listOf(
                FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                FieldSchema.Number("radiusMeters", "Radius meters", true, min = 1.0, max = 1_000_000.0),
                FieldSchema.Toggle("inside", "Inside radius"),
                FieldSchema.Number("maxAgeMinutes", "Maximum location age (minutes, 0 = any)", min = 0.0, max = 10_080.0),
            ),
            accessRequirements = setOf(AccessRequirement.LOCATION),
            keywords = setOf("location", "radius", "geofence", "inside", "outside", "distance"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val snapshot = latestKnownLocation() ?: return@ConditionEvaluator false
            matchesLocationRadius(feature.config, snapshot, System.currentTimeMillis())
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun latestKnownLocation(): LocationSnapshot? {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return null
        return runCatching {
            location.getProviders(true)
                .mapNotNull { provider -> runCatching { location.getLastKnownLocation(provider) }.getOrNull() }
                .maxByOrNull { it.time }
                ?.let { LocationSnapshot(it.latitude, it.longitude, it.time) }
        }.getOrNull()
    }
}

internal data class LocationSnapshot(val latitude: Double, val longitude: Double, val timestampEpochMs: Long)

internal fun matchesLocationRadius(config: ConfigMap, current: LocationSnapshot, nowEpochMs: Long): Boolean {
    val latitude = config["latitude"].numberOrNull() ?: return false
    val longitude = config["longitude"].numberOrNull() ?: return false
    val radius = config["radiusMeters"].numberOrNull() ?: return false
    if (!latitude.isFinite() || latitude !in -90.0..90.0) return false
    if (!longitude.isFinite() || longitude !in -180.0..180.0) return false
    if (!radius.isFinite() || radius !in 1.0..1_000_000.0) return false
    val maxAgeMinutes = config["maxAgeMinutes"].numberOrNull() ?: 0.0
    if (!maxAgeMinutes.isFinite() || maxAgeMinutes !in 0.0..10_080.0) return false
    if (maxAgeMinutes > 0.0) {
        val ageMs = (nowEpochMs - current.timestampEpochMs).coerceAtLeast(0L)
        if (ageMs > maxAgeMinutes * 60_000.0) return false
    }
    val inside = distanceMeters(current.latitude, current.longitude, latitude, longitude) <= radius
    return inside == config.boolean("inside", true)
}

internal fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val lat1Rad = Math.toRadians(lat1)
    val lat2Rad = Math.toRadians(lat2)
    val deltaLat = Math.toRadians(lat2 - lat1)
    val deltaLon = Math.toRadians(lon2 - lon1)
    val a = sin(deltaLat / 2).pow(2) + cos(lat1Rad) * cos(lat2Rad) * sin(deltaLon / 2).pow(2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0
