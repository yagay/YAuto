package com.yagay.yauto.core.runtime

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.Activation
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.Flow
import com.yagay.yauto.core.model.PredicateNode
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.storage.WorkspaceData
import com.yagay.yauto.core.storage.WorkspaceRepository

/**
 * Canonicalizes only Feature IDs that the current registry explicitly knows how to resolve.
 * Unknown/removed IDs are kept byte-for-byte at the model level so old or imported workspaces
 * remain editable and can recover if that feature is restored later.
 */
class WorkspaceReconciler(
    private val registry: FeatureRegistry,
) {
    fun reconcile(workspace: WorkspaceData): WorkspaceData = workspace.copy(
        automations = workspace.automations.map(::automation),
        flows = workspace.flows.map(::flow),
    )

    private fun automation(value: Automation): Automation = value.copy(
        activation = activation(value.activation),
        onEnter = actions(value.onEnter),
        onEvent = actions(value.onEvent),
        onExit = actions(value.onExit),
    )

    private fun activation(value: Activation): Activation = value.copy(
        events = value.events.map(::feature),
        states = value.states.map(::feature),
        condition = value.condition?.let(::predicate),
    )

    private fun flow(value: Flow): Flow = value.copy(actions = actions(value.actions))

    private fun actions(values: List<ActionNode>): List<ActionNode> = values.map(::action)

    private fun action(value: ActionNode): ActionNode = when (value) {
        is ActionNode.Action -> value.copy(feature = feature(value.feature))
        is ActionNode.If -> value.copy(
            condition = predicate(value.condition),
            thenActions = actions(value.thenActions),
            elseActions = actions(value.elseActions),
        )
        is ActionNode.Switch -> value.copy(
            cases = value.cases.map { case -> case.copy(actions = actions(case.actions)) },
            defaultActions = actions(value.defaultActions),
        )
        is ActionNode.Repeat -> value.copy(actions = actions(value.actions))
        is ActionNode.While -> value.copy(
            condition = predicate(value.condition),
            actions = actions(value.actions),
        )
        is ActionNode.ForEach -> value.copy(actions = actions(value.actions))
        is ActionNode.Parallel -> value.copy(branches = value.branches.map(::actions))
        is ActionNode.Try -> value.copy(
            actions = actions(value.actions),
            onError = actions(value.onError),
            finallyActions = actions(value.finallyActions),
        )
        is ActionNode.CallFlow,
        is ActionNode.Return,
        is ActionNode.Break,
        is ActionNode.Continue -> value
    }

    private fun predicate(value: PredicateNode): PredicateNode = when (value) {
        is PredicateNode.All -> value.copy(children = value.children.map(::predicate))
        is PredicateNode.Any -> value.copy(children = value.children.map(::predicate))
        is PredicateNode.None -> value.copy(children = value.children.map(::predicate))
        is PredicateNode.Condition -> value.copy(feature = feature(value.feature))
        is PredicateNode.Expression,
        is PredicateNode.Literal -> value
    }

    private fun feature(value: FeatureRef): FeatureRef = registry.canonicalRef(value)
}

/**
 * Keeps UI/runtime consumers on a reconciled workspace without coupling disk storage to the
 * FeatureRegistry. Saves are canonicalized too, so a user edit naturally persists known aliases.
 */
class ReconcilingWorkspaceRepository(
    private val delegate: WorkspaceRepository,
    registry: FeatureRegistry,
) : WorkspaceRepository {
    private val reconciler = WorkspaceReconciler(registry)

    override suspend fun load(): WorkspaceData = reconciler.reconcile(delegate.load())

    override suspend fun save(data: WorkspaceData) {
        delegate.save(reconciler.reconcile(data))
    }
}
