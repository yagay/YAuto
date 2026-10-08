package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.DefinitionFeaturePack
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureExecutionContext
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.resolveVariables
import kotlin.math.max
import kotlin.random.Random

class CollectionExpansionFeaturePack : FeaturePack {
    override val id: String = "standard.collection.expansion"
    private val delegate = DefinitionFeaturePack(
        id,
        ListExpansionFeatures.definitions + ObjectExpansionFeatures.definitions,
    )
    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}

private object ListExpansionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        listValueAction("data.list.prepend", "Prepend to list", "Insert a value at the beginning of a list") { feature, context, values ->
            ConfigValue.ListValue(listOf(feature.value(context)) + values)
        },
        listValueAction("data.list.insert", "Insert list item", "Insert a value at a zero-based list index", includeIndex = true) { feature, context, values ->
            val index = feature.index().coerceIn(0, values.size)
            ConfigValue.ListValue(values.toMutableList().apply { add(index, feature.value(context)) })
        },
        listValueAction("data.list.set", "Set list item", "Replace a list item at a zero-based index", includeIndex = true) { feature, context, values ->
            val index = feature.index()
            if (index !in values.indices) return@listValueAction null
            ConfigValue.ListValue(values.toMutableList().apply { this[index] = feature.value(context) })
        },
        action(
            "data.list.remove_value", "Remove list value", "Remove matching values from a list",
            listOf(listVariable(), valueField(), FieldSchema.Toggle("all", "Remove all matches"), resultVariable()),
            mapOf(
                "value" to FieldBehavior(supportsVariables = true),
                "all" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true)),
            ),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val target = feature.value(context)
            val output = if (feature.config.boolean("all", true)) {
                values.filterNot { it == target }
            } else {
                values.toMutableList().apply {
                    val found = indexOf(target)
                    if (found >= 0) removeAt(found)
                }
            }
            context.storeCollection(feature.destination(), ConfigValue.ListValue(output))
        },
        listValueAction("data.list.contains", "List contains value", "Check whether a list contains a value") { feature, context, values ->
            ConfigValue.BooleanValue(values.contains(feature.value(context)))
        },
        listValueAction("data.list.index_of", "Find list item", "Return the first zero-based index of a list value") { feature, context, values ->
            ConfigValue.NumberValue(values.indexOf(feature.value(context)).toDouble())
        },
        listValueAction("data.list.last_index_of", "Find last list item", "Return the last zero-based index of a list value") { feature, context, values ->
            ConfigValue.NumberValue(values.lastIndexOf(feature.value(context)).toDouble())
        },
        action(
            "data.list.slice", "Slice list", "Copy a range from a list using start and exclusive end indexes",
            listOf(
                listVariable(),
                FieldSchema.Number("start", "Start index", true, min = 0.0),
                FieldSchema.Number("end", "End index (exclusive)", true, min = 0.0),
                resultVariable(),
            ),
            mapOf(
                "start" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                "end" to FieldBehavior(defaultValue = ConfigValue.NumberValue(1.0)),
            ),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val start = feature.number("start").toInt().coerceIn(0, values.size)
            val end = feature.number("end").toInt().coerceIn(start, values.size)
            context.storeCollection(feature.destination(), ConfigValue.ListValue(values.subList(start, end)))
        },
        countListAction("data.list.take", "Take list items", "Take the first N list items") { values, count -> values.take(count) },
        countListAction("data.list.drop", "Drop list items", "Drop the first N list items") { values, count -> values.drop(count) },
        action(
            "data.list.chunk", "Chunk list", "Split a list into fixed-size sublists",
            listOf(listVariable(), FieldSchema.Number("size", "Chunk size", true, min = 1.0), resultVariable()),
            mapOf("size" to FieldBehavior(defaultValue = ConfigValue.NumberValue(2.0))),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val size = max(1, feature.number("size", 2.0).toInt())
            context.storeCollection(
                feature.destination(),
                ConfigValue.ListValue(values.chunked(size).map { ConfigValue.ListValue(it) }),
            )
        },
        simpleListAction("data.list.first", "First list item", "Read the first list item") { values -> values.firstOrNull() ?: ConfigValue.NullValue },
        simpleListAction("data.list.last", "Last list item", "Read the last list item") { values -> values.lastOrNull() ?: ConfigValue.NullValue },
        simpleListAction("data.list.count", "List item count", "Return the number of items in a list") { values -> ConfigValue.NumberValue(values.size.toDouble()) },
        action(
            "data.list.join", "Join list", "Join list items into text using a separator",
            listOf(listVariable(), FieldSchema.Text("separator", "Separator"), resultVariable()),
            mapOf("separator" to FieldBehavior(defaultValue = ConfigValue.StringValue(","), supportsVariables = true)),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val separator = feature.config.string("separator", ",").resolveVariables(context.variables)
            context.storeCollection(feature.destination(), ConfigValue.StringValue(values.joinToString(separator) { it.asText() }))
        },
        action(
            "data.list.flatten", "Flatten list", "Flatten nested list values by one level or recursively",
            listOf(listVariable(), FieldSchema.Toggle("recursive", "Flatten recursively"), resultVariable()),
            mapOf("recursive" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false))),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val output = if (feature.config.boolean("recursive", false)) {
                values.flattenDeep()
            } else {
                values.flatMap { (it as? ConfigValue.ListValue)?.value ?: listOf(it) }
            }
            context.storeCollection(feature.destination(), ConfigValue.ListValue(output))
        },
        action(
            "data.list.compact", "Compact list", "Remove null and optionally blank string values from a list",
            listOf(listVariable(), FieldSchema.Toggle("removeBlank", "Remove blank text"), resultVariable()),
            mapOf("removeBlank" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val removeBlank = feature.config.boolean("removeBlank", true)
            val output = values.filterNot {
                it == ConfigValue.NullValue || (removeBlank && it is ConfigValue.StringValue && it.value.isBlank())
            }
            context.storeCollection(feature.destination(), ConfigValue.ListValue(output))
        },
        numericListAction("data.list.sum", "Sum list", "Sum numeric list values") { it.sum() },
        numericListAction("data.list.average", "Average list", "Calculate the average of numeric list values") { if (it.isEmpty()) 0.0 else it.average() },
        numericListAction("data.list.min", "Minimum list value", "Return the minimum numeric list value") { it.minOrNull() ?: 0.0 },
        numericListAction("data.list.max", "Maximum list value", "Return the maximum numeric list value") { it.maxOrNull() ?: 0.0 },
        twoListAction("data.list.union", "Union lists", "Combine two lists while keeping unique values") { left, right -> (left + right).distinct() },
        twoListAction("data.list.intersection", "Intersect lists", "Keep values present in both lists") { left, right -> left.filter { it in right }.distinct() },
        twoListAction("data.list.subtract", "Subtract lists", "Remove values that are present in another list") { left, right -> left.filterNot { it in right } },
        simpleListAction("data.list.shuffle", "Shuffle list", "Randomize the order of list items") { values -> ConfigValue.ListValue(values.shuffled(Random.Default)) },
        action(
            "data.list.rotate", "Rotate list", "Rotate list items by a signed number of positions",
            listOf(listVariable(), FieldSchema.Number("distance", "Distance", true), resultVariable()),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            if (values.isEmpty()) return@action context.storeCollection(feature.destination(), ConfigValue.ListValue(emptyList()))
            val distance = feature.number("distance").toInt()
            val shift = ((distance % values.size) + values.size) % values.size
            val output = if (shift == 0) values else values.takeLast(shift) + values.dropLast(shift)
            context.storeCollection(feature.destination(), ConfigValue.ListValue(output))
        },
    )

    private fun listValueAction(
        id: String,
        title: String,
        description: String,
        includeIndex: Boolean = false,
        operation: (FeatureRef, FeatureExecutionContext, List<ConfigValue>) -> ConfigValue?,
    ): FeatureDefinition {
        val fields = buildList {
            add(listVariable())
            if (includeIndex) add(FieldSchema.Number("index", "Index", true, min = 0.0))
            add(valueField())
            add(resultVariable())
        }
        return action(id, title, description, fields, mapOf("value" to FieldBehavior(supportsVariables = true))) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val output = operation(feature, context, values) ?: return@action outOfBounds()
            context.storeCollection(feature.destination(), output)
        }
    }

    private fun simpleListAction(
        id: String,
        title: String,
        description: String,
        operation: (List<ConfigValue>) -> ConfigValue,
    ) = action(id, title, description, listOf(listVariable(), resultVariable())) { feature, context ->
        val values = context.list(feature.name()) ?: return@action notList()
        context.storeCollection(feature.destination(), operation(values))
    }

    private fun countListAction(
        id: String,
        title: String,
        description: String,
        operation: (List<ConfigValue>, Int) -> List<ConfigValue>,
    ) = action(
        id, title, description,
        listOf(listVariable(), FieldSchema.Number("count", "Count", true, min = 0.0), resultVariable()),
        mapOf("count" to FieldBehavior(defaultValue = ConfigValue.NumberValue(1.0))),
    ) { feature, context ->
        val values = context.list(feature.name()) ?: return@action notList()
        context.storeCollection(
            feature.destination(),
            ConfigValue.ListValue(operation(values, max(0, feature.number("count", 1.0).toInt()))),
        )
    }

    private fun numericListAction(
        id: String,
        title: String,
        description: String,
        operation: (List<Double>) -> Double,
    ) = action(id, title, description, listOf(listVariable(), resultVariable())) { feature, context ->
        val values = context.list(feature.name()) ?: return@action notList()
        val numbers = values.mapNotNull { it.numberValueOrNull() }
        if (numbers.size != values.size) return@action operationFailed(feature)
        context.storeCollection(feature.destination(), ConfigValue.NumberValue(operation(numbers)))
    }

    private fun twoListAction(
        id: String,
        title: String,
        description: String,
        operation: (List<ConfigValue>, List<ConfigValue>) -> List<ConfigValue>,
    ) = action(
        id, title, description,
        listOf(listVariable(), FieldSchema.Variable("other", "Other list variable", true), resultVariable()),
    ) { feature, context ->
        val left = context.list(feature.name()) ?: return@action notList()
        val right = context.list(feature.config.string("other")) ?: return@action notList()
        context.storeCollection(feature.destination(), ConfigValue.ListValue(operation(left, right)))
    }
}

