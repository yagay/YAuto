package com.yagay.yauto

import android.app.Activity
import android.os.Bundle
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ShortcutDispatchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AutomationRuntimeService.start(this)
        val shortcutId = intent.getStringExtra("shortcutId").orEmpty()
        val command = intent.getStringExtra("command").orEmpty()
        val graph = runCatching { (application as YAutoApplication).graph }
            .onFailure { StartupFailureRecorder.record(this, "shortcut-dispatch:graph", it) }
            .getOrNull()
        if (graph != null) {
            RuntimeEventDispatcher(graph, scope).dispatch(
                RuntimeEvent(
                    "android.event.shortcut",
                    mapOf(
                        "id" to ConfigValue.StringValue(shortcutId),
                        "command" to ConfigValue.StringValue(command),
                    ),
                    source = "android.shortcut",
                )
            )
        }
        finish()
    }
}
