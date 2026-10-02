package com.yagay.yauto.platform.android

import android.content.Context
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiManager
import android.os.Handler
import android.os.HandlerThread
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

class MidiDeviceEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.midi.devices"
    private val manager = context.applicationContext.getSystemService(MidiManager::class.java)
    private val started = AtomicBoolean(false)
    private val thread = HandlerThread("YAutoMidiEvents")
    @Volatile private var emitter: RuntimeEventEmitter? = null

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = emit("android.event.midi_device_added", device)
        override fun onDeviceRemoved(device: MidiDeviceInfo) = emit("android.event.midi_device_removed", device)
        override fun onDeviceStatusChanged(status: MidiDeviceStatus) {
            val device = status.deviceInfo
            val statusObject = ConfigValue.ObjectValue(
                buildMap {
                    val inputs = device.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                        .associate { port -> port.portNumber.toString() to ConfigValue.BooleanValue(runCatching { status.isInputPortOpen(port.portNumber) }.getOrDefault(false)) }
                    val outputs = device.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
                        .associate { port -> port.portNumber.toString() to ConfigValue.NumberValue(runCatching { status.getOutputPortOpenCount(port.portNumber).toDouble() }.getOrDefault(0.0)) }
                    put("inputPortOpen", ConfigValue.ObjectValue(inputs))
                    put("outputPortOpenCount", ConfigValue.ObjectValue(outputs))
                }
            )
            emitter?.emit(
                RuntimeEvent(
                    "android.event.midi_device_status",
                    midiDeviceObject(device).value + mapOf("status" to statusObject),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        thread.start()
        manager.registerDeviceCallback(callback, Handler(thread.looper))
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { manager.unregisterDeviceCallback(callback) }
        thread.quitSafely()
        emitter = null
    }

    private fun emit(typeId: String, device: MidiDeviceInfo) {
        emitter?.emit(RuntimeEvent(typeId, midiDeviceObject(device).value, source = id))
    }
}

internal fun midiDeviceObject(info: MidiDeviceInfo): ConfigValue.ObjectValue {
    val properties = info.properties
    val inputPorts = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
    val outputPorts = info.ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }
    return ConfigValue.ObjectValue(
        mapOf(
            "id" to ConfigValue.NumberValue(info.id.toDouble()),
            "type" to ConfigValue.NumberValue(info.type.toDouble()),
            "name" to ConfigValue.StringValue(properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty()),
            "manufacturer" to ConfigValue.StringValue(properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty()),
            "product" to ConfigValue.StringValue(properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty()),
            "serialNumber" to ConfigValue.StringValue(properties.getString(MidiDeviceInfo.PROPERTY_SERIAL_NUMBER).orEmpty()),
            "inputPorts" to ConfigValue.ListValue(inputPorts.map { port -> midiPortObject(port) }),
            "outputPorts" to ConfigValue.ListValue(outputPorts.map { port -> midiPortObject(port) }),
        )
    )
}

private fun midiPortObject(port: MidiDeviceInfo.PortInfo): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
    mapOf(
        "number" to ConfigValue.NumberValue(port.portNumber.toDouble()),
        "name" to ConfigValue.StringValue(port.name.orEmpty()),
        "type" to ConfigValue.StringValue(if (port.type == MidiDeviceInfo.PortInfo.TYPE_INPUT) "input" else "output"),
    )
)