private object ObjectExpansionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        objectKeyAction("data.object.remove", "Remove object field", "Remove a key from an object variable") { source, key -> ConfigValue.ObjectValue(source - key) },
        objectKeyAction("data.object.contains_key", "Object contains key", "Check whether an object contains a key") { source, key -> ConfigValue.BooleanValue(key in source) },
        simpleObjectAction("data.object.keys", "Object keys", "Return all object keys") { source -> ConfigValue.ListValue(source.keys.map(ConfigValue::StringValue)) },
        simpleObjectAction("data.object.values", "Object values", "Return all object values") { source -> ConfigValue.ListValue(source.values.toList()) },
        simpleObjectAction("data.object.size", "Object size", "Return the number of fields in an object") { source -> ConfigValue.NumberValue(source.size.toDouble()) },
        action(
            "data.object.merge", "Merge objects", "Merge two object variables; values from the second object win on key collisions",
            listOf(objectVariable(), FieldSchema.Variable("other", "Other object variable", true), resultVariable()),
        ) { feature, context ->
            val left = context.obj(feature.name()) ?: return@action notObject()
            val right = context.obj(feature.config.string("other")) ?: return@action notObject()
            context.storeCollection(feature.destination(), ConfigValue.ObjectValue(left + right))
        },
        simpleObjectAction("data.object.entries", "Object entries", "Convert object fields into a list of key/value objects") { source ->
            ConfigValue.ListValue(source.map { (key, value) ->
                ConfigValue.ObjectValue(mapOf("key" to ConfigValue.StringValue(key), "value" to value))
            })
        },
        renameOrCopy("data.object.rename_key", "Rename object key", "Rename a field in an object while preserving its value", removeOriginal = true),
        renameOrCopy("data.object.copy_key", "Copy object key", "Copy a field to another key in the same object", removeOriginal = false),
    )

    private fun simpleObjectAction(
        id: String,
        title: String,
        description: String,
        operation: (Map<String, ConfigValue>) -> ConfigValue,
    ) = action(id, title, description, listOf(objectVariable(), resultVariable())) { feature, context ->
        val source = context.obj(feature.name()) ?: return@action notObject()
        context.storeCollection(feature.destination(), operation(source))
    }

    private fun objectKeyAction(
        id: String,
        title: String,
        description: String,
        operation: (Map<String, ConfigValue>, String) -> ConfigValue,
    ) = action(
        id, title, description,
        listOf(objectVariable(), FieldSchema.Text("key", "Key", true), resultVariable()),
    ) { feature, context ->
        val source = context.obj(feature.name()) ?: return@action notObject()
        val key = feature.config.string("key")
        if (key.isBlank()) return@action keyEmpty()
        context.storeCollection(feature.destination(), operation(source, key))
    }

    private fun renameOrCopy(
        id: String,
        title: String,
        description: String,
        removeOriginal: Boolean,
    ) = action(
        id, title, description,
        listOf(
            objectVariable(),
            FieldSchema.Text("key", "Source key", true),
            FieldSchema.Text("newKey", "Destination key", true),
            resultVariable(),
        ),
    ) { feature, context ->
        val source = context.obj(feature.name()) ?: return@action notObject()
        val key = feature.config.string("key")
        val newKey = feature.config.string("newKey")
        if (key.isBlank() || newKey.isBlank()) return@action keyEmpty()
        val value = source[key] ?: return@action operationFailed(feature)
        val output = LinkedHashMap(source)
        if (removeOriginal) output.remove(key)
        output[newKey] = value
        context.storeCollection(feature.destination(), ConfigValue.ObjectValue(output))
    }
}

