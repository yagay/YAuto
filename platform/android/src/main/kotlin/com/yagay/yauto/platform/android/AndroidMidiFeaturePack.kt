package com.yagay.yauto.platform.android

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Handler
import android.os.Looper
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class AndroidMidiFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.midi"
    private val manager = context.applicationContext.getSystemService(MidiManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerQuery(registry)
        registerSend(registry)
        registerPresent(registry, FeatureKind.STATE, "android.state.midi_device_present")
        registerPresent(registry, FeatureKind.CONDITION, "android.condition.midi_device_present")
        registerDeviceEvent(registry, "android.event.midi_device_added", "MIDI device added")
        registerDeviceEvent(registry, "android.event.midi_device_removed", "MIDI device removed")
        registerDeviceEvent(registry, "android.event.midi_device_status", "MIDI device status changed")
    }

    private fun registerQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.midi.devices.query"), FeatureKind.ACTION,
                "Get MIDI devices", "Store currently available Android MIDI devices and their input/output ports",
                FeatureCategory.DEVICE,
                fields = deviceFilterFields() + FieldSchema.Variable("resultVariable", "Store MIDI device list in variable", true),
                keywords = setOf("midi", "device", "ports", "instrument"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val devices = midiDevices().filter { matchesMidiDevice(feature.config.string("nameContains"), feature.config.string("manufacturerContains"), feature.config.string("productContains"), it) }
            val output = ConfigValue.ListValue(devices.map(::midiDeviceObject))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerSend(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.midi.send"), FeatureKind.ACTION,
                "Send MIDI bytes", "Send hexadecimal MIDI bytes to an input port on a selected Android MIDI device",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Number("deviceId", "MIDI device ID", true, min = 0.0),
                    FieldSchema.Number("inputPort", "Input port number", true, min = 0.0),
                    FieldSchema.Text("hex", "MIDI bytes in hex", true, multiline = true),
                ),
                keywords = setOf("midi", "send", "bytes", "sysex", "note"), ownerPackId = id,
            )
        ) { feature, _ ->
            val deviceId = feature.config["deviceId"].numberOrNull()?.toInt()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.midi_device_id_invalid"))
            val portNumber = feature.config["inputPort"].numberOrNull()?.toInt()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.midi_port_invalid"))
            val bytes = parseMidiHex(feature.config.string("hex"))
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.midi_hex_invalid"))
            if (bytes.isEmpty() || bytes.size > MAX_MIDI_BYTES) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.midi_hex_size_invalid", MAX_MIDI_BYTES))
            }
            val info = midiDevices().firstOrNull { it.id == deviceId }
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.midi_device_not_found", deviceId))
            val device = openDevice(info)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.midi_open_failed", deviceId))
            try {
                val input = runCatching { device.openInputPort(portNumber) }.getOrNull()
                    ?: return@registerAction ActionExecutionResult(false, message = userText("feature.midi_port_open_failed", portNumber))
                input.use { port -> port.send(bytes, 0, bytes.size) }
                ActionExecutionResult(true, ConfigValue.NumberValue(bytes.size.toDouble()))
            } catch (error: Exception) {
                ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
            } finally {
                runCatching { device.close() }
            }
        }
    }

    private fun registerPresent(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "MIDI device present", "Check whether an available Android MIDI device matches the configured identity filters",
            FeatureCategory.DEVICE,
            fields = deviceFilterFields() + FieldSchema.Toggle("value", "Match exists"),
            keywords = setOf("midi", "device", "connected", "instrument"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val found = midiDevices().any { matchesMidiDevice(feature.config.string("nameContains"), feature.config.string("manufacturerContains"), feature.config.string("productContains"), it) }
            found == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerDeviceEvent(registry: FeatureRegistry, typeId: String, title: String) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT,
                title, "Run when Android reports this MIDI device lifecycle event and the device matches the configured filters",
                FeatureCategory.DEVICE,
                fields = deviceFilterFields(),
                keywords = setOf("midi", "device", "added", "removed", "status"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            matchesMidiSnapshot(
                feature.config.string("nameContains"),
                feature.config.string("manufacturerContains"),
                feature.config.string("productContains"),
                ctx.event.payload.string("name"),
                ctx.event.payload.string("manufacturer"),
                ctx.event.payload.string("product"),
            )
        }
    }

    private fun deviceFilterFields() = listOf(
        FieldSchema.Text("nameContains", "Device name contains"),
        FieldSchema.Text("manufacturerContains", "Manufacturer contains"),
        FieldSchema.Text("productContains", "Product contains"),
    )

    @Suppress("DEPRECATION")
    private fun midiDevices(): List<MidiDeviceInfo> = runCatching { manager.devices.toList() }.getOrDefault(emptyList())

    private suspend fun openDevice(info: MidiDeviceInfo): MidiDevice? {
        val opened = CompletableDeferred<MidiDevice?>()
        runCatching {
            manager.openDevice(info, { device -> if (!opened.isCompleted) opened.complete(device) }, Handler(Looper.getMainLooper()))
        }.onFailure { if (!opened.isCompleted) opened.complete(null) }
        return withTimeoutOrNull(5_000L) { opened.await() }
    }

    private companion object {
        const val MAX_MIDI_BYTES = 4096
    }
}

internal fun matchesMidiDevice(nameContains: String, manufacturerContains: String, productContains: String, info: MidiDeviceInfo): Boolean {
    val properties = info.properties
    return matchesMidiSnapshot(
        nameContains,
        manufacturerContains,
        productContains,
        properties.getString(MidiDeviceInfo.PROPERTY_NAME).orEmpty(),
        properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty(),
        properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty(),
    )
}

internal fun matchesMidiSnapshot(
    nameContains: String,
    manufacturerContains: String,
    productContains: String,
    actualName: String,
    actualManufacturer: String,
    actualProduct: String,
): Boolean =
    (nameContains.isBlank() || actualName.contains(nameContains, ignoreCase = true)) &&
        (manufacturerContains.isBlank() || actualManufacturer.contains(manufacturerContains, ignoreCase = true)) &&
        (productContains.isBlank() || actualProduct.contains(productContains, ignoreCase = true))

internal fun parseMidiHex(text: String): ByteArray? {
    val normalized = text.filterNot { it.isWhitespace() || it == ':' || it == '-' }
    if (normalized.isEmpty() || normalized.length % 2 != 0 || !normalized.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return runCatching {
        ByteArray(normalized.length / 2) { index -> normalized.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }.getOrNull()
}
