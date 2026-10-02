package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume
import com.yagay.yauto.core.model.userText

class AndroidDeviceDataFeaturePack(context: Context) : FeaturePack {
    override val id = "android.device.data"
    private val context = context.applicationContext
    private val sensors = context.applicationContext.getSystemService(SensorManager::class.java)
    private val locations = context.applicationContext.getSystemService(LocationManager::class.java)

    override fun install(registry: FeatureRegistry) {
        deviceInfo(registry)
        storageInfo(registry)
        sensorRead(registry)
        lastKnownLocation(registry)
        locationRadius(registry, FeatureKind.STATE, "android.state.location.radius")
        locationRadius(registry, FeatureKind.CONDITION, "android.condition.location.radius")
    }

    private fun deviceInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.device.info"), FeatureKind.ACTION, "Get device information",
                "Store common Android build/device properties in an object variable", FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store object in variable", true)),
                keywords = setOf("device", "model", "sdk", "build"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ObjectValue(mapOf(
                "manufacturer" to ConfigValue.StringValue(Build.MANUFACTURER),
                "brand" to ConfigValue.StringValue(Build.BRAND),
                "model" to ConfigValue.StringValue(Build.MODEL),
                "device" to ConfigValue.StringValue(Build.DEVICE),
                "product" to ConfigValue.StringValue(Build.PRODUCT),
                "sdk" to ConfigValue.NumberValue(Build.VERSION.SDK_INT.toDouble()),
                "release" to ConfigValue.StringValue(Build.VERSION.RELEASE.orEmpty()),
                "fingerprint" to ConfigValue.StringValue(Build.FINGERPRINT),
                "abis" to ConfigValue.ListValue(Build.SUPPORTED_ABIS.map(ConfigValue::StringValue)),
            ))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun storageInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.storage.info"), FeatureKind.ACTION, "Get storage information",
                "Read total/free/available bytes for a filesystem path", FeatureCategory.FILE,
                fields = listOf(FieldSchema.Text("path", "Path"), FieldSchema.Variable("resultVariable", "Store object in variable", true)),
                keywords = setOf("storage", "disk", "free space"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val path = feature.config.string("path").resolveVariables(ctx.variables).ifBlank { context.filesDir.absolutePath }
            runCatching {
                val stat = StatFs(File(path).absolutePath)
                val output = ConfigValue.ObjectValue(mapOf(
                    "path" to ConfigValue.StringValue(path),
                    "totalBytes" to ConfigValue.NumberValue(stat.totalBytes.toDouble()),
                    "freeBytes" to ConfigValue.NumberValue(stat.freeBytes.toDouble()),
                    "availableBytes" to ConfigValue.NumberValue(stat.availableBytes.toDouble()),
                ))
                ctx.variables.set(feature.config.string("resultVariable"), output)
                ActionExecutionResult(true, output)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun sensorRead(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sensor.read"), FeatureKind.ACTION, "Read sensor once",
                "Capture one value from a common Android hardware sensor", FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("sensor", "Sensor", true, listOf("accelerometer", "gyroscope", "light", "proximity", "magnetic_field", "pressure")),
                    FieldSchema.Duration("timeoutMs", "Timeout"),
                    FieldSchema.Variable("resultVariable", "Store object in variable", true),
                ), keywords = setOf("sensor", "accelerometer", "light", "proximity"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val type = when (feature.config.string("sensor", "accelerometer")) {
                "gyroscope" -> Sensor.TYPE_GYROSCOPE
                "light" -> Sensor.TYPE_LIGHT
                "proximity" -> Sensor.TYPE_PROXIMITY
                "magnetic_field" -> Sensor.TYPE_MAGNETIC_FIELD
                "pressure" -> Sensor.TYPE_PRESSURE
                else -> Sensor.TYPE_ACCELEROMETER
            }
            val sensor = sensors.getDefaultSensor(type)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.sensor_unavailable"))
            val timeout = feature.config.long("timeoutMs", 5_000).coerceIn(250, 30_000)
            val output = readSensor(sensor, timeout)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.sensor_timeout"))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun lastKnownLocation(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.last_known"), FeatureKind.ACTION, "Get last known location",
                "Read the newest last-known GPS/network location without starting continuous background tracking",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("provider", "Provider", true, listOf("any", "gps", "network")),
                    FieldSchema.Variable("resultVariable", "Store location object", true),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("location", "gps", "coordinates"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val location = runCatching { bestLastLocation(feature.config.string("provider", "any")) }.getOrNull()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.location_unavailable"))
            val output = locationValue(location)
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun locationRadius(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, "Within location radius",
            "Compare the newest last-known location with a latitude/longitude radius", FeatureCategory.DEVICE,
            fields = listOf(
                FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                FieldSchema.Number("radiusMeters", "Radius (metres)", true, min = 1.0),
                FieldSchema.Choice("provider", "Provider", true, listOf("any", "gps", "network")),
                FieldSchema.Duration("maxAgeMs", "Maximum location age"),
            ),
            accessRequirements = setOf(AccessRequirement.LOCATION),
            keywords = setOf("location", "radius", "geofence", "gps"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val location = runCatching { bestLastLocation(feature.config.string("provider", "any")) }.getOrNull() ?: return@ConditionEvaluator false
            val maxAge = feature.config.long("maxAgeMs", 3_600_000).coerceAtLeast(0)
            if (maxAge > 0 && System.currentTimeMillis() - location.time > maxAge) return@ConditionEvaluator false
            val targetLat = feature.config["latitude"].numberOrNull() ?: return@ConditionEvaluator false
            val targetLon = feature.config["longitude"].numberOrNull() ?: return@ConditionEvaluator false
            val radius = feature.config["radiusMeters"].numberOrNull() ?: return@ConditionEvaluator false
            val result = FloatArray(1)
            Location.distanceBetween(location.latitude, location.longitude, targetLat, targetLon, result)
            result[0] <= radius
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private suspend fun readSensor(sensor: Sensor, timeoutMs: Long): ConfigValue.ObjectValue? = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        sensors.unregisterListener(this)
                        if (continuation.isActive) continuation.resume(
                            ConfigValue.ObjectValue(mapOf(
                                "name" to ConfigValue.StringValue(sensor.name),
                                "type" to ConfigValue.NumberValue(sensor.type.toDouble()),
                                "accuracy" to ConfigValue.NumberValue(event.accuracy.toDouble()),
                                "values" to ConfigValue.ListValue(event.values.map { ConfigValue.NumberValue(it.toDouble()) }),
                                "timestampNs" to ConfigValue.NumberValue(event.timestamp.toDouble()),
                            ))
                        )
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                }
                if (!sensors.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)) {
                    continuation.resume(null)
                } else continuation.invokeOnCancellation { sensors.unregisterListener(listener) }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("MissingPermission")
    private fun bestLastLocation(provider: String): Location? {
        if (!hasLocationPermission()) return null
        val candidates = when (provider) {
            "gps" -> listOf(LocationManager.GPS_PROVIDER)
            "network" -> listOf(LocationManager.NETWORK_PROVIDER)
            else -> listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        }
        return candidates.mapNotNull { name -> runCatching { locations.getLastKnownLocation(name) }.getOrNull() }.maxByOrNull { it.time }
    }

    private fun locationValue(location: Location) = ConfigValue.ObjectValue(mapOf(
        "latitude" to ConfigValue.NumberValue(location.latitude),
        "longitude" to ConfigValue.NumberValue(location.longitude),
        "accuracy" to ConfigValue.NumberValue(location.accuracy.toDouble()),
        "provider" to ConfigValue.StringValue(location.provider.orEmpty()),
        "time" to ConfigValue.NumberValue(location.time.toDouble()),
        "altitude" to ConfigValue.NumberValue(if (location.hasAltitude()) location.altitude else 0.0),
        "speed" to ConfigValue.NumberValue(if (location.hasSpeed()) location.speed.toDouble() else 0.0),
        "bearing" to ConfigValue.NumberValue(if (location.hasBearing()) location.bearing.toDouble() else 0.0),
    ))
}
