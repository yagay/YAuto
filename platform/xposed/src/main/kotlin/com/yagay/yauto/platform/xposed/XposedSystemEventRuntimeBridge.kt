package com.yagay.yauto.platform.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

data class XposedHardwareKeySnapshot(
    val keyCode: Int,
    val scanCode: Int,
    val deviceId: Int,
    val action: Int,
)

object XposedSystemEventRuntimeBridge {
    @Volatile private var listener: ((RuntimeEvent) -> Unit)? = null
    private val nextHardwareKey = AtomicReference<CompletableDeferred<XposedHardwareKeySnapshot>?>(null)

    fun attach(value: ((RuntimeEvent) -> Unit)?) {
        listener = value
    }

    suspend fun awaitNextHardwareKey(timeoutMs: Long): XposedHardwareKeySnapshot? {
        val deferred = CompletableDeferred<XposedHardwareKeySnapshot>()
        nextHardwareKey.getAndSet(deferred)?.cancel()
        return try {
            withTimeoutOrNull(timeoutMs.coerceIn(1_000L, 60_000L)) { deferred.await() }
        } finally {
            nextHardwareKey.compareAndSet(deferred, null)
        }
    }

    internal fun dispatch(intent: Intent) {
        if (intent.action != SystemBridgeProtocol.SYSTEM_EVENT_ACTION) return
        val type = intent.getStringExtra("type").orEmpty()
        if (type == SystemBridgeProtocol.HARDWARE_KEY_CAPTURE_TYPE) {
            nextHardwareKey.getAndSet(null)?.complete(
                XposedHardwareKeySnapshot(
                    keyCode = intent.getIntExtra("keyCode", 0),
                    scanCode = intent.getIntExtra("scanCode", 0),
                    deviceId = intent.getIntExtra("deviceId", -1),
                    action = intent.getIntExtra("action", -1),
                )
            )
            return
        }
        if (!type.startsWith("android.event.")) return
        val payload = buildMap<String, ConfigValue> {
            intent.extras?.keySet().orEmpty().forEach { key ->
                if (key == "type" || key == "timestampEpochMs") return@forEach
                when (val value = intent.extras?.get(key)) {
                    is String -> put(key, ConfigValue.StringValue(value))
                    is Int -> put(key, ConfigValue.NumberValue(value.toDouble()))
                    is Long -> put(key, ConfigValue.NumberValue(value.toDouble()))
                    is Boolean -> put(key, ConfigValue.BooleanValue(value))
                }
            }
        }
        listener?.invoke(
            RuntimeEvent(
                typeId = type,
                payload = payload,
                source = "lsposed.system_server",
                timestampEpochMs = intent.getLongExtra("timestampEpochMs", System.currentTimeMillis()),
            )
        )
    }
}

class XposedSystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent != null) XposedSystemEventRuntimeBridge.dispatch(intent)
    }
}
