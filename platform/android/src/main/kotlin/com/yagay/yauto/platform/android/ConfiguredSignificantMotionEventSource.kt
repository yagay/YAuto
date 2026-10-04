package com.yagay.yauto.platform.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arms Android's one-shot significant-motion sensor only while an enabled automation references it.
 * Android automatically cancels a trigger sensor after each event, so this source re-arms it while
 * the rule remains enabled.
 */
class ConfiguredSignificantMotionEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.significant_motion.configured"

    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor: Sensor? = manager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val armed = AtomicBoolean(false)
    @Volatile private var configured = false
    @Volatile private var emitter: RuntimeEventEmitter? = null

    private val listener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            armed.set(false)
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.significant_motion",
                    payload = mapOf(
                        "value" to ConfigValue.NumberValue(event.values.firstOrNull()?.toDouble() ?: 1.0),
                        "timestampNs" to ConfigValue.NumberValue(event.timestamp.toDouble()),
                    ),
                    source = id,
                )
            )
            if (started.get() && configured) {
                scope.launch {
                    delay(250)
                    arm()
                }
            }
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        scope.launch {
            while (isActive && started.get()) {
                refresh()
                delay(5_000)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        configured = false
        if (armed.getAndSet(false)) runCatching { manager.cancelTriggerSensor(listener, sensor) }
        emitter = null
        scope.cancel()
    }

    private suspend fun refresh() {
        val needed = runCatching {
            workspace.load().automations.asSequence()
                .filter { it.enabled }
                .flatMap { it.activation.events.asSequence() }
                .any { it.typeId == "android.event.significant_motion" }
        }.getOrDefault(false)
        if (needed == configured) {
            if (needed) arm()
            return
        }
        configured = needed
        if (needed) {
            arm()
        } else if (armed.getAndSet(false)) {
            runCatching { manager.cancelTriggerSensor(listener, sensor) }
        }
    }

    private fun arm() {
        val target = sensor ?: return
        if (!started.get() || !configured || !armed.compareAndSet(false, true)) return
        val accepted = runCatching { manager.requestTriggerSensor(listener, target) }.getOrDefault(false)
        if (!accepted) armed.set(false)
    }
}
