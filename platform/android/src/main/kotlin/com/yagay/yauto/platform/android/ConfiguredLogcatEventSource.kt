package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/** Starts a root logcat stream only while at least one enabled System Log Entry trigger exists. */
class ConfiguredLogcatEventSource(
    private val workspace: WorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "android.logcat.configured"
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var emitter: RuntimeEventEmitter? = null
    private var refreshJob: Job? = null
    private var readerJob: Job? = null
    @Volatile private var process: Process? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        refreshJob = scope.launch {
            while (isActive && started.get()) {
                val needed = runCatching {
                    workspace.load().automations.any { automation ->
                        automation.enabled && automation.activation.events.any { it.typeId == "android.event.system_log_entry" }
                    }
                }.getOrDefault(false)
                if (needed && readerJob?.isActive != true) startReader()
                if (!needed && readerJob != null) stopReader()
                delay(2_000L)
            }
        }
    }

    private fun startReader() {
        readerJob = scope.launch {
            while (isActive && started.get()) {
                val running = runCatching {
                    ProcessBuilder("su", "-c", "logcat -v brief").redirectErrorStream(true).start()
                }.recoverCatching {
                    ProcessBuilder("logcat", "-v", "brief").redirectErrorStream(true).start()
                }.getOrNull()
                if (running == null) {
                    delay(5_000L)
                    continue
                }
                process = running
                runCatching {
                    BufferedReader(InputStreamReader(running.inputStream)).useLines { lines ->
                        lines.forEach { line ->
                            if (!started.get()) return@forEach
                            val parsed = parseLogcatLine(line)
                            emitter?.emit(
                                RuntimeEvent(
                                    typeId = "android.event.system_log_entry",
                                    payload = mapOf(
                                        "priority" to ConfigValue.StringValue(parsed.priority),
                                        "tag" to ConfigValue.StringValue(parsed.tag),
                                        "pid" to ConfigValue.NumberValue(parsed.pid.toDouble()),
                                        "message" to ConfigValue.StringValue(parsed.message),
                                        "line" to ConfigValue.StringValue(line),
                                    ),
                                    source = id,
                                )
                            )
                        }
                    }
                }
                runCatching { running.destroy() }
                process = null
                if (isActive && started.get()) delay(1_500L)
            }
        }
    }

    private fun stopReader() {
        readerJob?.cancel()
        readerJob = null
        runCatching { process?.destroy() }
        process = null
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        refreshJob?.cancel()
        refreshJob = null
        stopReader()
        emitter = null
        scope.cancel()
    }
}

internal data class ParsedLogcatLine(
    val priority: String,
    val tag: String,
    val pid: Int,
    val message: String,
)

internal fun parseLogcatLine(line: String): ParsedLogcatLine {
    val match = LOGCAT_BRIEF.matchEntire(line)
    return if (match == null) {
        ParsedLogcatLine("", "", -1, line)
    } else {
        ParsedLogcatLine(
            priority = match.groupValues[1],
            tag = match.groupValues[2].trim(),
            pid = match.groupValues[3].toIntOrNull() ?: -1,
            message = match.groupValues[4],
        )
    }
}

private val LOGCAT_BRIEF = Regex("""^([VDIWEAF])/(.+?)\(\s*(\d+)\):\s?(.*)$""")
