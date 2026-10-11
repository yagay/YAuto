package com.yagay.yauto.core.storage

import com.yagay.yauto.core.model.ActionNode
import com.yagay.yauto.core.model.Automation
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.ConfigValue

/**
 * Event feature IDs that the runtime must currently be able to receive.
 *
 * This includes activation triggers and WaitEvent nodes reachable from enabled automations.
 * Keeping this index in core storage gives Android event sources and Accessibility one canonical
 * subscription view instead of each backend independently rescanning workspace structure.
 */
fun WorkspaceData.runtimeEventFeatureIds(): Set<String> = buildSet {
    // Global pause must also stop expensive configured listeners, not only dispatch execution.
    if (!runtimeEnabled) return@buildSet
    val active = automations.filter { automation ->
        automation.enabled &&
            (automation.category == null || automation.category !in disabledCategories)
    }

    activeActivationEvents().forEach { add(it.typeId) }
    active.forEach { automation ->
        collectWaitEvents(automation.onEnter)
        collectWaitEvents(automation.onEvent)
        collectWaitEvents(automation.onExit)
        flowClosure(automation).forEach { flow -> collectWaitEvents(flow.actions) }
    }
}

/** Mirrors the runtime's persisted activation key for normal source.type and tag fields. */
private fun runtimeTriggerKey(automation: com.yagay.yauto.core.model.Automation, feature: FeatureRef): String {
    val sourceType = (feature.config["source.type"] as? ConfigValue.StringValue)?.value.orEmpty()
    val tag = (feature.config["tag"] as? ConfigValue.StringValue)?.value.orEmpty().trim()
    return automation.id.value + "|" + sourceType.ifBlank { feature.typeId } + "|" + tag
}

private fun MutableSet<String>.collectWaitEvents(nodes: List<ActionNode>) {
    nodes.walk().forEach { node ->
        if (node is ActionNode.WaitEvent) {
            addAll(node.events.map(FeatureRef::typeId))
        }
    }
}

/** Shared runtime subscription view; disabled categories and individual triggers are excluded. */
fun WorkspaceData.activeActivationEvents(): Sequence<FeatureRef> {
    if (!runtimeEnabled) return emptySequence()
    return automations.asSequence()
        .filter { it.enabled && (it.category == null || it.category !in disabledCategories) }
        .flatMap { automation ->
            automation.activation.events.asSequence().filter { feature ->
                runtimeTriggerKey(automation, feature) !in disabledTriggerKeys
            }
        }
}
