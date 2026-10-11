package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConflictPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Single owner for per-automation conflict policy locks. */
internal class RuntimeConflictCoordinator(private val jobs: ExecutionJobRegistry) {
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val replacementSequence = ConcurrentHashMap<String, AtomicLong>()

    suspend fun <T> execute(key: String, policy: ConflictPolicy, block: suspend () -> T): T? =
        when (policy) {
            ConflictPolicy.PARALLEL -> block()
            ConflictPolicy.QUEUE -> locks.computeIfAbsent(key) { Mutex() }.withLock { block() }
            ConflictPolicy.IGNORE_NEW -> {
                val lock = locks.computeIfAbsent(key) { Mutex() }
                if (!lock.tryLock()) null else try { block() } finally { lock.unlock() }
            }
            ConflictPolicy.CANCEL_PREVIOUS -> {
                // Cancellation MUST happen before waiting for the previous run's
                // handoff lock: otherwise CANCEL_PREVIOUS degenerates into QUEUE.
                val sequence = replacementSequence.computeIfAbsent(key) { AtomicLong() }
                val ticket = sequence.incrementAndGet()
                jobs.cancel(key)
                locks.computeIfAbsent(key) { Mutex() }.withLock {
                    if (ticket != sequence.get() || !jobs.cancelAndAwait(key)) null
                    else if (ticket != sequence.get()) null
                    else block()
                }
            }
        }
}
