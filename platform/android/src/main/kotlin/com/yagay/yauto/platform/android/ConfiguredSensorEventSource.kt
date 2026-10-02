package com.yagay.yauto.platform.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Registers only sensors referenced by enabled workspace trigger rules.
 * The workspace is refreshed periodically so sensor subscriptions can change without restarting
 * the app/runtime service. Rule thresholds and throttling are evaluated before events reach the
 * automation engine.
 */
class ConfiguredSensorEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource, SensorEventListener {
    override val id: String = "android.sensors.configured"
    private val manager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val thread = HandlerThread("YAutoSensors")
    private var handler: Handler? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null
    @Volatile private var rulesByType: Map<Int, List<SensorRule>> = emptyMap()
    private val lastEmit = ConcurrentHashMap<String, Long>()

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        thread.start()
        handler = Handler(thread.looper)
        scope.launch {
            while (isActive && started.get()) {
                refreshRules()
                delay(5_000)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        scope.cancel()
        emitter = null
        handler?.post { manager.unregisterListener(this@ConfiguredSensorEventSource) }
        thread.quitSafely()
        lastEmit.clear()
        rulesByType = emptyMap()
    }

    private suspend fun refreshRules() {
        val features = runCatching {
            workspace.load().automations.asSequence()
                .filter { it.enabled }
                .flatMap { it.activation.events.asSequence() }
                .filter { it.typeId == "android.event.sensor_value" || it.typeId == "android.event.shake" }
                .toList()
        }.getOrDefault(emptyList())

        val next = features.mapNotNull(::toRule).distinctBy { it.key }.groupBy { it.sensorType }
        val oldTypes = rulesByType.keys
        val newTypes = next.keys
        rulesByType = next
        lastEmit.keys.removeIf { key -> next.values.flatten().none { it.key == key } }

        if (oldTypes != newTypes) {
            handler?.post {
                manager.unregisterListener(this@ConfiguredSensorEventSource)
                newTypes.forEach { type ->
                    manager.getDefaultSensor(type)?.let { sensor ->
                        manager.registerListener(this@ConfiguredSensorEventSource, sensor, SensorManager.SENSOR_DELAY_NORMAL, handler)
                    }
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val rules = rulesByType[event.sensor.type].orEmpty()
        if (rules.isEmpty()) return
        val values = event.values.map { it.toDouble() }
        val x = values.getOrElse(0) { 0.0 }
        val y = values.getOrElse(1) { 0.0 }
        val z = values.getOrElse(2) { 0.0 }
        val value = values.firstOrNull() ?: 0.0
        val magnitude = sqrt(values.sumOf { it * it })
        val linearMagnitude = abs(magnitude - SensorManager.GRAVITY_EARTH)
        val now = android.os.SystemClock.elapsedRealtime()

        rules.forEach { rule ->
            val matched = when (rule.feature.typeId) {
                "android.event.shake" -> linearMagnitude >= (rule.feature.config["threshold"].numberOrNull() ?: 5.0)
                else -> {
                    val selected = when (rule.feature.config.string("axis", "value")) {
                        "x" -> x
                        "y" -> y
                        "z" -> z
                        "magnitude" -> magnitude
                        else -> value
                    }
                    sensorComparison(
                        selected,
                        rule.feature.config["threshold"].numberOrNull() ?: 0.0,
                        rule.feature.config.string("operator", ">="),
                    )
                }
            }
            if (!matched) return@forEach
            val previous = lastEmit[rule.key] ?: Long.MIN_VALUE / 2
            if (now - previous < rule.minimumIntervalMs) return@forEach
            lastEmit[rule.key] = now
            emitter?.emit(
                RuntimeEvent(
                    typeId = rule.feature.typeId,
                    payload = mapOf(
                        "subscription" to ConfigValue.StringValue(rule.key),
                        "sensor" to ConfigValue.StringValue(rule.sensorName),
                        "value" to ConfigValue.NumberValue(value),
                        "x" to ConfigValue.NumberValue(x),
                        "y" to ConfigValue.NumberValue(y),
                        "z" to ConfigValue.NumberValue(z),
                        "magnitude" to ConfigValue.NumberValue(magnitude),
                        "linearMagnitude" to ConfigValue.NumberValue(linearMagnitude),
                        "accuracy" to ConfigValue.NumberValue(event.accuracy.toDouble()),
                        "timestampNs" to ConfigValue.NumberValue(event.timestamp.toDouble()),
                    ),
                    source = id,
                )
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun toRule(feature: FeatureRef): SensorRule? {
        val sensorName = if (feature.typeId == "android.event.shake") "accelerometer" else feature.config.string("sensor", "light")
        val type = when (sensorName) {
            "accelerometer" -> Sensor.TYPE_ACCELEROMETER
            "gyroscope" -> Sensor.TYPE_GYROSCOPE
            "light" -> Sensor.TYPE_LIGHT
            "proximity" -> Sensor.TYPE_PROXIMITY
            "magnetic_field" -> Sensor.TYPE_MAGNETIC_FIELD
            "pressure" -> Sensor.TYPE_PRESSURE
            else -> return null
        }
        if (manager.getDefaultSensor(type) == null) return null
        val defaultInterval = if (feature.typeId == "android.event.shake") 1_000L else 500L
        return SensorRule(
            feature = feature,
            sensorName = sensorName,
            sensorType = type,
            key = sensorSubscriptionKey(feature),
            minimumIntervalMs = feature.config.long("minimumIntervalMs", defaultInterval).coerceIn(100, 3_600_000),
        )
    }

    private data class SensorRule(
        val feature: FeatureRef,
        val sensorName: String,
        val sensorType: Int,
        val key: String,
        val minimumIntervalMs: Long,
    )
}
