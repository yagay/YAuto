package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import com.yagay.yauto.core.logging.InMemoryExecutionTracer
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AutomationRuntimeTest {
    @Test fun `one broken rule does not block a healthy rule`() = runBlocking {
        val tracer = InMemoryExecutionTracer()
        val registry = FeatureRegistry().apply {
            registerState(FeatureDescriptor(FeatureId("broken"), FeatureKind.STATE, "Broken", "", FeatureCategory.CORE)) { _, _ -> error("broken state") }
        }
        val data = WorkspaceData(automations = listOf(
            Automation(AutomationId("broken"), "Broken", activation = Activation(states = listOf(FeatureRef("broken")))),
            Automation(AutomationId("healthy"), "Healthy", activation = Activation(events = listOf(FeatureRef("test"))))))
        val repository = object : WorkspaceRepository {
            override suspend fun load() = data
            override suspend fun save(data: WorkspaceData) = Unit
        }
        val result = AutomationRuntime(repository, registry, CapabilityClient { CapabilityResult(false) }, tracer).dispatch(RuntimeEvent("test"))
        assertEquals(listOf(AutomationId("healthy")), result.runs.map { it.automationId })
        assertEquals(AutomationId("broken"), tracer.snapshot().first { it.message == "runtime.dispatch_failed" }.automationId)
    }

    @Test fun `concurrent state dispatch enters once`() = runBlocking {
        val entered = AtomicInteger()
        val registry = FeatureRegistry().apply {
            registerState(FeatureDescriptor(FeatureId("state"), FeatureKind.STATE, "State", "", FeatureCategory.CORE)) { _, _ -> delay(10); true }
            registerAction(FeatureDescriptor(FeatureId("enter"), FeatureKind.ACTION, "Enter", "", FeatureCategory.CORE)) { _, _ -> entered.incrementAndGet(); ActionExecutionResult(true) }
        }
        val data = WorkspaceData(automations = listOf(Automation(AutomationId("state"), "State",
            activation = Activation(states = listOf(FeatureRef("state"))),
            onEnter = listOf(ActionNode.Action(NodeId("enter"), FeatureRef("enter"))))))
        val repository = object : WorkspaceRepository {
            override suspend fun load() = data
            override suspend fun save(data: WorkspaceData) = Unit
        }
        val runtime = AutomationRuntime(repository, registry, CapabilityClient { CapabilityResult(false) }, NoOpExecutionTracer)
        coroutineScope { (1..10).map { async { runtime.dispatch(RuntimeEvent("test")) } }.forEach { it.await() } }
        assertEquals(1, entered.get())
    }

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
