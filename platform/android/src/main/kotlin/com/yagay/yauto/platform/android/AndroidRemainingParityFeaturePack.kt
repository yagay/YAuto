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
    private val context = context.applicationContext
    private val packages = this.context.packageManager
    private val connectivity = this.context.getSystemService(ConnectivityManager::class.java)
    private val telephony = this.context.getSystemService(TelephonyManager::class.java)
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val camera = this.context.getSystemService(CameraManager::class.java)
    private val torch = TorchStateMonitor(this.context, camera)

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

    private fun registerLocation(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.current.query"), FeatureKind.ACTION,
                "Get current location", "Request a fresh or recent Android location without Google Play Services",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("provider", "Provider", options = listOf("best", "gps", "network", "passive")),
                    FieldSchema.Variable("resultVariable", "Store location object", true),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("location", "current location", "gps", "tasker", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!hasLocationPermission()) return@registerAction ActionExecutionResult(false, message = userText("feature.location_permission_required"))
            val manager = context.getSystemService(LocationManager::class.java)
            val provider = chooseProvider(manager, feature.config.string("provider", "best"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.location_unavailable"))
            val location = currentLocation(manager, provider)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.location_unavailable"))
            store(feature, ctx, locationValue(location))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.reverse_geocode"), FeatureKind.ACTION,
                "Reverse geocode", "Resolve latitude and longitude into an Android postal address",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                    FieldSchema.Variable("resultVariable", "Store address object", true),
                ),
                keywords = setOf("reverse geocode", "address", "latitude", "longitude", "location"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val lat = feature.config["latitude"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val lon = feature.config["longitude"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val address = withContext(Dispatchers.IO) {
                runCatching { Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull() }.getOrNull()
            } ?: return@registerAction ActionExecutionResult(false, message = userText("feature.geocode_failed"))
            store(feature, ctx, addressValue(address))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.forward_geocode"), FeatureKind.ACTION,
                "Geocode address", "Resolve an address or place name into coordinates",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("query", "Address / place", true),
                    FieldSchema.Variable("resultVariable", "Store address object", true),
                ),
                fieldBehaviors = mapOf("query" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("geocode", "address", "place", "coordinates", "location"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val query = feature.config.string("query").resolveVariables(ctx.variables).trim()
            if (query.isBlank()) return@registerAction ActionExecutionResult(false)
            val address = withContext(Dispatchers.IO) {
                runCatching { Geocoder(context, Locale.getDefault()).getFromLocationName(query, 1)?.firstOrNull() }.getOrNull()
            } ?: return@registerAction ActionExecutionResult(false, message = userText("feature.geocode_failed"))
            store(feature, ctx, addressValue(address))
        }
    }

    private fun registerNetwork(registry: FeatureRegistry) {
        resultAction(
            registry, "android.network.interfaces.query", "Query network interfaces",
            "Return local interfaces, addresses and interface state", FeatureCategory.NETWORK,
        ) {
            val result = runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().map { iface ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "name" to ConfigValue.StringValue(iface.name.orEmpty()),
                            "displayName" to ConfigValue.StringValue(iface.displayName.orEmpty()),
                            "up" to ConfigValue.BooleanValue(runCatching { iface.isUp }.getOrDefault(false)),
                            "loopback" to ConfigValue.BooleanValue(runCatching { iface.isLoopback }.getOrDefault(false)),
                            "virtual" to ConfigValue.BooleanValue(runCatching { iface.isVirtual }.getOrDefault(false)),
                            "mtu" to ConfigValue.NumberValue(runCatching { iface.mtu }.getOrDefault(0).toDouble()),
                            "addresses" to ConfigValue.ListValue(iface.inetAddresses.toList().map { ConfigValue.StringValue(it.hostAddress.orEmpty()) }),
                        )
                    )
                }
            }.getOrDefault(emptyList())
            ConfigValue.ListValue(result)
        }

        resultAction(
            registry, "android.network.active.details", "Active network details",
            "Return active transports, capabilities, DNS servers, routes and link addresses", FeatureCategory.NETWORK,
        ) {
            val network = connectivity.activeNetwork ?: return@resultAction ConfigValue.NullValue
            val caps = connectivity.getNetworkCapabilities(network)
            val link = connectivity.getLinkProperties(network)
            ConfigValue.ObjectValue(
                mapOf(
                    "validated" to ConfigValue.BooleanValue(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                    "metered" to ConfigValue.BooleanValue(connectivity.isActiveNetworkMetered),
                    "wifi" to ConfigValue.BooleanValue(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true),
                    "cellular" to ConfigValue.BooleanValue(caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true),
                    "ethernet" to ConfigValue.BooleanValue(caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true),
                    "vpn" to ConfigValue.BooleanValue(caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true),
                    "downKbps" to ConfigValue.NumberValue((caps?.linkDownstreamBandwidthKbps ?: 0).toDouble()),
                    "upKbps" to ConfigValue.NumberValue((caps?.linkUpstreamBandwidthKbps ?: 0).toDouble()),
                    "interfaceName" to ConfigValue.StringValue(link?.interfaceName.orEmpty()),
                    "dns" to ConfigValue.ListValue(link?.dnsServers.orEmpty().map { ConfigValue.StringValue(it.hostAddress.orEmpty()) }),
                    "addresses" to ConfigValue.ListValue(link?.linkAddresses.orEmpty().map { ConfigValue.StringValue(it.toString()) }),
                    "routes" to ConfigValue.ListValue(link?.routes.orEmpty().map { ConfigValue.StringValue(it.toString()) }),
                )
            )
        }
    }

    private fun registerTelephony(registry: FeatureRegistry) {
        resultAction(
            registry, "android.sim.defaults.query", "Query default SIM subscriptions",
            "Return Android default data, SMS and voice subscription IDs", FeatureCategory.NETWORK,
        ) {
            ConfigValue.ObjectValue(
                mapOf(
                    "dataSubscriptionId" to ConfigValue.NumberValue(SubscriptionManager.getDefaultDataSubscriptionId().toDouble()),
                    "smsSubscriptionId" to ConfigValue.NumberValue(SubscriptionManager.getDefaultSmsSubscriptionId().toDouble()),
                    "voiceSubscriptionId" to ConfigValue.NumberValue(SubscriptionManager.getDefaultVoiceSubscriptionId().toDouble()),
                    "defaultSubscriptionId" to ConfigValue.NumberValue(SubscriptionManager.getDefaultSubscriptionId().toDouble()),
                )
            )
        }

        privilegedBooleanAction(
            registry,
            "android.sensor_privacy.microphone.set",
            "Block microphone sensor",
            "Use Android sensor-privacy shell control to block or unblock microphone access",
        ) { enabled -> "cmd sensor_privacy " + (if (enabled) "enable" else "disable") + " 1 microphone" }

        privilegedBooleanAction(
            registry,
            "android.sensor_privacy.camera.set",
            "Block camera sensor",
            "Use Android sensor-privacy shell control to block or unblock camera access",
        ) { enabled -> "cmd sensor_privacy " + (if (enabled) "enable" else "disable") + " 1 camera" }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sim.default_data.set"), FeatureKind.ACTION,
                "Set default data SIM", "Set the default mobile-data subscription through Android's phone shell service when supported by the ROM",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Number("subscriptionId", "Subscription ID", true, min = 0.0)),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("sim", "default data", "subscription", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val subId = feature.config["subscriptionId"].numberOrNull()?.toInt()
                ?: return@registerAction ActionExecutionResult(false)
            privilegedShell(ctx, "cmd phone data set-default-subscription $subId")
        }
    }

    private fun registerKeyguard(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.keyguard.set"), FeatureKind.ACTION,
                "Set keyguard behavior",
                "Enable or disable lock-screen enforcement using Android locksettings through Root or Shizuku",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("mode", "Mode", true, listOf("enable", "disable", "lock_now", "dismiss")),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("keyguard", "lock screen", "locksettings", "macrodroid", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val command = when (feature.config.string("mode", "enable")) {
                "enable" -> "locksettings set-disabled false"
                "disable" -> "locksettings set-disabled true"
                "lock_now" -> "input keyevent 223"
                "dismiss" -> "wm dismiss-keyguard"
                else -> return@registerAction ActionExecutionResult(false)
            }
            privilegedShell(ctx, command)
        }
    }

    private fun registerDeviceQueries(registry: FeatureRegistry) {
        resultAction(registry, "android.device.features.query", "Query device features", "Return Android PackageManager system features", FeatureCategory.DEVICE) {
            ConfigValue.ListValue(packages.systemAvailableFeatures.orEmpty().map { feature ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "name" to ConfigValue.StringValue(feature.name.orEmpty()),
                        "version" to ConfigValue.NumberValue(feature.version.toDouble()),
                    )
                )
            })
        }

        resultAction(registry, "android.input.methods.query", "Query input methods", "Return installed Android input methods and services", FeatureCategory.DEVICE) {
            val manager = context.getSystemService(InputMethodManager::class.java)
            ConfigValue.ListValue(manager.inputMethodList.map { info ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "id" to ConfigValue.StringValue(info.id),
                        "package" to ConfigValue.StringValue(info.packageName),
                        "service" to ConfigValue.StringValue(info.serviceName),
                        "settingsActivity" to ConfigValue.StringValue(info.settingsActivity.orEmpty()),
                    )
                )
            })
        }

        resultAction(registry, "android.storage.volumes.query", "Query storage volumes", "Return mounted Android storage-volume details", FeatureCategory.FILE) {
            val manager = context.getSystemService(StorageManager::class.java)
            ConfigValue.ListValue(manager.storageVolumes.map { volume ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "description" to ConfigValue.StringValue(volume.getDescription(context)),
                        "state" to ConfigValue.StringValue(volume.state),
                        "directory" to ConfigValue.StringValue(volume.directory?.absolutePath.orEmpty()),
                        "uuid" to ConfigValue.StringValue(volume.uuid.orEmpty()),
                        "primary" to ConfigValue.BooleanValue(volume.isPrimary),
                        "removable" to ConfigValue.BooleanValue(volume.isRemovable),
                    )
                )
            })
        }

        resultAction(registry, "android.locale.list", "Query active locales", "Return the current ordered Android locale list", FeatureCategory.SYSTEM) {
            val list = LocaleList.getDefault()
            ConfigValue.ListValue((0 until list.size()).map { ConfigValue.StringValue(list[it].toLanguageTag()) })
        }

        resultAction(registry, "android.timezone.info", "Query time zone", "Return current time-zone identifiers and offsets", FeatureCategory.SYSTEM) {
            val zone = TimeZone.getDefault()
            val now = System.currentTimeMillis()
            ConfigValue.ObjectValue(
                mapOf(
                    "id" to ConfigValue.StringValue(zone.id),
                    "displayName" to ConfigValue.StringValue(zone.displayName),
                    "rawOffsetMs" to ConfigValue.NumberValue(zone.rawOffset.toDouble()),
                    "offsetMs" to ConfigValue.NumberValue(zone.getOffset(now).toDouble()),
                    "daylight" to ConfigValue.BooleanValue(zone.inDaylightTime(java.util.Date(now))),
                )
            )
        }

        resultAction(registry, "android.memory.runtime.query", "Query runtime memory", "Return Android memory pressure and available RAM", FeatureCategory.DEVICE) {
            val manager = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            ConfigValue.ObjectValue(
                mapOf(
                    "availableBytes" to ConfigValue.NumberValue(info.availMem.toDouble()),
                    "totalBytes" to ConfigValue.NumberValue(info.totalMem.toDouble()),
                    "thresholdBytes" to ConfigValue.NumberValue(info.threshold.toDouble()),
                    "lowMemory" to ConfigValue.BooleanValue(info.lowMemory),
                )
            )
        }

        resultAction(registry, "android.camera.devices.query", "Query camera devices", "Return camera IDs, lens facing and flash availability", FeatureCategory.DEVICE) {
            ConfigValue.ListValue(camera.cameraIdList.map { cameraId ->
                val c = camera.getCameraCharacteristics(cameraId)
                ConfigValue.ObjectValue(
                    mapOf(
                        "id" to ConfigValue.StringValue(cameraId),
                        "lensFacing" to ConfigValue.StringValue(
                            when (c.get(CameraCharacteristics.LENS_FACING)) {
                                CameraCharacteristics.LENS_FACING_FRONT -> "front"
                                CameraCharacteristics.LENS_FACING_BACK -> "back"
                                CameraCharacteristics.LENS_FACING_EXTERNAL -> "external"
                                else -> "unknown"
                            }
                        ),
                        "flash" to ConfigValue.BooleanValue(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true),
                    )
                )
            })
        }
    }

    private fun registerPackageQueries(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.package.permissions.query"), FeatureKind.ACTION,
                "Query app permissions", "Return requested permissions and current grant state for a package",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Variable("resultVariable", "Store permission list", true),
                ),
                keywords = setOf("package", "permissions", "app info", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").trim()
            val info = runCatching {
                @Suppress("DEPRECATION")
                packages.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            }.getOrNull() ?: return@registerAction ActionExecutionResult(false)
            val requested = info.requestedPermissions.orEmpty()
            val flags = info.requestedPermissionsFlags ?: IntArray(0)
            val value = ConfigValue.ListValue(requested.mapIndexed { index, permission ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "permission" to ConfigValue.StringValue(permission),
                        "granted" to ConfigValue.BooleanValue(
                            flags.getOrNull(index)?.and(android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                        ),
                    )
                )
            })
            store(feature, ctx, value)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.package.components.query"), FeatureKind.ACTION,
                "Query app components", "Return exported Activities, Services, Receivers and Providers declared by an app",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.AppPicker("package", "App / package", true),
                    FieldSchema.Variable("resultVariable", "Store component object", true),
                ),
                keywords = setOf("components", "activity", "service", "receiver", "provider", "package"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val pkg = feature.config.string("package").trim()
            val info = runCatching {
                @Suppress("DEPRECATION")
                packages.getPackageInfo(
                    pkg,
                    PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                        PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS,
                )
            }.getOrNull() ?: return@registerAction ActionExecutionResult(false)
            fun components(values: Array<out android.content.pm.ComponentInfo>?): ConfigValue =
                ConfigValue.ListValue(values.orEmpty().map { item ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "name" to ConfigValue.StringValue(item.name.orEmpty()),
                            "package" to ConfigValue.StringValue(item.packageName.orEmpty()),
                            "exported" to ConfigValue.BooleanValue(item.exported),
                            "enabled" to ConfigValue.BooleanValue(item.enabled),
                        )
                    )
                })
            store(
                feature,
                ctx,
                ConfigValue.ObjectValue(
                    mapOf(
                        "activities" to components(info.activities),
                        "services" to components(info.services),
                        "receivers" to components(info.receivers),
                        "providers" to components(info.providers),
                    )
                )
            )
        }

