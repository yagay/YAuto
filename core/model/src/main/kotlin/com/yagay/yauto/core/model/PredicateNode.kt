package com.yagay.yauto.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface PredicateNode {
    @Serializable
    @SerialName("all")
    data class All(val children: List<PredicateNode>) : PredicateNode

    @Serializable
    @SerialName("any")
    data class Any(val children: List<PredicateNode>) : PredicateNode

    @Serializable
    @SerialName("none")
    data class None(val children: List<PredicateNode>) : PredicateNode

    @Serializable
    @SerialName("condition")
    data class Condition(val feature: FeatureRef) : PredicateNode

    @Serializable
    @SerialName("expression")
    data class Expression(val expression: String) : PredicateNode

    @Serializable
    @SerialName("literal")
    data class Literal(val value: Boolean) : PredicateNode
}
