package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.PersistentVariableChange
import com.yagay.yauto.core.storage.WorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceMutationRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Owns typed persistent variables and legacy global-variable migration on write. */
internal class RuntimeVariableStore(
    private val repository: WorkspaceRepository,
    private val mutationLock: Mutex,
) {
    suspend fun get(name: String): ConfigValue? {
        val key = name.trim()
        if (key.isEmpty()) return null
        val workspace = repository.load()
        return workspace.persistentVariables[key]
            ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
    }

    suspend fun set(name: String, value: ConfigValue): PersistentVariableChange {
        val key = name.trim()
        if (key.isEmpty()) return PersistentVariableChange(false, key)
        var previous: ConfigValue? = null
        val mutation: (com.yagay.yauto.core.storage.WorkspaceData) -> com.yagay.yauto.core.storage.WorkspaceData = { workspace ->
            previous = workspace.persistentVariables[key]
                ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
            if (previous == value) workspace else workspace.copy(
                globalVariables = workspace.globalVariables - key,
                persistentVariables = workspace.persistentVariables + (key to value),
            )
        }
        if (repository is WorkspaceMutationRepository) repository.update(mutation)
        else mutationLock.withLock { repository.save(mutation(repository.load())) }
        return PersistentVariableChange(true, key, previous, value)
    }

    suspend fun clear(name: String): PersistentVariableChange {
        val key = name.trim()
        if (key.isEmpty()) return PersistentVariableChange(false, key)
        var previous: ConfigValue? = null
        val mutation: (com.yagay.yauto.core.storage.WorkspaceData) -> com.yagay.yauto.core.storage.WorkspaceData = { workspace ->
            previous = workspace.persistentVariables[key]
                ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
            if (previous == null) workspace else workspace.copy(
                globalVariables = workspace.globalVariables - key,
                persistentVariables = workspace.persistentVariables - key,
            )
        }
        if (repository is WorkspaceMutationRepository) repository.update(mutation)
        else mutationLock.withLock { repository.save(mutation(repository.load())) }
        return PersistentVariableChange(true, key, previous, null)
    }
}
