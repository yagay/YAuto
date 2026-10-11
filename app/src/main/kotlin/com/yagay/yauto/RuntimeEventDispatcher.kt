package com.yagay.yauto

import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.RuntimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import java.util.UUID

class RuntimeEventDispatcher(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
) {
    private val orderedInputs by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Channel<Pair<RuntimeEvent, Boolean>>(Channel.UNLIMITED).also { queue ->
            scope.launch {
                for ((event, statesOnly) in queue) execute(event, statesOnly)
            }
        }
    }

    fun dispatch(event: RuntimeEvent, statesOnly: Boolean = false) {
        if (event.typeId == "android.event.hardware_key" ||
            event.typeId == "android.event.hardware_key_gesture" ||
            event.typeId == "android.event.hardware_key_combo"
        ) {
            orderedInputs.trySend(event to statesOnly)
        } else {
            scope.launch { execute(event, statesOnly) }
        }
    }

    private suspend fun execute(event: RuntimeEvent, statesOnly: Boolean) {
            try {
                graph.runtime.dispatch(event, statesOnly)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                graph.tracer.record(
                    TraceEvent(
                        executionId = ExecutionId("runtime-${UUID.randomUUID()}"),
                        kind = TraceKind.ERROR,
                        level = TraceLevel.ERROR,
                        timestampEpochMs = System.currentTimeMillis(),
                        message = "Runtime event dispatch failed: ${error.message ?: error::class.simpleName}",
                        featureId = event.typeId,
                        success = false,
                        attributes = mapOf(
                            "event.source" to event.source,
                            "event.type" to event.typeId,
                            "exception" to error::class.qualifiedName.orEmpty(),
                        ),
                    )
                )
            }
    }
}
