package com.yagay.yauto

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.platform.android.ExternalCommandTokenStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ExternalCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_COMMAND) return
        val token = intent.getStringExtra("token").orEmpty()
        if (!ExternalCommandTokenStore(context).validate(token)) return
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AutomationRuntimeService.start(context)
        val app = context.applicationContext as? YAutoApplication
        val graph = runCatching { app?.graph }
            .onFailure { StartupFailureRecorder.record(context, "external-command:graph", it) }
            .getOrNull()
        if (graph != null) {
            RuntimeEventDispatcher(graph, scope).dispatch(
                RuntimeEvent(
                    "android.event.external_command",
                    mapOf(
                        "name" to ConfigValue.StringValue(intent.getStringExtra("name").orEmpty()),
                        "payload" to ConfigValue.StringValue(intent.getStringExtra("payload").orEmpty()),
                        "senderPackage" to ConfigValue.StringValue(intent.getStringExtra("senderPackage").orEmpty()),
                    ),
                    source = "android.external_command",
                )
            )
        }
        pending.finish()
    }

    companion object { const val ACTION_COMMAND = "com.yagay.yauto.COMMAND" }
}
