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
}
