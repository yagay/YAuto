package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
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
import kotlin.math.min
import kotlin.random.Random

/**
 * Structured collection operations inspired by mature automation tools while keeping YAuto's
 * native ConfigValue model. Every action is executable and returns the produced value.
 */
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
        mutateValue("data.list.prepend", "Prepend to list", "Insert a value at the beginning of a list") { feature, context, values ->
            listOf(feature.value(context)) + values
        },
        mutateValue("data.list.insert", "Insert list item", "Insert a value at a zero-based list index", index = true) { feature, context, values ->
            val index = feature.index().coerceIn(0, values.size)
            values.toMutableList().apply { add(index, feature.value(context)) }
        },
        mutateValue("data.list.set", "Set list item", "Replace a list item at a zero-based index", index = true) { feature, context, values ->
            val index = feature.index()
            if (index !in values.indices) return@mutateValue null
            values.toMutableList().apply { this[index] = feature.value(context) }
        },
        action(
            id = "data.list.remove_value",
            title = "Remove list value",
            description = "Remove matching values from a list",
            fields = listOf(
                listVariable(),
                FieldSchema.Text("value", "Value", true),
                FieldSchema.Toggle("all", "Remove all matches"),
                resultVariable(),
            ),
            behaviors = mapOf(
                "value" to FieldBehavior(supportsVariables = true),
                "all" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true)),
            ),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val target = feature.value(context)
            val output = if (feature.config.boolean("all", true)) {
                values.filterNot { it == target }
            } else {
                val mutable = values.toMutableList()
                mutable.indexOf(target).takeIf { it >= 0 }?.let(mutable::removeAt)
                mutable
            }
            context.store(feature.destination(), ConfigValue.ListValue(output))
        },
        predicateResult("data.list.contains", "List contains value", "Check whether a list contains a value") { feature, context, values ->
            values.contains(feature.value(context))
        },
        numberResult("data.list.index_of", "Find list item", "Return the first zero-based index of a list value") { feature, context, values ->
            values.indexOf(feature.value(context)).toDouble()
        },
        numberResult("data.list.last_index_of", "Find last list item", "Return the last zero-based index of a list value") { feature, context, values ->
            values.lastIndexOf(feature.value(context)).toDouble()
        },
        action(
            id = "data.list.slice",
            title = "Slice list",
            description = "Copy a range from a list using start and exclusive end indexes",
            fields = listOf(
                listVariable(),
                FieldSchema.Number("start", "Start index", true, min = 0.0),
                FieldSchema.Number("end", "End index (exclusive)", true, min = 0.0),
                resultVariable(),
            ),
            behaviors = mapOf(
                "start" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                "end" to FieldBehavior(defaultValue = ConfigValue.NumberValue(1.0)),
            ),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val start = feature.number("start").toInt().coerceIn(0, values.size)
            val end = feature.number("end").toInt().coerceIn(start, values.size)
            context.store(feature.destination(), ConfigValue.ListValue(values.subList(start, end)))
        },
        countListResult("data.list.take", "Take list items", "Take the first N list items") { values, count -> values.take(count) },
        countListResult("data.list.drop", "Drop list items", "Drop the first N list items") { values, count -> values.drop(count) },
        action(
            id = "data.list.chunk",
            title = "Chunk list",
            description = "Split a list into fixed-size sublists",
            fields = listOf(listVariable(), FieldSchema.Number("size", "Chunk size", true, min = 1.0), resultVariable()),
            behaviors = mapOf("size" to FieldBehavior(defaultValue = ConfigValue.NumberValue(2.0))),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val size = max(1, feature.number("size", 2.0).toInt())
            val output = values.chunked(size).map { ConfigValue.ListValue(it) }
            context.store(feature.destination(), ConfigValue.ListValue(output))
        },
        action(
            id = "data.list.first",
            title = "First list item",
            description = "Read the first list item",
            fields = listOf(listVariable(), resultVariable()),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            context.store(feature.destination(), values.firstOrNull() ?: ConfigValue.NullValue)
        },
        action(
            id = "data.list.last",
            title = "Last list item",
            description = "Read the last list item",
            fields = listOf(listVariable(), resultVariable()),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            context.store(feature.destination(), values.lastOrNull() ?: ConfigValue.NullValue)
        },
        action(
            id = "data.list.count",
            title = "List item count",
            description = "Return the number of items in a list",
            fields = listOf(listVariable(), resultVariable()),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            context.store(feature.destination(), ConfigValue.NumberValue(values.size.toDouble()))
        },
        action(
            id = "data.list.join",
            title = "Join list",
            description = "Join list items into text using a separator",
            fields = listOf(listVariable(), FieldSchema.Text("separator", "Separator"), resultVariable()),
            behaviors = mapOf("separator" to FieldBehavior(defaultValue = ConfigValue.StringValue(","), supportsVariables = true)),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val separator = feature.config.string("separator", ",").resolveVariables(context.variables)
            context.store(feature.destination(), ConfigValue.StringValue(values.joinToString(separator) { it.asText() }))
        },
        action(
            id = "data.list.flatten",
            title = "Flatten list",
            description = "Flatten nested list values by one level or recursively",
            fields = listOf(listVariable(), FieldSchema.Toggle("recursive", "Flatten recursively"), resultVariable()),
            behaviors = mapOf("recursive" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false))),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val recursive = feature.config.boolean("recursive", false)
            val output = if (recursive) values.flatMapDeep() else values.flatMap { (it as? ConfigValue.ListValue)?.value ?: listOf(it) }
            context.store(feature.destination(), ConfigValue.ListValue(output))
        },
        action(
            id = "data.list.compact",
            title = "Compact list",
            description = "Remove null and optionally blank string values from a list",
            fields = listOf(listVariable(), FieldSchema.Toggle("removeBlank", "Remove blank text"), resultVariable()),
            behaviors = mapOf("removeBlank" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val removeBlank = feature.config.boolean("removeBlank", true)
            val output = values.filterNot { value ->
                value == ConfigValue.NullValue || (removeBlank && value is ConfigValue.StringValue && value.value.isBlank())
            }
            context.store(feature.destination(), ConfigValue.ListValue(output))
        },
        numericAggregate("data.list.sum", "Sum list", "Sum numeric list values") { it.sum() },
        numericAggregate("data.list.average", "Average list", "Calculate the average of numeric list values") { if (it.isEmpty()) 0.0 else it.average() },
        numericAggregate("data.list.min", "Minimum list value", "Return the minimum numeric list value") { it.minOrNull() ?: 0.0 },
        numericAggregate("data.list.max", "Maximum list value", "Return the maximum numeric list value") { it.maxOrNull() ?: 0.0 },
        twoListResult("data.list.union", "Union lists", "Combine two lists while keeping unique values") { left, right -> (left + right).distinct() },
        twoListResult("data.list.intersection", "Intersect lists", "Keep values present in both lists") { left, right -> left.filter { it in right }.distinct() },
        twoListResult("data.list.subtract", "Subtract lists", "Remove values that are present in another list") { left, right -> left.filterNot { it in right } },
        action(
            id = "data.list.shuffle",
            title = "Shuffle list",
            description = "Randomize the order of list items",
            fields = listOf(listVariable(), resultVariable()),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            context.store(feature.destination(), ConfigValue.ListValue(values.shuffled(Random.Default)))
        },
        action(
            id = "data.list.rotate",
            title = "Rotate list",
            description = "Rotate list items by a signed number of positions",
            fields = listOf(listVariable(), FieldSchema.Number("distance", "Distance", true), resultVariable()),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            if (values.isEmpty()) return@action context.store(feature.destination(), ConfigValue.ListValue(emptyList()))
            val distance = feature.number("distance").toInt()
            val shift = ((distance % values.size) + values.size) % values.size
            val output = if (shift == 0) values else values.takeLast(shift) + values.dropLast(shift)
            context.store(feature.destination(), ConfigValue.ListValue(output))
        },
    )

    private fun mutateValue(
        id: String,
        title: String,
        description: String,
        index: Boolean = false,
        transform: (FeatureRef, FeatureExecutionContext, List<ConfigValue>) -> List<ConfigValue>?,
    ): FeatureDefinition {
        val fields = buildList {
            add(listVariable())
            if (index) add(FieldSchema.Number("index", "Index", true, min = 0.0))
            add(FieldSchema.Text("value", "Value", true))
            add(resultVariable())
        }
        return action(
            id,
            title,
            description,
            fields,
            behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
        ) { feature, context ->
            val values = context.list(feature.name()) ?: return@action notList()
            val output = transform(feature, context, values)
                ?: return@action ActionExecutionResult(false, message = "List index is out of bounds")
            context.store(feature.destination(), ConfigValue.ListValue(output))
        }
    }

    private fun predicateResult(
        id: String,
        title: String,
        description: String,
        evaluate: (FeatureRef, FeatureExecutionContext, List<ConfigValue>) -> Boolean,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        fields = listOf(listVariable(), FieldSchema.Text("value", "Value", true), resultVariable()),
        behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val values = context.list(feature.name()) ?: return@action notList()
        context.store(feature.destination(), ConfigValue.BooleanValue(evaluate(feature, context, values)))
    }

    private fun numberResult(
        id: String,
        title: String,
        description: String,
        evaluate: (FeatureRef, FeatureExecutionContext, List<ConfigValue>) -> Double,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        fields = listOf(listVariable(), FieldSchema.Text("value", "Value", true), resultVariable()),
        behaviors = mapOf("value" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val values = context.list(feature.name()) ?: return@action notList()
        context.store(feature.destination(), ConfigValue.NumberValue(evaluate(feature, context, values)))
    }

    private fun countListResult(
        id: String,
        title: String,
        description: String,
        transform: (List<ConfigValue>, Int) -> List<ConfigValue>,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        fields = listOf(listVariable(), FieldSchema.Number("count", "Count", true, min = 0.0), resultVariable()),
        behaviors = mapOf("count" to FieldBehavior(defaultValue = ConfigValue.NumberValue(1.0))),
    ) { feature, context ->
        val values = context.list(feature.name()) ?: return@action notList()
        val count = max(0, feature.number("count", 1.0).toInt())
        context.store(feature.destination(), ConfigValue.ListValue(transform(values, count)))
    }

    private fun numericAggregate(
        id: String,
        title: String,
        description: String,
        operation: (List<Double>) -> Double,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        fields = listOf(listVariable(), resultVariable()),
    ) { feature, context ->
        val values = context.list(feature.name()) ?: return@action notList()
        val numbers = values.mapNotNull(ConfigValue::numberValueOrNull)
        if (numbers.size != values.size) return@action ActionExecutionResult(false, message = "List contains non-numeric values")
        context.store(feature.destination(), ConfigValue.NumberValue(operation(numbers)))
    }

    private fun twoListResult(
        id: String,
        title: String,
        description: String,
        operation: (List<ConfigValue>, List<ConfigValue>) -> List<ConfigValue>,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        fields = listOf(listVariable(), FieldSchema.Variable("other", "Other list variable", true), resultVariable()),
    ) { feature, context ->
        val left = context.list(feature.name()) ?: return@action notList()
        val right = context.list(feature.config.string("other")) ?: return@action notList()
        context.store(feature.destination(), ConfigValue.ListValue(operation(left, right)))
    }
}

