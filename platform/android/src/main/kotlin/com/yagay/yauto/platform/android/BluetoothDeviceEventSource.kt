package com.yagay.yauto.platform.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Bluetooth device broadcasts are kept separate from audio-route events because they cover non-audio devices too. */
class BluetoothDeviceEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.bluetooth.devices"
    private val context = context.applicationContext
    private val started = AtomicBoolean(false)
    @Volatile private var emitter: RuntimeEventEmitter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            }
            val typeId = when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED, BluetoothDevice.ACTION_ACL_DISCONNECTED -> "android.event.bluetooth_device_connection"
                BluetoothDevice.ACTION_FOUND -> "android.event.bluetooth_device_found"
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> "android.event.bluetooth_bond_changed"
                else -> return
            }
            val payload = buildMap<String, ConfigValue> {
                put("action", ConfigValue.StringValue(action))
                put("address", ConfigValue.StringValue(safeAddress(device)))
                put("name", ConfigValue.StringValue(safeName(device)))
                put("bondState", ConfigValue.StringValue(bondStateName(safeBondState(device))))
                when (action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> put("state", ConfigValue.StringValue("connected"))
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> put("state", ConfigValue.StringValue("disconnected"))
                    BluetoothDevice.ACTION_FOUND -> {
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                        if (rssi != Short.MIN_VALUE) put("rssi", ConfigValue.NumberValue(rssi.toDouble()))
                    }
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val current = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                        put("bondState", ConfigValue.StringValue(bondStateName(current)))
                        put("previousBondState", ConfigValue.StringValue(bondStateName(previous)))
                    }
                }
            }
            emitter?.emit(RuntimeEvent(typeId, payload, source = id))
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(receiver, filter)
        } catch (error: Exception) {
            started.set(false)
            this.emitter = null
            throw error
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        emitter = null
    }

    private fun safeName(device: BluetoothDevice?): String {
        if (device == null || !hasConnectPermission()) return ""
        return runCatching { device.name.orEmpty() }.getOrDefault("")
    }

    private fun safeAddress(device: BluetoothDevice?): String {
        if (device == null || !hasConnectPermission()) return ""
        return runCatching { device.address.orEmpty() }.getOrDefault("")
    }

    private fun safeBondState(device: BluetoothDevice?): Int {
        if (device == null || !hasConnectPermission()) return BluetoothDevice.BOND_NONE
        return runCatching { device.bondState }.getOrDefault(BluetoothDevice.BOND_NONE)
    }

    private fun hasConnectPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
}

internal fun bondStateName(state: Int): String = when (state) {
    BluetoothDevice.BOND_BONDED -> "bonded"
    BluetoothDevice.BOND_BONDING -> "bonding"
    else -> "none"
}
