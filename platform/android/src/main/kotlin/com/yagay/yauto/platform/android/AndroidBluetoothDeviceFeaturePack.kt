package com.yagay.yauto.platform.android

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

class AndroidBluetoothDeviceFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.bluetooth.devices"
    private val context = context.applicationContext
    private val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java).adapter

    override fun install(registry: FeatureRegistry) {
        registerDiscovery(registry, start = true)
        registerDiscovery(registry, start = false)
        registerPairedQuery(registry)
        registerBondedMatch(registry, FeatureKind.STATE, "android.state.bluetooth_device_bonded")
        registerBondedMatch(registry, FeatureKind.CONDITION, "android.condition.bluetooth_device_bonded")
        registerConnectionEvent(registry)
        registerFoundEvent(registry)
        registerBondEvent(registry)
    }

    private fun registerDiscovery(registry: FeatureRegistry, start: Boolean) {
        val featureId = if (start) "android.bluetooth.discovery.start" else "android.bluetooth.discovery.stop"
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId), FeatureKind.ACTION,
                if (start) "Start Bluetooth discovery" else "Stop Bluetooth discovery",
                if (start) "Request classic Bluetooth device discovery; Android scan permission and system throttling still apply"
                else "Cancel an active classic Bluetooth discovery started by YAuto or another app",
                FeatureCategory.NETWORK,
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("bluetooth", "scan", "discovery", "device"), ownerPackId = id,
            )
        ) { _, _ ->
            if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.bluetooth_scan_permission_missing"))
            }
            val ok = runCatching {
                val current = adapter ?: return@runCatching false
                if (start) {
                    if (current.isDiscovering) current.cancelDiscovery()
                    current.startDiscovery()
                } else {
                    !current.isDiscovering || current.cancelDiscovery()
                }
            }.getOrDefault(false)
            ActionExecutionResult(ok, message = if (ok) null else userText("feature.bluetooth_discovery_failed"))
        }
    }

    private fun registerPairedQuery(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.bluetooth.paired.query"), FeatureKind.ACTION,
                "Get paired Bluetooth devices", "Store bonded Bluetooth device names, addresses and bond states as structured objects",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store device list in variable", true)),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("bluetooth", "paired", "bonded", "devices"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.bluetooth_connect_permission_missing"))
            }
            val devices = runCatching { adapter?.bondedDevices.orEmpty().sortedBy { it.address } }.getOrDefault(emptyList())
            val output = ConfigValue.ListValue(devices.map(::deviceObject))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerBondedMatch(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Bluetooth device bonded", "Check whether a bonded Bluetooth device matches a name or exact address",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Text("nameContains", "Device name contains"),
                FieldSchema.Text("address", "Device address exact"),
                FieldSchema.Toggle("value", "Match exists"),
            ),
            accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
            keywords = setOf("bluetooth", "bonded", "paired", "device"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return@ConditionEvaluator false
            val found = runCatching { adapter?.bondedDevices.orEmpty().any { matchesDevice(feature.config.string("nameContains"), feature.config.string("address"), it) } }
                .getOrDefault(false)
            found == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerConnectionEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.bluetooth_device_connection"), FeatureKind.EVENT,
                "Bluetooth device connection", "Run when a classic Bluetooth device connects or disconnects",
                FeatureCategory.NETWORK,
                fields = commonEventFields() + FieldSchema.Choice("state", "Connection", true, listOf("any", "connected", "disconnected")),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("bluetooth", "connect", "disconnect", "device"),
                ownerPackId = id,
                aliases = setOf(
                    "android.event.bluetooth_acl_connected",
                    "android.event.bluetooth_acl_disconnected",
                ),
                aliasConfigDefaults = mapOf(
                    "android.event.bluetooth_acl_connected" to mapOf("state" to ConfigValue.StringValue("connected")),
                    "android.event.bluetooth_acl_disconnected" to mapOf("state" to ConfigValue.StringValue("disconnected")),
                ),
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.bluetooth_device_connection") return@registerEvent false
            val expectedState = feature.config.string("state", "any")
            eventDeviceMatches(feature.config.string("nameContains"), feature.config.string("address"), ctx.event.payload.string("name"), ctx.event.payload.string("address")) &&
                (expectedState == "any" || ctx.event.payload.string("state") == expectedState)
        }
    }

    private fun registerFoundEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.bluetooth_device_found"), FeatureKind.EVENT,
                "Bluetooth device discovered", "Run when classic Bluetooth discovery finds a nearby device",
                FeatureCategory.NETWORK,
                fields = commonEventFields() + FieldSchema.Number("minRssi", "Minimum RSSI (dBm)", min = -127.0, max = 20.0),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("bluetooth", "scan", "found", "rssi", "device"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.bluetooth_device_found") return@registerEvent false
            if (!eventDeviceMatches(feature.config.string("nameContains"), feature.config.string("address"), ctx.event.payload.string("name"), ctx.event.payload.string("address"))) return@registerEvent false
            val minRssi = feature.config["minRssi"].numberOrNull()
            minRssi == null || (ctx.event.payload["rssi"] as? ConfigValue.NumberValue)?.value?.let { it >= minRssi } == true
        }
    }

    private fun registerBondEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.bluetooth_bond_changed"), FeatureKind.EVENT,
                "Bluetooth bond changed", "Run when a Bluetooth device pairing state changes",
                FeatureCategory.NETWORK,
                fields = commonEventFields() + FieldSchema.Choice("bondState", "Bond state", true, listOf("any", "none", "bonding", "bonded")),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("bluetooth", "pair", "bond", "device"),
                ownerPackId = id,
                aliases = setOf("android.event.bluetooth_bond_state_changed"),
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.bluetooth_bond_changed") return@registerEvent false
            val expected = feature.config.string("bondState", "any")
            eventDeviceMatches(feature.config.string("nameContains"), feature.config.string("address"), ctx.event.payload.string("name"), ctx.event.payload.string("address")) &&
                (expected == "any" || ctx.event.payload.string("bondState") == expected)
        }
    }

    private fun commonEventFields() = listOf(
        FieldSchema.Text("nameContains", "Device name contains"),
        FieldSchema.Text("address", "Device address exact"),
    )

    private fun matchesDevice(nameContains: String, address: String, device: BluetoothDevice): Boolean =
        eventDeviceMatches(
            nameContains,
            address,
            runCatching { device.name.orEmpty() }.getOrDefault(""),
            runCatching { device.address.orEmpty() }.getOrDefault(""),
        )

    private fun deviceObject(device: BluetoothDevice): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
        mapOf(
            "name" to ConfigValue.StringValue(runCatching { device.name.orEmpty() }.getOrDefault("")),
            "address" to ConfigValue.StringValue(runCatching { device.address.orEmpty() }.getOrDefault("")),
            "bondState" to ConfigValue.StringValue(bondStateName(runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE))),
            "type" to ConfigValue.NumberValue(runCatching { device.type.toDouble() }.getOrDefault(0.0)),
        )
    )

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

internal fun eventDeviceMatches(nameContains: String, address: String, actualName: String, actualAddress: String): Boolean =
    (nameContains.isBlank() || actualName.contains(nameContains, ignoreCase = true)) &&
        (address.isBlank() || actualAddress.equals(address, ignoreCase = true))
