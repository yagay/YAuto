package com.yagay.yauto.platform.android

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager

import android.provider.Settings
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/**
 * Third cross-reference batch derived from MacroDroid 5.67.8, ShortX+ 1.11 and Tasker 6.6.20.
 *
 * This pack focuses on runtime/system signals that were still missing after the broad action and
 * state coverage batches: biometric capability, live audio activity, DND, developer/display input
 * settings, camera hardware, generic settings-change triggers, torch events and filtered clipboard
 * triggers. Every feature is backed by a real Android API or runtime event; there are no aliases or
 * placeholder executors in this pack.
 */
class AndroidReferenceSignalFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.reference.signals"

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val biometric = this.context.getSystemService(BiometricManager::class.java)
    private val camera = this.context.getSystemService(CameraManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val packages = this.context.packageManager

    override fun install(registry: FeatureRegistry) {
        biometricPair(registry, "biometric_strong", "Strong biometric authentication", BiometricManager.Authenticators.BIOMETRIC_STRONG)
        biometricPair(registry, "biometric_weak", "Biometric authentication", BiometricManager.Authenticators.BIOMETRIC_WEAK)
        biometricPair(registry, "device_credential", "Device credential authentication", BiometricManager.Authenticators.DEVICE_CREDENTIAL)

        booleanPair(registry, "developer_options", "Developer options enabled", FeatureCategory.SYSTEM) {
            Settings.Global.getInt(resolver, "development_settings_enabled", 0) == 1
        }
        booleanPair(registry, "adb_debugging", "ADB debugging enabled", FeatureCategory.SYSTEM) {
            Settings.Global.getInt(resolver, "adb_enabled", 0) == 1
        }
        choicePair(registry, "screen_brightness_mode", "Screen brightness mode", FeatureCategory.DISPLAY, listOf("manual", "automatic", "unknown")) {
            when (Settings.System.getInt(resolver, "screen_brightness_mode", -1)) {
                0 -> "manual"
                1 -> "automatic"
                else -> "unknown"
            }
        }
        numberPair(registry, "screen_brightness", "Screen brightness", FeatureCategory.DISPLAY, 0.0, 255.0) {
            Settings.System.getInt(resolver, "screen_brightness", -1).takeIf { it >= 0 }?.toDouble()
        }
        numberPair(registry, "pointer_speed", "Pointer speed", FeatureCategory.DEVICE, -7.0, 7.0) {
            Settings.System.getInt(resolver, "pointer_speed", 0).toDouble()
        }
        booleanPair(registry, "show_touches", "Show touch points", FeatureCategory.DISPLAY) {
            Settings.System.getInt(resolver, "show_touches", 0) == 1
        }
        booleanPair(registry, "pointer_location", "Pointer location overlay", FeatureCategory.DISPLAY) {
            Settings.System.getInt(resolver, "pointer_location", 0) == 1
        }
        booleanPair(registry, "vibrate_when_ringing", "Vibrate while ringing", FeatureCategory.AUDIO) {
            Settings.System.getInt(resolver, "vibrate_when_ringing", 0) == 1
        }
        booleanPair(registry, "lockscreen_sounds", "Lock-screen sounds", FeatureCategory.AUDIO) {
            Settings.System.getInt(resolver, "lockscreen_sounds_enabled", 0) == 1
        }
        booleanPair(registry, "dial_pad_tones", "Dial-pad tones", FeatureCategory.AUDIO) {
            Settings.System.getInt(resolver, "dtmf_tone", 1) == 1
        }

        choicePair(
            registry,
            "dnd_filter",
            "Do Not Disturb filter",
            FeatureCategory.NOTIFICATION,
            listOf("all", "priority", "none", "alarms", "unknown"),
        ) { referenceDndFilterName(notifications.currentInterruptionFilter) }

        booleanPair(registry, "audio_playback_active", "Audio playback active", FeatureCategory.AUDIO) {
            currentPlaybackActiveCount() > 0
        }
        numberPair(registry, "audio_playback_count", "Active audio playback count", FeatureCategory.AUDIO, 0.0, 1024.0) {
            currentPlaybackActiveCount().toDouble()
        }
        booleanPair(registry, "audio_recording_active", "Audio recording active", FeatureCategory.AUDIO) {
            currentRecordingCount() > 0
        }
        numberPair(registry, "audio_recording_count", "Active audio recording count", FeatureCategory.AUDIO, 0.0, 1024.0) {
            currentRecordingCount().toDouble()
        }
        numberPair(registry, "camera_count", "Camera count", FeatureCategory.DEVICE, 0.0, 64.0) {
            runCatching { camera.cameraIdList.size.toDouble() }.getOrNull()
        }
        booleanPair(registry, "camera_flash_available", "Camera flash available", FeatureCategory.DEVICE) {
            packages.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        }

        registerGenericSettingEvent(registry)
        registerDndEvent(registry)
        registerAudioActivityEvent(registry, "android.event.audio_playback_activity_changed", "Audio playback activity changed")
        registerAudioActivityEvent(registry, "android.event.audio_recording_activity_changed", "Audio recording activity changed")
        registerTorchEvent(registry)
        registerClipboardFilteredEvent(registry)

        settingConvenienceEvent(registry, "developer_options_changed", "Developer options changed", "global", "development_settings_enabled", FeatureCategory.SYSTEM)
        settingConvenienceEvent(registry, "adb_debugging_changed", "ADB debugging changed", "global", "adb_enabled", FeatureCategory.SYSTEM)
        settingConvenienceEvent(registry, "screen_brightness_changed", "Screen brightness changed", "system", "screen_brightness", FeatureCategory.DISPLAY)
        settingConvenienceEvent(registry, "brightness_mode_changed", "Brightness mode changed", "system", "screen_brightness_mode", FeatureCategory.DISPLAY)
        settingConvenienceEvent(registry, "show_touches_changed", "Show touches changed", "system", "show_touches", FeatureCategory.DISPLAY)
        settingConvenienceEvent(registry, "pointer_location_changed", "Pointer location changed", "system", "pointer_location", FeatureCategory.DISPLAY)
        settingConvenienceEvent(registry, "pointer_speed_changed", "Pointer speed changed", "system", "pointer_speed", FeatureCategory.DEVICE)
        settingConvenienceEvent(registry, "vibrate_when_ringing_changed", "Vibrate while ringing changed", "system", "vibrate_when_ringing", FeatureCategory.AUDIO)
        settingConvenienceEvent(registry, "lockscreen_sounds_changed", "Lock-screen sounds changed", "system", "lockscreen_sounds_enabled", FeatureCategory.AUDIO)
        settingConvenienceEvent(registry, "dial_pad_tones_changed", "Dial-pad tones changed", "system", "dtmf_tone", FeatureCategory.AUDIO)
    }

    private fun biometricPair(registry: FeatureRegistry, key: String, title: String, authenticators: Int) {
        choicePair(
            registry,
            key,
            title,
            FeatureCategory.DEVICE,
            BIOMETRIC_STATUS_OPTIONS,
        ) { referenceBiometricStatusName(runCatching { biometric.canAuthenticate(authenticators) }.getOrElse { Int.MIN_VALUE }) }
    }

    private fun currentPlaybackActiveCount(): Int = runCatching {
        audio.activePlaybackConfigurations.size
    }.getOrDefault(0)

    private fun currentRecordingCount(): Int = runCatching { audio.activeRecordingConfigurations.size }.getOrDefault(0)

    private fun booleanPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        query: () -> Boolean,
    ) = pair(
        registry,
        key,
        title,
        "Compare the current Android runtime/system state",
        category,
        listOf(FieldSchema.Toggle("value", "Enabled / true")),
    ) { feature, _ -> query() == feature.config.boolean("value", true) }

    private fun choicePair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        options: List<String>,
        query: () -> String,
    ) = pair(
        registry,
        key,
        title,
        "Match the current Android runtime/system value",
        category,
        listOf(FieldSchema.Choice("value", "Expected value", true, options)),
    ) { feature, _ -> query() == feature.config.string("value", options.first()) }

    private fun numberPair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        category: FeatureCategory,
        allowedMin: Double,
        allowedMax: Double,
        query: () -> Double?,
    ) = pair(
        registry,
        key,
        title,
        "Compare the current Android runtime/system numeric value",
        category,
        listOf(
            FieldSchema.Number("min", "Minimum", min = allowedMin, max = allowedMax),
            FieldSchema.Number("max", "Maximum", min = allowedMin, max = allowedMax),
        ),
    ) { feature, _ ->
        val value = query() ?: return@pair false
        matchesReferenceSignalRange(value, feature.config["min"].numberOrNull(), feature.config["max"].numberOrNull(), allowedMin, allowedMax)
    }

    private fun pair(
        registry: FeatureRegistry,
        key: String,
        title: String,
        description: String,
        category: FeatureCategory,
        fields: List<FieldSchema>,
        evaluate: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> Boolean,
    ) {
        val evaluator = ConditionEvaluator { feature, context -> runCatching { evaluate(feature, context) }.getOrDefault(false) }
        val state = FeatureDescriptor(
            FeatureId("android.state.reference_signal.$key"),
            FeatureKind.STATE,
            title,
            description,
            category,
            fields = fields,
            keywords = setOf("MacroDroid", "ShortX", "Tasker", "runtime", "signal"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.reference_signal.$key"), kind = FeatureKind.CONDITION),
            evaluator,
        )
    }

    private fun registerGenericSettingEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.system_setting_changed_filtered"),
                FeatureKind.EVENT,
                "System setting changed",
                "Trigger when a System, Secure or Global Android setting changes",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Choice("namespace", "Settings namespace", options = listOf("any", "system", "secure", "global")),
                    FieldSchema.Choice("keyMode", "Key match", options = listOf("any", "exact", "contains", "regex")),
                    FieldSchema.Text("key", "Setting key"),
                    FieldSchema.Text("valueContains", "New value contains"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                ),
                keywords = setOf("settings", "system setting", "secure", "global", "content observer"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.system_setting_changed") return@registerEvent false
            val payload = context.event.payload
            val namespace = feature.config.string("namespace", "any")
            val actualNamespace = payload.string("namespace")
            if (namespace != "any" && namespace != actualNamespace) return@registerEvent false
            val ignoreCase = feature.config.boolean("ignoreCase", true)
            if (!referenceSignalTextMatches(
                    payload.string("key"),
                    feature.config.string("key"),
                    feature.config.string("keyMode", "any"),
                    ignoreCase,
                )) return@registerEvent false
            val valueContains = feature.config.string("valueContains")
            valueContains.isBlank() || payload.string("value").contains(valueContains, ignoreCase = ignoreCase)
        }
    }

    private fun registerDndEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.dnd_filter_changed_filtered"),
                FeatureKind.EVENT,
                "Do Not Disturb changed",
                "Trigger when Android's interruption filter changes",
                FeatureCategory.NOTIFICATION,
                fields = listOf(FieldSchema.Choice("filter", "DND filter", options = listOf("any", "all", "priority", "none", "alarms", "unknown"))),
                accessRequirements = setOf(AccessRequirement.DND_POLICY),
                keywords = setOf("dnd", "do not disturb", "interruption filter"),
                ownerPackId = id,
            )
        ) { feature, context ->
            context.event.typeId == "android.event.dnd_filter_changed" &&
                (feature.config.string("filter", "any") == "any" ||
                    feature.config.string("filter") == context.event.payload.string("filter"))
        }
    }

    private fun registerAudioActivityEvent(registry: FeatureRegistry, typeId: String, title: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("${typeId}_filtered"),
                FeatureKind.EVENT,
                title,
                "Trigger when the system audio activity configuration changes",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice("active", "Activity state", options = listOf("any", "active", "inactive")),
                    FieldSchema.Number("minCount", "Minimum active count", min = 0.0, max = 1024.0),
                    FieldSchema.Number("maxCount", "Maximum active count", min = 0.0, max = 1024.0),
                ),
                keywords = setOf("audio", "playback", "recording", "activity"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != typeId) return@registerEvent false
            val active = context.event.payload.boolean("active")
            val expected = feature.config.string("active", "any")
            if (expected == "active" && !active) return@registerEvent false
            if (expected == "inactive" && active) return@registerEvent false
            val count = context.event.payload["activeCount"].numberOrNull()
                ?: context.event.payload["count"].numberOrNull()
                ?: 0.0
            val min = feature.config["minCount"].numberOrNull() ?: 0.0
            val max = feature.config["maxCount"].numberOrNull() ?: 1024.0
            min <= max && count in min..max
        }
    }

    private fun registerTorchEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.torch_state_changed_filtered"),
                FeatureKind.EVENT,
                "Torch state changed",
                "Trigger when a camera torch becomes enabled, disabled or unavailable",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("cameraId", "Camera ID"),
                    FieldSchema.Choice("state", "Torch state", options = listOf("any", "on", "off", "unavailable")),
                ),
                keywords = setOf("torch", "flashlight", "camera"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.torch_state_changed") return@registerEvent false
            val cameraId = feature.config.string("cameraId")
            if (cameraId.isNotBlank() && context.event.payload.string("cameraId") != cameraId) return@registerEvent false
            val available = context.event.payload.boolean("available")
            val enabled = context.event.payload.boolean("enabled")
            when (feature.config.string("state", "any")) {
                "on" -> available && enabled
                "off" -> available && !enabled
                "unavailable" -> !available
                else -> true
            }
        }
    }

    private fun registerClipboardFilteredEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.clipboard_text_filtered"),
                FeatureKind.EVENT,
                "Filtered clipboard text changed",
                "Trigger when clipboard text changes and matches text or a regular expression",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("mode", "Text match", options = listOf("any", "exact", "contains", "regex")),
                    FieldSchema.Text("text", "Text / pattern"),
                    FieldSchema.Toggle("ignoreCase", "Ignore case"),
                    FieldSchema.Toggle("requireText", "Require non-empty text"),
                ),
                keywords = setOf("clipboard", "regex", "text changed"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.clipboard_changed") return@registerEvent false
            val actual = context.event.payload.string("text")
            if (feature.config.boolean("requireText", false) && actual.isEmpty()) return@registerEvent false
            referenceSignalTextMatches(
                actual,
                feature.config.string("text"),
                feature.config.string("mode", "any"),
                feature.config.boolean("ignoreCase", true),
            )
        }
    }

    private fun settingConvenienceEvent(
        registry: FeatureRegistry,
        suffix: String,
        title: String,
        namespace: String,
        key: String,
        category: FeatureCategory,
    ) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.$suffix"),
                FeatureKind.EVENT,
                title,
                "Trigger when the corresponding Android setting changes",
                category,
                ownerPackId = id,
                keywords = setOf("settings", key, "changed"),
            )
        ) { _, context ->
            context.event.typeId == "android.event.system_setting_changed" &&
                context.event.payload.string("namespace") == namespace &&
                context.event.payload.string("key") == key
        }
    }
}

