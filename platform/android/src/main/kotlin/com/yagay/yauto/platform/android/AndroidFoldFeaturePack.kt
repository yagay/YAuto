package com.yagay.yauto.platform.android

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.util.concurrent.atomic.AtomicReference

class AndroidFoldFeaturePack : FeaturePack {
    override val id: String = "android.fold"

    override fun install(registry: FeatureRegistry) {
        val angleFields = listOf(
            FieldSchema.Number("minAngle", "Minimum hinge angle", min = 0.0, max = 180.0),
            FieldSchema.Number("maxAngle", "Maximum hinge angle", min = 0.0, max = 180.0),
        )
        val angleEvaluator = ConditionEvaluator { feature, _ ->
            val angle = HingeAngleRuntime.currentAngle() ?: return@ConditionEvaluator false
            val min = feature.config["minAngle"].numberOrNull() ?: 0.0
            val max = feature.config["maxAngle"].numberOrNull() ?: 180.0
            min <= max && angle in min..max
        }
        val angleState = FeatureDescriptor(
            FeatureId("android.state.hinge_angle"), FeatureKind.STATE,
            "Hinge angle", "Check the current foldable-device hinge angle",
            FeatureCategory.DEVICE,
            fields = angleFields,
            keywords = setOf("fold", "hinge", "angle", "foldable"),
            ownerPackId = id,
        )
        registry.registerState(angleState, angleEvaluator)
        registry.registerCondition(angleState.copy(id = FeatureId("android.condition.hinge_angle"), kind = FeatureKind.CONDITION), angleEvaluator)

        val foldFields = listOf(
            FieldSchema.Choice("state", "Fold state", true, listOf("folded", "half_open", "flat")),
        )
        val foldEvaluator = ConditionEvaluator { feature, _ ->
            hingeState(HingeAngleRuntime.currentAngle()) == feature.config.string("state")
        }
        val foldState = FeatureDescriptor(
            FeatureId("android.state.fold_state"), FeatureKind.STATE,
            "Fold state", "Check whether a foldable device is folded, half-open or flat",
            FeatureCategory.DEVICE,
            fields = foldFields,
            keywords = setOf("fold", "hinge", "foldable", "state"),
            ownerPackId = id,
        )
        registry.registerState(foldState, foldEvaluator)
        registry.registerCondition(foldState.copy(id = FeatureId("android.condition.fold_state"), kind = FeatureKind.CONDITION), foldEvaluator)

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.hinge_angle_changed"), FeatureKind.EVENT,
                "Hinge angle changed", "Run when the foldable-device hinge angle changes",
                FeatureCategory.DEVICE,
                fields = angleFields + listOf(
                    FieldSchema.Choice("state", "Fold state", options = listOf("any", "folded", "half_open", "flat")),
                ),
                keywords = setOf("fold", "hinge", "angle changed", "foldable"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.hinge_angle_changed") return@registerEvent false
            val angle = (ctx.event.payload["angle"] as? ConfigValue.NumberValue)?.value ?: return@registerEvent false
            val min = feature.config["minAngle"].numberOrNull() ?: 0.0
            val max = feature.config["maxAngle"].numberOrNull() ?: 180.0
            if (min > max || angle !in min..max) return@registerEvent false
            val state = feature.config.string("state", "any")
            state == "any" || ctx.event.payload.string("state") == state
        }
    }
}

internal object HingeAngleRuntime {
    private val angle = AtomicReference<Double?>(null)
    fun currentAngle(): Double? = angle.get()
    fun update(value: Double) { angle.set(value.coerceIn(0.0, 180.0)) }
    fun clear() { angle.set(null) }
}

internal fun hingeState(angle: Double?): String = when {
    angle == null -> "unknown"
    angle <= 15.0 -> "folded"
    angle >= 165.0 -> "flat"
    else -> "half_open"
}

class HingeAngleEventSource(context: Context) : AndroidEventSource, SensorEventListener {
    override val id: String = "android.hinge_angle"
    private val sensorManager = context.applicationContext.getSystemService(SensorManager::class.java)
    private val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
    private var emitter: RuntimeEventEmitter? = null
    private var lastAngle: Double? = null

    override fun start(emitter: RuntimeEventEmitter) {
        this.emitter = emitter
        sensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        emitter = null
        lastAngle = null
        HingeAngleRuntime.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val value = event?.values?.firstOrNull()?.toDouble()?.coerceIn(0.0, 180.0) ?: return
        HingeAngleRuntime.update(value)
        val previous = lastAngle
        if (previous != null && kotlin.math.abs(value - previous) < 1.0) return
        lastAngle = value
        emitter?.emit(
            com.yagay.yauto.core.model.RuntimeEvent(
                typeId = "android.event.hinge_angle_changed",
                payload = mapOf(
                    "angle" to ConfigValue.NumberValue(value),
                    "state" to ConfigValue.StringValue(hingeState(value)),
                ),
                source = id,
            )
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
