package com.yagay.yauto.core.engine

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.NodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A coroutine-friendly debugger that halts BEFORE a node is executed.
 * A single permit advances exactly one node across nested branches and loops.
 * Do not use in ordinary scheduled production executions.
 */
class EngineDebugSession : EngineDebugObserver {
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

    private val mutex = Mutex()
    private var pending: CompletableDeferred<Unit>? = null
    private var mode: Mode = Mode.STEP
    private var paused: Paused? = null
    private val history = mutableListOf<Step>()
    private val before = mutableMapOf<Long, Map<String, ConfigValue>>()
    private val breakpoints = mutableSetOf<NodeId>()
    private enum class Mode { STEP, CONTINUE }

    suspend fun snapshot(): List<Step> = mutex.withLock { history.toList() }
    suspend fun pausedAt(): Paused? = mutex.withLock { paused }
    suspend fun setBreakpoint(id: NodeId, enabled: Boolean) = mutex.withLock {
        if (enabled) breakpoints.add(id) else breakpoints.remove(id)
    }

    suspend fun step() {
        mutex.withLock {
            mode = Mode.STEP
            pending?.complete(Unit)
            pending = null
        }
    }

    suspend fun continueExecution() {
        mutex.withLock {
            mode = Mode.CONTINUE
            pending?.complete(Unit)
            pending = null
        }
    }

    suspend fun cancelPause() {
        mutex.withLock {
            pending?.cancel()
            pending = null
        }
    }

    override suspend fun beforeNode(invocationId: Long, node: ActionNode, variables: Map<String, ConfigValue>) {
        val wait = mutex.withLock {
            before[invocationId] = variables.toMap()
            if (mode == Mode.CONTINUE && node.id !in breakpoints) return@withLock null
            paused = Paused(node.id, node.javaClass.simpleName, variables.toMap())
            CompletableDeferred<Unit>().also { pending = it }
        }
        try {
            wait?.await()
        } finally {
            mutex.withLock {
                if (pending === wait) {
                    pending = null
                    paused = null
                } else if (paused?.nodeId == node.id) {
                    paused = null
                }
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
            history += Step(
                invocationId = invocationId,
                nodeId = node.id,
                nodeType = node.javaClass.simpleName,
                variablesBefore = before.remove(invocationId).orEmpty(),
                variablesAfter = variables.toMap(),
                success = success,
                elapsedMs = elapsedMs,
            )
        }
    }
}
