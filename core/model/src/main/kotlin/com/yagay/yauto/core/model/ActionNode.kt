package com.yagay.yauto.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ActionNode {
    val id: NodeId

    @Serializable @SerialName("action")
    data class Action(
        override val id: NodeId,
        val feature: FeatureRef,
        val enabled: Boolean = true,
        val comment: String? = null,
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

    @Serializable @SerialName("for_each")
    data class ForEach(
        override val id: NodeId,
        val values: List<ConfigValue>,
        val variableName: String,
        val actions: List<ActionNode>,
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
