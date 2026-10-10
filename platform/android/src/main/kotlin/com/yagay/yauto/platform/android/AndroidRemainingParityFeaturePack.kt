package com.yagay.yauto.platform.android

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.SensorPrivacyManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.CancellationSignal
import android.os.LocaleList
import android.os.PowerManager
import android.os.storage.StorageManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt

class AndroidRemainingParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.remaining_parity"
    internal val context = context.applicationContext
    internal val packages = this.context.packageManager
    internal val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    internal val telephony = this.context.getSystemService(TelephonyManager::class.java)
    internal val audio = this.context.getSystemService(AudioManager::class.java)
    internal val camera = this.context.getSystemService(CameraManager::class.java)
    internal val torch = TorchStateMonitor(this.context, camera)

    override fun install(registry: FeatureRegistry) {
        registerLocation(registry)
        registerNetwork(registry)
        registerTelephony(registry)
        registerKeyguard(registry)
        registerDeviceQueries(registry)
        registerPackageQueries(registry)
        registerAudioAndTorch(registry)
        registerSoundLevel(registry)
        registerSensorPrivacy(registry)
        registerExternalIntegration(registry)
        registerConfigurationAndSimEvents(registry)
        registerWeather(registry)
        registerWeatherEvent(registry)
        registerRuntimeStates(registry)
    }

    internal fun sensorPrivacyPair(
        registry: FeatureRegistry,
        manager: SensorPrivacyManager,
        sensor: Int,
        key: String,
        title: String,
    ) {
        val evaluator = ConditionEvaluator { feature, _ ->
            val actual = runCatching {
                if (!manager.supportsSensorToggle(sensor)) false
                else {
                    val oneArg = manager.javaClass.methods.firstOrNull {
                        it.name == "isSensorPrivacyEnabled" && it.parameterCount == 1
                    }
                    if (oneArg != null) {
                        oneArg.invoke(manager, sensor) as? Boolean ?: false
                    } else {
                        val twoArg = manager.javaClass.methods.firstOrNull {
                            it.name == "isSensorPrivacyEnabled" && it.parameterCount == 2
                        }
                        twoArg?.invoke(manager, 1, sensor) as? Boolean ?: false
                    }
                }
            }.getOrDefault(false)
            actual == feature.config.boolean("value", true)
        }
        val descriptor = FeatureDescriptor(
            FeatureId("android.state.sensor_privacy_$key"), FeatureKind.STATE,
            title, "Check Android sensor-privacy state for $key",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Toggle("value", "Blocked")),
            keywords = setOf("sensor privacy", key, "privacy", "camera", "microphone"),
            ownerPackId = id,
        )
        registry.registerState(descriptor, evaluator)
        registry.registerCondition(descriptor.copy(id = FeatureId("android.condition.sensor_privacy_$key"), kind = FeatureKind.CONDITION), evaluator)
    }

    internal fun privilegedBooleanAction(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        command: (Boolean) -> String,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.ACTION, title, description, FeatureCategory.SYSTEM,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled / blocked")),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("root", "shizuku", "sensor privacy", "system"),
                ownerPackId = id,
            )
        ) { feature, ctx -> privilegedShell(ctx, command(feature.config.boolean("enabled", true))) }
    }

    internal suspend fun privilegedShell(ctx: FeatureExecutionContext, command: String): ActionExecutionResult {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    internal fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    internal fun resultAction(
        registry: FeatureRegistry,
        typeId: String,
        title: String,
        description: String,
        category: FeatureCategory,
        value: suspend () -> ConfigValue,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.ACTION, title, description, category,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store result", true)),
                keywords = setOf("query", "info", "reference", "tasker", "shortx", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = runCatching { value() }.getOrElse {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
            store(feature, ctx, output)
        }
    }

    internal fun registerBooleanPair(
        registry: FeatureRegistry,
        stateId: String,
        conditionId: String,
        title: String,
        category: FeatureCategory,
        requirements: Set<AccessRequirement>,
        extraFields: List<FieldSchema> = emptyList(),
        query: (com.yagay.yauto.core.model.FeatureRef) -> Boolean,
    ) {
        val fields = extraFields + FieldSchema.Toggle("value", "Enabled / true")
        val evaluator = ConditionEvaluator { feature, _ ->
            runCatching { query(feature) }.getOrDefault(false) == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId(stateId), FeatureKind.STATE, title, "Check current Android state", category,
            fields = fields, accessRequirements = requirements, ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId(conditionId), kind = FeatureKind.CONDITION), evaluator)
    }

    internal fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    internal fun chooseProvider(manager: LocationManager, requested: String): String? {
        val preferred = when (requested) {
            "gps" -> LocationManager.GPS_PROVIDER
            "network" -> LocationManager.NETWORK_PROVIDER
            "passive" -> LocationManager.PASSIVE_PROVIDER
            else -> null
        }
        if (preferred != null && runCatching { manager.isProviderEnabled(preferred) }.getOrDefault(false)) return preferred
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    }

    internal suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (!hasLocationPermission()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            @Suppress("MissingPermission")
            runCatching {
                manager.getCurrentLocation(provider, signal, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    internal fun locationValue(location: Location): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
        mapOf(
            "latitude" to ConfigValue.NumberValue(location.latitude),
            "longitude" to ConfigValue.NumberValue(location.longitude),
            "altitude" to ConfigValue.NumberValue(location.altitude),
            "accuracy" to ConfigValue.NumberValue(location.accuracy.toDouble()),
            "speed" to ConfigValue.NumberValue(location.speed.toDouble()),
            "bearing" to ConfigValue.NumberValue(location.bearing.toDouble()),
            "provider" to ConfigValue.StringValue(location.provider.orEmpty()),
            "time" to ConfigValue.NumberValue(location.time.toDouble()),
        )
    )

    internal fun addressValue(address: Address): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
        mapOf(
            "latitude" to ConfigValue.NumberValue(address.latitude),
            "longitude" to ConfigValue.NumberValue(address.longitude),
            "featureName" to ConfigValue.StringValue(address.featureName.orEmpty()),
            "thoroughfare" to ConfigValue.StringValue(address.thoroughfare.orEmpty()),
            "subThoroughfare" to ConfigValue.StringValue(address.subThoroughfare.orEmpty()),
            "locality" to ConfigValue.StringValue(address.locality.orEmpty()),
            "subLocality" to ConfigValue.StringValue(address.subLocality.orEmpty()),
            "adminArea" to ConfigValue.StringValue(address.adminArea.orEmpty()),
            "postalCode" to ConfigValue.StringValue(address.postalCode.orEmpty()),
            "countryCode" to ConfigValue.StringValue(address.countryCode.orEmpty()),
            "countryName" to ConfigValue.StringValue(address.countryName.orEmpty()),
            "addressLine" to ConfigValue.StringValue(
                if (address.maxAddressLineIndex >= 0) {
                    (0..address.maxAddressLineIndex).mapNotNull { runCatching { address.getAddressLine(it) }.getOrNull() }.joinToString(", ")
                } else ""
            ),
        )
    )

    internal fun store(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
        value: ConfigValue,
    ): ActionExecutionResult {
        val name = feature.config.string("resultVariable").trim()
        if (name.isBlank()) return ActionExecutionResult(false, message = userText("feature.result_variable_empty"))
        ctx.variables.set(name, value)
        return ActionExecutionResult(true, value)
    }

    internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    internal fun parseExtras(raw: String): Map<String, String> = buildMap {
        raw.lineSequence().forEach { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index).trim()
            if (key.isBlank()) return@forEach
            put(key, line.substring(index + 1))
        }
    }
    private fun Double?.orZero(): Double = this ?: 0.0
}
