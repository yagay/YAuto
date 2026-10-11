package com.yagay.yauto

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.AutomationEnableMode
import com.yagay.yauto.core.runtime.EmergencyPowerKeyDetector
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import com.yagay.yauto.ui.design.R as TextR

/** Emergency state survives process death; only explicit user recovery clears it. */
internal object RuntimeEmergencyState {
    private val paused = AtomicBoolean(false)
    fun restore(context: Context) { paused.set(RuntimeSettingsPreferences.emergencyLatched(context)) }
    fun isPaused(): Boolean = paused.get()
    fun trip(context: Context): Boolean {
        if (!paused.compareAndSet(false, true)) return false
        RuntimeSettingsPreferences.setEmergencyLatched(context, true)
        return true
    }
    fun resume(context: Context) {
        RuntimeSettingsPreferences.setEmergencyLatched(context, false)
        paused.set(false)
    }
}

internal class RuntimeSafetyController(
    private val context: Context,
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val detector = EmergencyPowerKeyDetector()
    private val main = Handler(Looper.getMainLooper())
    private var lastPromptAt = 0L

    fun observeKey(event: RuntimeEvent) {
        if (event.typeId != "android.event.hardware_key") return
        val code = event.payload["keyCode"].numberOrNull()?.toInt() ?: 0
        val repeat = event.payload["repeatCount"].numberOrNull()?.toInt() ?: 0
        val action = event.payload.string("action")
        val now = SystemClock.elapsedRealtime()

        if (RuntimeSettingsPreferences.emergencyPowerKey(context) &&
            detector.accept(code, action, repeat, now) &&
            RuntimeEmergencyState.trip(context)
        ) {
            // Paused synchronously, before the queued dispatcher can run again.
            scope.launch { graph.runtime.setRuntimeEnabled(AutomationEnableMode.DISABLE) }
            main.post {
                Toast.makeText(context, context.getString(TextR.string.sx_emergency_triggered), Toast.LENGTH_LONG).show()
            }
        }
        if (RuntimeSettingsPreferences.keyEventPrompt(context) && action == "up" &&
            now - lastPromptAt > 150L
        ) {
            lastPromptAt = now
            val scan = event.payload["scanCode"].numberOrNull()?.toInt() ?: 0
            main.post {
                Toast.makeText(context,
                    context.getString(TextR.string.sx_key_event_format, code, scan, event.source),
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
}
