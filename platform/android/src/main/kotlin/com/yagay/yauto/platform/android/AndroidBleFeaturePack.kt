package com.yagay.yauto.platform.android

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class AndroidBleFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.ble"
    private val context = context.applicationContext
    private val bluetooth = this.context.getSystemService(BluetoothManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerSnapshot(registry)
        registerAdvertisement(registry)
        registerFailure(registry)
    }

    private fun registerSnapshot(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.ble.scan.snapshot"), FeatureKind.ACTION,
                "Scan BLE devices", "Scan Bluetooth Low Energy advertisements for a bounded duration and return unique devices",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Duration("durationMs", "Scan duration"),
                    FieldSchema.Text("nameContains", "Device name contains"),
                    FieldSchema.Text("serviceUuid", "Service UUID"),
                    FieldSchema.Variable("resultVariable", "Store BLE device list", true),
                ),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("ble", "beacon", "bluetooth low energy", "scan", "advertisement"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (!runtimePermissionGranted(context, "bluetooth")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.bluetooth_permission_required"))
            }
            val duration = ((feature.config["durationMs"].numberOrNull() ?: 5_000.0).toLong()).coerceIn(500L, 30_000L)
            val nameContains = feature.config.string("nameContains").resolveVariables(ctx.variables)
            val serviceUuid = feature.config.string("serviceUuid").trim()
            val output = runCatching {
                scanSnapshot(duration).filter { value ->
                    val map = value.value
                    val name = (map["name"] as? ConfigValue.StringValue)?.value.orEmpty()
                    val uuids = (map["serviceUuids"] as? ConfigValue.ListValue)?.value.orEmpty()
                        .mapNotNull { (it as? ConfigValue.StringValue)?.value }
                    (nameContains.isBlank() || name.contains(nameContains, ignoreCase = true)) &&
                        (serviceUuid.isBlank() || uuids.any { it.equals(serviceUuid, ignoreCase = true) })
                }
            }.getOrElse {
                return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
            }
            val result = ConfigValue.ListValue(output)
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result) }
            ActionExecutionResult(true, result)
        }
    }

    @Suppress("MissingPermission")
    private suspend fun scanSnapshot(durationMs: Long): List<ConfigValue.ObjectValue> {
        val scanner = bluetooth.adapter?.bluetoothLeScanner ?: error("BLE scanner is unavailable")
        val found = ConcurrentHashMap<String, ConfigValue.ObjectValue>()
        var failure: Int? = null
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val value = bleResultValue(result)
                val key = (value.value["address"] as? ConfigValue.StringValue)?.value.orEmpty()
                    .ifBlank { "anonymous-${System.identityHashCode(result)}" }
                found[key] = value
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(0, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                failure = errorCode
            }
        }
        scanner.startScan(
            null,
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            callback,
        )
        try {
            delay(durationMs)
        } finally {
            runCatching { scanner.stopScan(callback) }
        }
        failure?.let { error("BLE scan failed: $it") }
        return found.values.sortedByDescending {
            (it.value["rssi"] as? ConfigValue.NumberValue)?.value ?: -999.0
        }
    }

    private fun registerAdvertisement(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.ble_advertisement"), FeatureKind.EVENT,
                "BLE advertisement", "Run when an enabled YAuto BLE watcher receives a matching advertisement",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("address", "Device address"),
                    FieldSchema.Text("nameContains", "Device name contains"),
                    FieldSchema.Text("serviceUuid", "Service UUID"),
                    FieldSchema.Number("minRssi", "Minimum RSSI", min = -127.0, max = 20.0),
                ),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("ble", "beacon", "advertisement", "bluetooth", "rssi"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.ble_advertisement") return@registerEvent false
            val address = feature.config.string("address")
            if (address.isNotBlank() && !ctx.event.payload.string("address").equals(address, ignoreCase = true)) return@registerEvent false
            val name = feature.config.string("nameContains")
            if (name.isNotBlank() && !ctx.event.payload.string("name").contains(name, ignoreCase = true)) return@registerEvent false
            val uuid = feature.config.string("serviceUuid")
            if (uuid.isNotBlank()) {
                val list = (ctx.event.payload["serviceUuids"] as? ConfigValue.ListValue)?.value.orEmpty()
                    .mapNotNull { (it as? ConfigValue.StringValue)?.value }
                if (list.none { it.equals(uuid, ignoreCase = true) }) return@registerEvent false
            }
            val minRssi = feature.config["minRssi"].numberOrNull()
            val rssi = (ctx.event.payload["rssi"] as? ConfigValue.NumberValue)?.value ?: -999.0
            minRssi == null || rssi >= minRssi
        }
    }

    private fun registerFailure(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.ble_scan_failed"), FeatureKind.EVENT,
                "BLE scan failed", "Run when the configured BLE advertisement watcher reports a scan error",
                FeatureCategory.NETWORK,
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("ble", "scan", "failure", "bluetooth"),
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.ble_scan_failed" }
    }
}

@Suppress("MissingPermission")
internal fun bleResultValue(result: ScanResult): ConfigValue.ObjectValue {
    val record = result.scanRecord
    val serviceUuids = record?.serviceUuids.orEmpty().map { ConfigValue.StringValue(it.uuid.toString()) }
    val manufacturer = buildMap<String, ConfigValue> {
        val data = record?.manufacturerSpecificData
        if (data != null) {
            for (index in 0 until data.size()) {
                put(
                    data.keyAt(index).toString(),
                    ConfigValue.StringValue(data.valueAt(index)?.joinToString("") { "%02X".format(it) }.orEmpty())
                )
            }
        }
    }
    return ConfigValue.ObjectValue(
        mapOf(
            "address" to ConfigValue.StringValue(runCatching { result.device.address }.getOrDefault("")),
            "name" to ConfigValue.StringValue(record?.deviceName.orEmpty()),
            "rssi" to ConfigValue.NumberValue(result.rssi.toDouble()),
            "timestampNanos" to ConfigValue.NumberValue(result.timestampNanos.toDouble()),
            "connectable" to ConfigValue.BooleanValue(result.isConnectable),
            "serviceUuids" to ConfigValue.ListValue(serviceUuids),
            "manufacturerData" to ConfigValue.ObjectValue(manufacturer),
        )
    )
}
