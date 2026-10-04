package com.yagay.yauto.platform.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent

object XposedSystemEventRuntimeBridge {
    @Volatile private var listener: ((RuntimeEvent) -> Unit)? = null

    fun attach(value: ((RuntimeEvent) -> Unit)?) {
        listener = value
    }

    internal fun dispatch(intent: Intent) {
        if (intent.action != SystemBridgeProtocol.SYSTEM_EVENT_ACTION) return
        val type = intent.getStringExtra("type").orEmpty()
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
