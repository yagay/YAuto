package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test

class RuntimeVariableStoreTest {
    private class InMemoryRepository(initial: WorkspaceData) : WorkspaceRepository {
        var workspace = initial
        override suspend fun load(): WorkspaceData = workspace
        override suspend fun save(data: WorkspaceData) { workspace = data }
    }

    @Test fun writesMigrateLegacyGlobalsWithoutChangingOtherKeys() = runBlocking {
        val repo = InMemoryRepository(WorkspaceData(globalVariables = mapOf(
            "counter" to "old", "untouched" to "keep",
        )))
        val store = RuntimeVariableStore(repo, Mutex())
        assertEquals(ConfigValue.StringValue("old"), store.get("counter"))
        val change = store.set(" counter ", ConfigValue.NumberValue(42.0))
        assertTrue(change.success)
        assertEquals(ConfigValue.StringValue("old"), change.previous)
        assertEquals(ConfigValue.NumberValue(42.0), store.get("counter"))
        assertFalse("counter" in repo.workspace.globalVariables)
        assertEquals("keep", repo.workspace.globalVariables["untouched"])
        val removed = store.clear("counter")
        assertEquals(ConfigValue.NumberValue(42.0), removed.previous)
        assertNull(store.get("counter"))
    }

    @Test fun emptyVariableKeysNeverModifyWorkspace() = runBlocking {
        val repo = InMemoryRepository(WorkspaceData())
        val store = RuntimeVariableStore(repo, Mutex())
        assertFalse(store.set(" ", ConfigValue.StringValue("value")).success)
        assertFalse(store.clear(" ").success)
        assertNull(store.get(" "))
        assertTrue(repo.workspace.persistentVariables.isEmpty())
    }
}
