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


internal fun AndroidRemainingParityFeaturePack.registerLocation(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.current.query"), FeatureKind.ACTION,
                "Get current location", "Request a fresh or recent Android location without Google Play Services",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("provider", "Provider", options = listOf("best", "gps", "network", "passive")),
                    FieldSchema.Variable("resultVariable", "Store location object", true),
                    FieldSchema.Number("timeoutMillis", "Location timeout (ms)", min = 1000.0, max = 120000.0),
                    FieldSchema.Toggle("shortxContextOutput", "Populate ShortX coordinate variables"),
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
            val timeout = (feature.config["timeoutMillis"].numberOrNull()?.toLong() ?: 15000L)
                .coerceIn(1000L, 120000L)
            val location = withTimeoutOrNull(timeout) { currentLocation(manager, provider) }
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.location_unavailable"))
            if (feature.config.boolean("shortxContextOutput")) {
                ctx.variables.set("latitude", ConfigValue.NumberValue(location.latitude))
                ctx.variables.set("longitude", ConfigValue.NumberValue(location.longitude))
                ctx.variables.set("provider", ConfigValue.StringValue(location.provider.orEmpty()))
                ctx.variables.set("accuracy", ConfigValue.NumberValue(location.accuracy.toDouble()))
            }
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

internal fun AndroidRemainingParityFeaturePack.registerNetwork(registry: FeatureRegistry) {
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

internal fun AndroidRemainingParityFeaturePack.registerTelephony(registry: FeatureRegistry) {
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

internal fun AndroidRemainingParityFeaturePack.registerKeyguard(registry: FeatureRegistry) {
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

internal fun AndroidRemainingParityFeaturePack.registerDeviceQueries(registry: FeatureRegistry) {
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

internal fun AndroidRemainingParityFeaturePack.registerPackageQueries(registry: FeatureRegistry) {
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

