package com.yagay.yauto.core.model

import kotlinx.serialization.Serializable

@Serializable
data class FlowParameter(
    val name: String,
    val type: ValueType,
    val required: Boolean = false,
    val defaultValue: ConfigValue = ConfigValue.NullValue,
)

@Serializable
data class Flow(
    val id: FlowId,
    val name: String,
    val inputs: List<FlowParameter> = emptyList(),
    val outputs: List<FlowParameter> = emptyList(),
    val actions: List<ActionNode> = emptyList(),
    val description: String? = null,
    val source: SourceMetadata? = null,
)

@Serializable
enum class ValueType {
    STRING,
    NUMBER,
    BOOLEAN,
    LIST,
    OBJECT,
    APP,
    PACKAGE,
    COMPONENT,
    URI,
    FILE,
    DATE_TIME,
    DURATION,
    COLOR,
    LOCATION,
    ANY,
}
