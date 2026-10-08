package com.yagay.yauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.platform.android.AndroidPpnFeaturePack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PpnActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidPpnFeaturePack.PPN_ACTION) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AutomationRuntimeService.start(context)
        val graph = runCatching { (context.applicationContext as YAutoApplication).graph }.getOrNull()
        if (graph != null) {
            RuntimeEventDispatcher(graph, scope).dispatch(
                RuntimeEvent(
                    typeId = "android.event.ppn_action",
                    payload = mapOf(
                        "tag" to ConfigValue.StringValue(intent.getStringExtra("tag").orEmpty()),
                        "action" to ConfigValue.StringValue(intent.getStringExtra("action").orEmpty()),
                        "buttonIndex" to ConfigValue.NumberValue(intent.getIntExtra("buttonIndex", -1).toDouble()),
                    ),
                    source = "android.ppn",
                )
            )
        }
        pending.finish()
    }
}
