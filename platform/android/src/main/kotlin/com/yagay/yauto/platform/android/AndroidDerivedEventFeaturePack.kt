package com.yagay.yauto.platform.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.net.wifi.WifiManager
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

/** High-value filtered triggers derived from event payloads YAuto already receives. */
class AndroidDerivedEventFeaturePack : FeaturePack {
    override val id: String = "android.events.derived"

    override fun install(registry: FeatureRegistry) {
        networkFlag(registry, "network_validated", "Validated network update", "validated", true)
        networkFlag(registry, "network_unvalidated", "Unvalidated network update", "validated", false)
        networkFlag(registry, "network_metered", "Metered network update", "metered", true)
        networkFlag(registry, "network_unmetered", "Unmetered network update", "metered", false)
        networkFlag(registry, "network_roaming", "Roaming network update", "roaming", true)
        networkFlag(registry, "network_restricted", "Restricted network update", "restricted", true)
        networkFlag(registry, "network_suspended", "Suspended network update", "suspended", true)
        networkFlag(registry, "network_wifi", "Wi-Fi default network update", "wifi", true)
        networkFlag(registry, "network_cellular", "Cellular default network update", "cellular", true)
        networkFlag(registry, "network_ethernet", "Ethernet default network update", "ethernet", true)
        networkFlag(registry, "network_vpn", "VPN default network update", "vpn", true)
        networkFlag(registry, "network_bluetooth", "Bluetooth default network update", "bluetooth", true)
        networkFlag(registry, "network_internet_capable", "Internet-capable network update", "internet", true)
        dockState(registry)
        bluetoothAdapterState(registry)
        bluetoothBondState(registry)
        wifiAdapterState(registry)
        wifiRssi(registry)
        wifiSupplicantConnection(registry, connected = true)
        wifiSupplicantConnection(registry, connected = false)
        networkCapabilities(registry)
    }

