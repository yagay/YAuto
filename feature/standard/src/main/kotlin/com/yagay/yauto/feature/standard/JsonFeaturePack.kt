package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.serialization.json.*

class JsonFeaturePack : FeaturePack {
    override val id: String = "standard.json"
    private val json = Json { prettyPrint = false; isLenient = true; ignoreUnknownKeys = true }

    override fun install(registry: FeatureRegistry) {
        action(registry, "data.json.parse", "Parse JSON", "Parse JSON text into a structured YAuto variable", listOf(
            FieldSchema.Text("text", "JSON text", true, multiline = true),
            FieldSchema.Variable("resultVariable", "Store parsed value in variable", true),
        ), setOf("json", "parse", "object", "array")) { feature, ctx ->
            val raw = feature.config.string("text").resolveVariables(ctx.variables)
            val output = runCatching { json.parseToJsonElement(raw).toConfigValue() }
                .getOrElse { return@action failed(it) }
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        action(registry, "data.json.stringify", "Convert value to JSON", "Serialize a YAuto variable as compact or pretty JSON text", listOf(
            FieldSchema.Variable("name", "Source variable", true),
            FieldSchema.Toggle("pretty", "Pretty print"),
            FieldSchema.Variable("resultVariable", "Store JSON text in variable", true),
        ), setOf("json", "stringify", "serialize", "format")) { feature, ctx ->
            val source = ctx.variables.get(feature.config.string("name"))
                ?: return@action ActionExecutionResult(false, message = userText("feature.variable_missing", feature.config.string("name")))
            val writer = if (feature.config.boolean("pretty")) Json { prettyPrint = true } else json
            val output = ConfigValue.StringValue(writer.encodeToString(JsonElement.serializer(), source.toJsonElement()))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        action(registry, "data.json.path.get", "Read JSON path", "Read a value from an object/list variable using a path such as user.items[0].name", listOf(
            FieldSchema.Variable("name", "Source variable", true),
            FieldSchema.Text("path", "JSON path", true),
            FieldSchema.Variable("resultVariable", "Store result in variable", true),
        ), setOf("json", "path", "get", "object", "array")) { feature, ctx ->
            val source = ctx.variables.get(feature.config.string("name"))
                ?: return@action ActionExecutionResult(false, message = userText("feature.variable_missing", feature.config.string("name")))
            val path = parseJsonPath(feature.config.string("path").resolveVariables(ctx.variables))
                ?: return@action invalidPath()
            val output = getAtPath(source, path) ?: return@action ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        action(registry, "data.json.path.set", "Write JSON path", "Set or replace a value inside an object/list variable using a JSON path", listOf(
            FieldSchema.Variable("name", "Target variable", true),
            FieldSchema.Text("path", "JSON path", true),
            FieldSchema.Text("value", "Value", multiline = true),
            FieldSchema.Choice("valueType", "Value type", true, listOf("string", "number", "boolean", "null", "json")),
        ), setOf("json", "path", "set", "object", "array")) { feature, ctx ->
            val name = feature.config.string("name")
            val source = ctx.variables.get(name) ?: return@action ActionExecutionResult(false, message = userText("feature.variable_missing", name))
            val path = parseJsonPath(feature.config.string("path").resolveVariables(ctx.variables)) ?: return@action invalidPath()
            if (path.isEmpty()) return@action invalidPath()
            val value = parseConfiguredValue(feature.config.string("value").resolveVariables(ctx.variables), feature.config.string("valueType", "string"))
                ?: return@action ActionExecutionResult(false, message = userText("feature.json_value_invalid"))
            val updated = setAtPath(source, path, value) ?: return@action ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            ctx.variables.set(name, updated)
            ActionExecutionResult(true, updated)
        }

        action(registry, "data.json.path.remove", "Remove JSON path", "Remove an object key or list item from a structured variable", listOf(
            FieldSchema.Variable("name", "Target variable", true),
            FieldSchema.Text("path", "JSON path", true),
        ), setOf("json", "path", "remove", "delete")) { feature, ctx ->
            val name = feature.config.string("name")
            val source = ctx.variables.get(name) ?: return@action ActionExecutionResult(false, message = userText("feature.variable_missing", name))
            val path = parseJsonPath(feature.config.string("path").resolveVariables(ctx.variables)) ?: return@action invalidPath()
            if (path.isEmpty()) return@action invalidPath()
            val updated = removeAtPath(source, path) ?: return@action ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            ctx.variables.set(name, updated)
            ActionExecutionResult(true, updated)
        }

        action(registry, "data.json.keys", "Get object keys", "Store the keys of an object variable or JSON-path object as a list", listOf(
            FieldSchema.Variable("name", "Source variable", true),
            FieldSchema.Text("path", "Optional JSON path"),
            FieldSchema.Variable("resultVariable", "Store key list in variable", true),
        ), setOf("json", "keys", "object", "map")) { feature, ctx ->
            val source = ctx.variables.get(feature.config.string("name")) ?: return@action ActionExecutionResult(false, message = userText("feature.variable_missing", feature.config.string("name")))
            val pathText = feature.config.string("path").resolveVariables(ctx.variables).trim()
            val target = if (pathText.isBlank()) source else {
                val path = parseJsonPath(pathText) ?: return@action invalidPath()
                getAtPath(source, path) ?: return@action ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            }
            val obj = target as? ConfigValue.ObjectValue ?: return@action ActionExecutionResult(false, message = userText("feature.json_object_required"))
            val output = ConfigValue.ListValue(obj.value.keys.sorted().map(ConfigValue::StringValue))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        action(registry, "data.json.array_length", "Get array length", "Read the size of a list variable or list at a JSON path", listOf(
            FieldSchema.Variable("name", "Source variable", true),
            FieldSchema.Text("path", "Optional JSON path"),
            FieldSchema.Variable("resultVariable", "Store length in variable", true),
        ), setOf("json", "array", "length", "size")) { feature, ctx ->
            val source = ctx.variables.get(feature.config.string("name")) ?: return@action ActionExecutionResult(false, message = userText("feature.variable_missing", feature.config.string("name")))
            val pathText = feature.config.string("path").resolveVariables(ctx.variables).trim()
            val target = if (pathText.isBlank()) source else {
                val path = parseJsonPath(pathText) ?: return@action invalidPath()
                getAtPath(source, path) ?: return@action ActionExecutionResult(false, message = userText("feature.json_path_not_found"))
            }
            val list = target as? ConfigValue.ListValue ?: return@action ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            val output = ConfigValue.NumberValue(list.value.size.toDouble())
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun action(registry: FeatureRegistry, typeId: String, title: String, description: String, fields: List<FieldSchema>, keywords: Set<String>, executor: suspend (com.yagay.yauto.core.model.FeatureRef, FeatureExecutionContext) -> ActionExecutionResult) {
        registry.registerAction(FeatureDescriptor(FeatureId(typeId), FeatureKind.ACTION, title, description, FeatureCategory.VARIABLE, fields = fields, keywords = keywords, ownerPackId = id), ActionExecutor(executor))
    }

    private fun failed(error: Throwable) = ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
    private fun invalidPath() = ActionExecutionResult(false, message = userText("feature.json_path_invalid"))

    private fun parseConfiguredValue(raw: String, type: String): ConfigValue? = when (type) {
        "string" -> ConfigValue.StringValue(raw)
        "number" -> raw.toDoubleOrNull()?.let(ConfigValue::NumberValue)
        "boolean" -> when (raw.trim().lowercase()) { "true" -> ConfigValue.BooleanValue(true); "false" -> ConfigValue.BooleanValue(false); else -> null }
        "null" -> ConfigValue.NullValue
        "json" -> runCatching { json.parseToJsonElement(raw).toConfigValue() }.getOrNull()
        else -> null
    }
}

internal sealed interface JsonPathPart {
    data class Key(val value: String) : JsonPathPart
    data class Index(val value: Int) : JsonPathPart
}

internal fun parseJsonPath(raw: String): List<JsonPathPart>? {
    val text = raw.trim().removePrefix("$").removePrefix(".")
    if (text.isBlank()) return emptyList()
    val parts = mutableListOf<JsonPathPart>()
    var i = 0
    while (i < text.length) {
        if (text[i] == '.') { i++; continue }
        if (text[i] == '[') {
            val end = text.indexOf(']', i + 1)
            if (end < 0) return null
            val index = text.substring(i + 1, end).trim().toIntOrNull() ?: return null
            if (index < 0) return null
            parts += JsonPathPart.Index(index)
            i = end + 1
            continue
        }
        val start = i
        while (i < text.length && text[i] != '.' && text[i] != '[') i++
        val key = text.substring(start, i).trim()
        if (key.isBlank()) return null
        parts += JsonPathPart.Key(key)
    }
    return parts
}

internal fun getAtPath(root: ConfigValue, path: List<JsonPathPart>): ConfigValue? = path.fold(root as ConfigValue?) { current, part ->
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
            val obj = root as? ConfigValue.ObjectValue ?: return null
            val current = obj.value[head.value]
            val replacement = if (tail.isEmpty()) newValue else current?.let { setAtPath(it, tail, newValue) } ?: return null
            ConfigValue.ObjectValue(obj.value + (head.value to replacement))
        }
        is JsonPathPart.Index -> {
            val list = root as? ConfigValue.ListValue ?: return null
            if (head.value !in list.value.indices) return null
            val values = list.value.toMutableList()
            values[head.value] = if (tail.isEmpty()) newValue else setAtPath(values[head.value], tail, newValue) ?: return null
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
            val obj = root as? ConfigValue.ObjectValue ?: return null
            if (head.value !in obj.value) return null
            if (tail.isEmpty()) ConfigValue.ObjectValue(obj.value - head.value)
            else {
                val child = obj.value[head.value] ?: return null
                val replacement = removeAtPath(child, tail) ?: return null
                ConfigValue.ObjectValue(obj.value + (head.value to replacement))
            }
        }
        is JsonPathPart.Index -> {
            val list = root as? ConfigValue.ListValue ?: return null
            if (head.value !in list.value.indices) return null
            val values = list.value.toMutableList()
            if (tail.isEmpty()) values.removeAt(head.value)
            else values[head.value] = removeAtPath(values[head.value], tail) ?: return null
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
