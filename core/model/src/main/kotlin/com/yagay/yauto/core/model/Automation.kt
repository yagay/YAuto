package com.yagay.yauto.core.model

import kotlinx.serialization.Serializable

@Serializable
data class Activation(
    val events: List<FeatureRef> = emptyList(),
    val states: List<FeatureRef> = emptyList(),
    val condition: PredicateNode? = null,
)

@Serializable
data class Automation(
    val id: AutomationId,
    val name: String,
    val enabled: Boolean = true,
    /** Optional logical category/group. Disabled categories are skipped by the runtime. */
    val category: String? = null,
    val workspaceId: WorkspaceId? = null,
    val activation: Activation = Activation(),
    val onEnter: List<ActionNode> = emptyList(),
    val onEvent: List<ActionNode> = emptyList(),
    val onExit: List<ActionNode> = emptyList(),
    val variables: Map<String, ConfigValue> = emptyMap(),
    val executionPolicy: ExecutionPolicy = ExecutionPolicy(),
    val description: String? = null,
    val source: SourceMetadata? = null,
)

@Serializable
data class ExecutionPolicy(
    val conflictPolicy: ConflictPolicy = ConflictPolicy.QUEUE,
    val maxRuntimeMs: Long = 120_000,
    val maxLoopIterations: Int = 10_000,
)

@Serializable
enum class ConflictPolicy {
    QUEUE,
    PARALLEL,
    CANCEL_PREVIOUS,
    IGNORE_NEW,
}
