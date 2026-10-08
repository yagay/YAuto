package com.yagay.yauto.core.logging

import com.yagay.yauto.core.model.ExecutionId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryExecutionTracerTest {
    @Test
    fun `ring buffer keeps newest events without copy on write semantics`() = runBlocking {
        val tracer = InMemoryExecutionTracer(maxEvents = 3)
        repeat(5) { index ->
            tracer.record(
                TraceEvent(
                    executionId = ExecutionId("e$index"),
                    kind = TraceKind.MESSAGE,
                    timestampEpochMs = index.toLong(),
                    message = "m$index",
                )
            )
        }

        assertEquals(listOf("m2", "m3", "m4"), tracer.snapshot().map { it.message })
        tracer.clear()
        assertEquals(emptyList<TraceEvent>(), tracer.snapshot())
    }
}
