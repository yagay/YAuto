package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.storage.WorkspaceRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ConfiguredSoundLevelEventSource(
    context: Context,
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.sound.configured"
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val lastSampleAt = ConcurrentHashMap<String, Long>()
    private var job: Job? = null
    @Volatile private var emitter: RuntimeEventEmitter? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        job = scope.launch {
            while (isActive && started.get()) {
                val rules = loadRules()
                val active = rules.map(::soundRuleKey).toSet()
                lastSampleAt.keys.removeIf { it !in active }
                if (rules.isEmpty() || !hasPermission()) {
                    delay(2_000L)
                    continue
                }

                val now = System.currentTimeMillis()
                val due = rules.filter { rule ->
                    now - (lastSampleAt[soundRuleKey(rule)] ?: 0L) >=
                        rule.config.long("intervalMs", 1_000L).coerceIn(250L, 3_600_000L)
                }
                if (due.isEmpty()) {
                    delay(100L)
                    continue
                }

                val sampleMs = due.minOfOrNull {
                    it.config.long("sampleMs", 200L).coerceIn(50L, 5_000L)
                } ?: 200L
                val measurement = measure(sampleMs)
                if (measurement != null) {
                    due.forEach { rule ->
                        val key = soundRuleKey(rule)
                        lastSampleAt[key] = now
                        emitter.emit(
                            RuntimeEvent(
                                "android.event.sound_level",
                                mapOf(
                                    "ruleKey" to ConfigValue.StringValue(key),
                                    "dbfs" to ConfigValue.NumberValue(measurement.dbfs),
                                    "rms" to ConfigValue.NumberValue(measurement.rms),
                                    "peak" to ConfigValue.NumberValue(measurement.peak.toDouble()),
                                    "sampleMs" to ConfigValue.NumberValue(sampleMs.toDouble()),
                                ),
                                source = id,
                            )
                        )
                    }
                }
                delay(100L)
            }
        }
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        job?.cancel()
        job = null
        emitter = null
        lastSampleAt.clear()
        scope.cancel()
    }

    private suspend fun loadRules(): List<FeatureRef> = runCatching {
        workspace.load().automations.asSequence()
            .filter { it.enabled }
            .flatMap { it.activation.events.asSequence() }
            .filter { it.typeId == "android.event.sound_level" }
            .distinctBy(::soundRuleKey)
            .toList()
    }.getOrDefault(emptyList())

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun measure(sampleMs: Long): Measurement? {
        val sampleRate = 16_000
        val minSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minSize <= 0) return null
        val bufferSize = maxOf(minSize * 2, sampleRate / 2)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.DEFAULT,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull() ?: return null
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return null
        }
        return try {
            record.startRecording()
            val deadline = System.nanoTime() + sampleMs * 1_000_000L
            val buffer = ShortArray(minSize.coerceAtMost(4096))
            var sumSquares = 0.0
            var count = 0L
            var peak = 0
            while (System.nanoTime() < deadline) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                for (index in 0 until read) {
                    val value = buffer[index].toInt()
                    val abs = kotlin.math.abs(value)
                    if (abs > peak) peak = abs
                    sumSquares += value.toDouble() * value.toDouble()
                }
                count += read
            }
            if (count <= 0L) null
            else {
                val rms = sqrt(sumSquares / count.toDouble())
                val dbfs = if (rms <= 0.0) -120.0
                else (20.0 * log10(rms / Short.MAX_VALUE.toDouble())).coerceIn(-120.0, 0.0)
                Measurement(dbfs, rms, peak)
            }
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalStateException) {
            null
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    private data class Measurement(val dbfs: Double, val rms: Double, val peak: Int)
}

internal fun soundRuleKey(feature: FeatureRef): String =
    feature.config.entries
        .filterKeys { it !in setOf("source.raw", "source.type", "source.importer", "tag") }
        .toSortedMap()
        .entries
        .joinToString(";") { it.key + "=" + it.value }
