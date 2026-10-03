package com.yagay.yauto.platform.android

/** Lifecycle phase used by the runtime to report isolated event-source failures. */
enum class EventSourcePhase { CONSTRUCT, START, STOP }

data class EventSourceFailure(
    val phase: EventSourcePhase,
    val component: String,
    val sourceId: String? = null,
    val error: Throwable,
)

/**
 * Owns Android event-source lifecycle for the automation runtime.
 *
 * Sources are deliberately isolated from each other: one bad source must not prevent unrelated
 * triggers from starting or stopping. The manager also enforces unique source IDs so a newly added
 * trigger backend cannot silently register duplicate listeners.
 */
class AndroidEventSourceManager {
    private data class Entry(val component: String, val source: AndroidEventSource)

    private val entries = mutableListOf<Entry>()

    fun add(component: String, factory: () -> AndroidEventSource): EventSourceFailure? {
        return try {
            val source = factory()
            require(entries.none { it.source.id == source.id }) {
                "Duplicate AndroidEventSource id: ${source.id}"
            }
            entries += Entry(component, source)
            null
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath) throw error
            EventSourceFailure(EventSourcePhase.CONSTRUCT, component, error = error)
        }
    }

    fun startAll(emitter: RuntimeEventEmitter): List<EventSourceFailure> = buildList {
        entries.forEach { entry ->
            try {
                entry.source.start(emitter)
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                add(EventSourceFailure(EventSourcePhase.START, entry.component, entry.source.id, error))
            }
        }
    }

    fun stopAll(): List<EventSourceFailure> = buildList {
        entries.asReversed().forEach { entry ->
            try {
                entry.source.stop()
            } catch (error: Throwable) {
                if (error is VirtualMachineError || error is ThreadDeath) throw error
                add(EventSourceFailure(EventSourcePhase.STOP, entry.component, entry.source.id, error))
            }
        }
    }

    fun clear() {
        entries.clear()
    }

    fun sourceIds(): List<String> = entries.map { it.source.id }
}
