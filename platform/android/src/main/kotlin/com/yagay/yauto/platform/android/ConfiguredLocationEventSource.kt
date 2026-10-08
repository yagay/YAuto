package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Subscribes to LocationManager only while enabled workspace automations actually use a continuous
 * location or geofence event. Rules are refreshed without restarting the runtime service.
 */
class ConfiguredLocationEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource, LocationListener {
    override val id: String = "android.location.configured"
    private val context = context.applicationContext
    private val manager = context.applicationContext.getSystemService(LocationManager::class.java)
    private val runtimePrefs = this.context.getSharedPreferences(
        AndroidMacroDroidSystemParityFeaturePack.PREFS,
        Context.MODE_PRIVATE,
    )
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thread = HandlerThread("YAutoLocation")
    private var handler: Handler? = null
    private var refreshJob: Job? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null
    @Volatile private var updateRules: List<LocationUpdateRule> = emptyList()
    @Volatile private var geofenceRules: List<GeofenceRule> = emptyList()
    @Volatile private var subscriptionSignature: String = ""
    private val lastUpdateAt = ConcurrentHashMap<String, Long>()
    private val lastUpdateLocation = ConcurrentHashMap<String, LocationPoint>()
    private val geofenceState = ConcurrentHashMap<String, Boolean>()

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        thread.start()
        handler = Handler(thread.looper)
        refreshJob = scope.launch {
            while (isActive && started.get()) {
                refreshRules()
                delay(5_000)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        refreshJob?.cancel()
        refreshJob = null
        scope.cancel()
        runCatching { manager.removeUpdates(this) }
        thread.quitSafely()
        emitter = null
        updateRules = emptyList()
        geofenceRules = emptyList()
        subscriptionSignature = ""
        lastUpdateAt.clear()
        lastUpdateLocation.clear()
        geofenceState.clear()
    }

    private suspend fun refreshRules() {
        val features = runCatching {
            workspace.load().automations.asSequence()
                .filter { it.enabled }
                .flatMap { it.activation.events.asSequence() }
                .filter { it.typeId == "android.event.location_update" || it.typeId == "android.event.geofence_transition" }
                .toList()
        }.getOrDefault(emptyList())

        val nextUpdates = features.filter { it.typeId == "android.event.location_update" }
            .mapNotNull(::toUpdateRule)
            .distinctBy { it.key }
        val nextGeofences = features.filter { it.typeId == "android.event.geofence_transition" }
            .mapNotNull(::toGeofenceRule)
            .distinctBy { it.key }
        updateRules = nextUpdates
        geofenceRules = nextGeofences

        val activeKeys = (nextUpdates.map { it.key } + nextGeofences.map { it.key }).toSet()
        lastUpdateAt.keys.removeIf { it !in activeKeys }
        lastUpdateLocation.keys.removeIf { it !in activeKeys }
        geofenceState.keys.removeIf { it !in activeKeys }

        val all = nextUpdates.map { it.subscription } + nextGeofences.map { it.subscription }
        val nextSignature = all.sortedBy { it.key }.joinToString(";") { it.key }
        if (nextSignature == subscriptionSignature) return
        subscriptionSignature = nextSignature
        reconfigureSubscriptions(all)
    }

    private fun reconfigureSubscriptions(rules: List<LocationSubscription>) {
        handler?.post {
            runCatching { manager.removeUpdates(this@ConfiguredLocationEventSource) }
            if (rules.isEmpty() || !hasLocationPermission()) return@post

            val minTime = rules.minOfOrNull { it.intervalMs }?.coerceIn(1_000L, 3_600_000L) ?: 30_000L
            val minDistance = rules.minOfOrNull { it.distanceMeters }?.coerceIn(0f, 100_000f) ?: 10f
            val providers = rules.flatMap { rule -> providersFor(rule.provider) }.distinct()
            providers.forEach { provider ->
                if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false) && provider != LocationManager.PASSIVE_PROVIDER) return@forEach
                try {
                    @Suppress("DEPRECATION")
                    manager.requestLocationUpdates(provider, minTime, minDistance, this@ConfiguredLocationEventSource, thread.looper)
                } catch (_: SecurityException) {
                    // Permission can change while the service is running; refresh will retry after it is granted.
                } catch (_: IllegalArgumentException) {
                    // Provider is not present on this device.
                }
            }
        }
    }

    override fun onLocationChanged(location: Location) {
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val provider = normalizeProvider(location.provider)
        val point = LocationPoint(location.latitude, location.longitude, location.time)

        updateRules.forEach { rule ->
            if (!providerMatches(rule.subscription.provider, provider)) return@forEach
            if (!accuracyMatches(rule.subscription.maxAccuracyMeters, location)) return@forEach
            val previousAt = lastUpdateAt[rule.key]
            if (previousAt != null && nowElapsed - previousAt < rule.subscription.intervalMs) return@forEach
            val previousLocation = lastUpdateLocation[rule.key]
            if (previousLocation != null && rule.subscription.distanceMeters > 0f) {
                val moved = distanceMeters(previousLocation.latitude, previousLocation.longitude, location.latitude, location.longitude)
                if (moved < rule.subscription.distanceMeters) return@forEach
            }
            lastUpdateAt[rule.key] = nowElapsed
            lastUpdateLocation[rule.key] = point
            emitter?.emit(RuntimeEvent("android.event.location_update", locationPayload(location, rule.key), source = id))
        }

        geofenceRules.forEach { rule ->
            if (!providerMatches(rule.subscription.provider, provider)) return@forEach
            if (!accuracyMatches(rule.subscription.maxAccuracyMeters, location)) return@forEach
            val distance = distanceMeters(location.latitude, location.longitude, rule.latitude, rule.longitude)
            val inside = distance <= rule.radiusMeters
            val previous = geofenceState.put(rule.key, inside)
            val transition = when {
                previous == null && rule.emitInitial -> if (inside) "enter" else "exit"
                previous == null -> null
                !previous && inside -> "enter"
                previous && !inside -> "exit"
                else -> null
            } ?: return@forEach
            emitter?.emit(
                RuntimeEvent(
                    "android.event.geofence_transition",
                    locationPayload(location, rule.key) + mapOf(
                        "transition" to ConfigValue.StringValue(transition),
                        "inside" to ConfigValue.BooleanValue(inside),
                        "distanceMeters" to ConfigValue.NumberValue(distance),
                        "radiusMeters" to ConfigValue.NumberValue(rule.radiusMeters),
                        "targetLatitude" to ConfigValue.NumberValue(rule.latitude),
                        "targetLongitude" to ConfigValue.NumberValue(rule.longitude),
                    ),
                    source = id,
                )
            )
        }
    }

    @Deprecated("Deprecated in Android SDK; retained for LocationListener compatibility")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun toUpdateRule(feature: FeatureRef): LocationUpdateRule? {
        val subscription = subscription(feature, defaultIntervalMs = 30_000L, defaultDistanceMeters = 10.0) ?: return null
        return LocationUpdateRule(locationSubscriptionKey(feature), subscription)
    }

    private fun toGeofenceRule(feature: FeatureRef): GeofenceRule? {
        val latitude = feature.config["latitude"].numberOrNull() ?: return null
        val longitude = feature.config["longitude"].numberOrNull() ?: return null
        val radius = feature.config["radiusMeters"].numberOrNull() ?: return null
        if (!latitude.isFinite() || latitude !in -90.0..90.0) return null
        if (!longitude.isFinite() || longitude !in -180.0..180.0) return null
        if (!radius.isFinite() || radius !in 1.0..1_000_000.0) return null
        val subscription = subscription(feature, defaultIntervalMs = 15_000L, defaultDistanceMeters = 25.0) ?: return null
        return GeofenceRule(
            key = locationSubscriptionKey(feature),
            subscription = subscription,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radius,
            emitInitial = feature.config.boolean("emitInitial"),
        )
    }

    private fun subscription(feature: FeatureRef, defaultIntervalMs: Long, defaultDistanceMeters: Double): LocationSubscription? {
        val provider = feature.config.string("provider", "any")
        if (provider !in setOf("any", "gps", "network", "passive")) return null
        val configuredInterval = feature.config.long("minimumIntervalMs", defaultIntervalMs)
        val globalInterval = runtimePrefs.getLong(
            AndroidMacroDroidSystemParityFeaturePack.KEY_LOCATION_INTERVAL,
            -1L,
        )
        val interval = (if (globalInterval > 0L) globalInterval else configuredInterval)
            .coerceIn(1_000L, 3_600_000L)
        val configuredDistance =
            (feature.config["minimumDistanceMeters"].numberOrNull() ?: defaultDistanceMeters)
        val globalDistance = if (
            runtimePrefs.contains(AndroidMacroDroidSystemParityFeaturePack.KEY_LOCATION_DISTANCE)
        ) runtimePrefs.getFloat(
            AndroidMacroDroidSystemParityFeaturePack.KEY_LOCATION_DISTANCE,
            configuredDistance.toFloat(),
        ).toDouble() else configuredDistance
        val distance = globalDistance.coerceIn(0.0, 100_000.0)
        val maxAccuracy = (feature.config["maxAccuracyMeters"].numberOrNull() ?: 0.0).coerceIn(0.0, 100_000.0)
        return LocationSubscription(locationSubscriptionKey(feature), provider, interval, distance.toFloat(), maxAccuracy.toFloat())
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providersFor(provider: String): List<String> = when (provider) {
        "gps" -> listOf(LocationManager.GPS_PROVIDER)
        "network" -> listOf(LocationManager.NETWORK_PROVIDER)
        "passive" -> listOf(LocationManager.PASSIVE_PROVIDER)
        else -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
    }

    private fun normalizeProvider(provider: String?): String = when (provider) {
        LocationManager.GPS_PROVIDER -> "gps"
        LocationManager.NETWORK_PROVIDER -> "network"
        LocationManager.PASSIVE_PROVIDER -> "passive"
        else -> provider.orEmpty()
    }

    private fun providerMatches(expected: String, actual: String): Boolean = expected == "any" || expected == actual
    private fun accuracyMatches(maxAccuracy: Float, location: Location): Boolean =
        maxAccuracy <= 0f || !location.hasAccuracy() || location.accuracy <= maxAccuracy

    private fun locationPayload(location: Location, subscription: String): Map<String, ConfigValue> = buildMap {
        put("subscription", ConfigValue.StringValue(subscription))
        put("latitude", ConfigValue.NumberValue(location.latitude))
        put("longitude", ConfigValue.NumberValue(location.longitude))
        put("provider", ConfigValue.StringValue(normalizeProvider(location.provider)))
        put("timestampEpochMs", ConfigValue.NumberValue(location.time.toDouble()))
        if (location.hasAccuracy()) put("accuracyMeters", ConfigValue.NumberValue(location.accuracy.toDouble()))
        if (location.hasAltitude()) put("altitudeMeters", ConfigValue.NumberValue(location.altitude))
        if (location.hasSpeed()) put("speedMetersPerSecond", ConfigValue.NumberValue(location.speed.toDouble()))
        if (location.hasBearing()) put("bearingDegrees", ConfigValue.NumberValue(location.bearing.toDouble()))
    }

    private data class LocationUpdateRule(val key: String, val subscription: LocationSubscription)
    private data class GeofenceRule(
        val key: String,
        val subscription: LocationSubscription,
        val latitude: Double,
        val longitude: Double,
        val radiusMeters: Double,
        val emitInitial: Boolean,
    )
    private data class LocationSubscription(
        val key: String,
        val provider: String,
        val intervalMs: Long,
        val distanceMeters: Float,
        val maxAccuracyMeters: Float,
    )
    private data class LocationPoint(val latitude: Double, val longitude: Double, val timestampEpochMs: Long)
}
