package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.storage.WorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceMutationRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes runtime metadata writes with other workspace mutations using the shared lock. */
internal class RuntimeLastRunStore(
    private val repository: WorkspaceRepository,
    private val mutationLock: Mutex,
) {
    suspend fun record(id: AutomationId, timestamp: Long) {
        val mutate: (com.yagay.yauto.core.storage.WorkspaceData) -> com.yagay.yauto.core.storage.WorkspaceData = { workspace ->
            if (workspace.automationLastRunEpochMs[id.value] == timestamp) workspace
            else workspace.copy(
                automationLastRunEpochMs = workspace.automationLastRunEpochMs + (id.value to timestamp),
            )
        }
        if (repository is WorkspaceMutationRepository) repository.update(mutate)
        else mutationLock.withLock { repository.save(mutate(repository.load())) }
    }

    suspend fun get(id: AutomationId): Long? =
        repository.load().automationLastRunEpochMs[id.value]
}
