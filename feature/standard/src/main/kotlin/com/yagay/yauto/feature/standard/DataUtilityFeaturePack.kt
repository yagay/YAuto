package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.random.Random

/** Common data transformations kept as one removable standard feature pack. */
class DataUtilityFeaturePack : FeaturePack {
    override val id: String = "standard.data.utility"

    override fun install(registry: FeatureRegistry) {
        registerCalculate(registry)
        registerRound(registry)
        registerClamp(registry)
        registerRandomDecimal(registry)
        registerTextLength(registry)
        registerSubstring(registry)
        registerTextCase(registry)
        registerListSort(registry)
        registerListReverse(registry)
        registerListDistinct(registry)
    }

    private fun registerCalculate(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.math.calculate"), FeatureKind.ACTION,
                "Calculate numbers", "Apply a common arithmetic operation to two numbers and store the result",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Number("a", "First number", true),
                    FieldSchema.Choice("operation", "Operation", true, listOf("add", "subtract", "multiply", "divide", "modulo", "power", "min", "max")),
                    FieldSchema.Number("b", "Second number", true),
                    FieldSchema.Variable("resultVariable", "Store result in variable", true),
                ), keywords = setOf("math", "calculate", "add", "divide", "power"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val a = feature.config["a"].numberOrNull() ?: 0.0
            val b = feature.config["b"].numberOrNull() ?: 0.0
            val value = when (feature.config.string("operation", "add")) {
                "add" -> a + b
                "subtract" -> a - b
                "multiply" -> a * b
                "divide" -> if (b == 0.0) null else a / b
                "modulo" -> if (b == 0.0) null else a % b
                "power" -> a.pow(b)
                "min" -> minOf(a, b)
                "max" -> maxOf(a, b)
                else -> null
            }?.takeIf(Double::isFinite)
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.math_invalid_operation"))
            storeNumber(feature.config.string("resultVariable"), value, ctx)
        }
    }

    private fun registerRound(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.math.round"), FeatureKind.ACTION,
                "Round number", "Round, floor or ceil a number with optional decimal precision",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Number("value", "Number", true),
                    FieldSchema.Choice("mode", "Rounding mode", true, listOf("round", "floor", "ceil")),
                    FieldSchema.Number("decimals", "Decimal places", min = 0.0, max = 12.0),
                    FieldSchema.Variable("resultVariable", "Store result in variable", true),
                ), keywords = setOf("math", "round", "floor", "ceil", "decimal"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val value = feature.config["value"].numberOrNull() ?: 0.0
            val decimals = (feature.config["decimals"].numberOrNull()?.toInt() ?: 0).coerceIn(0, 12)
            val factor = 10.0.pow(decimals)
            val scaled = value * factor
            val rounded = when (feature.config.string("mode", "round")) {
                "round" -> round(scaled)
                "floor" -> floor(scaled)
                "ceil" -> ceil(scaled)
                else -> return@registerAction ActionExecutionResult(false, message = userText("feature.math_invalid_operation"))
            } / factor
            storeNumber(feature.config.string("resultVariable"), rounded, ctx)
        }
    }

    private fun registerClamp(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.math.clamp"), FeatureKind.ACTION,
                "Clamp number", "Keep a number inside an inclusive minimum and maximum range",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Number("value", "Number", true),
                    FieldSchema.Number("min", "Minimum", true),
                    FieldSchema.Number("max", "Maximum", true),
                    FieldSchema.Variable("resultVariable", "Store result in variable", true),
                ), keywords = setOf("math", "clamp", "range", "limit"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val value = feature.config["value"].numberOrNull() ?: 0.0
            val min = feature.config["min"].numberOrNull() ?: 0.0
            val max = feature.config["max"].numberOrNull() ?: 0.0
            if (max < min) return@registerAction ActionExecutionResult(false, message = userText("feature.maximum_below_minimum"))
            storeNumber(feature.config.string("resultVariable"), value.coerceIn(min, max), ctx)
        }
    }

    private fun registerRandomDecimal(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.random.decimal"), FeatureKind.ACTION,
                "Random decimal", "Generate a random decimal number between an inclusive minimum and maximum",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Number("min", "Minimum", true),
                    FieldSchema.Number("max", "Maximum", true),
                    FieldSchema.Number("decimals", "Decimal places", min = 0.0, max = 12.0),
                    FieldSchema.Variable("resultVariable", "Store number in variable", true),
                ), keywords = setOf("random", "decimal", "number"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val min = feature.config["min"].numberOrNull() ?: 0.0
            val max = feature.config["max"].numberOrNull() ?: 1.0
            if (max < min) return@registerAction ActionExecutionResult(false, message = userText("feature.maximum_below_minimum"))
            val decimals = (feature.config["decimals"].numberOrNull()?.toInt() ?: 2).coerceIn(0, 12)
            val raw = if (max == min) min else Random.nextDouble(min, max)
            val value = BigDecimal.valueOf(raw).setScale(decimals, RoundingMode.HALF_UP).toDouble().coerceIn(min, max)
            storeNumber(feature.config.string("resultVariable"), value, ctx)
        }
    }

    private fun registerTextLength(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.text.length"), FeatureKind.ACTION,
                "Get text length", "Count Unicode characters in text and store the result",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Text("text", "Text", true, multiline = true), FieldSchema.Variable("resultVariable", "Store length in variable", true)),
                keywords = setOf("text", "length", "count", "characters"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            storeNumber(feature.config.string("resultVariable"), text.codePointCount(0, text.length).toDouble(), ctx)
        }
    }

    private fun registerSubstring(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.text.substring"), FeatureKind.ACTION,
                "Extract substring", "Extract text using a zero-based start index and optional character count",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Number("start", "Start index", true, min = 0.0),
                    FieldSchema.Number("length", "Character count", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store text in variable", true),
                ), keywords = setOf("text", "substring", "slice", "extract"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val start = (feature.config["start"].numberOrNull()?.toInt() ?: 0).coerceAtLeast(0)
            val count = feature.config["length"].numberOrNull()?.toInt()?.coerceAtLeast(0)
            if (start > text.length) return@registerAction ActionExecutionResult(false, message = userText("feature.text_index_out_of_range"))
            val end = count?.let { (start + it).coerceAtMost(text.length) } ?: text.length
            storeText(feature.config.string("resultVariable"), text.substring(start, end), ctx)
        }
    }

    private fun registerTextCase(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.text.case"), FeatureKind.ACTION,
                "Change text case", "Convert text to upper case, lower case, or trim surrounding whitespace",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Choice("mode", "Text transformation", true, listOf("upper", "lower", "trim")),
                    FieldSchema.Variable("resultVariable", "Store text in variable", true),
                ), keywords = setOf("text", "upper", "lower", "trim", "case"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val output = when (feature.config.string("mode", "trim")) {
                "upper" -> text.uppercase()
                "lower" -> text.lowercase()
                "trim" -> text.trim()
                else -> return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", "invalid text transformation"))
            }
            storeText(feature.config.string("resultVariable"), output, ctx)
        }
    }

    private fun registerListSort(registry: FeatureRegistry) {
        listAction(registry, "data.list.sort", "Sort list", "Sort a list as text or numeric values") { feature, values ->
            val descending = feature.config.string("order", "ascending") == "descending"
            val numeric = feature.config.string("type", "text") == "number"
            val sorted = if (numeric) values.sortedBy { it.asText().toDoubleOrNull() ?: Double.POSITIVE_INFINITY } else values.sortedBy { it.asText().lowercase() }
            if (descending) sorted.reversed() else sorted
        }
    }

    private fun registerListReverse(registry: FeatureRegistry) {
        listAction(registry, "data.list.reverse", "Reverse list", "Reverse the order of items in a list") { _, values -> values.reversed() }
    }

    private fun registerListDistinct(registry: FeatureRegistry) {
        listAction(registry, "data.list.distinct", "Remove duplicate list items", "Keep only the first occurrence of each list value") { _, values ->
            val seen = linkedSetOf<String>()
            values.filter { seen.add(it.asText()) }
        }
    }

    private fun listAction(
        registry: FeatureRegistry,
        featureId: String,
        title: String,
        description: String,
        transform: (com.yagay.yauto.core.model.FeatureRef, List<ConfigValue>) -> List<ConfigValue>,
    ) {
        val extras = if (featureId == "data.list.sort") listOf(
            FieldSchema.Choice("type", "Sort as", true, listOf("text", "number")),
            FieldSchema.Choice("order", "Sort order", true, listOf("ascending", "descending")),
        ) else emptyList()
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId), FeatureKind.ACTION, title, description, FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("name", "List variable", true)) + extras + FieldSchema.Variable("resultVariable", "Store list in variable", true),
                keywords = setOf("list", "sort", "reverse", "distinct", "data"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val values = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            val output = ConfigValue.ListValue(transform(feature, values))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun storeNumber(name: String, value: Double, ctx: FeatureExecutionContext): ActionExecutionResult {
        val output = ConfigValue.NumberValue(value)
        ctx.variables.set(name, output)
        return ActionExecutionResult(true, output)
    }

    private fun storeText(name: String, value: String, ctx: FeatureExecutionContext): ActionExecutionResult {
        val output = ConfigValue.StringValue(value)
        ctx.variables.set(name, output)
        return ActionExecutionResult(true, output)
    }
}

private fun ConfigValue.asText(): String = when (this) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.toString()
}
