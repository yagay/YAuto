package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.*
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RuntimeEventWaitRegistryTest {
    @Test fun matchingEventResumesWaitingExecution() = runBlocking {
        val registry = RuntimeEventWaitRegistry()
        val event = FeatureRef(typeId = "example.event")
        val waiting = async {
            registry.await(listOf(event), emptyMap(), 5_000L, ExecutionId("run"), NodeId("node"))
        }
        repeat(100) {
            if (waiting.isCompleted) return@repeat
            yield()
        }
        registry.notify(RuntimeEvent(typeId = "example.event")) { request, actual ->
            request.events.any { it.typeId == actual.typeId }
        }
        assertTrue(waiting.await())
    }

    @Test fun emptyEventListDoesNotRegisterWaiter() = runBlocking {
        val registry = RuntimeEventWaitRegistry()
        assertFalse(registry.await(emptyList(), emptyMap(), null, ExecutionId("run"), NodeId("node")))
    }
}
