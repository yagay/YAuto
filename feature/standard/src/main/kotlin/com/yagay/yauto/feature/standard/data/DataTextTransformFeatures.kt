package com.yagay.yauto.feature.standard.data

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema
import com.yagay.yauto.core.registry.actionFeature
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

internal object DataTextTransformFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        actionFeature(
            dataDescriptor(
                "data.text.split",
                "Split text",
                "Split text into a list variable",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("delimiter", "Delimiter / regex", true),
                    FieldSchema.Toggle("regex", "Delimiter is regex"),
                    FieldSchema.Variable("resultVariable", "Store list in variable", true),
                ),
                keywords = setOf("split", "text", "list", "regex"),
                behaviors = mapOf(
                    "text" to variableTextBehavior(),
                    "delimiter" to variableTextBehavior(),
                    "regex" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(false)),
                ),
            )
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val delimiter = feature.config.string("delimiter").resolveVariables(context.variables)
            val values = runCatching {
                if (feature.config.boolean("regex")) text.split(Regex(delimiter)) else text.split(delimiter)
            }.getOrElse { error ->
                return@actionFeature ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName),
                )
            }
            context.storeValue(
                feature.destination(),
                ConfigValue.ListValue(values.map(ConfigValue::StringValue)),
            )
        },
        actionFeature(
            dataDescriptor(
                "data.text.join",
                "Join list",
                "Join a list variable into text",
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Text("delimiter", "Delimiter"),
                    FieldSchema.Variable("resultVariable", "Store text in variable", true),
                ),
                keywords = setOf("join", "list", "text"),
                behaviors = mapOf("delimiter" to variableTextBehavior()),
            )
        ) { feature, context ->
            val values = (context.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value
                ?: return@actionFeature ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            val delimiter = feature.config.string("delimiter").resolveVariables(context.variables)
            context.storeText(
                feature.destination(),
                values.joinToString(delimiter) { it.asDataText() },
            )
        },
        actionFeature(
            dataDescriptor(
                "data.regex.extract",
                "Extract regex matches",
                "Extract regex matches or one capture group into a list",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("pattern", "Regular expression", true),
                    FieldSchema.Number("group", "Capture group (0 = full match)", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store matches in variable", true),
                ),
                keywords = setOf("regex", "extract", "match", "parse"),
                behaviors = mapOf(
                    "text" to variableTextBehavior(),
                    "pattern" to variableTextBehavior(),
                    "group" to FieldBehavior(defaultValue = ConfigValue.NumberValue(0.0)),
                ),
            )
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val pattern = feature.config.string("pattern").resolveVariables(context.variables)
            val group = feature.number("group").toInt()
            val matches = runCatching {
                Regex(pattern).findAll(text)
                    .mapNotNull { match -> match.groups[group]?.value }
                    .toList()
            }.getOrElse { error ->
                return@actionFeature ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName),
                )
            }
            context.storeValue(
                feature.destination(),
                ConfigValue.ListValue(matches.map(ConfigValue::StringValue)),
            )
        },
        codecFeature(
            id = "data.base64.encode",
            title = "Base64 encode",
            description = "Encode UTF-8 text as Base64",
        ) { Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) },
        codecFeature(
            id = "data.base64.decode",
            title = "Base64 decode",
            description = "Decode Base64 to UTF-8 text",
        ) { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) },
        codecFeature(
            id = "data.url.encode",
            title = "URL encode",
            description = "Percent-encode text for URL/form use",
        ) { URLEncoder.encode(it, StandardCharsets.UTF_8) },
        codecFeature(
            id = "data.url.decode",
            title = "URL decode",
            description = "Decode percent-encoded text",
        ) { URLDecoder.decode(it, StandardCharsets.UTF_8) },
        actionFeature(
            dataDescriptor(
                "data.hash",
                "Hash text",
                "Hash UTF-8 text with SHA-256, SHA-1 or MD5",
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Choice("algorithm", "Algorithm", true, listOf("SHA-256", "SHA-1", "MD5")),
                    FieldSchema.Variable("resultVariable", "Store hash in variable", true),
                ),
                keywords = setOf("hash", "sha256", "md5", "digest"),
                behaviors = mapOf(
                    "text" to variableTextBehavior(),
                    "algorithm" to FieldBehavior(defaultValue = ConfigValue.StringValue("SHA-256")),
                ),
            )
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val algorithm = feature.config.string("algorithm", "SHA-256")
            val value = runCatching {
                MessageDigest.getInstance(algorithm)
                    .digest(text.toByteArray(StandardCharsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            }.getOrElse { error ->
                return@actionFeature ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName),
                )
            }
            context.storeText(feature.destination(), value)
        },
    )

    private fun codecFeature(
        id: String,
        title: String,
        description: String,
        transform: (String) -> String,
    ): FeatureDefinition = actionFeature(
        dataDescriptor(
            id,
            title,
            description,
            fields = listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                FieldSchema.Variable("resultVariable", "Store result in variable", true),
            ),
            behaviors = mapOf("text" to variableTextBehavior()),
        )
    ) { feature, context ->
        val input = feature.config.string("text").resolveVariables(context.variables)
        val output = runCatching { transform(input) }.getOrElse { error ->
            return@actionFeature ActionExecutionResult(
                false,
                message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName),
            )
        }
        context.storeText(feature.destination(), output)
    }
}
