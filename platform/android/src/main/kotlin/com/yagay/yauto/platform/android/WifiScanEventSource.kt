package com.yagay.yauto.platform.android

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import java.util.concurrent.atomic.AtomicBoolean

class WifiScanEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.wifi.scan"
    private val context = context.applicationContext
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var emitter: RuntimeEventEmitter? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            val values = if (hasFineLocation()) {
                runCatching { wifi.scanResults.orEmpty().sortedByDescending { it.level }.take(500).map(::scanResultObject) }
                    .getOrDefault(emptyList())
            } else emptyList()
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.wifi_scan_results",
                    payload = mapOf(
                        "updated" to ConfigValue.BooleanValue(updated),
                        "results" to ConfigValue.ListValue(values),
                        "count" to ConfigValue.NumberValue(values.size.toDouble()),
                    ),
                    source = id,
                )
            )
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
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

    private fun hasFineLocation(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
}