    private fun networkFlag(
        registry: FeatureRegistry,
        key: String,
        title: String,
        payloadKey: String,
        expected: Boolean,
    ) {
        val featureId = "android.event.$key"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = title,
                description = "Trigger on default-network capability updates matching the selected capability",
                category = FeatureCategory.NETWORK,
                keywords = setOf("network", "capability", payloadKey),
                ownerPackId = id,
            )
        ) { _, context ->
            context.event.typeId == "android.event.network_changed" &&
                context.event.payload.boolean(payloadKey) == expected
        }
    }

    private fun networkCapabilities(registry: FeatureRegistry) {
        val featureId = "android.event.network_capabilities_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Filtered network capability update",
                description = "Trigger on a default-network update after filtering transport and network capabilities",
                category = FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("transport", "Transport", options = listOf("any", "wifi", "cellular", "ethernet", "vpn", "bluetooth")),
                    FieldSchema.Choice("internet", "Internet capability", options = listOf("any", "yes", "no")),
                    FieldSchema.Choice("validated", "Validated internet", options = listOf("any", "yes", "no")),
                    FieldSchema.Choice("metered", "Metered network", options = listOf("any", "yes", "no")),
                    FieldSchema.Choice("roaming", "Roaming network", options = listOf("any", "yes", "no")),
                    FieldSchema.Choice("restricted", "Restricted network", options = listOf("any", "yes", "no")),
                    FieldSchema.Choice("suspended", "Suspended network", options = listOf("any", "yes", "no")),
                ),
                keywords = setOf("network", "transport", "validated", "metered", "roaming", "internet", "vpn"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.network_changed") return@registerEvent false
            val payload = context.event.payload
            val transport = feature.config.string("transport", "any")
            val transportMatches = transport == "any" || payload.boolean(transport)
            transportMatches &&
                triStateMatches(feature.config.string("internet", "any"), payload.boolean("internet")) &&
                triStateMatches(feature.config.string("validated", "any"), payload.boolean("validated")) &&
                triStateMatches(feature.config.string("metered", "any"), payload.boolean("metered")) &&
                triStateMatches(feature.config.string("roaming", "any"), payload.boolean("roaming")) &&
                triStateMatches(feature.config.string("restricted", "any"), payload.boolean("restricted")) &&
                triStateMatches(feature.config.string("suspended", "any"), payload.boolean("suspended"))
        }
    }

    private fun dockState(registry: FeatureRegistry) {
        val featureId = "android.event.dock_state_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Filtered dock state change",
                description = "Trigger when Android reports a selected physical dock state",
                category = FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice(
                        "state",
                        "Dock state",
                        true,
                        listOf("any", "undocked", "desk", "car", "analog_desk", "digital_desk"),
                    )
                ),
                keywords = setOf("dock", "desk", "car"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.dock_changed") return@registerEvent false
            val actual = context.event.payload["dockState"].numberOrNull()?.toInt() ?: return@registerEvent false
            val expected = when (feature.config.string("state", "any")) {
                "undocked" -> 0
                "desk" -> 1
                "car" -> 2
                "analog_desk" -> 3
                "digital_desk" -> 4
                else -> null
            }
            expected == null || actual == expected
        }
    }

    private fun bluetoothAdapterState(registry: FeatureRegistry) {
        val featureId = "android.event.bluetooth_adapter_state_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Filtered Bluetooth adapter state",
                description = "Trigger when the Bluetooth adapter enters a selected state",
                category = FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("state", "Adapter state", true, listOf("any", "off", "turning_on", "on", "turning_off"))
                ),
                keywords = setOf("bluetooth", "adapter", "state"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.bluetooth_state_changed") return@registerEvent false
            val actual = context.event.payload["state"].numberOrNull()?.toInt() ?: return@registerEvent false
            val expected = when (feature.config.string("state", "any")) {
                "off" -> BluetoothAdapter.STATE_OFF
                "turning_on" -> BluetoothAdapter.STATE_TURNING_ON
                "on" -> BluetoothAdapter.STATE_ON
                "turning_off" -> BluetoothAdapter.STATE_TURNING_OFF
                else -> null
            }
            expected == null || actual == expected
        }
    }

    private fun bluetoothBondState(registry: FeatureRegistry) {
        val featureId = "android.event.bluetooth_bond_state_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Filtered Bluetooth bond state",
                description = "Trigger when Bluetooth pairing enters a selected bond state",
                category = FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("state", "Bond state", true, listOf("any", "none", "bonding", "bonded"))
                ),
                keywords = setOf("bluetooth", "pair", "bond"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.bluetooth_bond_state_changed") return@registerEvent false
            val actual = context.event.payload["bondState"].numberOrNull()?.toInt() ?: return@registerEvent false
            val expected = when (feature.config.string("state", "any")) {
                "none" -> BluetoothDevice.BOND_NONE
                "bonding" -> BluetoothDevice.BOND_BONDING
                "bonded" -> BluetoothDevice.BOND_BONDED
                else -> null
            }
            expected == null || actual == expected
        }
    }

    private fun wifiAdapterState(registry: FeatureRegistry) {
        val featureId = "android.event.wifi_adapter_state_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Filtered Wi-Fi adapter state",
                description = "Trigger when Wi-Fi enters a selected adapter state",
                category = FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("state", "Wi-Fi state", true, listOf("any", "disabled", "disabling", "enabled", "enabling", "unknown"))
                ),
                keywords = setOf("wifi", "adapter", "state"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.wifi_state_changed") return@registerEvent false
            val actual = context.event.payload["wifiState"].numberOrNull()?.toInt() ?: return@registerEvent false
            val expected = when (feature.config.string("state", "any")) {
                "disabled" -> WifiManager.WIFI_STATE_DISABLED
                "disabling" -> WifiManager.WIFI_STATE_DISABLING
                "enabled" -> WifiManager.WIFI_STATE_ENABLED
                "enabling" -> WifiManager.WIFI_STATE_ENABLING
                "unknown" -> WifiManager.WIFI_STATE_UNKNOWN
                else -> null
            }
            expected == null || actual == expected
        }
    }

    private fun wifiRssi(registry: FeatureRegistry) {
        val featureId = "android.event.wifi_rssi_filtered"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = "Filtered Wi-Fi signal update",
                description = "Trigger on Wi-Fi RSSI updates inside the configured dBm range",
                category = FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("minRssi", "Minimum RSSI (dBm)", min = -127.0, max = 0.0),
                    FieldSchema.Number("maxRssi", "Maximum RSSI (dBm)", min = -127.0, max = 0.0),
                ),
                keywords = setOf("wifi", "rssi", "signal", "dbm"),
                ownerPackId = id,
            )
        ) { feature, context ->
            if (context.event.typeId != "android.event.wifi_rssi_changed") return@registerEvent false
            val actual = context.event.payload["rssi"].numberOrNull() ?: return@registerEvent false
            val min = feature.config["minRssi"].numberOrNull() ?: -127.0
            val max = feature.config["maxRssi"].numberOrNull() ?: 0.0
            min <= max && actual in min..max
        }
    }

    private fun wifiSupplicantConnection(registry: FeatureRegistry, connected: Boolean) {
        val suffix = if (connected) "connected" else "disconnected"
        val featureId = "android.event.wifi_supplicant_$suffix"
        registry.registerEvent(
            FeatureDescriptor(
                id = FeatureId(featureId),
                kind = FeatureKind.EVENT,
                title = if (connected) "Wi-Fi supplicant connected" else "Wi-Fi supplicant disconnected",
                description = "Trigger when Android reports the Wi-Fi supplicant connection state",
                category = FeatureCategory.NETWORK,
                keywords = setOf("wifi", "supplicant", suffix),
                ownerPackId = id,
            )
        ) { _, context ->
            context.event.typeId == "android.event.wifi_supplicant_connection_changed" &&
                context.event.payload.boolean("connected") == connected
        }
    }

    private fun triStateMatches(mode: String, actual: Boolean): Boolean = when (mode) {
        "yes" -> actual
        "no" -> !actual
        else -> true
    }
}
