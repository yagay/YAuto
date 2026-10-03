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

/** High-frequency text operations present across the reference automation apps. */
internal object DataTextExpansionFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        booleanTextAction(
            "data.regex.matches",
            "Regex matches",
            "Check whether a regular expression finds a match in text",
            fields = listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                FieldSchema.Text("pattern", "Regular expression", true),
                resultField("Store match result in variable"),
            ),
        ) { feature, context ->
            Regex(feature.config.string("pattern").resolveVariables(context.variables))
                .containsMatchIn(feature.config.string("text").resolveVariables(context.variables))
        },
        actionFeature(
            dataDescriptor(
                "data.regex.replace",
                "Regex replace",
                "Replace regular-expression matches in text",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("pattern", "Regular expression", true),
                    FieldSchema.Text("replacement", "Replacement", multiline = true),
                    resultField(),
                ),
                keywords = setOf("regex", "replace", "text", "pattern"),
                behaviors = variableBehaviors("text", "pattern", "replacement"),
            )
        ) { feature, context ->
            val result = runCatching {
                Regex(feature.config.string("pattern").resolveVariables(context.variables)).replace(
                    feature.config.string("text").resolveVariables(context.variables),
                    feature.config.string("replacement").resolveVariables(context.variables),
                )
            }.getOrElse { error ->
                return@actionFeature ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
            }
            context.storeText(feature.destination(), result)
        },
        textTransformAction(
            "data.text.replace",
            "Replace text",
            "Replace literal text occurrences",
            extra = listOf(
                FieldSchema.Text("find", "Find text", true),
                FieldSchema.Text("replacement", "Replacement", multiline = true),
                FieldSchema.Toggle("ignoreCase", "Ignore case"),
            ),
            extraVariables = setOf("find", "replacement"),
        ) { text, feature, context ->
            text.replace(
                feature.config.string("find").resolveVariables(context.variables),
                feature.config.string("replacement").resolveVariables(context.variables),
                ignoreCase = (feature.config["ignoreCase"] as? ConfigValue.BooleanValue)?.value == true,
            )
        },
        booleanTextAction(
            "data.text.contains",
            "Text contains",
            "Check whether text contains a value",
            fields = listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                FieldSchema.Text("value", "Value", true),
                FieldSchema.Toggle("ignoreCase", "Ignore case"),
                resultField("Store result in variable"),
            ),
        ) { feature, context ->
            feature.config.string("text").resolveVariables(context.variables).contains(
                feature.config.string("value").resolveVariables(context.variables),
                ignoreCase = (feature.config["ignoreCase"] as? ConfigValue.BooleanValue)?.value == true,
            )
        },
        booleanTextAction(
            "data.text.starts_with",
            "Text starts with",
            "Check whether text starts with a value",
            fields = listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                FieldSchema.Text("value", "Value", true),
                FieldSchema.Toggle("ignoreCase", "Ignore case"),
                resultField("Store result in variable"),
            ),
        ) { feature, context ->
            feature.config.string("text").resolveVariables(context.variables).startsWith(
                feature.config.string("value").resolveVariables(context.variables),
                ignoreCase = (feature.config["ignoreCase"] as? ConfigValue.BooleanValue)?.value == true,
            )
        },
        booleanTextAction(
            "data.text.ends_with",
            "Text ends with",
            "Check whether text ends with a value",
            fields = listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                FieldSchema.Text("value", "Value", true),
                FieldSchema.Toggle("ignoreCase", "Ignore case"),
                resultField("Store result in variable"),
            ),
        ) { feature, context ->
            feature.config.string("text").resolveVariables(context.variables).endsWith(
                feature.config.string("value").resolveVariables(context.variables),
                ignoreCase = (feature.config["ignoreCase"] as? ConfigValue.BooleanValue)?.value == true,
            )
        },
        numberTextAction("data.text.index_of", "Find text index", "Return the first zero-based index of a value") { feature, context ->
            feature.config.string("text").resolveVariables(context.variables).indexOf(
                feature.config.string("value").resolveVariables(context.variables),
                ignoreCase = (feature.config["ignoreCase"] as? ConfigValue.BooleanValue)?.value == true,
            ).toDouble()
        },
        numberTextAction("data.text.last_index_of", "Find last text index", "Return the last zero-based index of a value") { feature, context ->
            feature.config.string("text").resolveVariables(context.variables).lastIndexOf(
                feature.config.string("value").resolveVariables(context.variables),
                ignoreCase = (feature.config["ignoreCase"] as? ConfigValue.BooleanValue)?.value == true,
            ).toDouble()
        },
        simpleTextTransform("data.text.reverse", "Reverse text", "Reverse text by Unicode code points") { reverseCodePoints(it) },
        textTransformAction(
            "data.text.repeat",
            "Repeat text",
            "Repeat text a bounded number of times",
            extra = listOf(FieldSchema.Number("count", "Repeat count", true, min = 0.0, max = 10_000.0)),
        ) { text, feature, _ ->
            val count = feature.number("count").toInt().coerceIn(0, 10_000)
            text.repeat(count)
        },
        textTransformAction(
            "data.text.pad_start",
            "Pad text start",
            "Pad text on the left to a target length",
            extra = listOf(
                FieldSchema.Number("length", "Target length", true, min = 0.0, max = 100_000.0),
                FieldSchema.Text("padChar", "Padding character"),
            ),
            extraVariables = setOf("padChar"),
        ) { text, feature, context ->
            val length = feature.number("length").toInt().coerceIn(0, 100_000)
            val char = feature.config.string("padChar").resolveVariables(context.variables).firstOrNull() ?: ' '
            text.padStart(length, char)
        },
        textTransformAction(
            "data.text.pad_end",
            "Pad text end",
            "Pad text on the right to a target length",
            extra = listOf(
                FieldSchema.Number("length", "Target length", true, min = 0.0, max = 100_000.0),
                FieldSchema.Text("padChar", "Padding character"),
            ),
            extraVariables = setOf("padChar"),
        ) { text, feature, context ->
            val length = feature.number("length").toInt().coerceIn(0, 100_000)
            val char = feature.config.string("padChar").resolveVariables(context.variables).firstOrNull() ?: ' '
            text.padEnd(length, char)
        },
        simpleTextTransform(
            "data.text.normalize_whitespace",
            "Normalize whitespace",
            "Trim text and collapse consecutive whitespace to single spaces",
        ) { it.trim().replace(Regex("\\s+"), " ") },
        textTransformAction(
            "data.text.remove_prefix",
            "Remove text prefix",
            "Remove a literal prefix when present",
            extra = listOf(FieldSchema.Text("prefix", "Prefix", true)),
            extraVariables = setOf("prefix"),
        ) { text, feature, context ->
            text.removePrefix(feature.config.string("prefix").resolveVariables(context.variables))
        },
        textTransformAction(
            "data.text.remove_suffix",
            "Remove text suffix",
            "Remove a literal suffix when present",
            extra = listOf(FieldSchema.Text("suffix", "Suffix", true)),
            extraVariables = setOf("suffix"),
        ) { text, feature, context ->
            text.removeSuffix(feature.config.string("suffix").resolveVariables(context.variables))
        },
        actionFeature(
            dataDescriptor(
                "data.text.char_at",
                "Character at index",
                "Read one Unicode code point by zero-based index",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Number("index", "Character index", true, min = 0.0),
                    resultField(),
                ),
                keywords = setOf("text", "character", "index", "unicode"),
                behaviors = variableBehaviors("text"),
            )
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val index = feature.number("index").toInt()
            val points = text.codePoints().toArray()
            if (index !in points.indices) return@actionFeature ActionExecutionResult(false, message = userText("feature.text_index_out_of_range"))
            context.storeText(feature.destination(), String(Character.toChars(points[index])))
        },
        listTextAction("data.text.lines", "Text lines", "Split text into lines") { text ->
            text.lineSequence().toList()
        },
        listTextAction("data.text.words", "Text words", "Split trimmed text on consecutive whitespace") { text ->
            val trimmed = text.trim()
            if (trimmed.isEmpty()) emptyList() else trimmed.split(Regex("\\s+"))
        },
    )

    private fun simpleTextTransform(
        id: String,
        title: String,
        description: String,
        transform: (String) -> String,
    ): FeatureDefinition = textTransformAction(id, title, description, transform = { text, _, _ -> transform(text) })

    private fun textTransformAction(
        id: String,
        title: String,
        description: String,
        extra: List<FieldSchema> = emptyList(),
        extraVariables: Set<String> = emptySet(),
        transform: (String, com.yagay.yauto.core.model.FeatureRef, com.yagay.yauto.core.registry.FeatureExecutionContext) -> String,
    ): FeatureDefinition = actionFeature(
        dataDescriptor(
            id,
            title,
            description,
            fields = listOf(FieldSchema.Text("text", "Text", true, multiline = true)) + extra + resultField(),
            keywords = setOf("text", "string", "transform"),
            behaviors = variableBehaviors(*(setOf("text") + extraVariables).toTypedArray()),
        )
    ) { feature, context ->
        val text = feature.config.string("text").resolveVariables(context.variables)
        val output = runCatching { transform(text, feature, context) }.getOrElse { error ->
            return@actionFeature ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
        }
        context.storeText(feature.destination(), output)
    }

    private fun booleanTextAction(
        id: String,
        title: String,
        description: String,
        fields: List<FieldSchema>,
        evaluate: (com.yagay.yauto.core.model.FeatureRef, com.yagay.yauto.core.registry.FeatureExecutionContext) -> Boolean,
    ): FeatureDefinition = actionFeature(
        dataDescriptor(
            id,
            title,
            description,
            fields = fields,
            keywords = setOf("text", "match", "compare", "regex"),
            behaviors = variableBehaviors(*fields.filterIsInstance<FieldSchema.Text>().map { it.key }.toTypedArray()),
        )
    ) { feature, context ->
        val result = runCatching { evaluate(feature, context) }.getOrElse { error ->
            return@actionFeature ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
        }
        context.storeValue(feature.destination(), ConfigValue.BooleanValue(result))
    }

    private fun numberTextAction(
        id: String,
        title: String,
        description: String,
        evaluate: (com.yagay.yauto.core.model.FeatureRef, com.yagay.yauto.core.registry.FeatureExecutionContext) -> Double,
    ): FeatureDefinition = actionFeature(
        dataDescriptor(
            id,
            title,
            description,
            fields = listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                FieldSchema.Text("value", "Value", true),
                FieldSchema.Toggle("ignoreCase", "Ignore case"),
                resultField("Store index in variable"),
            ),
            keywords = setOf("text", "index", "find", "search"),
            behaviors = variableBehaviors("text", "value"),
        )
    ) { feature, context ->
        context.storeNumber(feature.destination(), evaluate(feature, context))
    }

    private fun listTextAction(
        id: String,
        title: String,
        description: String,
        transform: (String) -> List<String>,
    ): FeatureDefinition = actionFeature(
        dataDescriptor(
            id,
            title,
            description,
            fields = listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                resultField("Store list in variable"),
            ),
            keywords = setOf("text", "split", "list"),
            behaviors = variableBehaviors("text"),
        )
    ) { feature, context ->
        val values = transform(feature.config.string("text").resolveVariables(context.variables))
        context.storeValue(feature.destination(), ConfigValue.ListValue(values.map(ConfigValue::StringValue)))
    }

    private fun resultField(label: String = "Store result in variable") =
        FieldSchema.Variable("resultVariable", label, true)

    private fun variableBehaviors(vararg keys: String): Map<String, FieldBehavior> =
        keys.associateWith { variableTextBehavior() }

    private fun reverseCodePoints(text: String): String =
        text.codePoints().toArray().reversedArray().let { points ->
            buildString { points.forEach { appendCodePoint(it) } }
        }
}
