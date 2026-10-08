package com.yagay.yauto.platform.android

import android.content.Context
import android.os.PowerManager
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

/**
 * Bounded process wake-lock controls.
 *
 * Only PARTIAL_WAKE_LOCK is exposed: keeping the display on is a different concern and remains
 * handled by screen/display actions. Every acquire has a mandatory timeout to prevent accidental
 * indefinite battery drain.
 */
class AndroidWakeLockFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.wake_lock"
    private val controller = WakeLockController(context.applicationContext)

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.power.wake_lock.acquire"),
                FeatureKind.ACTION,
                "Acquire CPU wake lock",
                "Keep the CPU awake for a bounded duration using Android PARTIAL_WAKE_LOCK",
                FeatureCategory.SYSTEM,
                fields = listOf(
                    FieldSchema.Duration("timeoutMs", "Maximum hold duration", true),
                ),
                fieldBehaviors = mapOf(
                    "timeoutMs" to FieldBehavior(defaultValue = ConfigValue.NumberValue(DEFAULT_WAKE_LOCK_TIMEOUT_MS.toDouble())),
                ),
                keywords = setOf("wake lock", "wakelock", "keep awake", "cpu", "stay awake", "ShortX", "Tasker"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val timeout = validatedWakeLockTimeout(feature.config["timeoutMs"].numberOrNull())
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", feature.typeId))
            val acquired = controller.acquire(timeout)
            ActionExecutionResult(
                acquired,
                ConfigValue.BooleanValue(acquired),
                if (acquired) null else userText("feature.operation_failed", feature.typeId),
            )
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.power.wake_lock.release"),
                FeatureKind.ACTION,
                "Release CPU wake lock",
                "Release YAuto's currently held CPU wake lock",
                FeatureCategory.SYSTEM,
                keywords = setOf("wake lock", "wakelock", "release", "cpu", "stay awake"),
                ownerPackId = id,
            )
        ) { _, _ ->
            val released = controller.release()
            ActionExecutionResult(true, ConfigValue.BooleanValue(released))
        }

        val fields = listOf(FieldSchema.Toggle("value", "Wake lock held"))
        val evaluator = ConditionEvaluator { feature, _ ->
            controller.isHeld() == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.wake_lock_held"),
            FeatureKind.STATE,
            "CPU wake lock held",
            "Check whether YAuto currently holds its bounded CPU wake lock",
            FeatureCategory.SYSTEM,
            fields = fields,
            keywords = setOf("wake lock", "wakelock", "cpu", "stay awake"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(
            state.copy(
                id = FeatureId("android.condition.wake_lock_held"),
                kind = FeatureKind.CONDITION,
            ),
            evaluator,
        )
    }
}

private class WakeLockController(context: Context) {
    private val lock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YAuto:AutomationWakeLock")
        .apply { setReferenceCounted(false) }

    @Synchronized
    fun acquire(timeoutMs: Long): Boolean = runCatching {
        if (lock.isHeld) lock.release()
        lock.acquire(timeoutMs)
        lock.isHeld
    }.getOrDefault(false)

    @Synchronized
    fun release(): Boolean {
        val wasHeld = runCatching { lock.isHeld }.getOrDefault(false)
        if (wasHeld) runCatching { lock.release() }
        return wasHeld
    }

    fun isHeld(): Boolean = runCatching { lock.isHeld }.getOrDefault(false)
}

internal const val DEFAULT_WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
internal const val MAX_WAKE_LOCK_TIMEOUT_MS = 24 * 60 * 60_000L

internal fun validatedWakeLockTimeout(value: Double?): Long? {
    val timeout = value ?: DEFAULT_WAKE_LOCK_TIMEOUT_MS.toDouble()
    if (!timeout.isFinite() || timeout % 1.0 != 0.0) return null
    val millis = timeout.toLong()
    return millis.takeIf { it in 1_000L..MAX_WAKE_LOCK_TIMEOUT_MS }
}
