package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.random.Random
import com.yagay.yauto.core.model.userText

class DataFeaturePack : FeaturePack {
    override val id = "standard.data"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.text.split"), FeatureKind.ACTION, "Split text", "Split text into a list variable",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("delimiter", "Delimiter / regex", true),
                    FieldSchema.Toggle("regex", "Delimiter is regex"),
                    FieldSchema.Variable("resultVariable", "Store list in variable", true),
                ), keywords = setOf("split", "text", "list", "regex"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val delimiter = feature.config.string("delimiter").resolveVariables(ctx.variables)
            val values = runCatching {
                if (feature.config.boolean("regex")) text.split(Regex(delimiter)) else text.split(delimiter)
            }.getOrElse { return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            val output = ConfigValue.ListValue(values.map(ConfigValue::StringValue))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.text.join"), FeatureKind.ACTION, "Join list", "Join a list variable into text",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Variable("name", "List variable", true),
                    FieldSchema.Text("delimiter", "Delimiter"),
                    FieldSchema.Variable("resultVariable", "Store text in variable", true),
                ), keywords = setOf("join", "list", "text"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val list = (ctx.variables.get(feature.config.string("name")) as? ConfigValue.ListValue)?.value
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.variable_not_list"))
            val output = ConfigValue.StringValue(list.joinToString(feature.config.string("delimiter")) { it.textValue() })
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.regex.extract"), FeatureKind.ACTION, "Extract regex matches", "Extract regex matches or one capture group into a list",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("pattern", "Regular expression", true),
                    FieldSchema.Number("group", "Capture group (0 = full match)", min = 0.0),
                    FieldSchema.Variable("resultVariable", "Store matches in variable", true),
                ), keywords = setOf("regex", "extract", "match", "parse"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val pattern = feature.config.string("pattern").resolveVariables(ctx.variables)
            val group = (feature.config["group"] as? ConfigValue.NumberValue)?.value?.toInt() ?: 0
            val matches = runCatching {
                Regex(pattern).findAll(text)
                    .mapNotNull { match: MatchResult -> match.groups[group]?.value }
                    .toList()
            }.getOrElse { return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            val output = ConfigValue.ListValue(matches.map(ConfigValue::StringValue))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        codec(registry, "data.base64.encode", "Base64 encode", "Encode UTF-8 text as Base64") { Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) }
        codec(registry, "data.base64.decode", "Base64 decode", "Decode Base64 to UTF-8 text") { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) }
        codec(registry, "data.url.encode", "URL encode", "Percent-encode text for URL/form use") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        codec(registry, "data.url.decode", "URL decode", "Decode percent-encoded text") { URLDecoder.decode(it, StandardCharsets.UTF_8) }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.hash"), FeatureKind.ACTION, "Hash text", "Hash UTF-8 text with SHA-256, SHA-1 or MD5",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Choice("algorithm", "Algorithm", true, listOf("SHA-256", "SHA-1", "MD5")),
                    FieldSchema.Variable("resultVariable", "Store hash in variable", true),
                ), keywords = setOf("hash", "sha256", "md5", "digest"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val algorithm = feature.config.string("algorithm", "SHA-256")
            val value = runCatching {
                MessageDigest.getInstance(algorithm).digest(text.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
            }.getOrElse { return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            val output = ConfigValue.StringValue(value)
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.random.number"), FeatureKind.ACTION, "Random number", "Generate an inclusive random integer",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Number("min", "Minimum", true),
                    FieldSchema.Number("max", "Maximum", true),
                    FieldSchema.Variable("resultVariable", "Store number in variable", true),
                ), keywords = setOf("random", "number"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val min = (feature.config["min"] as? ConfigValue.NumberValue)?.value?.toLong() ?: 0
            val max = (feature.config["max"] as? ConfigValue.NumberValue)?.value?.toLong() ?: 0
            if (max < min) return@registerAction ActionExecutionResult(false, message = userText("feature.maximum_below_minimum"))
            val number = if (max == Long.MAX_VALUE) Random.nextLong(min, max) else Random.nextLong(min, max + 1)
            val output = ConfigValue.NumberValue(number.toDouble())
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.uuid"), FeatureKind.ACTION, "Generate UUID", "Generate a random UUID string",
                FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store UUID in variable", true)),
                keywords = setOf("uuid", "random", "id"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = ConfigValue.StringValue(UUID.randomUUID().toString())
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun codec(registry: FeatureRegistry, idValue: String, title: String, description: String, transform: (String) -> String) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(idValue), FeatureKind.ACTION, title, description, FeatureCategory.VARIABLE,
                fields = listOf(FieldSchema.Text("text", "Text", true, multiline = true), FieldSchema.Variable("resultVariable", "Store result in variable", true)),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val input = feature.config.string("text").resolveVariables(ctx.variables)
            val output = runCatching { ConfigValue.StringValue(transform(input)) }
                .getOrElse { return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }
}

private fun ConfigValue.textValue(): String = when (this) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.textValue() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { "${it.key}=${it.value.textValue()}" }
}
