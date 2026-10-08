package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.Activation
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.storage.ObservableWorkspaceRepository
import com.yagay.yauto.core.storage.WorkspaceData
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGatedEventSourceLifecycleTest {
    private class FakeWorkspace(initial: WorkspaceData) : ObservableWorkspaceRepository {
        @Volatile var current = initial
        private val listeners = CopyOnWriteArrayList<(WorkspaceData) -> Unit>()
        override suspend fun load(): WorkspaceData = current
        override suspend fun save(data: WorkspaceData) {
            current = data
            listeners.forEach { it(data) }
        }
        override fun snapshotOrNull(): WorkspaceData = current
        override fun addListener(listener: (WorkspaceData) -> Unit): AutoCloseable {
            listeners += listener
            return AutoCloseable { listeners -= listener }
        }
    }

    private fun activeWorkspace() = WorkspaceData(automations = listOf(
        Automation(
            AutomationId("enabled"),
            "Sensor",
            activation = Activation(events = listOf(FeatureRef("android.event.sensor_value"))),
        ),
    ))

    private fun eventually(condition: () -> Boolean) {
        repeat(60) {
            if (condition()) return
            Thread.sleep(20)
        }
        assertTrue("The event-source state did not converge", condition())
    }

    @Test fun `start after stop creates a working subscription rather than reusing cancelled scope`() {
        val workspace = FakeWorkspace(activeWorkspace())
        val startedCount = AtomicInteger()
        val stoppedCount = AtomicInteger()
        val gate = WorkspaceGatedEventSource(
            workspace,
            setOf("android.event.sensor_value"),
        ) {
            object : AndroidEventSource {
                override val id: String = "sensor"
                override fun start(emitter: RuntimeEventEmitter) {
                    startedCount.incrementAndGet()
                }
                override fun stop() {
                    stoppedCount.incrementAndGet()
                }
            }
        }
        val emitter = RuntimeEventEmitter { }
        gate.start(emitter)
        eventually { startedCount.get() == 1 }
        gate.stop()
        assertEquals(1, stoppedCount.get())
        gate.start(emitter)
        eventually { startedCount.get() == 2 }
        gate.stop()
        assertEquals(2, stoppedCount.get())
    }

    @Test fun `disabled workspace does not restart delegate after stop`() {
        val workspace = FakeWorkspace(activeWorkspace().copy(runtimeEnabled = false))
        val startedCount = AtomicInteger()
        val gate = WorkspaceGatedEventSource(
            workspace, setOf("android.event.sensor_value"),
        ) {
            object : AndroidEventSource {
                override val id: String = "sensor"
                override fun start(emitter: RuntimeEventEmitter) {
                    startedCount.incrementAndGet()
                }
                override fun stop() = Unit
            }
        }
        val emitter = RuntimeEventEmitter { }
        gate.start(emitter)
        Thread.sleep(80)
        assertEquals(0, startedCount.get())
        gate.stop()
        gate.start(emitter)
        Thread.sleep(80)
        assertEquals(0, startedCount.get())
        gate.stop()
    }
}