internal val REFERENCE_SIGNAL_PAIR_KEYS = listOf(
    "biometric_strong",
    "biometric_weak",
    "device_credential",
    "developer_options",
    "adb_debugging",
    "screen_brightness_mode",
    "screen_brightness",
    "pointer_speed",
    "show_touches",
    "pointer_location",
    "vibrate_when_ringing",
    "lockscreen_sounds",
    "dial_pad_tones",
    "dnd_filter",
    "audio_playback_active",
    "audio_playback_count",
    "audio_recording_active",
    "audio_recording_count",
    "camera_count",
    "camera_flash_available",
)

internal val BIOMETRIC_STATUS_OPTIONS = listOf(
    "success",
    "no_hardware",
    "none_enrolled",
    "hardware_unavailable",
    "security_update_required",
    "unsupported",
    "unknown",
    "error",
)

internal fun referenceBiometricStatusName(status: Int): String = when (status) {
    BiometricManager.BIOMETRIC_SUCCESS -> "success"
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "no_hardware"
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "none_enrolled"
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "hardware_unavailable"
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "security_update_required"
    -2 -> "unsupported"
    -1 -> "unknown"
    else -> "error"
}

internal fun referenceDndFilterName(filter: Int): String = when (filter) {
    NotificationManager.INTERRUPTION_FILTER_ALL -> "all"
    NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "priority"
    NotificationManager.INTERRUPTION_FILTER_NONE -> "none"
    NotificationManager.INTERRUPTION_FILTER_ALARMS -> "alarms"
    else -> "unknown"
}

internal fun matchesReferenceSignalRange(
    value: Double,
    min: Double?,
    max: Double?,
    allowedMin: Double,
    allowedMax: Double,
): Boolean {
    if (!value.isFinite() || value !in allowedMin..allowedMax) return false
    val safeMin = min ?: allowedMin
    val safeMax = max ?: allowedMax
    if (!safeMin.isFinite() || !safeMax.isFinite()) return false
    if (safeMin !in allowedMin..allowedMax || safeMax !in allowedMin..allowedMax || safeMax < safeMin) return false
    return value in safeMin..safeMax
}

internal fun referenceSignalTextMatches(actual: String, expected: String, mode: String, ignoreCase: Boolean): Boolean = when (mode) {
    "exact" -> actual.equals(expected, ignoreCase = ignoreCase)
    "contains" -> actual.contains(expected, ignoreCase = ignoreCase)
    "regex" -> if (expected.isBlank()) false else runCatching {
        Regex(expected, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()).containsMatchIn(actual)
    }.getOrDefault(false)
    else -> true
}
