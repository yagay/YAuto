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
) {
    private data class Entry(
        val signature: String,
        val atMs: Long,
        var event: RuntimeEvent,
        var rank: Int,
    )
    private data class Delivered(
        val signature: String,
        val atMs: Long,
        val source: String,
    )

    private val lock = Any()
    private val pending = ArrayList<Entry>()
    private val delivered = ArrayList<Delivered>()
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
        val rank = rank(event.source)
        var created: Entry? = null
        synchronized(lock) {
            if (closed) return
            // Suppress a late report of an already delivered transition from
            // another observer without suppressing a second press on the same
            // source (which may legitimately occur within 180 ms).
            if (delivered.any { it.signature == signature && it.source != event.source &&
                    now >= it.atMs && now - it.atMs < LATE_DUPLICATE_MS }) return
            val match = pending.firstOrNull {
                it.signature == signature && it.event.source != event.source &&
                    now >= it.atMs && now - it.atMs < COLLECTION_MS
            }
            if (match != null) {
                if (rank < match.rank) {
                    match.event = event
                    match.rank = rank
                }
            } else {
                created = Entry(signature, now, event, rank).also(pending::add)
            }
        }
        created?.let { entry ->
            schedule(COLLECTION_MS) {
                val selected = synchronized(lock) {
                    if (closed || !pending.remove(entry)) null
                    else {
                        delivered.add(Delivered(entry.signature, clock(), entry.event.source))
                        if (delivered.size > 128) delivered.subList(0, delivered.size - 128).clear()
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
        val device = event.payload["deviceId"].numberOrNull()?.toInt() ?: -1
        // Android's key identity is more useful than a backend-local deviceId.
        // Preserve distinct known devices when both observations identify them.
        val repeat = event.payload["repeatCount"].numberOrNull()?.toInt() ?: 0
        return "$identity:$action:$repeat" +
            if (device >= 0) ":dev:$device" else ""
    }

    private fun rank(source: String): Int = when {
        source.startsWith("lsposed") -> CapabilityBackendPriority.rank("lsposed", true)
        source.startsWith("root") -> CapabilityBackendPriority.rank("root", true)
        source.startsWith("shizuku") -> CapabilityBackendPriority.rank("shizuku", true)
        source.startsWith("accessibility") -> CapabilityBackendPriority.rank("accessibility", true)
        else -> 5
    }

    companion object {
        const val COLLECTION_MS = 65L
        const val LATE_DUPLICATE_MS = 180L
    }
}
