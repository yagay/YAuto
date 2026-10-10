package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes runtime metadata writes with other workspace mutations using the shared lock. */
internal class RuntimeLastRunStore(
    private val repository: WorkspaceRepository,
    private val mutationLock: Mutex,
) {
    suspend fun record(id: AutomationId, timestamp: Long) {
        mutationLock.withLock {
            val workspace = repository.load()
            if (workspace.automationLastRunEpochMs[id.value] == timestamp) return@withLock
            repository.save(workspace.copy(
                automationLastRunEpochMs = workspace.automationLastRunEpochMs + (id.value to timestamp),
            ))
        }
    }

    suspend fun get(id: AutomationId): Long? =
        repository.load().automationLastRunEpochMs[id.value]
}
