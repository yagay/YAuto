package com.yagay.yauto

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.platform.android.AndroidEventSource
import com.yagay.yauto.platform.android.RuntimeEventEmitter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android OEM-key fallback for buttons that do not reliably reach Android KeyEvent.
 *
 * User-facing rules remain Android KeyCode/scanCode based. The Linux EV_KEY value is stored as
 * hidden learned identity and is used only when the Android input path is unavailable.
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
                    HARDWARE_KEY_GESTURE_EVENT -> event.config.hasRawFallback()

                    HARDWARE_KEY_COMBO_EVENT ->
                        event.config.hasRawFallback("1") || event.config.hasRawFallback("2")

                    else -> false
                }
            }
        }
        synchronized(lock) {
            if (!started.get()) return
            if (needed) startListenerLocked() else stopListenerLocked()
        }
    }

    private fun Map<String, ConfigValue>.hasRawFallback(suffix: String = ""): Boolean {
        val hidden = (this["hardwareIdentity$suffix"] as? ConfigValue.ObjectValue)?.value.orEmpty()
        val learnedEvKey = (hidden["linuxEvKey"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 0
        if (learnedEvKey > 0) return true

        // Compatibility with rules learned before EV_KEY and Android scanCode were separated.
        val keyCode = (this["keyCode$suffix"] as? ConfigValue.NumberValue)?.value?.toInt()
        val legacyScan = (this["scanCode$suffix"] as? ConfigValue.NumberValue)?.value?.toInt()
        return keyCode == 0 && legacyScan != null && legacyScan > 0
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
                // Root denial or a missing getevent binary simply disables this OEM fallback.
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
                payload = buildMap {
                    put("keyCode", ConfigValue.NumberValue(0.0))
                    put("scanCode", ConfigValue.NumberValue(0.0))
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

    private companion object {
        const val HARDWARE_KEY_EVENT = "android.event.hardware_key"
        const val HARDWARE_KEY_GESTURE_EVENT = "android.event.hardware_key_gesture"
        const val HARDWARE_KEY_COMBO_EVENT = "android.event.hardware_key_combo"
        val RAW_KEY_EVENT = Regex(":\\s+0001\\s+([0-9a-fA-F]{4})\\s+([0-9a-fA-F]{8})")
        val RAW_DEVICE_PATH = Regex("(/dev/input/event\\d+):")
    }
}
