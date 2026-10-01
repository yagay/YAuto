package com.yagay.yauto.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ConfigValue {
    @Serializable
    @SerialName("null")
    data object NullValue : ConfigValue

    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : ConfigValue

    @Serializable
    @SerialName("number")
    data class NumberValue(val value: Double) : ConfigValue

    @Serializable
    @SerialName("boolean")
    data class BooleanValue(val value: Boolean) : ConfigValue

    @Serializable
    @SerialName("list")
    data class ListValue(val value: List<ConfigValue>) : ConfigValue

    @Serializable
    @SerialName("object")
    data class ObjectValue(val value: Map<String, ConfigValue>) : ConfigValue
}

typealias ConfigMap = Map<String, ConfigValue>

fun ConfigValue?.stringOrNull(): String? = (this as? ConfigValue.StringValue)?.value
fun ConfigValue?.booleanOrNull(): Boolean? = (this as? ConfigValue.BooleanValue)?.value
fun ConfigValue?.numberOrNull(): Double? = (this as? ConfigValue.NumberValue)?.value
fun ConfigValue?.listOrEmpty(): List<ConfigValue> = (this as? ConfigValue.ListValue)?.value.orEmpty()
fun ConfigMap.string(key: String, default: String = ""): String = get(key).stringOrNull() ?: default
fun ConfigMap.boolean(key: String, default: Boolean = false): Boolean = get(key).booleanOrNull() ?: default
fun ConfigMap.long(key: String, default: Long = 0): Long = get(key).numberOrNull()?.toLong() ?: default
