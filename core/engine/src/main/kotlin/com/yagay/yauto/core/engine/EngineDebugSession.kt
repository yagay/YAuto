package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.NodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One debug session per execution. Invocation IDs distinguish repeated and concurrently running
 * instances of the same node; a FIFO wait queue prevents parallel branches losing breakpoints.
 */
class EngineDebugSession : EngineDebugObserver {
    private companion object { const val MAX_HISTORY_STEPS = 1_000 }
    data class Step(
        val invocationId: Long,
        val nodeId: NodeId,
        val nodeType: String,
        val variablesBefore: Map<String, ConfigValue>,
        val variablesAfter: Map<String, ConfigValue>,
        val success: Boolean,
        val elapsedMs: Long,
    )

    data class Paused(
        val nodeId: NodeId,
        val nodeType: String,
        val variables: Map<String, ConfigValue>,
    )

    private data class Waiter(val invocationId: Long, val info: Paused, val deferred: CompletableDeferred<Unit>)
    private enum class Mode { STEP, CONTINUE }

    private val mutex = Mutex()
    private var mode = Mode.STEP
    private val waiting = ArrayDeque<Waiter>()
    private val history = ArrayDeque<Step>()
    private val before = mutableMapOf<Long, Map<String, ConfigValue>>()
    private val breakpoints = mutableSetOf<NodeId>()

    suspend fun snapshot(): List<Step> = mutex.withLock { history.toList() }
    suspend fun pausedAt(): Paused? = mutex.withLock { waiting.firstOrNull()?.info }
    suspend fun setBreakpoint(id: NodeId, enabled: Boolean) {
        mutex.withLock {
            if (enabled) breakpoints.add(id) else breakpoints.remove(id)
        }
    }

    suspend fun step() {
        mutex.withLock {
            mode = Mode.STEP
            waiting.removeFirstOrNull()?.deferred?.complete(Unit)
        }
    }

    suspend fun continueExecution() {
        mutex.withLock {
            mode = Mode.CONTINUE
            while (waiting.isNotEmpty()) waiting.removeFirst().deferred.complete(Unit)
        }
    }

    suspend fun cancelPause() {
        mutex.withLock {
            while (waiting.isNotEmpty()) waiting.removeFirst().deferred.cancel()
        }
    }

    override suspend fun beforeNode(
        invocationId: Long,
        node: ActionNode,
        variables: Map<String, ConfigValue>,
    ) {
        val waiter = mutex.withLock {
            before[invocationId] = variables.toMap()
            if (mode == Mode.CONTINUE && node.id !in breakpoints) return@withLock null
            Waiter(
                invocationId,
                Paused(node.id, node.javaClass.simpleName, variables.toMap()),
                CompletableDeferred(),
            ).also(waiting::addLast)
        }
        try {
            waiter?.deferred?.await()
        } finally {
            mutex.withLock {
                if (waiter != null) waiting.remove(waiter)
                // An invocation cancelled while paused will never reach afterNode.
                if (waiter?.deferred?.isCancelled == true) before.remove(invocationId)
            }
        }
    }

    override suspend fun afterNode(
        invocationId: Long,
        node: ActionNode,
        variables: Map<String, ConfigValue>,
        success: Boolean,
        elapsedMs: Long,
    ) {
        mutex.withLock {
            if (history.size == MAX_HISTORY_STEPS) history.removeFirst()
            history.addLast(Step(
                invocationId = invocationId,
                nodeId = node.id,
                nodeType = node.javaClass.simpleName,
                variablesBefore = before.remove(invocationId).orEmpty(),
                variablesAfter = variables.toMap(),
                success = success,
                elapsedMs = elapsedMs,
            ))
        }
    }
}
