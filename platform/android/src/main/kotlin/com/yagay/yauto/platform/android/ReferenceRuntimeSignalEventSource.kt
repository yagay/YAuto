package com.yagay.yauto.platform.android

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.ConcurrentHashMap\nimport java.util.concurrent.atomic.AtomicBoolean

/**
 * Runtime signals repeatedly exposed by MacroDroid, ShortX and Tasker but not covered by
 * Android's normal public broadcasts. This source intentionally uses public Android APIs only.
 */
class ReferenceRuntimeSignalEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.reference.runtime_signals"

    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val audio = this.context.getSystemService(AudioManager::class.java)
    private val camera = this.context.getSystemService(CameraManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            super.onChange(selfChange, uri)
            emitSetting(uri)
        }
    }

    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) return
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.dnd_filter_changed",
                    payload = mapOf(
                        "filter" to ConfigValue.StringValue(referenceDndFilterName(notifications.currentInterruptionFilter))
                    ),
                    source = id,
                )
            )
        }
    }

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val activeCount = configs.size
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.audio_playback_activity_changed",
                    payload = mapOf(
                        "count" to ConfigValue.NumberValue(configs.size.toDouble()),
                        "activeCount" to ConfigValue.NumberValue(activeCount.toDouble()),
                        "active" to ConfigValue.BooleanValue(activeCount > 0),
                    ),
                    source = id,
                )
            )
        }
    }

    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.audio_recording_activity_changed",
                    payload = mapOf(
                        "count" to ConfigValue.NumberValue(configs.size.toDouble()),
                        "active" to ConfigValue.BooleanValue(configs.isNotEmpty()),
                    ),
                    source = id,
                )
            )
        }
    }

    private val cameraUnavailable = ConcurrentHashMap.newKeySet<String>()

    private val cameraAvailabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            cameraUnavailable.remove(cameraId)
            emitCameraAvailability(cameraId, available = true)
        }

        override fun onCameraUnavailable(cameraId: String) {
            cameraUnavailable.add(cameraId)
            emitCameraAvailability(cameraId, available = false)
        }
    }

    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            ReferenceRuntimeSignalState.updateTorch(cameraId, available = true, enabled = enabled)
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.torch_state_changed",
                    payload = mapOf(
                        "cameraId" to ConfigValue.StringValue(cameraId),
                        "available" to ConfigValue.BooleanValue(true),
                        "enabled" to ConfigValue.BooleanValue(enabled),
                    ),
                    source = id,
                )
            )
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            ReferenceRuntimeSignalState.updateTorch(cameraId, available = false, enabled = false)
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.torch_state_changed",
                    payload = mapOf(
                        "cameraId" to ConfigValue.StringValue(cameraId),
                        "available" to ConfigValue.BooleanValue(false),
                        "enabled" to ConfigValue.BooleanValue(false),
                    ),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        try {
            resolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
            resolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, settingsObserver)
            resolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)
            registerDndReceiver()
            audio.registerAudioPlaybackCallback(playbackCallback, handler)
            audio.registerAudioRecordingCallback(recordingCallback, handler)
            camera.registerTorchCallback(torchCallback, handler)\n            camera.registerAvailabilityCallback(cameraAvailabilityCallback, handler)
        } catch (error: Exception) {
            stop()
            throw error
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { resolver.unregisterContentObserver(settingsObserver) }
        runCatching { context.unregisterReceiver(dndReceiver) }
        runCatching { audio.unregisterAudioPlaybackCallback(playbackCallback) }
        runCatching { audio.unregisterAudioRecordingCallback(recordingCallback) }
        runCatching { camera.unregisterTorchCallback(torchCallback) }\n        runCatching { camera.unregisterAvailabilityCallback(cameraAvailabilityCallback) }\n        cameraUnavailable.clear()
        emitter = null
    }

    private fun registerDndReceiver() {
        val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(dndReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(dndReceiver, filter)
        }
    }

    private fun emitCameraAvailability(cameraId: String, available: Boolean) {
        val unavailableCount = cameraUnavailable.size
        ReferenceRuntimeSignalState.updateUnavailableCameras(cameraUnavailable)
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.camera_availability_changed",
                payload = mapOf(
                    "cameraId" to ConfigValue.StringValue(cameraId),
                    "available" to ConfigValue.BooleanValue(available),
                    "inUse" to ConfigValue.BooleanValue(!available),
                    "unavailableCount" to ConfigValue.NumberValue(unavailableCount.toDouble()),
                    "cameraCount" to ConfigValue.NumberValue(runCatching { camera.cameraIdList.size.toDouble() }.getOrDefault(0.0)),
                ),
                source = id,
            )
        )
    }

    private fun emitSetting(uri: Uri?) {
        val settingUri = uri ?: return
        val namespace = referenceSettingNamespace(settingUri) ?: return
        val key = settingUri.lastPathSegment.orEmpty()
        val value = readSetting(namespace, key)
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.system_setting_changed",
                payload = mapOf(
                    "namespace" to ConfigValue.StringValue(namespace),
                    "key" to ConfigValue.StringValue(key),
                    "value" to ConfigValue.StringValue(value.orEmpty()),
                    "valueAvailable" to ConfigValue.BooleanValue(value != null),
                ),
                source = id,
            )
        )
    }

    private fun readSetting(namespace: String, key: String): String? = runCatching {
        if (key.isBlank()) return@runCatching null
        when (namespace) {
            "system" -> Settings.System.getString(resolver, key)
            "secure" -> Settings.Secure.getString(resolver, key)
            "global" -> Settings.Global.getString(resolver, key)
            else -> null
        }
    }.getOrNull()
}

internal fun referenceSettingNamespace(uri: Uri): String? = when {
    uri.toString().startsWith(Settings.System.CONTENT_URI.toString()) -> "system"
    uri.toString().startsWith(Settings.Secure.CONTENT_URI.toString()) -> "secure"
    uri.toString().startsWith(Settings.Global.CONTENT_URI.toString()) -> "global"
    else -> null
}


internal object ReferenceRuntimeSignalState {
    private val unavailableCameras = ConcurrentHashMap.newKeySet<String>()
    private val availableTorches = ConcurrentHashMap.newKeySet<String>()
    private val enabledTorches = ConcurrentHashMap.newKeySet<String>()

    fun updateUnavailableCameras(values: Collection<String>) {
        unavailableCameras.clear()
        unavailableCameras.addAll(values)
    }

    fun updateTorch(cameraId: String, available: Boolean, enabled: Boolean) {
        if (available) availableTorches.add(cameraId) else availableTorches.remove(cameraId)
        if (available && enabled) enabledTorches.add(cameraId) else enabledTorches.remove(cameraId)
    }

    fun cameraInUse(): Boolean = unavailableCameras.isNotEmpty()
    fun unavailableCameraCount(): Int = unavailableCameras.size
    fun torchAvailable(): Boolean = availableTorches.isNotEmpty()
    fun torchEnabled(): Boolean = enabledTorches.isNotEmpty()
}
