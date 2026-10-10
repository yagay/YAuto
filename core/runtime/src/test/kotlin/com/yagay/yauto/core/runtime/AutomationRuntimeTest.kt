package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.capability.CapabilityClient
import com.yagay.yauto.core.capability.CapabilityResult
import com.yagay.yauto.core.logging.InMemoryExecutionTracer
import com.yagay.yauto.core.logging.NoOpExecutionTracer
import com.yagay.yauto.core.model.*
import com.yagay.yauto.core.registry.*
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        val repository = MutableWorkspaceRepository(WorkspaceData(automations = listOf(automation)))
        val capabilities = CapabilityClient { CapabilityResult(false, message = "not used") }
        val runtime = AutomationRuntime(repository, registry, capabilities, NoOpExecutionTracer)

        val result = runtime.dispatch(RuntimeEvent("test.event"))

        assertEquals(1, result.runs.size)
        assertEquals(1, counter.get())
    }

    @Test
    fun `automation control runs target and returns value`() = runBlocking {
        val target = Automation(
            id = AutomationId("target"),
            name = "Target",
            onEvent = listOf(ActionNode.Return(NodeId("return"), ConfigValue.StringValue("done"))),
        )
        val repository = MutableWorkspaceRepository(WorkspaceData(automations = listOf(target)))
        val runtime = AutomationRuntime(
            repository,
            FeatureRegistry(),
            CapabilityClient { CapabilityResult(false) },
            NoOpExecutionTracer,
        )

        val result = runtime.run("target")

        assertTrue(result.success)
        assertEquals(ConfigValue.StringValue("done"), result.value)
    }

    @Test
    fun `automation control persists toggle state`() = runBlocking {
        val target = Automation(AutomationId("target"), "Target", enabled = true)
        val repository = MutableWorkspaceRepository(WorkspaceData(automations = listOf(target)))
        val runtime = AutomationRuntime(
            repository,
            FeatureRegistry(),
            CapabilityClient { CapabilityResult(false) },
            NoOpExecutionTracer,
        )

        val result = runtime.setEnabled("Target", AutomationEnableMode.TOGGLE)

        assertTrue(result.success)
        assertEquals(ConfigValue.BooleanValue(false), result.value)
        assertFalse(repository.data.automations.single().enabled)
        assertEquals(false, runtime.isEnabled("target"))
    }

    @Test
    fun `automation control blocks recursive invocation`() = runBlocking {
        lateinit var runtime: AutomationRuntime
        val registry = FeatureRegistry().apply {
            registerAction(
                FeatureDescriptor(FeatureId("recurse"), FeatureKind.ACTION, "Recurse", "", FeatureCategory.CORE)
            ) { _, _ -> runtime.run("target") }
        }
        val target = Automation(
            id = AutomationId("target"),
            name = "Target",
            onEvent = listOf(ActionNode.Action(NodeId("recurse"), FeatureRef("recurse"))),
        )
        val repository = MutableWorkspaceRepository(WorkspaceData(automations = listOf(target)))
        runtime = AutomationRuntime(
            repository,
            registry,
            CapabilityClient { CapabilityResult(false) },
            NoOpExecutionTracer,
        )

        val result = runtime.run("target")

        assertFalse(result.success)
        assertEquals("runtime.automation_recursive_call", result.message)
    }

    @Test
    fun `automation control cancels tracked execution without cancelling caller`() = runBlocking {
        val registry = FeatureRegistry().apply {
            registerAction(
                FeatureDescriptor(FeatureId("slow"), FeatureKind.ACTION, "Slow", "", FeatureCategory.CORE)
            ) { _, _ ->
                delay(10_000)
                ActionExecutionResult(true)
            }
        }
        val target = Automation(
            id = AutomationId("target"),
            name = "Target",
            executionPolicy = ExecutionPolicy(conflictPolicy = ConflictPolicy.PARALLEL),
            onEvent = listOf(ActionNode.Action(NodeId("slow"), FeatureRef("slow"))),
        )
        val repository = MutableWorkspaceRepository(WorkspaceData(automations = listOf(target)))
        val runtime = AutomationRuntime(
            repository,
            registry,
            CapabilityClient { CapabilityResult(false) },
            NoOpExecutionTracer,
        )

        val running = async { runtime.run("target") }
        delay(50)
        val cancelled = runtime.cancel("target")
        val result = running.await()

        assertEquals(ConfigValue.BooleanValue(true), cancelled.value)
        assertFalse(result.success)
        assertEquals("runtime.automation_cancelled", result.message)
    }

    @Test
    fun `persistent variable upgrades legacy string to typed value`() = runBlocking {
        val repository = MutableWorkspaceRepository(
            WorkspaceData(globalVariables = mapOf("answer" to "old"))
        )
        val runtime = AutomationRuntime(
            repository,
            FeatureRegistry(),
            CapabilityClient { CapabilityResult(false) },
            NoOpExecutionTracer,
        )

        assertEquals(ConfigValue.StringValue("old"), runtime.get("answer"))
        val change = runtime.set("answer", ConfigValue.NumberValue(42.0))

        assertTrue(change.success)
        assertEquals(ConfigValue.StringValue("old"), change.previous)
        assertEquals(ConfigValue.NumberValue(42.0), runtime.get("answer"))
        assertNull(repository.data.globalVariables["answer"])
        assertEquals(ConfigValue.NumberValue(42.0), repository.data.persistentVariables["answer"])
    }

    @Test
    fun `persistent variable change triggers other automation without recursively triggering writer`() = runBlocking {
        lateinit var runtime: AutomationRuntime
        val writerRuns = AtomicInteger()
        val watcherRuns = AtomicInteger()
        val registry = FeatureRegistry().apply {
            registerAction(
                FeatureDescriptor(FeatureId("persist"), FeatureKind.ACTION, "Persist", "", FeatureCategory.CORE)
            ) { _, _ ->
                writerRuns.incrementAndGet()
                val change = runtime.set("shared", ConfigValue.NumberValue(1.0))
                ActionExecutionResult(change.success)
            }
            registerAction(
                FeatureDescriptor(FeatureId("watch"), FeatureKind.ACTION, "Watch", "", FeatureCategory.CORE)
            ) { _, _ ->
                watcherRuns.incrementAndGet()
                ActionExecutionResult(true)
            }
        }
        val writer = Automation(
            id = AutomationId("writer"),
            name = "Writer",
            activation = Activation(events = listOf(FeatureRef("test.start"), FeatureRef("core.event.variable_changed"))),
            onEvent = listOf(ActionNode.Action(NodeId("persist"), FeatureRef("persist"))),
        )
        val watcher = Automation(
            id = AutomationId("watcher"),
            name = "Watcher",
            activation = Activation(events = listOf(FeatureRef("core.event.variable_changed"))),
            onEvent = listOf(ActionNode.Action(NodeId("watch"), FeatureRef("watch"))),
        )
        val repository = MutableWorkspaceRepository(WorkspaceData(automations = listOf(writer, watcher)))
        runtime = AutomationRuntime(
            repository,
            registry,
            CapabilityClient { CapabilityResult(false) },
            NoOpExecutionTracer,
        )

        runtime.dispatch(RuntimeEvent("test.start"))

        assertEquals(1, writerRuns.get())
        assertEquals(1, watcherRuns.get())
        assertEquals(ConfigValue.NumberValue(1.0), repository.data.persistentVariables["shared"])
    }

    private class MutableWorkspaceRepository(initial: WorkspaceData) : WorkspaceRepository {
        var data: WorkspaceData = initial
            private set

        override suspend fun load(): WorkspaceData = data

        override suspend fun save(data: WorkspaceData) {
            this.data = data
        }
    }
    @Test
    fun `runtime control writes use the shared atomic workspace repository`() = runBlocking {
        val registry = FeatureRegistry()
        val automation = Automation(
            AutomationId("target"), "Target", category = "work",
            activation = Activation(events = listOf(FeatureRef("test.event"))),
        )
        val delegate = object : WorkspaceRepository {
            var data = WorkspaceData(automations = listOf(automation))
            override suspend fun load(): WorkspaceData = data
            override suspend fun save(data: WorkspaceData) { this.data = data }
        }
        val repository = ReconcilingWorkspaceRepository(delegate, registry)
        val runtime = AutomationRuntime(
            repository, registry, CapabilityClient { CapabilityResult(false) }, NoOpExecutionTracer,
        )
        assertTrue(runtime.setEnabled("target", AutomationEnableMode.DISABLE).success)
        assertTrue(runtime.setTriggerEnabled("target", "test.event", "", AutomationEnableMode.DISABLE).success)
        assertTrue(runtime.setCategoryEnabled("work", AutomationEnableMode.DISABLE).success)
        assertTrue(runtime.setRuntimeEnabled(AutomationEnableMode.DISABLE).success)
        assertFalse(delegate.data.automations.single().enabled)
        assertTrue(delegate.data.disabledCategories.contains("work"))
        assertTrue(delegate.data.disabledTriggerKeys.isNotEmpty())
        assertFalse(delegate.data.runtimeEnabled)
    }

}