private fun action(
    id: String,
    title: String,
    description: String,
    fields: List<FieldSchema>,
    behaviors: Map<String, FieldBehavior> = emptyMap(),
    block: suspend (FeatureRef, FeatureExecutionContext) -> ActionExecutionResult,
): FeatureDefinition = actionFeature(
    FeatureDescriptor(
        id = FeatureId(id),
        kind = FeatureKind.ACTION,
        title = title,
        description = description,
        category = FeatureCategory.VARIABLE,
        fields = fields,
        fieldBehaviors = behaviors,
        keywords = setOf("collection", "list", "object", "array", "map", "data"),
    ),
) { feature, context -> block(feature, context) }

private fun listVariable() = FieldSchema.Variable("name", "List variable", true)
private fun objectVariable() = FieldSchema.Variable("name", "Object variable", true)
private fun valueField() = FieldSchema.Text("value", "Value", true)
private fun resultVariable() = FieldSchema.Text("resultVariable", "Store result in variable", true)

private fun FeatureRef.name(): String = config.string("name")
private fun FeatureRef.destination(): String = config.string("resultVariable")
private fun FeatureRef.index(): Int = number("index").toInt()
private fun FeatureRef.number(key: String, default: Double = 0.0): Double = config[key].numberOrNull() ?: default
private fun FeatureRef.value(context: FeatureExecutionContext): ConfigValue =
    (config["value"] ?: ConfigValue.NullValue).resolveVariables(context.variables)

