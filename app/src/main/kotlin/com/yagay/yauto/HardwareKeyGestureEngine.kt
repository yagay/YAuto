package com.yagay.yauto

import android.os.SystemClock
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Derives ShortX-style key gestures and key combinations from the single raw hardware-key stream.
 *
 * Input capture stays in Accessibility/LSPosed/root. Keeping gesture state here avoids long-lived
 * state machines inside system_server and gives every backend identical gesture semantics.
 */
internal class HardwareKeyGestureEngine(
    private val scope: CoroutineScope,
    private val emit: (RuntimeEvent) -> Unit,
) {
    private val lock = Any()
    private val activeKeys = linkedMapOf<KeyIdentity, DownState>()
    private val sequences = linkedMapOf<KeyIdentity, PressSequence>()
    private val activeCombos = linkedSetOf<KeyPair>()

    fun accept(event: RuntimeEvent) {
        if (event.typeId != RAW_EVENT) return
        val identity = event.identity() ?: return
        when (event.payload.string("action")) {
            "down" -> onDown(identity, event)
            "up" -> onUp(identity, event)
        }
    }

    fun clear() {
        synchronized(lock) {
            sequences.values.forEach { it.finalizeJob?.cancel() }
            sequences.clear()
            activeKeys.clear()
            activeCombos.clear()
        }
    }

    private fun onDown(identity: KeyIdentity, event: RuntimeEvent) {
        val repeatCount = event.payload["repeatCount"].numberOrNull()?.toInt() ?: 0
        if (repeatCount > 0) return

        val comboEvents = mutableListOf<RuntimeEvent>()
        synchronized(lock) {
            if (identity in activeKeys) return
            activeKeys[identity] = DownState(SystemClock.elapsedRealtime())

            activeKeys.keys
                .asSequence()
                .filter { it != identity && compatibleDevices(it.deviceId, identity.deviceId) }
                .forEach { other ->
                    val pair = KeyPair.of(other, identity)
                    if (activeCombos.add(pair)) {
                        comboEvents += comboEvent(pair, event)
                    }
                }
        }
        comboEvents.forEach(emit)
    }

    private fun onUp(identity: KeyIdentity, event: RuntimeEvent) {
        val now = SystemClock.elapsedRealtime()
        var immediate: RuntimeEvent? = null

        synchronized(lock) {
            val down = activeKeys.remove(identity) ?: DownState(now)
            activeCombos.removeAll { identity == it.first || identity == it.second }
            val holdMs = (now - down.startedElapsedMs).coerceAtLeast(0L)

            val existing = sequences[identity]
            if (existing != null && now - existing.lastReleaseElapsedMs > MULTI_PRESS_WINDOW_MS) {
                existing.finalizeJob?.cancel()
                sequences.remove(identity)
            }

            val state = sequences.getOrPut(identity) { PressSequence() }
            state.finalizeJob?.cancel()
            state.pressCount += 1
            state.maxHoldMs = maxOf(state.maxHoldMs, holdMs)
            state.lastReleaseElapsedMs = now
            state.source = event.source

            if (state.pressCount >= 3) {
                sequences.remove(identity)
                immediate = gestureEvent(identity, state)
            } else {
                val expectedCount = state.pressCount
                state.finalizeJob = scope.launch {
                    delay(MULTI_PRESS_WINDOW_MS)
                    val finalized = synchronized(lock) {
                        val current = sequences[identity]
                        if (current == null || current.pressCount != expectedCount) {
                            null
                        } else {
                            sequences.remove(identity)
                            current
                        }
                    }
                    if (finalized != null) emit(gestureEvent(identity, finalized))
                }
            }
        }

        immediate?.let(emit)
    }

    private fun gestureEvent(
        identity: KeyIdentity,
        state: PressSequence,
    ): RuntimeEvent {
        val gesture = when {
            state.pressCount >= 3 -> "triple_press"
            state.pressCount == 2 -> "double_press"
            state.maxHoldMs >= DEFAULT_LONG_PRESS_MS -> "long_press"
            else -> "single_press"
        }
        return RuntimeEvent(
            typeId = GESTURE_EVENT,
            payload = mapOf(
                "keyCode" to ConfigValue.NumberValue(identity.keyCode.toDouble()),
                "scanCode" to ConfigValue.NumberValue(identity.scanCode.toDouble()),
                "deviceId" to ConfigValue.NumberValue(identity.deviceId.toDouble()),
                "pressCount" to ConfigValue.NumberValue(state.pressCount.toDouble()),
                "maxHoldMs" to ConfigValue.NumberValue(state.maxHoldMs.toDouble()),
                "gesture" to ConfigValue.StringValue(gesture),
            ),
            source = state.source.ifBlank { "hardware-key.gesture" },
        )
    }

    private fun comboEvent(pair: KeyPair, source: RuntimeEvent): RuntimeEvent =
        RuntimeEvent(
            typeId = COMBO_EVENT,
            payload = mapOf(
                "keyCode1" to ConfigValue.NumberValue(pair.first.keyCode.toDouble()),
                "scanCode1" to ConfigValue.NumberValue(pair.first.scanCode.toDouble()),
                "keyCode2" to ConfigValue.NumberValue(pair.second.keyCode.toDouble()),
                "scanCode2" to ConfigValue.NumberValue(pair.second.scanCode.toDouble()),
                "deviceId" to ConfigValue.NumberValue(
                    if (pair.first.deviceId == pair.second.deviceId) pair.first.deviceId.toDouble() else -1.0
                ),
            ),
            source = source.source.ifBlank { "hardware-key.combo" },
        )

    private fun RuntimeEvent.identity(): KeyIdentity? {
        val keyCode = payload["keyCode"].numberOrNull()?.toInt() ?: 0
        val scanCode = payload["scanCode"].numberOrNull()?.toInt() ?: 0
        val deviceId = payload["deviceId"].numberOrNull()?.toInt() ?: -1
        if (keyCode <= 0 && scanCode <= 0) return null
        return KeyIdentity(keyCode, scanCode, deviceId)
    }

    private fun compatibleDevices(first: Int, second: Int): Boolean =
        first < 0 || second < 0 || first == second

    private data class KeyIdentity(
        val keyCode: Int,
        val scanCode: Int,
        val deviceId: Int,
    ) {
        val sortKey: String
            get() = "%08d:%08d:%08d".format(deviceId, keyCode, scanCode)
    }

    private data class KeyPair(
        val first: KeyIdentity,
        val second: KeyIdentity,
    ) {
        companion object {
            fun of(left: KeyIdentity, right: KeyIdentity): KeyPair =
                if (left.sortKey <= right.sortKey) KeyPair(left, right) else KeyPair(right, left)
        }
    }

    private data class DownState(
        val startedElapsedMs: Long,
    )

    private data class PressSequence(
        var pressCount: Int = 0,
        var maxHoldMs: Long = 0L,
        var lastReleaseElapsedMs: Long = 0L,
        var source: String = "",
        var finalizeJob: Job? = null,
    )

    private companion object {
        const val RAW_EVENT = "android.event.hardware_key"
        const val GESTURE_EVENT = "android.event.hardware_key_gesture"
        const val COMBO_EVENT = "android.event.hardware_key_combo"
        const val MULTI_PRESS_WINDOW_MS = 350L
        const val DEFAULT_LONG_PRESS_MS = 500L
    }
}
