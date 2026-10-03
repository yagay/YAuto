package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps one lightweight coroutine per unique enabled interval rule. Workspace changes are detected
 * without restarting the runtime service; removed/changed rules cancel their previous jobs.
 */
class ConfiguredIntervalEventSource(
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.interval.configured"
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs = ConcurrentHashMap<String, Job>()
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private var refreshJob: Job? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        refreshJob = scope.launch {
            while (isActive && started.get()) {
                refreshRules()
                delay(2_000L)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        refreshJob?.cancel()
        refreshJob = null
        jobs.values.forEach(Job::cancel)
        jobs.clear()
        emitter = null
        scope.cancel()
    }

    private suspend fun refreshRules() {
        val rules = runCatching {
            workspace.load().automations.asSequence()
                .filter { it.enabled }
                .flatMap { it.activation.events.asSequence() }
                .filter { it.typeId == "android.event.interval" }
                .map(::toRule)
                .distinctBy { it.key }
                .associateBy { it.key }
        }.getOrDefault(emptyMap())

        jobs.keys.filter { it !in rules }.forEach { key -> jobs.remove(key)?.cancel() }
        rules.values.forEach { rule ->
            if (jobs[rule.key]?.isActive == true) return@forEach
            jobs[rule.key] = scope.launch { runRule(rule) }
        }
    }

    private suspend fun runRule(rule: IntervalRule) {
        if (!rule.fireImmediately) delay(rule.intervalMs)
        var tick = 0L
        while (isActive && started.get()) {
            tick += 1
            val now = System.currentTimeMillis()
            emitter?.emit(
                RuntimeEvent(
                    typeId = "android.event.interval",
                    payload = mapOf(
                        "subscription" to ConfigValue.StringValue(rule.key),
                        "intervalMs" to ConfigValue.NumberValue(rule.intervalMs.toDouble()),
                        "tick" to ConfigValue.NumberValue(tick.toDouble()),
                        "timestampEpochMs" to ConfigValue.NumberValue(now.toDouble()),
                    ),
                    source = id,
                )
            )
            delay(rule.intervalMs)
        }
    }

    private fun toRule(feature: FeatureRef): IntervalRule = IntervalRule(
        key = intervalSubscriptionKey(feature),
        intervalMs = intervalDurationMs(feature),
        fireImmediately = feature.config.boolean("fireImmediately"),
    )

    private data class IntervalRule(
        val key: String,
        val intervalMs: Long,
        val fireImmediately: Boolean,
    )
}
