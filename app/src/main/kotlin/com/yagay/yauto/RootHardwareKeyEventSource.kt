package com.yagay.yauto

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.activeActivationEvents
import com.yagay.yauto.platform.android.AndroidEventSource
import com.yagay.yauto.platform.android.RuntimeEventEmitter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Android OEM-key fallback for buttons that do not reliably reach Android KeyEvent.
 *
 * User-facing rules remain Android KeyCode/scanCode based. The Linux EV_KEY value is stored as
 * hidden learned identity and is used only when the Android input path is unavailable.
 */
class RootHardwareKeyEventSource(
    private val workspace: ObservableWorkspaceRepository,
    private val rootShell: com.yagay.yauto.platform.root.RootShell,
) : AndroidEventSource {
    override val id: String = "root.hardware-key.raw"

    private val started = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var emitter: RuntimeEventEmitter? = null
    @Volatile private var subscription: AutoCloseable? = null
    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var listening = false
    @Volatile private var learningPaused = false
    /** Generation changes on stop/restart, invalidating any in-flight process creation. */
    @Volatile private var generation = 0L
    @Volatile private var learnedMappings: Map<Int, List<LearnedAndroidKey>> = emptyMap()

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        activeReaders.add(this)
        setLearningPaused(HardwareKeyCaptureSession.active)
        subscription = workspace.addListener(::applyWorkspace)
        workspace.snapshotOrNull()?.let(::applyWorkspace)
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        subscription?.close()
        subscription = null
        activeReaders.remove(this)
        synchronized(lock) { stopListenerLocked() }
        emitter = null
    }

    private fun applyWorkspace(data: WorkspaceData) {
        val mappings = linkedMapOf<Int, MutableList<LearnedAndroidKey>>()
        data.activeActivationEvents().forEach { event ->
                when (event.typeId) {
                    HARDWARE_KEY_EVENT,
                    HARDWARE_KEY_GESTURE_EVENT -> event.config.rawMapping()?.let { mappings.getOrPut(it.first) { mutableListOf() }.add(it.second) }

                    HARDWARE_KEY_COMBO_EVENT -> {
                        event.config.rawMapping("1")?.let { mappings.getOrPut(it.first) { mutableListOf() }.add(it.second) }
                        event.config.rawMapping("2")?.let { mappings.getOrPut(it.first) { mutableListOf() }.add(it.second) }
                    }
                }
            }
        learnedMappings = mappings.mapValues { (_, matches) -> matches.distinct() }

        synchronized(lock) {
            if (!started.get()) return
            if (mappings.isNotEmpty() && !learningPaused) startListenerLocked() else stopListenerLocked()
        }
    }

    private fun setLearningPaused(paused: Boolean) {
        synchronized(lock) {
            learningPaused = paused
            if (paused) {
                stopListenerLocked()
            } else if (started.get() && learnedMappings.isNotEmpty()) {
                startListenerLocked()
            }
        }
    }

    private fun Map<String, ConfigValue>.rawMapping(
        suffix: String = "",
    ): Pair<Int, LearnedAndroidKey>? {
        val hidden = (this["hardwareIdentity$suffix"] as? ConfigValue.ObjectValue)?.value.orEmpty()
        val evKey = (hidden["linuxEvKey"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 0
        if (evKey > 0) {
            val learnedKeyCode = (hidden["androidKeyCode"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 0
            val learnedScanCode = (hidden["androidScanCode"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 0
            val rawDevicePath = (hidden["deviceDescriptor"] as? ConfigValue.StringValue)?.value.orEmpty()
                .takeIf { it.startsWith("/dev/input/event") }.orEmpty()
            return evKey to LearnedAndroidKey(learnedKeyCode, learnedScanCode, rawDevicePath)
        }

        // Compatibility for rules created before EV_KEY and Android scanCode were separated.
        val keyCode = (this["keyCode$suffix"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 0
        val legacyRaw = (this["scanCode$suffix"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 0
        return if (keyCode == 0 && legacyRaw > 0) {
            legacyRaw to LearnedAndroidKey(0, 0)
        } else {
            null
        }
    }

    private fun startListenerLocked() {
        if (listening) return
        listening = true
        val myGeneration = ++generation
        readerThread = Thread({
            var backoffMs = 2_000L
            try {
                // A one-off getevent reader silently dies after su revocation,
                // process restarts, or input service resets. Restart while OEM
                // key rules remain subscribed, with bounded exponential backoff.
                while (myGeneration == generation && started.get() && listening) {
                    var child: Process? = null
                    try {
                        val opened = ProcessBuilder(rootShell.commandArguments("getevent -t 2>/dev/null"))
                            .redirectErrorStream(true)
                            .start()
                        child = opened
                        val accepted = synchronized(lock) {
                            if (myGeneration != generation || !listening || !started.get()) false
                            else { process = opened; true }
                        }
                        if (!accepted) break
                        opened.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { line ->
                                if (myGeneration == generation && started.get() && listening) {
                                    parseRawKey(line)?.let(::emit)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // A rejected Root grant or temporarily inaccessible input
                        // device must not permanently disable a learned OEM rule.
                    } finally {
                        child?.destroyForcibly()
                        synchronized(lock) {
                            if (process === child) process = null
                        }
                    }
                    if (myGeneration != generation || !started.get() || !listening) break
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        break
                    }
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                }
            } finally {
                synchronized(lock) {
                    if (myGeneration == generation) {
                        process = null
                        readerThread = null
                        listening = false
                    }
                }
            }
        }, "yauto-root-key-input").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopListenerLocked() {
        generation++
        listening = false
        process?.destroyForcibly()
        process = null
        readerThread?.interrupt()
        readerThread = null
    }

    private fun emit(event: RawKeyEvent) {
        // Never dispatch unrelated OEM keys as a second observation. The
        // Root listener is a fallback for keys explicitly learned by a rule.
        val choices = learnedMappings[event.evKey].orEmpty()
        // An EV_KEY number is not globally unique across input devices.
        // Unknown or conflicting device mappings are ambiguous: never guess.
        val specific = choices.filter { it.devicePath.isNotEmpty() && it.devicePath == event.devicePath }
        val eligible = if (specific.isNotEmpty()) specific else choices.filter { it.devicePath.isEmpty() }
        val mapped = eligible.distinct().singleOrNull() ?: return
        val action = when (event.value) {
            0 -> "up"
            1, 2 -> "down"
            else -> return
        }
        emitter?.emit(
            RuntimeEvent(
                typeId = HARDWARE_KEY_EVENT,
                payload = buildMap {
                    put("keyCode", ConfigValue.NumberValue(mapped.keyCode.toDouble()))
                    put("scanCode", ConfigValue.NumberValue(mapped.scanCode.toDouble()))
                    put("linuxEvKey", ConfigValue.NumberValue(event.evKey.toDouble()))
                    put("action", ConfigValue.StringValue(action))
                    put("repeatCount", ConfigValue.NumberValue(if (event.value == 2) 1.0 else 0.0))
                    put("metaState", ConfigValue.NumberValue(0.0))
                    put("deviceId", ConfigValue.NumberValue(-1.0))
                    if (event.devicePath.isNotBlank()) {
                        put("deviceDescriptor", ConfigValue.StringValue(event.devicePath))
                    }
                },
                source = "root.input",
            )
        )
    }

    private fun parseRawKey(line: String): RawKeyEvent? {
        val match = RAW_KEY_EVENT.find(line) ?: return null
        val evKey = match.groupValues[1].toIntOrNull(16) ?: return null
        val value = match.groupValues[2].toIntOrNull(16) ?: return null
        if (value !in 0..2) return null
        val devicePath = RAW_DEVICE_PATH.find(line)?.groupValues?.getOrNull(1).orEmpty()
        return RawKeyEvent(evKey, value, devicePath)
    }

    private data class RawKeyEvent(
        val evKey: Int,
        val value: Int,
        val devicePath: String,
    )

    private data class LearnedAndroidKey(
        val keyCode: Int,
        val scanCode: Int,
        val devicePath: String = "",
    )

    companion object {
        private val activeReaders = CopyOnWriteArraySet<RootHardwareKeyEventSource>()

        internal fun pauseReadersForLearning() {
            activeReaders.forEach { it.setLearningPaused(true) }
        }

        internal fun resumeReadersAfterLearning() {
            activeReaders.forEach { it.setLearningPaused(false) }
        }

        private const val HARDWARE_KEY_EVENT = "android.event.hardware_key"
        const val HARDWARE_KEY_GESTURE_EVENT = "android.event.hardware_key_gesture"
        const val HARDWARE_KEY_COMBO_EVENT = "android.event.hardware_key_combo"
        val RAW_KEY_EVENT = Regex(":\\s+0001\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})")
        val RAW_DEVICE_PATH = Regex("(/dev/input/event\\d+):")
    }
}
