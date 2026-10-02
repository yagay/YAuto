package com.yagay.yauto.platform.android

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Emits physical/audio-route headset connection changes without relying on deprecated wired broadcasts. */
class AudioDeviceEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.audio.devices"
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)
    private val started = AtomicBoolean(false)
    private val knownHeadsets = ConcurrentHashMap<Int, String>()
    private var emitter: RuntimeEventEmitter? = null

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            addedDevices.forEach { device ->
                val category = device.yautoHeadsetCategory() ?: return@forEach
                if (knownHeadsets.putIfAbsent(device.id, category) != null) return@forEach
                emit(device, category, connected = true)
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            removedDevices.forEach { device ->
                val category = knownHeadsets.remove(device.id) ?: device.yautoHeadsetCategory() ?: return@forEach
                emit(device, category, connected = false)
            }
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        knownHeadsets.clear()
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).forEach { device ->
            device.yautoHeadsetCategory()?.let { knownHeadsets[device.id] = it }
        }
        runCatching { audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
            .onFailure {
                started.set(false)
                knownHeadsets.clear()
                this.emitter = null
                throw it
            }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { audio.unregisterAudioDeviceCallback(callback) }
        knownHeadsets.clear()
        emitter = null
    }

    private fun emit(device: AudioDeviceInfo, category: String, connected: Boolean) {
        emitter?.emit(
            RuntimeEvent(
                typeId = "android.event.headset_changed",
                payload = mapOf(
                    "connected" to ConfigValue.BooleanValue(connected),
                    "category" to ConfigValue.StringValue(category),
                    "name" to ConfigValue.StringValue(device.productName?.toString().orEmpty()),
                    "deviceType" to ConfigValue.NumberValue(device.type.toDouble()),
                ),
                source = id,
            )
        )
    }
}

internal fun AudioDeviceInfo.yautoHeadsetCategory(): String? = when (type) {
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_LINE_ANALOG,
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "wired"

    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_HEARING_AID -> "bluetooth"

    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb"

    else -> null
}
