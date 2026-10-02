package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidBluetoothAudioEventFeaturePack : FeaturePack {
    override val id: String = "android.bluetooth.audio.events"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.bluetooth_audio_device_changed"), FeatureKind.EVENT,
                "Bluetooth audio device changed", "Run when a Bluetooth audio route connects or disconnects and optionally match its name or address",
                FeatureCategory.AUDIO,
                fields = listOf(
                    FieldSchema.Choice("state", "Connection", options = listOf("any", "connected", "disconnected")),
                    FieldSchema.Text("nameContains", "Device name contains"),
                    FieldSchema.Text("address", "Device address exact"),
                ),
                keywords = setOf("bluetooth", "headset", "earbuds", "speaker", "connect", "disconnect", "address"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.bluetooth_audio_device_changed" &&
                matchesBluetoothAudioEvent(feature.config, ctx.event.payload)
        }
    }
}

internal fun matchesBluetoothAudioEvent(config: ConfigMap, payload: ConfigMap): Boolean {
    val connected = payload.boolean("connected")
    val stateMatches = when (config.string("state", "any")) {
        "connected" -> connected
        "disconnected" -> !connected
        else -> true
    }
    if (!stateMatches) return false
    val name = config.string("nameContains").trim()
    val address = config.string("address").trim()
    return (name.isBlank() || payload.string("name").contains(name, ignoreCase = true)) &&
        (address.isBlank() || payload.string("address").equals(address, ignoreCase = true))
}
