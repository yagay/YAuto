package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutomationRuntimeTest {
    @Test
    fun `dispatches matching event into automation engine`() = runBlocking {
        val counter = AtomicInteger(0)
        val registry = FeatureRegistry().apply {
            registerEvent(
                FeatureDescriptor(FeatureId("test.event"), FeatureKind.EVENT, "Test", "", FeatureCategory.CORE)
            ) { _, ctx -> ctx.event.typeId == "test.event" }
            registerAction(
                FeatureDescriptor(FeatureId("test.action"), FeatureKind.ACTION, "Test action", "", FeatureCategory.CORE)
            ) { _, _ ->
                counter.incrementAndGet()
                ActionExecutionResult(true)
            }
        }
        val automation = Automation(
            id = AutomationId("a1"),
            name = "Runtime test",
            activation = Activation(events = listOf(FeatureRef("test.event"))),
            onEvent = listOf(ActionNode.Action(NodeId("n1"), FeatureRef("test.action"))),
        )
        val repository = object : WorkspaceRepository {
            private var data = WorkspaceData(automations = listOf(automation))
            override suspend fun load(): WorkspaceData = data
            override suspend fun save(data: WorkspaceData) { this.data = data }
        }
        val capabilities = CapabilityClient { CapabilityResult(false, message = "not used") }
        val runtime = AutomationRuntime(repository, registry, capabilities, NoOpExecutionTracer)

        val result = runtime.dispatch(RuntimeEvent("test.event"))

        assertEquals(1, result.runs.size)
        assertEquals(1, counter.get())
    }
}
