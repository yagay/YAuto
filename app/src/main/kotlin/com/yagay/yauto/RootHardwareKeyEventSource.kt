package com.yagay.yauto

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.platform.android.AndroidEventSource
import com.yagay.yauto.platform.android.RuntimeEventEmitter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Raw Linux input fallback for OEM keys that never reach Accessibility or Android KeyEvent.
 *
 * It is active only while the workspace contains an enabled hardware-key trigger whose Android
 * keyCode is UNKNOWN (0) and whose Linux scanCode is configured. Standard keys remain on the
 * Accessibility path to avoid duplicate trigger delivery.
 */
class RootHardwareKeyEventSource(
    private val workspace: ObservableWorkspaceRepository,
) : AndroidEventSource {
    override val id: String = "root.hardware-key.raw"

    private val started = AtomicBoolean(false)
    private val lock = Any()
    @Volatile private var emitter: RuntimeEventEmitter? = null
    @Volatile private var subscription: AutoCloseable? = null
    @Volatile private var process: Process? = null
    @Volatile private var readerThread: Thread? = null
    @Volatile private var listening = false

    override fun start(emitter: RuntimeEventEmitter) {
        if (!started.compareAndSet(false, true)) return
        this.emitter = emitter
        subscription = workspace.addListener(::applyWorkspace)
        workspace.snapshotOrNull()?.let(::applyWorkspace)
    }

    override fun stop() {
        if (!started.compareAndSet(true, false)) return
        subscription?.close()
        subscription = null
        synchronized(lock) { stopListenerLocked() }
        emitter = null
    }

    private fun applyWorkspace(data: WorkspaceData) {
        val needed = data.automations.any { automation ->
            automation.enabled && automation.activation.events.any { event ->
                when (event.typeId) {
                    HARDWARE_KEY_EVENT,
                    HARDWARE_KEY_GESTURE_EVENT -> {
                        val keyCode = (event.config["keyCode"] as? ConfigValue.NumberValue)?.value?.toInt()
                        val scanCode = (event.config["scanCode"] as? ConfigValue.NumberValue)?.value?.toInt()
                        keyCode == 0 && scanCode != null && scanCode > 0
                    }
                    HARDWARE_KEY_COMBO_EVENT -> {
                        val keyCode1 = (event.config["keyCode1"] as? ConfigValue.NumberValue)?.value?.toInt()
                        val scanCode1 = (event.config["scanCode1"] as? ConfigValue.NumberValue)?.value?.toInt()
                        val keyCode2 = (event.config["keyCode2"] as? ConfigValue.NumberValue)?.value?.toInt()
                        val scanCode2 = (event.config["scanCode2"] as? ConfigValue.NumberValue)?.value?.toInt()
                        (keyCode1 == 0 && scanCode1 != null && scanCode1 > 0) ||
                            (keyCode2 == 0 && scanCode2 != null && scanCode2 > 0)
                    }
                    else -> false
                }
            }
        }
        synchronized(lock) {
            if (!started.get()) return
            if (needed) startListenerLocked() else stopListenerLocked()
        }
    }

    private fun startListenerLocked() {
        if (listening) return
        listening = true
        readerThread = Thread({
            try {
                val child = ProcessBuilder("su", "-c", "getevent -t 2>/dev/null")
                    .redirectErrorStream(true)
                    .start()
                process = child
                child.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (!started.get() || !listening) return@forEach
                        parseRawKey(line)?.let { emit(it) }
                    }
                }
            } catch (_: Exception) {
                // Root denial or missing getevent leaves this fallback inactive; Accessibility and
                // LSPosed paths remain available.
            } finally {
                process?.destroyForcibly()
                process = null
                listening = false
            }
        }, "yauto-root-key-input").apply {
            isDaemon = true
            start()
        }
    }

    private fun stopListenerLocked() {
        listening = false
        process?.destroyForcibly()
        process = null
        readerThread?.interrupt()
        readerThread = null
    }

    private fun emit(event: RawKeyEvent) {
        val action = when (event.value) {
            0 -> "up"
            1, 2 -> "down"
            else -> return
        }
        emitter?.emit(
            RuntimeEvent(
                typeId = HARDWARE_KEY_EVENT,
                payload = mapOf(
                    "keyCode" to ConfigValue.NumberValue(0.0),
                    "scanCode" to ConfigValue.NumberValue(event.scanCode.toDouble()),
                    "action" to ConfigValue.StringValue(action),
                    "repeatCount" to ConfigValue.NumberValue(if (event.value == 2) 1.0 else 0.0),
                    "metaState" to ConfigValue.NumberValue(0.0),
                    "deviceId" to ConfigValue.NumberValue(-1.0),
                ),
                source = "root.input",
            )
        )
    }

    private fun parseRawKey(line: String): RawKeyEvent? {
        val match = RAW_KEY_EVENT.find(line) ?: return null
        val scanCode = match.groupValues[1].toIntOrNull(16) ?: return null
        val value = match.groupValues[2].toIntOrNull(16) ?: return null
        if (value !in 0..2) return null
        return RawKeyEvent(scanCode, value)
    }

    private data class RawKeyEvent(
        val scanCode: Int,
        val value: Int,
    )

    private companion object {
        const val HARDWARE_KEY_EVENT = "android.event.hardware_key"
        const val HARDWARE_KEY_GESTURE_EVENT = "android.event.hardware_key_gesture"
        const val HARDWARE_KEY_COMBO_EVENT = "android.event.hardware_key_combo"
        val RAW_KEY_EVENT = Regex(":\\s+0001\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})")
    }
}
