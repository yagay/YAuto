package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.PersistentVariableChange
import com.yagay.yauto.core.storage.WorkspaceRepository
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
        return mutationLock.withLock {
            val workspace = repository.load()
            val previous = workspace.persistentVariables[key]
                ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
            if (previous != value) {
                repository.save(workspace.copy(
                    globalVariables = workspace.globalVariables - key,
                    persistentVariables = workspace.persistentVariables + (key to value),
                ))
            }
            PersistentVariableChange(true, key, previous, value)
        }
    }

    suspend fun clear(name: String): PersistentVariableChange {
        val key = name.trim()
        if (key.isEmpty()) return PersistentVariableChange(false, key)
        return mutationLock.withLock {
            val workspace = repository.load()
            val previous = workspace.persistentVariables[key]
                ?: workspace.globalVariables[key]?.let(ConfigValue::StringValue)
            if (previous != null) {
                repository.save(workspace.copy(
                    globalVariables = workspace.globalVariables - key,
                    persistentVariables = workspace.persistentVariables - key,
                ))
            }
            PersistentVariableChange(true, key, previous, null)
        }
    }
}
