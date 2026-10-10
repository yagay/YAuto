package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConflictPolicy
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Single owner for per-automation conflict policy locks. */
internal class RuntimeConflictCoordinator(private val jobs: ExecutionJobRegistry) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> execute(key: String, policy: ConflictPolicy, block: suspend () -> T): T? =
        when (policy) {
            ConflictPolicy.PARALLEL -> block()
            ConflictPolicy.QUEUE -> locks.computeIfAbsent(key) { Mutex() }.withLock { block() }
            ConflictPolicy.IGNORE_NEW -> {
                val lock = locks.computeIfAbsent(key) { Mutex() }
                if (!lock.tryLock()) null else try { block() } finally { lock.unlock() }
            }
            ConflictPolicy.CANCEL_PREVIOUS -> {
                jobs.cancel(key)
                block()
            }
        }
}
