package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceMutationTest {
    @Test fun concurrentIndependentUpdatesPreserveEveryChange() = runBlocking {
        val delegate = object : WorkspaceRepository {
            var value = WorkspaceData()
            override suspend fun load(): WorkspaceData = value
            override suspend fun save(data: WorkspaceData) { value = data }
        }
        val repository = ReconcilingWorkspaceRepository(delegate, FeatureRegistry())
        (1..50).map { index ->
            async {
                repository.update { current ->
                    current.copy(persistentVariables = current.persistentVariables +
                        ("var$index" to ConfigValue.NumberValue(index.toDouble())))
                }
            }
        }.awaitAll()
        assertEquals(50, repository.load().persistentVariables.size)
        assertEquals(50, delegate.value.persistentVariables.size)
    }
    @Test fun unchangedTransactionDoesNotWriteOrNotify() = runBlocking {
        val delegate = object : WorkspaceRepository {
            var data = WorkspaceData()
            var writes = 0
            override suspend fun load(): WorkspaceData = data
            override suspend fun save(data: WorkspaceData) { this.data = data; writes++ }
        }
        val repository = ReconcilingWorkspaceRepository(delegate, FeatureRegistry())
        var notifications = 0
        repository.load()
        val subscription = repository.addListener { notifications++ }
        repository.update { it }
        assertEquals(0, delegate.writes)
        assertEquals(1, notifications)
        repository.update { it.copy(runtimeEnabled = false) }
        assertEquals(1, delegate.writes)
        assertEquals(2, notifications)
        subscription.close()
    }

}
