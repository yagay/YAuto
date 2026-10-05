package com.yagay.yauto.platform.android

import android.Manifest
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.resolveVariables
import java.io.File
import java.util.UUID

/**
 * Small native utilities verified against Tasker 6.6.20 function/output classes.
 * They stay isolated from larger Android packs so Tasker parity helpers remain easy to audit.
 */
class AndroidTaskerUtilityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.tasker.utilities"

    private val context = context.applicationContext
    private val usage = this.context.getSystemService(UsageStatsManager::class.java)
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val storage = this.context.getSystemService(StorageManager::class.java)
    private val subscriptions = this.context.getSystemService(SubscriptionManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerUuid(registry)
        registerArraysCompare(registry)
        registerForegroundApp(registry)
        registerAudioStreamLimits(registry)
        registerStorageVolumes(registry)
        registerSubscriptions(registry)
        registerLocationDistance(registry)
        registerMockLocation(registry)
        registerVibrationCancel(registry)
        registerFileToContentUri(registry)
        registerDeviceName(registry)
    }

    private fun registerUuid(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.uuid.generate"),
                FeatureKind.ACTION,
                "Generate UUID",
                "Generate a random RFC 4122 UUID",
                FeatureCategory.ADVANCED,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store UUID", true)),
                keywords = setOf("uuid", "random id", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.StringValue(UUID.randomUUID().toString())
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerArraysCompare(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.arrays.compare"),
                FeatureKind.ACTION,
                "Compare arrays",
                "Compare two runtime list variables and return common, distinct and match results",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Variable("first", "First list variable", true),
                    FieldSchema.Variable("second", "Second list variable", true),
                    FieldSchema.Variable("resultVariable", "Store comparison object", true),
                ),
                keywords = setOf("arrays compare", "common", "distinct", "exact match", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val left = (ctx.variables.get(feature.config.string("first")) as? ConfigValue.ListValue)?.value
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            val right = (ctx.variables.get(feature.config.string("second")) as? ConfigValue.ListValue)?.value
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            val common = left.filter { item -> item in right }.distinct()
            val leftOnly = left.filterNot { item -> item in right }.distinct()
            val rightOnly = right.filterNot { item -> item in left }.distinct()
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "common" to ConfigValue.ListValue(common),
                    "firstOnly" to ConfigValue.ListValue(leftOnly),
                    "secondOnly" to ConfigValue.ListValue(rightOnly),
                    "exactMatch" to ConfigValue.BooleanValue(left == right),
                    "sameMembers" to ConfigValue.BooleanValue(left.toSet() == right.toSet()),
                )
            )
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerForegroundApp(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.app.foreground.query"),
                FeatureKind.ACTION,
                "Get foreground app and activity",
                "Read the latest foreground package and activity from Android usage events",
                FeatureCategory.APP,
                fields = listOf(
                    FieldSchema.Duration("lookbackMs", "Look-back window"),
                    FieldSchema.Variable("resultVariable", "Store foreground app object", true),
                ),
                accessRequirements = setOf(AccessRequirement.USAGE_STATS),
                keywords = setOf("foreground app", "current activity", "usage stats", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val now = System.currentTimeMillis()
            val lookback = (feature.config["lookbackMs"].numberOrNull() ?: 3_600_000.0)
                .toLong().coerceIn(60_000L, 7L * 24L * 60L * 60L * 1_000L)
            val event = latestForegroundEvent(now - lookback, now)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.foreground_event_unavailable"))
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "package" to ConfigValue.StringValue(event.packageName),
                    "activity" to ConfigValue.StringValue(event.className),
                    "timestampEpochMs" to ConfigValue.NumberValue(event.timestampEpochMs.toDouble()),
                )
            )
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun latestForegroundEvent(from: Long, to: Long): ForegroundUsageSnapshot? = runCatching {
        val events = usage.queryEvents(from, to)
        val current = UsageEvents.Event()
        var latest: ForegroundUsageSnapshot? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(current)
            if (
                current.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                current.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
            ) {
                latest = ForegroundUsageSnapshot(
                    packageName = current.packageName.orEmpty(),
                    className = current.className.orEmpty(),
                    timestampEpochMs = current.timeStamp,
                )
            }
        }
        latest
    }.getOrNull()

    private fun registerAudioStreamLimits(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.audio.stream_limits.query"),
                FeatureKind.ACTION,
                "Get maximum audio volumes",
                "Read Android maximum volume indexes for common audio streams",
                FeatureCategory.AUDIO,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store stream limits object", true)),
                keywords = setOf("max volume", "audio streams", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val streams = linkedMapOf(
                "voiceCall" to AudioManager.STREAM_VOICE_CALL,
                "system" to AudioManager.STREAM_SYSTEM,
                "ring" to AudioManager.STREAM_RING,
                "music" to AudioManager.STREAM_MUSIC,
                "alarm" to AudioManager.STREAM_ALARM,
                "notification" to AudioManager.STREAM_NOTIFICATION,
                "dtmf" to AudioManager.STREAM_DTMF,
                "accessibility" to AudioManager.STREAM_ACCESSIBILITY,
            )
            val output = ConfigValue.ObjectValue(
                streams.mapValues { (_, stream) ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "min" to ConfigValue.NumberValue(runCatching { audio.getStreamMinVolume(stream) }.getOrDefault(0).toDouble()),
                            "max" to ConfigValue.NumberValue(audio.getStreamMaxVolume(stream).toDouble()),
                            "current" to ConfigValue.NumberValue(audio.getStreamVolume(stream).toDouble()),
                        )
                    )
                }
            )
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerStorageVolumes(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.storage.volumes.query"),
                FeatureKind.ACTION,
                "List storage volumes",
                "Read Android StorageManager volume metadata",
                FeatureCategory.FILE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store volume list", true)),
                keywords = setOf("storage volumes", "sd card", "usb storage", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.ListValue(
                storage.storageVolumes.map { volume ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "description" to ConfigValue.StringValue(volume.getDescription(context)),
                            "directory" to ConfigValue.StringValue(volume.directory?.absolutePath.orEmpty()),
                            "uuid" to ConfigValue.StringValue(volume.uuid.orEmpty()),
                            "state" to ConfigValue.StringValue(volume.state),
                            "primary" to ConfigValue.BooleanValue(volume.isPrimary),
                            "removable" to ConfigValue.BooleanValue(volume.isRemovable),
                            "emulated" to ConfigValue.BooleanValue(volume.isEmulated),
                        )
                    )
                }
            )
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerSubscriptions(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.sim.subscriptions.query"),
                FeatureKind.ACTION,
                "Get SIM subscriptions",
                "Read active Android SIM/eSIM subscription metadata",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store subscription list", true)),
                accessRequirements = setOf(AccessRequirement.PHONE),
                keywords = setOf("sim", "esim", "subscription", "slot", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.phone_permission_required"))
            }
            val values = runCatching {
                subscriptions.activeSubscriptionInfoList.orEmpty().map { info ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "subscriptionId" to ConfigValue.NumberValue(info.subscriptionId.toDouble()),
                            "slotIndex" to ConfigValue.NumberValue(info.simSlotIndex.toDouble()),
                            "displayName" to ConfigValue.StringValue(info.displayName?.toString().orEmpty()),
                            "carrierName" to ConfigValue.StringValue(info.carrierName?.toString().orEmpty()),
                            "countryIso" to ConfigValue.StringValue(info.countryIso.orEmpty()),
                            "mcc" to ConfigValue.StringValue(info.mccString.orEmpty()),
                            "mnc" to ConfigValue.StringValue(info.mncString.orEmpty()),
                            "embedded" to ConfigValue.BooleanValue(info.isEmbedded),
                            "opportunistic" to ConfigValue.BooleanValue(info.isOpportunistic),
                            "cardId" to ConfigValue.NumberValue(info.cardId.toDouble()),
                        )
                    )
                }
            }.getOrElse {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
            val output = ConfigValue.ListValue(values)
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerLocationDistance(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.distance"),
                FeatureKind.ACTION,
                "Calculate location distance",
                "Calculate geodesic distance and initial/final bearings between two coordinates",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("lat1", "Latitude 1", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("lon1", "Longitude 1", true, min = -180.0, max = 180.0),
                    FieldSchema.Number("lat2", "Latitude 2", true, min = -90.0, max = 90.0),
                    FieldSchema.Number("lon2", "Longitude 2", true, min = -180.0, max = 180.0),
                    FieldSchema.Variable("resultVariable", "Store distance object", true),
                ),
                keywords = setOf("location distance", "meters", "bearing", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val lat1 = feature.config["lat1"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val lon1 = feature.config["lon1"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val lat2 = feature.config["lat2"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val lon2 = feature.config["lon2"].numberOrNull() ?: return@registerAction ActionExecutionResult(false)
            val result = FloatArray(3)
            Location.distanceBetween(lat1, lon1, lat2, lon2, result)
            val output = ConfigValue.ObjectValue(
                mapOf(
                    "meters" to ConfigValue.NumberValue(result[0].toDouble()),
                    "initialBearing" to ConfigValue.NumberValue(result[1].toDouble()),
                    "finalBearing" to ConfigValue.NumberValue(result[2].toDouble()),
                )
            )
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerMockLocation(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.location.mock.set"),
                FeatureKind.ACTION,
                "Set mock location",
                "Inject or clear an Android test-provider location. YAuto must be allowed as the system mock-location app.",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("operation", "Operation", true, listOf("set", "clear")),
                    FieldSchema.Text("provider", "Provider name"),
                    FieldSchema.Number("latitude", "Latitude", min = -90.0, max = 90.0),
                    FieldSchema.Number("longitude", "Longitude", min = -180.0, max = 180.0),
                    FieldSchema.Number("altitudeMeters", "Altitude meters", min = -1000.0, max = 100000.0),
                    FieldSchema.Number("accuracyMeters", "Accuracy meters", min = 0.1, max = 100000.0),
                ),
                keywords = setOf("mock location", "fake gps", "test provider", "tasker"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val manager = context.getSystemService(LocationManager::class.java)
            val provider = feature.config.string("provider").trim().ifBlank { LocationManager.GPS_PROVIDER }
            val operation = feature.config.string("operation", "set")
            val result = runCatching {
                if (operation == "clear") {
                    runCatching { manager.setTestProviderEnabled(provider, false) }
                    runCatching { manager.removeTestProvider(provider) }
                    true
                } else {
                    val latitude = feature.config["latitude"].numberOrNull() ?: error("Latitude is required")
                    val longitude = feature.config["longitude"].numberOrNull() ?: error("Longitude is required")
                    require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "Invalid coordinates" }
                    val properties = ProviderProperties.Builder()
                        .setAccuracy(ProviderProperties.ACCURACY_FINE)
                        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                        .build()
                    runCatching { manager.removeTestProvider(provider) }
                    manager.addTestProvider(provider, properties)
                    manager.setTestProviderEnabled(provider, true)
                    val location = Location(provider).apply {
                        this.latitude = latitude
                        this.longitude = longitude
                        altitude = feature.config["altitudeMeters"].numberOrNull() ?: 0.0
                        accuracy = (feature.config["accuracyMeters"].numberOrNull() ?: 5.0).toFloat().coerceAtLeast(0.1f)
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    manager.setTestProviderLocation(provider, location)
                    true
                }
            }.getOrElse {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                )
            }
            ActionExecutionResult(result, ConfigValue.BooleanValue(result))
        }
    }

    private fun registerVibrationCancel(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.vibrate.cancel"),
                FeatureKind.ACTION,
                "Stop vibration",
                "Cancel active vibration started through Android's vibrator service",
                FeatureCategory.DEVICE,
                keywords = setOf("stop vibrate", "cancel vibration", "tasker"),
                ownerPackId = id,
            )
        ) { _, _ ->
            runCatching {
                val manager = context.getSystemService(android.os.VibratorManager::class.java)
                manager.cancel()
                ActionExecutionResult(true)
            }.getOrElse {
                ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
        }
    }

    private fun registerFileToContentUri(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.file.to_content_uri"),
                FeatureKind.ACTION,
                "File path to content URI",
                "Convert a file path covered by YAuto's FileProvider into a shareable content URI",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("path", "File path", true),
                    FieldSchema.Variable("resultVariable", "Store content URI", true),
                ),
                fieldBehaviors = mapOf("path" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("file path", "content uri", "fileprovider", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val file = File(feature.config.string("path").resolveVariables(ctx.variables)).canonicalFile
            if (!file.exists()) return@registerAction ActionExecutionResult(false, message = userText("feature.file_not_found"))
            val uri = runCatching {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            }.getOrElse {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
            val output = ConfigValue.StringValue(uri.toString())
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerDeviceName(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.device.name.get"),
                FeatureKind.ACTION,
                "Get device name",
                "Read the Android user-visible device name with model fallback",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store device name", true)),
                keywords = setOf("device name", "model", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val value = Settings.Global.getString(context.contentResolver, "device_name")
                ?.takeIf(String::isNotBlank)
                ?: Build.MODEL.orEmpty()
            val output = ConfigValue.StringValue(value)
            store(feature.config.string("resultVariable"), output, ctx)
            ActionExecutionResult(true, output)
        }
    }

    private fun store(name: String, value: ConfigValue, ctx: FeatureExecutionContext) {
        name.trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, value) }
    }
}


private data class ForegroundUsageSnapshot(
    val packageName: String,
    val className: String,
    val timestampEpochMs: Long,
)
