package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidNetworkProfileEventFeaturePack : FeaturePack {
    override val id: String = "android.network.profile.events"

    override fun install(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.network_profile_changed"), FeatureKind.EVENT,
                "Active network changed", "Run when the active network transport, validation or metered state changes",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("connected", "Connection", options = listOf("any", "connected", "disconnected")),
                    FieldSchema.Choice("transport", "Transport", options = listOf("any", "wifi", "cellular", "ethernet", "vpn")),
                    FieldSchema.Choice("validated", "Internet validation", options = listOf("any", "yes", "no")),
                    FieldSchema.Choice("metered", "Metered network", options = listOf("any", "yes", "no")),
                ),
                keywords = setOf("network", "internet", "wifi", "cellular", "ethernet", "vpn", "metered", "validated"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.network_profile_changed" && matchesNetworkProfileEvent(feature.config, ctx.event.payload)
        }
    }
}

internal fun matchesNetworkProfileEvent(config: ConfigMap, payload: ConfigMap): Boolean {
    val connected = payload.boolean("connected")
    val connectionMode = config.string("connected", "any")
    when (connectionMode) {
        "connected" -> if (!connected) return false
        "disconnected" -> if (connected) return false
    }
    if (!connected) return connectionMode != "connected"

    val transportMatches = when (config.string("transport", "any")) {
        "wifi" -> payload.boolean("wifi")
        "cellular" -> payload.boolean("cellular")
        "ethernet" -> payload.boolean("ethernet")
        "vpn" -> payload.boolean("vpn")
        else -> true
    }
    if (!transportMatches) return false
    if (!eventTriState(config.string("validated", "any"), payload.boolean("validated"))) return false
    return eventTriState(config.string("metered", "any"), payload.boolean("metered"))
}

private fun eventTriState(mode: String, value: Boolean): Boolean = when (mode) {
    "yes" -> value
    "no" -> !value
    else -> true
}
