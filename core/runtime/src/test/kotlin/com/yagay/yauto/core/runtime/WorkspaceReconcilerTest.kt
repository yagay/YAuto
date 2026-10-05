package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.Activation
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.AutomationId
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.core.model.FlowId
import com.yagay.yauto.core.model.NodeId
import com.yagay.yauto.core.model.PredicateNode
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceReconcilerTest {
    @Test
    fun `known aliases are canonicalized recursively while unknown and wrong-kind ids are preserved`() {
        val registry = FeatureRegistry().apply {
            registerDescriptor(descriptor("event.new", "event.old", FeatureKind.EVENT))
            registerDescriptor(descriptor("condition.new", "condition.old", FeatureKind.CONDITION))
            registerDescriptor(descriptor("action.new", "action.old", FeatureKind.ACTION))
            registerDescriptor(descriptor("condition.cross", "legacy.cross", FeatureKind.CONDITION))
        }
        val workspace = WorkspaceData(
            automations = listOf(
                Automation(
                    id = AutomationId("automation"),
                    name = "Legacy",
                    activation = Activation(
                        events = listOf(FeatureRef("event.old")),
                        states = listOf(FeatureRef("state.removed")),
                        condition = PredicateNode.All(
                            listOf(PredicateNode.Condition(FeatureRef("condition.old")))
                        ),
                    ),
                    onEvent = listOf(
                        ActionNode.If(
                            id = NodeId("if"),
                            condition = PredicateNode.Condition(FeatureRef("condition.old")),
                            thenActions = listOf(
                                ActionNode.Try(
                                    id = NodeId("try"),
                                    actions = listOf(ActionNode.Action(NodeId("action"), FeatureRef("action.old"))),
                                    onError = listOf(
                                        ActionNode.Action(NodeId("unknown"), FeatureRef("action.removed")),
                                        ActionNode.Action(NodeId("wrong-kind"), FeatureRef("legacy.cross")),
                                    ),
                                    finallyActions = listOf(
                                        ActionNode.Parallel(
                                            id = NodeId("parallel"),
                                            branches = listOf(
                                                listOf(ActionNode.Action(NodeId("deep"), FeatureRef("action.old")))
                                            ),
                                        )
                                    ),
                                )
                            ),
                        )
                    ),
                )
            ),
            flows = listOf(
                Flow(
                    id = FlowId("flow"),
                    name = "Flow",
                    actions = listOf(ActionNode.Action(NodeId("flow-action"), FeatureRef("action.old"))),
                )
            ),
        )

        val reconciled = WorkspaceReconciler(registry).reconcile(workspace)
        val automation = reconciled.automations.single()

        assertEquals("event.new", automation.activation.events.single().typeId)
        assertEquals("state.removed", automation.activation.states.single().typeId)
        val topCondition = automation.activation.condition as PredicateNode.All
        assertEquals(
            "condition.new",
            (topCondition.children.single() as PredicateNode.Condition).feature.typeId,
        )
        val ifNode = automation.onEvent.single() as ActionNode.If
        assertEquals("condition.new", (ifNode.condition as PredicateNode.Condition).feature.typeId)
        val tryNode = ifNode.thenActions.single() as ActionNode.Try
        assertEquals("action.new", (tryNode.actions.single() as ActionNode.Action).feature.typeId)
        assertEquals("action.removed", (tryNode.onError[0] as ActionNode.Action).feature.typeId)
        assertEquals("legacy.cross", (tryNode.onError[1] as ActionNode.Action).feature.typeId)
        val parallel = tryNode.finallyActions.single() as ActionNode.Parallel
        assertEquals(
            "action.new",
            (parallel.branches.single().single() as ActionNode.Action).feature.typeId,
        )
        assertEquals("action.new", (reconciled.flows.single().actions.single() as ActionNode.Action).feature.typeId)
    }

    @Test
    fun `repository boundary reconciles both load and save without dropping unknown nodes`() = runBlocking {
        val registry = FeatureRegistry().apply {
            registerDescriptor(descriptor("action.new", "action.old", FeatureKind.ACTION))
        }
        val initial = WorkspaceData(
            flows = listOf(
                Flow(
                    id = FlowId("flow"),
                    name = "Flow",
                    actions = listOf(
                        ActionNode.Action(NodeId("known"), FeatureRef("action.old")),
                        ActionNode.Action(NodeId("unknown"), FeatureRef("action.removed")),
                    ),
                )
            )
        )
        val delegate = FakeWorkspaceRepository(initial)
        val repository = ReconcilingWorkspaceRepository(delegate, registry)

        val loaded = repository.load()
        assertEquals("action.new", (loaded.flows.single().actions[0] as ActionNode.Action).feature.typeId)
        assertEquals("action.removed", (loaded.flows.single().actions[1] as ActionNode.Action).feature.typeId)
        assertEquals("action.old", (delegate.data.flows.single().actions[0] as ActionNode.Action).feature.typeId)

        repository.save(initial)

        assertEquals("action.new", (delegate.data.flows.single().actions[0] as ActionNode.Action).feature.typeId)
        assertEquals("action.removed", (delegate.data.flows.single().actions[1] as ActionNode.Action).feature.typeId)
        assertTrue(delegate.saved)
    }

    @Test
    fun `repository caches reconciled workspace and publishes saves`() = runBlocking {
        val registry = FeatureRegistry()
        val initial = WorkspaceData()
        val delegate = FakeWorkspaceRepository(initial)
        val repository = ReconcilingWorkspaceRepository(delegate, registry)
        val observed = mutableListOf<WorkspaceData>()
        val subscription = repository.addListener { observed += it }

        val first = repository.load()
        val second = repository.load()

        assertSame(first, second)
        assertEquals(1, delegate.loadCount)

        val updated = initial.copy(runtimeEnabled = false)
        repository.save(updated)

        val cachedAfterSave = repository.snapshotOrNull()
        assertEquals(updated, cachedAfterSave)
        assertSame(cachedAfterSave, repository.load())
        assertEquals(1, delegate.loadCount)
        assertTrue(observed.contains(updated))
        subscription.close()
    }

    private fun descriptor(
        canonical: String,
        legacy: String,
        kind: FeatureKind,
    ) = FeatureDescriptor(
        id = FeatureId(canonical),
        kind = kind,
        title = canonical,
        description = "test",
        category = FeatureCategory.CORE,
        aliases = setOf(legacy),
    )

    private class FakeWorkspaceRepository(initial: WorkspaceData) : WorkspaceRepository {
        var data = initial
        var saved = false
        var loadCount = 0

        override suspend fun load(): WorkspaceData {
            loadCount += 1
            return data
        }

        override suspend fun save(data: WorkspaceData) {
            this.data = data
            saved = true
        }
    }
}
