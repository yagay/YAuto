package com.yagay.yauto.core.registry

import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel

suspend fun FeatureExecutionContext.executeCapability(
    featureId: String,
    request: CapabilityRequest,
): CapabilityResult {
    val startedAt = System.currentTimeMillis()
    return try {
        val result = capabilities.execute(request)
        tracer.record(
            TraceEvent(
                executionId = executionId,
                kind = TraceKind.CAPABILITY,
                level = if (result.success) TraceLevel.INFO else TraceLevel.WARN,
                timestampEpochMs = System.currentTimeMillis(),
                message = result.message ?: if (result.success) "Capability completed" else "Capability failed",
                nodeId = nodeId,
                featureId = featureId,
                backendId = result.backendId,
                success = result.success,
                durationMs = System.currentTimeMillis() - startedAt,
                attributes = buildMap {
                    put("capability", request.capability.value)
                    put("operationId", request.operationId)
                    put("allowFallback", request.allowFallback.toString())
                    if (result.attempts.isNotEmpty()) {
                        put(
                            "attempts",
                            result.attempts.joinToString(" | ") { attempt ->
                                buildString {
                                    append(attempt.backendId)
                                    append(':')
                                    append(if (attempt.success) "success" else "failed")
                                    attempt.message?.takeIf { it.isNotBlank() }?.let { append('(').append(it.take(300)).append(')') }
                                }
                            }
                        )
                    }
                },
            )
        )
        result
    } catch (error: Throwable) {
        tracer.record(
            TraceEvent(
                executionId = executionId,
                kind = TraceKind.CAPABILITY,
                level = TraceLevel.ERROR,
                timestampEpochMs = System.currentTimeMillis(),
                message = error.message ?: error::class.simpleName.orEmpty(),
                nodeId = nodeId,
                featureId = featureId,
                success = false,
                durationMs = System.currentTimeMillis() - startedAt,
                attributes = mapOf(
                    "capability" to request.capability.value,
                    "operationId" to request.operationId,
                    "exception" to error::class.qualifiedName.orEmpty(),
                ),
            )
        )
        throw error
    }
}
