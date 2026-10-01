package com.yagay.yauto.core.logging

import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.FlowId
import com.yagay.yauto.core.model.NodeId
import kotlinx.serialization.Serializable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

@Serializable
enum class TraceKind {
    EXECUTION_START, EXECUTION_END,
    NODE_START, NODE_END,
    TRIGGER, STATE, CONDITION, ACTION, FLOW,
    CAPABILITY, IMPORT, MESSAGE, ERROR,
}

@Serializable
enum class TraceLevel { DEBUG, INFO, WARN, ERROR }

@Serializable
data class TraceEvent(
    val executionId: ExecutionId,
    val sequence: Long = 0,
    val kind: TraceKind,
    val level: TraceLevel = TraceLevel.INFO,
    val timestampEpochMs: Long,
    val message: String,
    val automationId: AutomationId? = null,
    val flowId: FlowId? = null,
    val nodeId: NodeId? = null,
    val featureId: String? = null,
    val backendId: String? = null,
    val success: Boolean? = null,
    val durationMs: Long? = null,
    val attributes: Map<String, String> = emptyMap(),
)

interface ExecutionTracer {
    suspend fun record(event: TraceEvent)
}

class SequencedExecutionTracer(
    private val delegate: ExecutionTracer,
) : ExecutionTracer {
    private val sequence = AtomicLong(0)
    override suspend fun record(event: TraceEvent) {
        delegate.record(event.copy(sequence = sequence.incrementAndGet()))
    }
}

class CompositeExecutionTracer(
    private val delegates: List<ExecutionTracer>,
) : ExecutionTracer {
    override suspend fun record(event: TraceEvent) {
        delegates.forEach { runCatching { it.record(event) } }
    }
}

class InMemoryExecutionTracer(
    private val maxEvents: Int = 10_000,
) : ExecutionTracer {
    private val events = CopyOnWriteArrayList<TraceEvent>()

    override suspend fun record(event: TraceEvent) {
        events += event
        while (events.size > maxEvents) events.removeAt(0)
    }

    fun snapshot(): List<TraceEvent> = events.toList()
    fun snapshot(executionId: ExecutionId): List<TraceEvent> = events.filter { it.executionId == executionId }
    fun clear() = events.clear()
}

object NoOpExecutionTracer : ExecutionTracer {
    override suspend fun record(event: TraceEvent) = Unit
}
