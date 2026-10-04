package com.yagay.yauto.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ActionFailurePolicy {
    /** Preserve the historical YAuto behavior: stop the current action chain on failure. */
    STOP,

    /** Record the failed action, then continue with the next node in the same action chain. */
    CONTINUE,

    /** Retry the action according to [ActionRetryPolicy], then stop if all attempts fail. */
    RETRY,
}

/**
 * Retry settings for one action node.
 *
 * [maxAttempts] is the total number of executions including the first attempt. Runtime code clamps
 * untrusted/imported values to safe limits so malformed external backups cannot create an
 * unbounded retry loop or excessively long per-attempt delay.
 */
@Serializable
data class ActionRetryPolicy(
    val maxAttempts: Int = 3,
    val delayMs: Long = 500L,
)

@Serializable
sealed interface ActionNode {
    val id: NodeId

    @Serializable @SerialName("action")
    data class Action(
        override val id: NodeId,
        val feature: FeatureRef,
        val enabled: Boolean = true,
        val comment: String? = null,
        /** Missing in older workspaces, so STOP must remain the default for backwards compatibility. */
        val failurePolicy: ActionFailurePolicy = ActionFailurePolicy.STOP,
        val retryPolicy: ActionRetryPolicy = ActionRetryPolicy(),
    ) : ActionNode

    @Serializable @SerialName("if")
    data class If(
        override val id: NodeId,
        val condition: PredicateNode,
        val thenActions: List<ActionNode>,
        val elseActions: List<ActionNode> = emptyList(),
    ) : ActionNode

    @Serializable @SerialName("switch")
    data class Switch(
        override val id: NodeId,
        val expression: String,
        val cases: List<SwitchCase>,
        val defaultActions: List<ActionNode> = emptyList(),
    ) : ActionNode

    @Serializable @SerialName("repeat")
    data class Repeat(
        override val id: NodeId,
        val times: Int,
        val actions: List<ActionNode>,
    ) : ActionNode

    @Serializable @SerialName("while")
    data class While(
        override val id: NodeId,
        val condition: PredicateNode,
        val actions: List<ActionNode>,
    ) : ActionNode

    @Serializable @SerialName("do_while")
    data class DoWhile(
        override val id: NodeId,
        val condition: PredicateNode,
        val actions: List<ActionNode>,
    ) : ActionNode

    @Serializable @SerialName("for_each")
    data class ForEach(
        override val id: NodeId,
        val values: List<ConfigValue> = emptyList(),
        val variableName: String,
        val actions: List<ActionNode>,
        /** Optional runtime list variable. When set, it takes precedence over [values]. */
        val sourceVariable: String? = null,
    ) : ActionNode

    @Serializable @SerialName("parallel")
    data class Parallel(
        override val id: NodeId,
        val branches: List<List<ActionNode>>,
    ) : ActionNode

    @Serializable @SerialName("try")
    data class Try(
        override val id: NodeId,
        val actions: List<ActionNode>,
        val onError: List<ActionNode> = emptyList(),
        val finallyActions: List<ActionNode> = emptyList(),
    ) : ActionNode

    /**
     * Polls a normal YAuto predicate until it becomes true or the timeout is reached. Keeping the
     * predicate as a first-class node means every future Condition automatically becomes usable by
     * wait-until without adding another compatibility or executor layer.
     */
    @Serializable @SerialName("wait_until")
    data class WaitUntil(
        override val id: NodeId,
        val condition: PredicateNode,
        val timeoutMs: Long = 60_000L,
        val pollIntervalMs: Long = 500L,
        /** Wait without a deadline. Used by sources such as Tasker's Wait Until. */
        val unlimited: Boolean = false,
    ) : ActionNode

    @Serializable @SerialName("wait_event")
    data class WaitEvent(
        override val id: NodeId,
        val events: List<FeatureRef>,
        val timeoutMs: Long = 60_000L,
        val unlimited: Boolean = false,
        val continueOnTimeout: Boolean = false,
    ) : ActionNode

    @Serializable @SerialName("call_flow")
    data class CallFlow(
        override val id: NodeId,
        val flowId: FlowId,
        val input: ConfigMap = emptyMap(),
        val resultVariable: String? = null,
    ) : ActionNode

    @Serializable @SerialName("return")
    data class Return(
        override val id: NodeId,
        val value: ConfigValue = ConfigValue.NullValue,
    ) : ActionNode

    @Serializable @SerialName("break")
    data class Break(override val id: NodeId) : ActionNode

    @Serializable @SerialName("continue")
    data class Continue(override val id: NodeId) : ActionNode
}

@Serializable
data class SwitchCase(
    val match: String,
    val actions: List<ActionNode>,
)
