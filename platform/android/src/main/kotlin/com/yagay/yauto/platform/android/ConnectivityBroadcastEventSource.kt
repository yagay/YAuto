package com.yagay.yauto.platform.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

class ConnectivityBroadcastEventSource(context: Context) : AndroidEventSource {
    override val id = "android.connectivity.broadcasts"
    private val context = context.applicationContext
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val started = AtomicBoolean(false)
    private var emitter: RuntimeEventEmitter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> emitWifi("network")
                WifiManager.RSSI_CHANGED_ACTION -> emitWifi("rssi")
                WifiManager.WIFI_STATE_CHANGED_ACTION -> emitWifi("state")
                BluetoothAdapter.ACTION_STATE_CHANGED -> emitBluetooth(intent)
            }
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        val filter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
        emitWifi("snapshot")
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        emitter = null
    }

    private fun emitWifi(changeType: String) {
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) caps?.transportInfo as? WifiInfo else null
        @Suppress("DEPRECATION")
        val fallback = runCatching { wifi.connectionInfo }.getOrNull()
        val selected = info ?: fallback
        val ssid = selected?.ssid.orEmpty().removePrefix("\"").removeSuffix("\"").takeUnless { it == WifiManager.UNKNOWN_SSID }.orEmpty()
        emitter?.emit(
            RuntimeEvent(
                "android.event.wifi_changed",
                mapOf(
                    "connected" to ConfigValue.BooleanValue(connected),
                    "ssid" to ConfigValue.StringValue(ssid),
                    "bssid" to ConfigValue.StringValue(selected?.bssid.orEmpty()),
                    "rssi" to ConfigValue.NumberValue((selected?.rssi ?: Int.MIN_VALUE).toDouble()),
                    "wifiEnabled" to ConfigValue.BooleanValue(wifi.isWifiEnabled),
                    "changeType" to ConfigValue.StringValue(changeType),
                ),
                source = id,
            )
        )
    }

    private fun emitBluetooth(intent: Intent) {
        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        val enabled = state == BluetoothAdapter.STATE_ON
        val permission = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        emitter?.emit(
            RuntimeEvent(
                "android.event.bluetooth_state",
                mapOf(
                    "enabled" to ConfigValue.BooleanValue(enabled),
                    "state" to ConfigValue.NumberValue(state.toDouble()),
                    "permission" to ConfigValue.BooleanValue(permission),
                ),
                source = id,
            )
        )
    }
}
