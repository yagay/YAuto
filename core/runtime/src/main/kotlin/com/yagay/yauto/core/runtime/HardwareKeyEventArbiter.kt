package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.capability.CapabilityBackendPriority
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string

/**
 * Combines observations of one physical key transition before it enters the
 * runtime and gesture state machine. This only arbitrates events, never grabs
 * input devices or disables other backend permissions.
 *
 * Never conflate unknown Linux scan codes with unrelated Android KeyCodes:
 * doing so would silently destroy genuine simultaneous-key combinations.
 */
class HardwareKeyEventArbiter(
    private val publish: (RuntimeEvent) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val privilegedFirst: () -> Boolean = { true },
) {
    private data class Entry(
        val signature: String,
        val atMs: Long,
        var event: RuntimeEvent,
        var rank: Int,
        val deviceId: Int,
    )
    private data class Delivered(
        val signature: String,
        val atMs: Long,
        val source: String,
        val deviceId: Int,
    )

    private val lock = Any()
    private val pending = ArrayList<Entry>()
    private val delivered = ArrayList<Delivered>()
    /** Keep a physical press on one backend until release to prevent split gestures. */
    private val pressOwners = HashMap<String, String>()
    private var closed = false

    fun accept(event: RuntimeEvent) {
        if (event.typeId != "android.event.hardware_key") {
            publish(event)
            return
        }
        val signature = signature(event)
        if (signature == null) {
            // Invalid/missing physical identity cannot safely be coalesced.
            publish(event)
            return
        }
        val now = clock()
        val identity = signature.substringBefore(":${event.payload.string("action")}:")
        val owner = synchronized(lock) { pressOwners[identity] }
        val rank = rank(event.source) - if (owner == event.source) 10 else 0
        val deviceId = event.payload["deviceId"].numberOrNull()?.toInt() ?: -1
        var created: Entry? = null
        synchronized(lock) {
            if (closed) return
            // Suppress a late report of an already delivered transition from
            // another observer without suppressing a second press on the same
            // source (which may legitimately occur within 180 ms).
            if (delivered.any { it.signature == signature && it.source != event.source &&
                    sameDevice(it.deviceId, deviceId) &&
                    now >= it.atMs && now - it.atMs < LATE_DUPLICATE_MS }) return
            val match = pending.firstOrNull {
                it.signature == signature && it.event.source != event.source &&
                    sameDevice(it.deviceId, deviceId) &&
                    now >= it.atMs && now - it.atMs < COLLECTION_MS
            }
            if (match != null) {
                if (rank < match.rank) {
                    match.event = event
                    match.rank = rank
                }
            } else {
                created = Entry(signature, now, event, rank, deviceId).also(pending::add)
            }
        }
        created?.let { entry ->
            schedule(COLLECTION_MS) {
                val selected = synchronized(lock) {
                    if (closed || !pending.remove(entry)) null
                    else {
                        delivered.add(Delivered(entry.signature, clock(), entry.event.source, entry.deviceId))
                        if (delivered.size > 128) delivered.subList(0, delivered.size - 128).clear()
                        val selectedIdentity = entry.signature.substringBefore(
                            ":${entry.event.payload.string("action")}:"
                        )
                        when (entry.event.payload.string("action")) {
                            "down" -> pressOwners[selectedIdentity] = entry.event.source
                            "up" -> pressOwners.remove(selectedIdentity)
                        }
                        entry.event
                    }
                }
                if (selected != null) publish(selected)
            }
        }
    }

    fun close() = synchronized(lock) {
        closed = true
        pending.clear()
        delivered.clear()
        pressOwners.clear()
    }

    private fun signature(event: RuntimeEvent): String? {
        val action = event.payload.string("action")
        if (action != "down" && action != "up") return null
        val key = event.payload["keyCode"].numberOrNull()?.toInt() ?: 0
        val scan = event.payload["scanCode"].numberOrNull()?.toInt() ?: 0
        val linux = event.payload["linuxEvKey"].numberOrNull()?.toInt() ?: 0
        val identity = when {
            key > 0 -> "k:$key"
            scan > 0 -> "s:$scan"
            linux > 0 -> "l:$linux"
            else -> return null
        }
        val repeat = event.payload["repeatCount"].numberOrNull()?.toInt() ?: 0
        return "$identity:$action:$repeat"
    }

    private fun sameDevice(first: Int, second: Int): Boolean =
        first < 0 || second < 0 || first == second

    private fun rank(source: String): Int = when {
        source.startsWith("lsposed") -> CapabilityBackendPriority.rank("lsposed", privilegedFirst())
        source.startsWith("root") -> CapabilityBackendPriority.rank("root", privilegedFirst())
        source.startsWith("shizuku") -> CapabilityBackendPriority.rank("shizuku", privilegedFirst())
        source.startsWith("accessibility") -> CapabilityBackendPriority.rank("accessibility", privilegedFirst())
        else -> 5
    }

    companion object {
        const val COLLECTION_MS = 65L
        const val LATE_DUPLICATE_MS = 180L
    }
}
