package com.yagay.yauto.platform.android

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Scans BLE only while at least one enabled BLE advertisement trigger exists. */
class ConfiguredBleEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.ble.configured"
    private val context = context.applicationContext
    private val bluetooth = this.context.getSystemService(BluetoothManager::class.java)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private var refreshJob: Job? = null
    private var scanning = false

    private val callback = object : ScanCallback() {
        @Suppress("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.ble_advertisement",
                    payload = bleResultValue(result).value,
                    source = id,
                )
            )
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.ble_scan_failed",
                    payload = mapOf("errorCode" to ConfigValue.NumberValue(errorCode.toDouble())),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        refreshJob = scope.launch {
            while (isActive && started.get()) {
                val needed = runCatching {
                    workspace.load().automations.any { automation ->
                        automation.enabled && automation.activation.events.any { it.typeId == "android.event.ble_advertisement" }
                    }
                }.getOrDefault(false)
                if (needed && !scanning) startScan()
                if (!needed && scanning) stopScan()
                delay(2_000L)
            }
        }
    }

    @Suppress("MissingPermission")
    private fun startScan() {
        if (!runtimePermissionGranted(context, "bluetooth")) return
        val scanner = bluetooth.adapter?.bluetoothLeScanner ?: return
        runCatching {
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build(),
                callback,
            )
            scanning = true
        }
    }

    @Suppress("MissingPermission")
    private fun stopScan() {
        runCatching { bluetooth.adapter?.bluetoothLeScanner?.stopScan(callback) }
        scanning = false
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        refreshJob?.cancel()
        refreshJob = null
        if (scanning) stopScan()
        emitter = null
        scope.cancel()
    }
}