private fun FeatureExecutionContext.list(name: String): List<ConfigValue>? =
    (variables.get(name) as? ConfigValue.ListValue)?.value

private fun FeatureExecutionContext.obj(name: String): Map<String, ConfigValue>? =
    (variables.get(name) as? ConfigValue.ObjectValue)?.value

private fun FeatureExecutionContext.storeCollection(name: String, value: ConfigValue): ActionExecutionResult {
    if (name.isBlank()) return ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
    variables.set(name, value)
    return ActionExecutionResult(true, value)
}

private fun notList() = ActionExecutionResult(false, message = userText("feature.variable_not_list"))
private fun notObject() = ActionExecutionResult(false, message = userText("feature.variable_not_object"))
private fun outOfBounds() = ActionExecutionResult(false, message = userText("feature.list_index_out_of_bounds"))
private fun keyEmpty() = ActionExecutionResult(false, message = userText("feature.key_empty"))
private fun operationFailed(feature: FeatureRef) =
    ActionExecutionResult(false, message = userText("feature.operation_failed", feature.id.value))

private fun ConfigValue.asText(): String = when (this) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { (key, value) -> "$key=${value.asText()}" }
}

private fun ConfigValue.numberValueOrNull(): Double? = when (this) {
    is ConfigValue.NumberValue -> value
    is ConfigValue.StringValue -> value.toDoubleOrNull()
    else -> null
}

private fun List<ConfigValue>.flattenDeep(): List<ConfigValue> = flatMap { value ->
    when (value) {
        is ConfigValue.ListValue -> value.value.flattenDeep()
        else -> listOf(value)
    }
}
