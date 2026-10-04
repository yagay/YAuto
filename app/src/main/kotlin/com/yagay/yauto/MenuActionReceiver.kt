package com.yagay.yauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MenuActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_MENU) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AutomationRuntimeService.start(context)
        val graph = runCatching { (context.applicationContext as YAutoApplication).graph }
            .onFailure { StartupFailureRecorder.record(context, "menu-action:graph", it) }
            .getOrNull()
        if (graph != null) {
            RuntimeEventDispatcher(graph, scope).dispatch(
                RuntimeEvent(
                    "android.event.menu_action",
                    mapOf(
                        "name" to ConfigValue.StringValue(intent.getStringExtra("name").orEmpty()),
                        "payload" to ConfigValue.StringValue(intent.getStringExtra("payload").orEmpty()),
                    ),
                    source = "android.menu_action",
                )
            )
        }
        pending.finish()
    }

    companion object { const val ACTION_MENU = "com.yagay.yauto.MENU_ACTION" }
}
