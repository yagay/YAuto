package com.yagay.yauto.core.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Owns activation state and serial evaluation per automation. */
internal class RuntimeEventState {
    private val active = ConcurrentHashMap<String, Boolean>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withEvaluationLock(id: String, block: suspend () -> T): T =
        locks.computeIfAbsent(id) { Mutex() }.withLock { block() }

    fun isActive(id: String): Boolean = active[id] ?: false
    fun setActive(id: String, enabled: Boolean) { active[id] = enabled }
    fun remove(id: String) {
        active.remove(id)
        locks.remove(id)
    }

    fun reset(id: String? = null) {
        if (id == null) active.clear() else active.remove(id)
    }
}
