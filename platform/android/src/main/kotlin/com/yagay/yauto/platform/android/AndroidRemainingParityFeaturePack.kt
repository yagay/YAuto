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
import android.media.AudioManager
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
import java.net.NetworkInterface
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

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
        registerDeviceQueries(registry)
        registerPackageQueries(registry)
        registerAudioAndTorch(registry)
        registerSensorPrivacy(registry)
        registerExternalIntegration(registry)
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
            } ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "geocoder"))
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
            } ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "geocoder"))
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

        registerBooleanPair(
            registry,
            "android.state.mobile_data_enabled",
            "android.condition.mobile_data_enabled",
            "Mobile data enabled",
            FeatureCategory.NETWORK,
            setOf(AccessRequirement.PHONE),
        ) { _ -> runCatching { telephony.isDataEnabled }.getOrDefault(false) }
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
            val flags = info.requestedPermissionsFlags.orEmpty()
            val value = ConfigValue.ListValue(requested.mapIndexed { index, permission ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "permission" to ConfigValue.StringValue(permission),
                        "granted" to ConfigValue.BooleanValue(
                            flags.getOrNull(index)?.and(PackageManager.REQUESTED_PERMISSION_GRANTED) != 0
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
                            "permission" to ConfigValue.StringValue(item.permission.orEmpty()),
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

        registerBooleanPair(
            registry,
            "android.state.battery_optimization_ignored",
            "android.condition.battery_optimization_ignored",
            "Battery optimization ignored",
            FeatureCategory.APP,
            emptySet(),
            extraFields = listOf(FieldSchema.AppPicker("package", "App / package", true)),
        ) { feature ->
            val pkg = feature.config.string("package").trim()
            context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(pkg)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.battery_optimization.settings.open"), FeatureKind.ACTION,
                "Open battery optimization settings", "Open Android battery optimization management",
                FeatureCategory.APP,
                keywords = setOf("battery optimization", "doze", "background", "settings"),
                ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }
    }

    private fun registerAudioAndTorch(registry: FeatureRegistry) {
        resultAction(registry, "android.audio.devices.query", "Query audio devices", "Return current input and output audio devices", FeatureCategory.AUDIO) {
            ConfigValue.ListValue(audio.getDevices(AudioManager.GET_DEVICES_ALL).map { device ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "id" to ConfigValue.NumberValue(device.id.toDouble()),
                        "type" to ConfigValue.NumberValue(device.type.toDouble()),
                        "productName" to ConfigValue.StringValue(device.productName?.toString().orEmpty()),
                        "source" to ConfigValue.BooleanValue(device.isSource),
                        "sink" to ConfigValue.BooleanValue(device.isSink),
                        "sampleRates" to ConfigValue.ListValue(device.sampleRates.map { ConfigValue.NumberValue(it.toDouble()) }),
                        "channelCounts" to ConfigValue.ListValue(device.channelCounts.map { ConfigValue.NumberValue(it.toDouble()) }),
                    )
                )
            })
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.torch.state.query"), FeatureKind.ACTION,
                "Query torch state", "Return observed torch state for all camera flash units",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store torch map", true)),
                keywords = setOf("torch", "flashlight", "state", "shortx"),
                ownerPackId = id,
            )
        ) { feature, ctx -> store(feature, ctx, torch.value()) }

        val evaluator = ConditionEvaluator { feature, _ ->
            val cameraId = feature.config.string("cameraId").trim()
            val actual = if (cameraId.isBlank()) torch.anyEnabled() else torch.enabled(cameraId)
            actual == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.torch_on"), FeatureKind.STATE,
            "Torch enabled", "Check observed flashlight/torch state",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Text("cameraId", "Camera ID"), FieldSchema.Toggle("value", "Torch on")),
            keywords = setOf("torch", "flashlight", "state", "shortx"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.torch_on"), kind = FeatureKind.CONDITION), evaluator)
    }

    private fun registerSensorPrivacy(registry: FeatureRegistry) {
        val manager = context.getSystemService(SensorPrivacyManager::class.java)
        sensorPrivacyPair(registry, manager, SensorPrivacyManager.Sensors.MICROPHONE, "microphone", "Microphone privacy blocked")
        sensorPrivacyPair(registry, manager, SensorPrivacyManager.Sensors.CAMERA, "camera", "Camera privacy blocked")
    }

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

    private fun sensorPrivacyPair(
        registry: FeatureRegistry,
        manager: SensorPrivacyManager,
        sensor: Int,
        key: String,
        title: String,
    ) {
        val evaluator = ConditionEvaluator { feature, _ ->
            val actual = runCatching {
                manager.supportsSensorToggle(sensor) && manager.isSensorPrivacyEnabled(sensor)
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

    private fun parseExtras(raw: String): Map<String, String> = buildMap {
        raw.lineSequence().forEach { line ->
            val index = line.indexOf('=')
            if (index <= 0) return@forEach
            val key = line.substring(0, index).trim()
            if (key.isBlank()) return@forEach
            put(key, line.substring(index + 1))
        }
    }
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
