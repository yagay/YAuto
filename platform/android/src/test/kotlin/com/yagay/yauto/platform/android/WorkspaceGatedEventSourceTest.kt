package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.Activation
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkspaceGatedEventSourceTest {
    @Test
    fun `delegate only runs while matching enabled trigger exists and is recreated after stop`() {
        val workspace = FakeObservableWorkspace()
        var created = 0
        val sources = mutableListOf<FakeSource>()
        val gated = WorkspaceGatedEventSource(
            workspace = workspace,
            requiredEventTypeIds = setOf("android.event.sound_level"),
        ) {
            created += 1
            FakeSource().also(sources::add)
        }

        gated.start(RuntimeEventEmitter { })
        assertEquals(0, created)

        workspace.publish(data("android.event.sound_level"))
        assertEquals(1, created)
        assertEquals(1, sources.single().starts)

        workspace.publish(WorkspaceData())
        assertEquals(1, sources.single().stops)

        workspace.publish(data("android.event.sound_level"))
        assertEquals(2, created)
        assertEquals(1, sources.last().starts)

        gated.stop()
        assertEquals(1, sources.last().stops)
    }

    private fun data(typeId: String) = WorkspaceData(
        automations = listOf(
            Automation(
                id = AutomationId("a"),
                name = "A",
                activation = Activation(events = listOf(FeatureRef(typeId))),
            )
        )
    )

    private class FakeSource : AndroidEventSource {
        override val id = "fake"
        var starts = 0
        var stops = 0
        override fun start(emitter: RuntimeEventEmitter) { starts += 1 }
        override fun stop() { stops += 1 }
    }

    private class FakeObservableWorkspace : ObservableWorkspaceRepository {
        private val listeners = CopyOnWriteArrayList<(WorkspaceData) -> Unit>()
        private var data = WorkspaceData()

        override suspend fun load(): WorkspaceData = data
        override suspend fun save(data: WorkspaceData) = publish(data)
        override fun snapshotOrNull(): WorkspaceData = data

        override fun addListener(listener: (WorkspaceData) -> Unit): AutoCloseable {
            listeners += listener
            listener(data)
            return AutoCloseable { listeners -= listener }
        }

        fun publish(next: WorkspaceData) {
            data = next
            listeners.forEach { it(next) }
        }
    }
}
