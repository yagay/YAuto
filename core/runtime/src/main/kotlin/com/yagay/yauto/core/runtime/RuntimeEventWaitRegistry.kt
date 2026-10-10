package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Lifecycle ownership for event waiters, including timeout and cancellation cleanup. */
internal class RuntimeEventWaitRegistry {
    data class Request(
        val id: String,
        val events: List<FeatureRef>,
        val baseVariables: Map<String, ConfigValue>,
        val executionId: ExecutionId,
        val nodeId: NodeId,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val requests = ConcurrentHashMap<String, Request>()

    suspend fun await(
        events: List<FeatureRef>,
        baseVariables: Map<String, ConfigValue>,
        timeoutMs: Long?,
        executionId: ExecutionId,
        nodeId: NodeId,
    ): Boolean {
        if (events.isEmpty()) return false
        val request = Request(
            UUID.randomUUID().toString(), events, baseVariables.toMap(),
            executionId, nodeId, CompletableDeferred(),
        )
        requests[request.id] = request
        return try {
            if (timeoutMs == null) request.deferred.await()
            else withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
                request.deferred.await()
            } ?: false
        } finally {
            requests.remove(request.id, request)
        }
    }

    suspend fun notify(event: RuntimeEvent, matches: suspend (Request, RuntimeEvent) -> Boolean) {
        for (request in requests.values.toList()) {
            if (request.deferred.isCompleted) continue
            if (matches(request, event) && requests.remove(request.id, request)) {
                request.deferred.complete(true)
            }
        }
    }
}
