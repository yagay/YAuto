package com.yagay.yauto.platform.android

import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceChangeMonitorTest {
    @Test fun observableRepositoryUpdatesAreDeliveredWithoutPolling() = runBlocking {
        val repository = FakeWorkspace()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = Channel<WorkspaceData>(Channel.UNLIMITED)
        val observer = scope.watchWorkspaceChanges(repository) { seen.send(it) }
        try {
            assertEquals(true, withTimeout(2_000) { seen.receive() }.runtimeEnabled)
            repository.save(WorkspaceData(runtimeEnabled = false))
            assertEquals(false, withTimeout(2_000) { seen.receive() }.runtimeEnabled)
        } finally {
            observer.cancelAndJoin()
            scope.cancel()
            seen.close()
        }
        assertEquals(0, repository.listenerCount())
    }

    private class FakeWorkspace : ObservableWorkspaceRepository {
        private val listeners = CopyOnWriteArrayList<(WorkspaceData) -> Unit>()
        @Volatile private var data = WorkspaceData()
        override suspend fun load(): WorkspaceData = data
        override suspend fun save(data: WorkspaceData) {
            this.data = data
            listeners.forEach { it(data) }
        }
        override fun snapshotOrNull(): WorkspaceData = data
        override fun addListener(listener: (WorkspaceData) -> Unit): AutoCloseable {
            listeners.add(listener)
            return AutoCloseable { listeners.remove(listener) }
        }
        fun listenerCount(): Int = listeners.size
    }
}
