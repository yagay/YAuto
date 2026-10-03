package com.yagay.yauto.feature.standard.data

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import com.yagay.yauto.core.registry.resolveVariables

internal object DataTextUtilityFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            dataDescriptor(
                "data.text.length",
                "Get text length",
                "Count Unicode characters in text and store the result",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Variable("resultVariable", "Store length in variable", true),
                ),
                keywords = setOf("text", "length", "count", "characters"),
                behaviors = mapOf("text" to variableTextBehavior()),
            )
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            context.storeNumber(feature.destination(), text.codePointCount(0, text.length).toDouble())
        },
        actionFeature(
            dataDescriptor(
                "data.text.substring",
                "Extract substring",
                "Extract text using a zero-based start index and optional character count",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Number("start", "Start index", true, min = 0.0),
                    FieldSchema.Number("length", "Character count", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store text in variable", true),
                ),
                keywords = setOf("text", "substring", "slice", "extract"),
                behaviors = mapOf(
                    "text" to variableTextBehavior(),
                    "start" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                ),
            )
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val start = feature.number("start").toInt().coerceAtLeast(0)
            val count = feature.config["length"]
                ?.let { (it as? ConfigValue.NumberValue)?.value }
                ?.toInt()
                ?.coerceAtLeast(0)
            if (start > text.length) {
                return@actionFeature ActionExecutionResult(false, message = userText("feature.text_index_out_of_range"))
            }
            val end = count?.let { (start + it).coerceAtMost(text.length) } ?: text.length
            context.storeText(feature.destination(), text.substring(start, end))
        },
        actionFeature(
            dataDescriptor(
                "data.text.case",
                "Change text case",
                "Convert text to upper case, lower case, or trim surrounding whitespace",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Choice("mode", "Text transformation", true, listOf("upper", "lower", "trim")),
                    FieldSchema.Variable("resultVariable", "Store text in variable", true),
                ),
                keywords = setOf("text", "upper", "lower", "trim", "case"),
                behaviors = mapOf(
                    "text" to variableTextBehavior(),
                    "mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("trim")),
                ),
            )
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val output = when (feature.config.string("mode", "trim")) {
                "upper" -> text.uppercase()
                "lower" -> text.lowercase()
                "trim" -> text.trim()
                else -> return@actionFeature ActionExecutionResult(false, message = userText("feature.text_transformation_invalid"))
            }
            context.storeText(feature.destination(), output)
        },
    )
}
