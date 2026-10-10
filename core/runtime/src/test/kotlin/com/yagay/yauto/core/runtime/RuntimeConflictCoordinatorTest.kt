package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConflictPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RuntimeConflictCoordinatorTest {
    @Test fun ignoreNewRejectsConcurrentRun() = runBlocking {
        val coordinator = RuntimeConflictCoordinator(ExecutionJobRegistry())
        val gate = CompletableDeferred<Unit>()
        val first = async {
            coordinator.execute("same", ConflictPolicy.IGNORE_NEW) { gate.await(); "first" }
        }
        repeat(100) { yield() }
        assertNull(coordinator.execute("same", ConflictPolicy.IGNORE_NEW) { "second" })
        gate.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("third", coordinator.execute("same", ConflictPolicy.IGNORE_NEW) { "third" })
    }

    @Test fun distinctAutomationsUseIndependentLocks() = runBlocking {
        val coordinator = RuntimeConflictCoordinator(ExecutionJobRegistry())
        val gate = CompletableDeferred<Unit>()
        val waiting = async {
            coordinator.execute("a", ConflictPolicy.QUEUE) { gate.await() }
        }
        repeat(100) { yield() }
        assertEquals("done", coordinator.execute("b", ConflictPolicy.QUEUE) { "done" })
        gate.complete(Unit)
        waiting.await()
    }
}
