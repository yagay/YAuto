package com.yagay.yauto.feature.standard.json

import com.yagay.yauto.core.model.ConfigValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull

internal sealed interface JsonPathPart {
    data class Key(val value: String) : JsonPathPart
    data class Index(val value: Int) : JsonPathPart
}

internal fun parseJsonPath(raw: String): List<JsonPathPart>? {
    val text = raw.trim().removePrefix("$").removePrefix(".")
    if (text.isBlank()) return emptyList()
    val parts = mutableListOf<JsonPathPart>()
    var index = 0
    while (index < text.length) {
        if (text[index] == '.') {
            index++
            continue
        }
        if (text[index] == '[') {
            val end = text.indexOf(']', index + 1)
            if (end < 0) return null
            val itemIndex = text.substring(index + 1, end).trim().toIntOrNull() ?: return null
            if (itemIndex < 0) return null
            parts += JsonPathPart.Index(itemIndex)
            index = end + 1
            continue
        }
        val start = index
        while (index < text.length && text[index] != '.' && text[index] != '[') index++
        val key = text.substring(start, index).trim()
        if (key.isBlank()) return null
        parts += JsonPathPart.Key(key)
    }
    return parts
}

internal fun getAtPath(root: ConfigValue, path: List<JsonPathPart>): ConfigValue? =
    path.fold(root as ConfigValue?) { current, part ->
        when (part) {
            is JsonPathPart.Key -> (current as? ConfigValue.ObjectValue)?.value?.get(part.value)
            is JsonPathPart.Index -> (current as? ConfigValue.ListValue)?.value?.getOrNull(part.value)
        }
    }

internal fun setAtPath(root: ConfigValue, path: List<JsonPathPart>, newValue: ConfigValue): ConfigValue? {
    if (path.isEmpty()) return newValue
    val head = path.first()
    val tail = path.drop(1)
    return when (head) {
        is JsonPathPart.Key -> {
            val objectValue = root as? ConfigValue.ObjectValue ?: return null
            val current = objectValue.value[head.value]
            val replacement = if (tail.isEmpty()) {
                newValue
            } else {
                current?.let { setAtPath(it, tail, newValue) } ?: return null
            }
            ConfigValue.ObjectValue(objectValue.value + (head.value to replacement))
        }
        is JsonPathPart.Index -> {
            val listValue = root as? ConfigValue.ListValue ?: return null
            if (head.value !in listValue.value.indices) return null
            val values = listValue.value.toMutableList()
            values[head.value] = if (tail.isEmpty()) {
                newValue
            } else {
                setAtPath(values[head.value], tail, newValue) ?: return null
            }
            ConfigValue.ListValue(values)
        }
    }
}

internal fun removeAtPath(root: ConfigValue, path: List<JsonPathPart>): ConfigValue? {
    if (path.isEmpty()) return null
    val head = path.first()
    val tail = path.drop(1)
    return when (head) {
        is JsonPathPart.Key -> {
            val objectValue = root as? ConfigValue.ObjectValue ?: return null
            if (head.value !in objectValue.value) return null
            if (tail.isEmpty()) {
                ConfigValue.ObjectValue(objectValue.value - head.value)
            } else {
                val child = objectValue.value[head.value] ?: return null
                val replacement = removeAtPath(child, tail) ?: return null
                ConfigValue.ObjectValue(objectValue.value + (head.value to replacement))
            }
        }
        is JsonPathPart.Index -> {
            val listValue = root as? ConfigValue.ListValue ?: return null
            if (head.value !in listValue.value.indices) return null
            val values = listValue.value.toMutableList()
            if (tail.isEmpty()) {
                values.removeAt(head.value)
            } else {
                values[head.value] = removeAtPath(values[head.value], tail) ?: return null
            }
            ConfigValue.ListValue(values)
        }
    }
}

internal fun JsonElement.toConfigValue(): ConfigValue = when (this) {
    JsonNull -> ConfigValue.NullValue
    is JsonPrimitive -> when {
        isString -> ConfigValue.StringValue(content)
        booleanOrNull != null -> ConfigValue.BooleanValue(boolean)
        doubleOrNull != null -> ConfigValue.NumberValue(double)
        else -> ConfigValue.StringValue(content)
    }
    is JsonArray -> ConfigValue.ListValue(map { it.toConfigValue() })
    is JsonObject -> ConfigValue.ObjectValue(mapValues { it.value.toConfigValue() })
}

internal fun ConfigValue.toJsonElement(): JsonElement = when (this) {
    ConfigValue.NullValue -> JsonNull
    is ConfigValue.StringValue -> JsonPrimitive(value)
    is ConfigValue.NumberValue -> JsonPrimitive(value)
    is ConfigValue.BooleanValue -> JsonPrimitive(value)
    is ConfigValue.ListValue -> JsonArray(value.map { it.toJsonElement() })
    is ConfigValue.ObjectValue -> JsonObject(value.mapValues { it.value.toJsonElement() })
}
