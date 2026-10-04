package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import android.os.SystemClock
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.storage.WorkspaceRepository
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Configured runtime source for ShortX-compatible duration/CPU signals and first unlock after boot.
 * It stays mostly idle when the workspace does not contain the corresponding triggers.
 */
class ConfiguredRuntimeParityEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.runtime.parity"
    private val context = context.applicationContext
    private val power = this.context.getSystemService(PowerManager::class.java)
    private val prefs = this.context.getSharedPreferences("runtime_parity", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private var job: Job? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null

    private var interactive = power.isInteractive
    private var interactiveSinceElapsed = if (interactive) SystemClock.elapsedRealtime() else 0L
    private var previousCpu: CpuSnapshot? = null
    private val cpuAvailabilitySamples = ArrayDeque<Pair<Long, Double>>()
    private val emittedScreenRules = mutableMapOf<String, Long>()

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    interactive = true
                    interactiveSinceElapsed = SystemClock.elapsedRealtime()
                    emittedScreenRules.clear()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    interactive = false
                    interactiveSinceElapsed = 0L
                    emittedScreenRules.clear()
                }
                Intent.ACTION_USER_PRESENT -> emitFirstUserPresent()
            }
        }
    }

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
        )
        job = scope.launch {
            while (isActive && started.get()) {
                val events = runCatching {
                    workspace.load().automations.asSequence()
                        .filter { it.enabled }
                        .flatMap { it.activation.events.asSequence() }
                        .filter {
                            it.typeId == "android.event.screen_on_duration" ||
                                it.typeId == "android.event.cpu_availability"
                        }
                        .toList()
                }.getOrDefault(emptyList())

                if (events.any { it.typeId == "android.event.screen_on_duration" }) {
                    evaluateScreenOnRules(events.filter { it.typeId == "android.event.screen_on_duration" })
                }
                if (events.any { it.typeId == "android.event.cpu_availability" }) {
                    evaluateCpu(events.filter { it.typeId == "android.event.cpu_availability" })
                } else {
                    previousCpu = null
                    cpuAvailabilitySamples.clear()
                }
                delay(1_000L)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { context.unregisterReceiver(receiver) }
        job?.cancel()
        job = null
        emitter = null
        previousCpu = null
        cpuAvailabilitySamples.clear()
        emittedScreenRules.clear()
        scope.cancel()
    }

    private fun evaluateScreenOnRules(features: List<FeatureRef>) {
        if (!interactive) return
        val nowElapsed = SystemClock.elapsedRealtime()
        features.forEach { feature ->
            val from = feature.config.string("from", "screen_on")
            val thresholdMs = (feature.config["seconds"].numberOrNull() ?: 0.0)
                .coerceAtLeast(0.0).times(1_000.0).toLong()
            val base = when (from) {
                "system_ready" -> 0L
                else -> interactiveSinceElapsed
            }
            if (base <= 0L && from != "system_ready") return@forEach
            val elapsed = (nowElapsed - base).coerceAtLeast(0L)
            if (elapsed < thresholdMs) return@forEach
            val key = screenRuleKey(feature)
            val previous = emittedScreenRules[key]
            if (previous != null && previous == base) return@forEach
            emittedScreenRules[key] = base
            emitter?.emit(
                RuntimeEvent(
                    "android.event.screen_on_duration",
                    mapOf(
                        "seconds" to ConfigValue.NumberValue(elapsed / 1_000.0),
                        "thresholdSeconds" to ConfigValue.NumberValue(thresholdMs / 1_000.0),
                        "from" to ConfigValue.StringValue(from),
                        "ruleKey" to ConfigValue.StringValue(screenRuleKey(feature)),
                    ),
                    source = id,
                )
            )
        }
    }

    private fun evaluateCpu(features: List<FeatureRef>) {
        val current = readCpuSnapshot() ?: return
        val previous = previousCpu
        previousCpu = current
        if (previous == null) return
        val totalDelta = current.total - previous.total
        val idleDelta = current.idle - previous.idle
        if (totalDelta <= 0L) return
        val available = (idleDelta.toDouble() * 100.0 / totalDelta.toDouble()).coerceIn(0.0, 100.0)
        val now = System.currentTimeMillis()
        cpuAvailabilitySamples.addLast(now to available)
        val maxWindow = features.maxOfOrNull {
            (it.config["pastWindowMs"].numberOrNull() ?: 60_000.0).toLong().coerceIn(1_000L, 3_600_000L)
        } ?: 60_000L
        while (cpuAvailabilitySamples.isNotEmpty() && now - cpuAvailabilitySamples.first().first > maxWindow) {
            cpuAvailabilitySamples.removeFirst()
        }
        features.forEach { feature ->
            val windowMs = (feature.config["pastWindowMs"].numberOrNull() ?: 60_000.0)
                .toLong().coerceIn(1_000L, 3_600_000L)
            val rolling = cpuAvailabilitySamples.asSequence()
                .filter { now - it.first <= windowMs }
                .map { it.second }
                .toList()
            val average = rolling.average().takeIf(Double::isFinite) ?: available
            emitter?.emit(
                RuntimeEvent(
                    "android.event.cpu_availability",
                    mapOf(
                        "latestPercent" to ConfigValue.NumberValue(available),
                        "averagePercent" to ConfigValue.NumberValue(average),
                        "windowMs" to ConfigValue.NumberValue(windowMs.toDouble()),
                    ),
                    source = id,
                )
            )
        }
    }

    private fun emitFirstUserPresent() {
        val bootId = currentBootId()
        if (prefs.getString("first_present_boot", null) == bootId) return
        prefs.edit().putString("first_present_boot", bootId).apply()
        emitter?.emit(
            RuntimeEvent(
                "android.event.user_present_first_after_boot",
                mapOf("bootId" to ConfigValue.StringValue(bootId)),
                source = id,
            )
        )
    }

    private fun currentBootId(): String =
        runCatching { File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()
            .takeUnless(String?::isNullOrBlank)
            ?: (System.currentTimeMillis() - SystemClock.elapsedRealtime()).toString()

    private fun readCpuSnapshot(): CpuSnapshot? = runCatching {
        val parts = File("/proc/stat").useLines { lines -> lines.first { it.startsWith("cpu ") } }
            .trim().split(Regex("\\s+")).drop(1).mapNotNull(String::toLongOrNull)
        if (parts.size < 4) return@runCatching null
        val idle = parts.getOrElse(3) { 0L } + parts.getOrElse(4) { 0L }
        CpuSnapshot(parts.sum(), idle)
    }.getOrNull()

    private fun screenRuleKey(feature: FeatureRef): String =
        feature.config.string("from", "screen_on") + ":" +
            (feature.config["seconds"].numberOrNull() ?: 0.0)

    private data class CpuSnapshot(val total: Long, val idle: Long)
}
