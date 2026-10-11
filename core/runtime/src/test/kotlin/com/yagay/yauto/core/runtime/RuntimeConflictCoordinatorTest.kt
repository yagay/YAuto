package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConflictPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class RuntimeConflictCoordinatorTest {
    @Test fun ignoreNewRejectsConcurrentRun() { runBlocking {
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
    } }

    @Test fun distinctAutomationsUseIndependentLocks() { runBlocking {
        val coordinator = RuntimeConflictCoordinator(ExecutionJobRegistry())
        val gate = CompletableDeferred<Unit>()
        val waiting = async {
            coordinator.execute("a", ConflictPolicy.QUEUE) { gate.await() }
        }
        repeat(100) { yield() }
        assertEquals("done", coordinator.execute("b", ConflictPolicy.QUEUE) { "done" })
        gate.complete(Unit)
        waiting.await()
    } }
    @Test fun cancelPreviousWaitsForOldExecutionCleanup() { runBlocking {
        val registry = ExecutionJobRegistry()
        val coordinator = RuntimeConflictCoordinator(registry)
        val cleaned = CompletableDeferred<Unit>()
        val first = async {
            coordinator.execute("same", ConflictPolicy.PARALLEL) {
                val running = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                registry.track("same", running)
                try { kotlinx.coroutines.awaitCancellation() }
                finally { registry.untrack("same", running); cleaned.complete(Unit) }
            }
        }
        repeat(100) { yield() }
        val replacement = coordinator.execute("same", ConflictPolicy.CANCEL_PREVIOUS) {
            assertTrue(cleaned.isCompleted)
            "replacement"
        }
        assertEquals("replacement", replacement)
        first.cancel()
    } }

    @Test fun cancelPreviousPreemptsAlreadyRunningCancelPrevious() = runBlocking {
        val jobs = ExecutionJobRegistry()
        val coordinator = RuntimeConflictCoordinator(jobs)
        val started = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val first = async {
            coordinator.execute("same", ConflictPolicy.CANCEL_PREVIOUS) {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                jobs.track("same", job)
                try {
                    started.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    jobs.untrack("same", job)
                    cleaned.complete(Unit)
                }
            }
        }
        started.await()
        val second = async {
            coordinator.execute("same", ConflictPolicy.CANCEL_PREVIOUS) {
                assertTrue("Old execution must be cancelled first", cleaned.isCompleted)
                "replacement"
            }
        }
        assertEquals("replacement", kotlinx.coroutines.withTimeout(2_000) { second.await() })
        first.cancel()
    }

    @Test fun onlyNewestQueuedReplacementExecutes() = runBlocking {
        val jobs = ExecutionJobRegistry()
        val coordinator = RuntimeConflictCoordinator(jobs)
        val firstReady = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val first = async {
            coordinator.execute("same", ConflictPolicy.CANCEL_PREVIOUS) {
                val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
                jobs.track("same", job)
                try {
                    firstReady.complete(Unit)
                    try { gate.await() } catch (_: kotlinx.coroutines.CancellationException) {
                        // Hold the handoff briefly to simulate cleanup.
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                            kotlinx.coroutines.delay(60)
                        }
                    }
                    "old"
                } finally { jobs.untrack("same", job) }
            }
        }
        firstReady.await()
        val middle = async {
            coordinator.execute("same", ConflictPolicy.CANCEL_PREVIOUS) { "middle" }
        }
        kotlinx.coroutines.yield()
        val newest = async {
            coordinator.execute("same", ConflictPolicy.CANCEL_PREVIOUS) { "newest" }
        }
        assertEquals("newest", kotlinx.coroutines.withTimeout(2_000) { newest.await() })
        assertNull(kotlinx.coroutines.withTimeout(2_000) { middle.await() })
        first.cancel()
    }

}