?: return@registerAction ActionExecutionResult(false, message = userText("feature.audio_measurement_failed"))
            store(feature, ctx, output)
        }
    }

    @Suppress("MissingPermission")
    private fun measureSoundLevel(sampleRate: Int, durationMs: Long): ConfigValue.ObjectValue {
        val min = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 2)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(min * 2)
            .build()
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            error("AudioRecord initialization failed")
        }
        val buffer = ShortArray(min)
        var sumSquares = 0.0
        var peak = 0
        var count = 0L
        val end = android.os.SystemClock.elapsedRealtime() + durationMs
        try {
            recorder.startRecording()
            while (android.os.SystemClock.elapsedRealtime() < end) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (index in 0 until read) {
                    val value = buffer[index].toInt()
                    val abs = kotlin.math.abs(value)
                    if (abs > peak) peak = abs
                    sumSquares += value.toDouble() * value.toDouble()
                }
                count += read
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        val rms = if (count > 0) sqrt(sumSquares / count) else 0.0
        val dbfs = if (rms > 0.0) 20.0 * log10(rms / Short.MAX_VALUE.toDouble()) else -120.0
        return ConfigValue.ObjectValue(
            mapOf(
                "rms" to ConfigValue.NumberValue(rms),
                "peak" to ConfigValue.NumberValue(peak.toDouble()),
                "dbfs" to ConfigValue.NumberValue(dbfs.coerceAtLeast(-120.0)),
                "sampleCount" to ConfigValue.NumberValue(count.toDouble()),
                "sampleRate" to ConfigValue.NumberValue(sampleRate.toDouble()),
            )
        )
    }

    private fun registerSensorPrivacy(registry: FeatureRegistry) {
        val manager = context.getSystemService(SensorPrivacyManager::class.java)
        sensorPrivacyPair(registry, manager, SensorPrivacyManager.Sensors.MICROPHONE, "microphone", "Microphone privacy blocked")
        sensorPrivacyPair(registry, manager, SensorPrivacyManager.Sensors.CAMERA, "camera", "Camera privacy blocked")
    }

    private fun registerConfigurationAndSimEvents(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.configuration_changed"), FeatureKind.EVENT,
                "Android configuration changed", "Run when Android configuration changes",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("orientation", "Orientation", options = listOf("any", "portrait", "landscape", "square", "undefined")),
                ),
                keywords = setOf("configuration", "orientation", "font scale", "ui mode"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.configuration_changed") return@registerEvent false
            val wanted = feature.config.string("orientation", "any")
            wanted == "any" || ctx.event.payload.string("orientation") == wanted
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.orientation_changed"), FeatureKind.EVENT,
                "Device orientation changed", "Run when Android reports portrait/landscape configuration changes",
                FeatureCategory.DISPLAY,
                fields = listOf(
                    FieldSchema.Choice("orientation", "Orientation", options = listOf("any", "portrait", "landscape", "square", "undefined")),
                ),
                keywords = setOf("orientation", "portrait", "landscape", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.orientation_changed") return@registerEvent false
            val wanted = feature.config.string("orientation", "any")
            wanted == "any" || ctx.event.payload.string("orientation") == wanted
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.sim_subscription_changed"), FeatureKind.EVENT,
                "SIM subscription changed", "Run when active SIMs or Android default data/SMS/voice subscriptions change",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("change", "Change type", options = listOf("any", "active_set", "default_data", "default_sms", "default_voice")),
                    FieldSchema.Number("subscriptionId", "Subscription ID (-1 = any)", min = -1.0),
                ),
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("sim", "subscription", "default data", "sim changed", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.sim_subscription_changed") return@registerEvent false
            val wantedChange = feature.config.string("change", "any")
            val changeMatches = when (wantedChange) {
                "active_set" -> ctx.event.payload.boolean("activeSetChanged")
                "default_data" -> ctx.event.payload.boolean("defaultDataChanged")
                "default_sms" -> ctx.event.payload.boolean("defaultSmsChanged")
                "default_voice" -> ctx.event.payload.boolean("defaultVoiceChanged")
                else -> true
            }
            if (!changeMatches) return@registerEvent false
            val wantedId = feature.config["subscriptionId"].numberOrNull()?.toInt() ?: -1
            if (wantedId < 0) return@registerEvent true
            val ids = (ctx.event.payload["activeIds"] as? ConfigValue.ListValue)?.value.orEmpty()
                .mapNotNull { (it as? ConfigValue.NumberValue)?.value?.toInt() }
            wantedId in ids ||
                (ctx.event.payload["defaultDataId"] as? ConfigValue.NumberValue)?.value?.toInt() == wantedId ||
                (ctx.event.payload["defaultSmsId"] as? ConfigValue.NumberValue)?.value?.toInt() == wantedId ||
                (ctx.event.payload["defaultVoiceId"] as? ConfigValue.NumberValue)?.value?.toInt() == wantedId
        }
    }

    private fun registerWeatherEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.weather_changed"), FeatureKind.EVENT,
                "Weather condition update",
                "Run when configured weather data updates and matches temperature, wind, humidity, condition or wind-direction filters",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                    FieldSchema.Duration("intervalMs", "Refresh interval"),
                    FieldSchema.Choice("metric", "Weather metric", true, listOf("any_update", "temperature", "wind_speed", "humidity", "condition", "wind_direction")),
                    FieldSchema.Choice("operator", "Comparison", options = listOf("any", "above", "below")),
                    FieldSchema.Number("value", "Threshold"),
                    FieldSchema.Choice("condition", "Weather condition", options = listOf("any", "clear", "cloudy", "rain", "thunder", "snow")),
                    FieldSchema.Number("directionMin", "Wind direction minimum °", min = 0.0, max = 360.0),
                    FieldSchema.Number("directionMax", "Wind direction maximum °", min = 0.0, max = 360.0),
                ),
                keywords = setOf("weather trigger", "temperature", "wind", "humidity", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.weather_changed") return@registerEvent false
            val lat = feature.config["latitude"].numberOrNull()
            val lon = feature.config["longitude"].numberOrNull()
            if (lat != null && kotlin.math.abs(ctx.event.payload["latitude"].numberOrNull().orZero() - lat) > 0.001) return@registerEvent false
            if (lon != null && kotlin.math.abs(ctx.event.payload["longitude"].numberOrNull().orZero() - lon) > 0.001) return@registerEvent false
            when (feature.config.string("metric", "any_update")) {
                "temperature" -> weatherCompare(ctx.event.payload["temperatureC"].numberOrNull(), feature)
                "wind_speed" -> weatherCompare(ctx.event.payload["windSpeedKmh"].numberOrNull(), feature)
                "humidity" -> weatherCompare(ctx.event.payload["humidityPercent"].numberOrNull(), feature)
                "condition" -> {
                    val wanted = feature.config.string("condition", "any")
                    wanted == "any" || ctx.event.payload.string("condition") == wanted
                }
                "wind_direction" -> {
                    val value = ctx.event.payload["windDirectionDeg"].numberOrNull() ?: return@registerEvent false
                    val min = feature.config["directionMin"].numberOrNull() ?: 0.0
                    val max = feature.config["directionMax"].numberOrNull() ?: 360.0
                    if (min <= max) value in min..max else value >= min || value <= max
                }
                else -> true
            }
        }
    }

    private fun weatherCompare(actual: Double?, feature: com.yagay.yauto.core.model.FeatureRef): Boolean {
        actual ?: return false
        val threshold = feature.config["value"].numberOrNull() ?: return false
        return when (feature.config.string("operator", "any")) {
            "above" -> actual >= threshold
            "below" -> actual <= threshold
            else -> true
        }
    }

    private fun registerWeather(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.weather.current"), FeatureKind.ACTION,
                "Get current weather", "Query current weather for coordinates using Open-Meteo without an API key",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("latitude", "Latitude", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("longitude", "Longitude", true, min = -180.0, max = 180.0),
                    FieldSchema.Variable("resultVariable", "Store weather object", true),
                ),
                keywords = setOf("weather", "temperature", "wind", "forecast", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val lat = feature.config["latitude"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val lon = feature.config["longitude"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val output = withContext(Dispatchers.IO) { fetchWeather(lat, lon) }
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.weather_query_failed"))
            store(feature, ctx, output)
        }
    }

    private fun fetchWeather(latitude: Double, longitude: Double): ConfigValue.ObjectValue? = runCatching {
        val url = URL(
            "https://api.open-meteo.com/v1/forecast?latitude=" + latitude +
                "&longitude=" + longitude +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,weather_code,wind_speed_10m,wind_direction_10m&timezone=auto"
        )
        val connection = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
        }
        try {
            if (connection.responseCode !in 200..299) return@runCatching null
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val current = root.optJSONObject("current") ?: return@runCatching null
            ConfigValue.ObjectValue(
                mapOf(
                    "latitude" to ConfigValue.NumberValue(root.optDouble("latitude", latitude)),
                    "longitude" to ConfigValue.NumberValue(root.optDouble("longitude", longitude)),
                    "timezone" to ConfigValue.StringValue(root.optString("timezone")),
                    "time" to ConfigValue.StringValue(current.optString("time")),
                    "temperatureC" to ConfigValue.NumberValue(current.optDouble("temperature_2m", Double.NaN)),
                    "apparentTemperatureC" to ConfigValue.NumberValue(current.optDouble("apparent_temperature", Double.NaN)),
                    "humidityPercent" to ConfigValue.NumberValue(current.optDouble("relative_humidity_2m", Double.NaN)),
                    "precipitationMm" to ConfigValue.NumberValue(current.optDouble("precipitation", 0.0)),
                    "weatherCode" to ConfigValue.NumberValue(current.optDouble("weather_code", -1.0)),
                    "windSpeedKmh" to ConfigValue.NumberValue(current.optDouble("wind_speed_10m", 0.0)),
                    "windDirectionDeg" to ConfigValue.NumberValue(current.optDouble("wind_direction_10m", 0.0)),
                )
            )
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun registerExternalIntegration(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.external.intent.invoke"), FeatureKind.ACTION,
                "Invoke external integration", "Start an Activity, Service or Broadcast in another app with simple string extras",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Choice("mode", "Mode", true, listOf("broadcast", "activity", "service")),
                    FieldSchema.Text("action", "Intent action", true),
                    FieldSchema.Text("package", "Target package"),
                    FieldSchema.Text("component", "Component package/class"),
                    FieldSchema.Text("dataUri", "Data URI"),
                    FieldSchema.Text("extras", "Extras, key=value per line", multiline = true),
                ),
                keywords = setOf("plugin", "intent", "tasker plugin", "external app", "broadcast", "service"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val action = feature.config.string("action").resolveVariables(ctx.variables).trim()
            if (action.isBlank()) return@registerAction ActionExecutionResult(false)
            val intent = Intent(action).apply {
                feature.config.string("package").resolveVariables(ctx.variables).trim().takeIf { it.isNotBlank() }?.let(::setPackage)
                feature.config.string("component").resolveVariables(ctx.variables).trim().takeIf { it.isNotBlank() }
                    ?.let(ComponentName::unflattenFromString)?.let(::setComponent)
                feature.config.string("dataUri").resolveVariables(ctx.variables).trim().takeIf { it.isNotBlank() }?.let { data = Uri.parse(it) }
                parseExtras(feature.config.string("extras").resolveVariables(ctx.variables)).forEach { (key, value) -> putExtra(key, value) }
            }
            runCatching {
                when (feature.config.string("mode", "broadcast")) {
                    "activity" -> context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    "service" -> context.startService(intent)
                    else -> context.sendBroadcast(intent)
                }
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wireguard.tunnel.set"), FeatureKind.ACTION,
                "Set WireGuard tunnel", "Ask the official WireGuard Android app to bring a named tunnel up or down",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("tunnel", "Tunnel name", true),
                    FieldSchema.Toggle("enabled", "Tunnel up"),
                ),
                keywords = setOf("wireguard", "vpn", "tunnel", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val tunnel = feature.config.string("tunnel").resolveVariables(ctx.variables).trim()
            if (tunnel.isBlank()) return@registerAction ActionExecutionResult(false)
            val action = if (feature.config.boolean("enabled", true)) {
                "com.wireguard.android.action.SET_TUNNEL_UP"
            } else {
                "com.wireguard.android.action.SET_TUNNEL_DOWN"
            }
            runCatching {
                context.sendBroadcast(Intent(action).setPackage("com.wireguard.android").putExtra("tunnel", tunnel))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }


    private fun registerRuntimeStates(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.mobile_data_changed"), FeatureKind.EVENT,
                "Mobile data changed", "Run when Android's global mobile-data setting changes",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Choice("state", "State", options = listOf("any", "enabled", "disabled"))),
                keywords = setOf("mobile data", "cellular data", "changed", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.mobile_data_changed") return@registerEvent false
            when (feature.config.string("state", "any")) {
                "enabled" -> ctx.event.payload.boolean("enabled")
                "disabled" -> !ctx.event.payload.boolean("enabled")
                else -> true
            }
        }


        val serviceFields = listOf(
            FieldSchema.AppPicker("package", "App / package", true),
            FieldSchema.Text("service", "Service class contains"),
            FieldSchema.Toggle("value", "Running"),
        )
        val serviceEvaluator = ConditionEvaluator { feature, ctx ->
            val pkg = feature.config.string("package").trim()
            if (!pkg.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) return@ConditionEvaluator false
            val service = feature.config.string("service").trim()
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "system.shell.execute",
                    payload = mapOf(
                        "command" to ConfigValue.StringValue("dumpsys activity services " + shellQuote(pkg)),
                        "timeoutMs" to ConfigValue.NumberValue(5_000.0),
                    ),
                )
            )
            val stdout = ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()
            val running = result.success &&
                stdout.contains(pkg, ignoreCase = false) &&
                (service.isBlank() || stdout.contains(service, ignoreCase = true))
            running == feature.config.boolean("value", true)
        }
        val serviceState = FeatureDescriptor(
            FeatureId("android.state.service_running"), FeatureKind.STATE,
            "Android service running",
            "Check a package/service in ActivityManager using Root or Shizuku dumpsys",
            FeatureCategory.APP,
            fields = serviceFields,
            capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
            implementationOptions = privilegedOptions(),
            keywords = setOf("service running", "background service", "dumpsys", "shortx", "root", "shizuku"),
            ownerPackId = id,
        )
        registry.registerState(serviceState, serviceEvaluator)
        registry.registerCondition(
            serviceState.copy(id = FeatureId("android.condition.service_running"), kind = FeatureKind.CONDITION),
            serviceEvaluator,
        )
    }

    private fun sensorPrivacyPair(
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

    private fun privilegedBooleanAction(
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

    private suspend fun privilegedShell(ctx: FeatureExecutionContext, command: String): ActionExecutionResult {
        val result = ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )
        return ActionExecutionResult(result.success, result.value, result.message)
    }

    private fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    private fun resultAction(
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

    private fun registerBooleanPair(
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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun chooseProvider(manager: LocationManager, requested: String): String? {
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

    private suspend fun currentLocation(manager: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { continuation ->
            if (!hasLocationPermission()) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            @Suppress("MissingPermission")
            runCatching {
                manager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }.onFailure {
                if (continuation.isActive) continuation.resume(null)
            }
        }

    private fun locationValue(location: Location): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
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

    private fun addressValue(address: Address): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
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

    private fun store(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
        value: ConfigValue,
    ): ActionExecutionResult {
        val name = feature.config.string("resultVariable").trim()
        if (name.isBlank()) return ActionExecutionResult(false, message = userText("feature.result_variable_empty"))
        ctx.variables.set(name, value)
        return ActionExecutionResult(true, value)
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun parseExtras(raw: String): Map<String, String> = buildMap {
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

private class TorchStateMonitor(context: Context, camera: CameraManager) {
    private val states = ConcurrentHashMap<String, Boolean>()

    init {
        runCatching {
            camera.registerTorchCallback(context.mainExecutor, object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    states[cameraId] = enabled
                }

                override fun onTorchModeUnavailable(cameraId: String) {
                    states[cameraId] = false
                }
            })
        }
    }

    fun enabled(cameraId: String): Boolean = states[cameraId] == true
    fun anyEnabled(): Boolean = states.values.any { it }
    fun value(): ConfigValue.ObjectValue = ConfigValue.ObjectValue(states.mapValues { ConfigValue.BooleanValue(it.value) })
}


class SubscriptionChangeEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.sim.subscriptions"
    private val context = context.applicationContext
    private val subscriptions = this.context.getSystemService(SubscriptionManager::class.java)
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null
    private var previous: Snapshot? = null

    private data class Snapshot(
        val activeIds: List<Int>,
        val dataId: Int,
        val smsId: Int,
        val voiceId: Int,
    )

    private val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
        override fun onSubscriptionsChanged() {
            val next = snapshot()
            val old = previous
            previous = next
            if (old == null || old == next) return
            emitter?.emit(
                com.yagay.yauto.core.model.RuntimeEvent(
                    typeId = "android.event.sim_subscription_changed",
                    payload = mapOf(
                        "activeIds" to ConfigValue.ListValue(next.activeIds.map { ConfigValue.NumberValue(it.toDouble()) }),
                        "defaultDataId" to ConfigValue.NumberValue(next.dataId.toDouble()),
                        "defaultSmsId" to ConfigValue.NumberValue(next.smsId.toDouble()),
                        "defaultVoiceId" to ConfigValue.NumberValue(next.voiceId.toDouble()),
                        "activeSetChanged" to ConfigValue.BooleanValue(old.activeIds != next.activeIds),
                        "defaultDataChanged" to ConfigValue.BooleanValue(old.dataId != next.dataId),
                        "defaultSmsChanged" to ConfigValue.BooleanValue(old.smsId != next.smsId),
                        "defaultVoiceChanged" to ConfigValue.BooleanValue(old.voiceId != next.voiceId),
                    ),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        previous = snapshot()
        runCatching { subscriptions.addOnSubscriptionsChangedListener(context.mainExecutor, listener) }
            .onFailure {
                started.set(false)
                this.emitter = null
                previous = null
                throw it
            }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { subscriptions.removeOnSubscriptionsChangedListener(listener) }
        emitter = null
        previous = null
    }

    private fun snapshot(): Snapshot {
        val active = runCatching {
            subscriptions.activeSubscriptionInfoList.orEmpty().map { it.subscriptionId }.sorted()
        }.getOrDefault(emptyList())
        return Snapshot(
            activeIds = active,
            dataId = SubscriptionManager.getDefaultDataSubscriptionId(),
            smsId = SubscriptionManager.getDefaultSmsSubscriptionId(),
            voiceId = SubscriptionManager.getDefaultVoiceSubscriptionId(),
        )
    }

    private companion object {
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}
