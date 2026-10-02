package com.yagay.yauto

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.yagay.yauto.platform.android.NfcRuntimeEventMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** No-UI NFC entry point so tag routing does not disturb MainActivity navigation state. */
class NfcDispatchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        dispatch(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        dispatch(intent)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun dispatch(intent: Intent?) {
        val event = intent?.let(NfcRuntimeEventMapper::fromIntent)
        if (event == null) {
            finish()
            return
        }
        AutomationRuntimeService.start(this)
        val graph = runCatching { (application as YAutoApplication).graph }
            .onFailure { StartupFailureRecorder.record(this, "nfc-dispatch:graph", it) }
            .getOrNull()
        if (graph == null) {
            finish()
            return
        }
        scope.launch {
            try {
                graph.runtime.dispatch(event)
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                StartupFailureRecorder.record(this@NfcDispatchActivity, "nfc-dispatch:runtime", error)
            } finally {
                finish()
            }
        }
    }
}
