package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.FeatureRef

/**
 * Event feature IDs that the runtime must currently be able to receive.
 *
 * This includes activation triggers and WaitEvent nodes reachable from enabled automations.
 * Keeping this index in core storage gives Android event sources and Accessibility one canonical
 * subscription view instead of each backend independently rescanning workspace structure.
 */
fun WorkspaceData.runtimeEventFeatureIds(): Set<String> = buildSet {
    val active = automations.filter { automation ->
        automation.enabled &&
            (automation.category == null || automation.category !in disabledCategories)
    }

    active.forEach { automation ->
        addAll(automation.activation.events.map(FeatureRef::typeId))
        collectWaitEvents(automation.onEnter)
        collectWaitEvents(automation.onEvent)
        collectWaitEvents(automation.onExit)
        flowClosure(automation).forEach { flow -> collectWaitEvents(flow.actions) }
    }
}

private fun MutableSet<String>.collectWaitEvents(nodes: List<ActionNode>) {
    nodes.walk().forEach { node ->
        if (node is ActionNode.WaitEvent) {
            addAll(node.events.map(FeatureRef::typeId))
        }
    }
}