private object ObjectExpansionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        action(
            id = "data.object.remove",
            title = "Remove object field",
            description = "Remove a key from an object variable",
            fields = listOf(objectVariable(), FieldSchema.Text("key", "Key", true), resultVariable()),
        ) { feature, context ->
            val source = context.obj(feature.name()) ?: return@action notObject()
            context.store(feature.destination(), ConfigValue.ObjectValue(source - feature.config.string("key")))
        },
        action(
            id = "data.object.contains_key",
            title = "Object contains key",
            description = "Check whether an object contains a key",
            fields = listOf(objectVariable(), FieldSchema.Text("key", "Key", true), resultVariable()),
        ) { feature, context ->
            val source = context.obj(feature.name()) ?: return@action notObject()
            context.store(feature.destination(), ConfigValue.BooleanValue(feature.config.string("key") in source))
        },
        objectProjection("data.object.keys", "Object keys", "Return all object keys") { source -> source.keys.map(ConfigValue::StringValue) },
        objectProjection("data.object.values", "Object values", "Return all object values") { source -> source.values.toList() },
        action(
            id = "data.object.size",
            title = "Object size",
            description = "Return the number of fields in an object",
            fields = listOf(objectVariable(), resultVariable()),
        ) { feature, context ->
            val source = context.obj(feature.name()) ?: return@action notObject()
            context.store(feature.destination(), ConfigValue.NumberValue(source.size.toDouble()))
        },
        action(
            id = "data.object.merge",
            title = "Merge objects",
            description = "Merge two object variables; values from the second object win on key collisions",
            fields = listOf(objectVariable(), FieldSchema.Variable("other", "Other object variable", true), resultVariable()),
        ) { feature, context ->
            val left = context.obj(feature.name()) ?: return@action notObject()
            val right = context.obj(feature.config.string("other")) ?: return@action notObject()
            context.store(feature.destination(), ConfigValue.ObjectValue(left + right))
        },
        action(
            id = "data.object.entries",
            title = "Object entries",
            description = "Convert object fields into a list of key/value objects",
            fields = listOf(objectVariable(), resultVariable()),
        ) { feature, context ->
            val source = context.obj(feature.name()) ?: return@action notObject()
            val entries = source.map { (key, value) ->
                ConfigValue.ObjectValue(mapOf("key" to ConfigValue.StringValue(key), "value" to value))
            }
            context.store(feature.destination(), ConfigValue.ListValue(entries))
        },
        action(
            id = "data.object.rename_key",
            title = "Rename object key",
            description = "Rename a field in an object while preserving its value",
            fields = listOf(
                objectVariable(),
                FieldSchema.Text("key", "Current key", true),
                FieldSchema.Text("newKey", "New key", true),
                resultVariable(),
            ),
        ) { feature, context ->
            val source = context.obj(feature.name()) ?: return@action notObject()
            val key = feature.config.string("key")
            val newKey = feature.config.string("newKey")
            val value = source[key] ?: return@action ActionExecutionResult(false, message = "Object key does not exist")
            val output = LinkedHashMap(source)
            output.remove(key)
            output[newKey] = value
            context.store(feature.destination(), ConfigValue.ObjectValue(output))
        },
        action(
            id = "data.object.copy_key",
            title = "Copy object key",
            description = "Copy a field to another key in the same object",
            fields = listOf(
                objectVariable(),
                FieldSchema.Text("key", "Source key", true),
                FieldSchema.Text("newKey", "Destination key", true),
                resultVariable(),
            ),
        ) { feature, context ->
            val source = context.obj(feature.name()) ?: return@action notObject()
            val value = source[feature.config.string("key")]
                ?: return@action ActionExecutionResult(false, message = "Object key does not exist")
            context.store(
                feature.destination(),
                ConfigValue.ObjectValue(source + (feature.config.string("newKey") to value)),
            )
        },
    )

    private fun objectProjection(
        id: String,
        title: String,
        description: String,
        operation: (Map<String, ConfigValue>) -> List<ConfigValue>,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        fields = listOf(objectVariable(), resultVariable()),
    ) { feature, context ->
        val source = context.obj(feature.name()) ?: return@action notObject()
        context.store(feature.destination(), ConfigValue.ListValue(operation(source)))
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
    block,
)

private fun listVariable() = FieldSchema.Variable("name", "List variable", true)
private fun objectVariable() = FieldSchema.Variable("name", "Object variable", true)
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

private fun FeatureExecutionContext.store(name: String, value: ConfigValue): ActionExecutionResult {
    if (name.isBlank()) return ActionExecutionResult(false, message = "Destination variable is empty")
    variables.set(name, value)
    return ActionExecutionResult(true, value)
}

private fun notList() = ActionExecutionResult(false, message = "Selected variable is not a list")
private fun notObject() = ActionExecutionResult(false, message = "Selected variable is not an object")

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

private fun List<ConfigValue>.flatMapDeep(): List<ConfigValue> = flatMap { value ->
    when (value) {
        is ConfigValue.ListValue -> value.value.flatMapDeep()
        else -> listOf(value)
    }
}
