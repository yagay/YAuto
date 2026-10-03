package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
import com.yagay.yauto.core.model.boolean
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/** Stateless data encoders and typed ConfigValue conversion helpers. */
class DataCodecFeaturePack : FeaturePack {
    override val id: String = "standard.data.codec"

    private val delegate = DefinitionFeaturePack(id, DataCodecFeatures.definitions)

    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}

private object DataCodecFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        textTransform(
            "data.text.base64_encode",
            "Base64 encode",
            "Encode UTF-8 text as Base64",
        ) { Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) },
        textTransform(
            "data.text.base64_decode",
            "Base64 decode",
            "Decode Base64 into UTF-8 text",
        ) { String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8) },
        textTransform(
            "data.text.base64_url_encode",
            "Base64 URL encode",
            "Encode UTF-8 text using URL-safe Base64 without padding",
        ) { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8)) },
        textTransform(
            "data.text.base64_url_decode",
            "Base64 URL decode",
            "Decode URL-safe Base64 into UTF-8 text",
        ) { String(Base64.getUrlDecoder().decode(padBase64(it)), StandardCharsets.UTF_8) },
        textTransform(
            "data.text.url_encode",
            "URL encode",
            "Percent-encode text for use in a URL or form value",
        ) { URLEncoder.encode(it, StandardCharsets.UTF_8.name()) },
        textTransform(
            "data.text.url_decode",
            "URL decode",
            "Decode percent-encoded URL or form text",
        ) { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) },
        textTransform(
            "data.text.hex_encode",
            "Hex encode",
            "Encode UTF-8 text as hexadecimal bytes",
        ) { input -> input.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) } },
        textTransform(
            "data.text.hex_decode",
            "Hex decode",
            "Decode hexadecimal bytes into UTF-8 text",
        ) { input ->
            require(input.length % 2 == 0) { "Hex text must contain an even number of characters" }
            val bytes = ByteArray(input.length / 2) { index ->
                input.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            String(bytes, StandardCharsets.UTF_8)
        },
        textTransform(
            "data.text.sha256",
            "SHA-256 hash",
            "Hash UTF-8 text with SHA-256",
        ) { digestHex("SHA-256", it) },
        textTransform(
            "data.text.sha512",
            "SHA-512 hash",
            "Hash UTF-8 text with SHA-512",
        ) { digestHex("SHA-512", it) },
        valueTransform(
            "data.value.type",
            "Value type",
            "Return the YAuto type name of a value variable",
        ) { value -> ConfigValue.StringValue(value.typeName()) },
        valueTransform(
            "data.value.to_text",
            "Convert value to text",
            "Convert a typed value into readable text",
        ) { value -> ConfigValue.StringValue(value.asText()) },
        valueTransform(
            "data.value.to_number",
            "Convert value to number",
            "Convert a number, boolean, or numeric text value into a number",
        ) { value -> ConfigValue.NumberValue(value.asNumberStrict()) },
        valueTransform(
            "data.value.to_boolean",
            "Convert value to boolean",
            "Convert a value using automation-friendly truthiness rules",
        ) { value -> ConfigValue.BooleanValue(value.asBoolean()) },
        valueTransform(
            "data.value.is_null",
            "Is value null",
            "Return true when a variable is missing or contains null",
            allowMissing = true,
        ) { value -> ConfigValue.BooleanValue(value == ConfigValue.NullValue) },
        action(
            id = "data.list.zip",
            title = "Zip lists",
            description = "Pair items from two lists into two-item sublists",
            fields = listOf(
                FieldSchema.Variable("left", "Left list", true),
                FieldSchema.Variable("right", "Right list", true),
                resultField(),
            ),
        ) { feature, context ->
            val left = context.list(feature.config.string("left")) ?: return@action fail("Left variable is not a list")
            val right = context.list(feature.config.string("right")) ?: return@action fail("Right variable is not a list")
            val size = minOf(left.size, right.size)
            val output = (0 until size).map { index -> ConfigValue.ListValue(listOf(left[index], right[index])) }
            context.storeCodec(feature.resultVariable(), ConfigValue.ListValue(output))
        },
        action(
            id = "data.list.enumerate",
            title = "Enumerate list",
            description = "Convert list items into objects containing index and value",
            fields = listOf(FieldSchema.Variable("source", "List variable", true), resultField()),
        ) { feature, context ->
            val values = context.list(feature.config.string("source")) ?: return@action fail("Selected variable is not a list")
            val output = values.mapIndexed { index, value ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "index" to ConfigValue.NumberValue(index.toDouble()),
                        "value" to value,
                    )
                )
            }
            context.storeCodec(feature.resultVariable(), ConfigValue.ListValue(output))
        },
        action(
            id = "data.list.frequency",
            title = "Count list frequencies",
            description = "Count occurrences of each text representation in a list",
            fields = listOf(FieldSchema.Variable("source", "List variable", true), resultField()),
        ) { feature, context ->
            val values = context.list(feature.config.string("source")) ?: return@action fail("Selected variable is not a list")
            val counts = linkedMapOf<String, Double>()
            values.forEach { value ->
                val key = value.asText()
                counts[key] = (counts[key] ?: 0.0) + 1.0
            }
            context.storeCodec(
                feature.resultVariable(),
                ConfigValue.ObjectValue(counts.mapValues { ConfigValue.NumberValue(it.value) }),
            )
        },
        action(
            id = "data.object.from_lists",
            title = "Build object from lists",
            description = "Create an object from parallel key and value lists",
            fields = listOf(
                FieldSchema.Variable("keys", "Keys list", true),
                FieldSchema.Variable("values", "Values list", true),
                resultField(),
            ),
        ) { feature, context ->
            val keys = context.list(feature.config.string("keys")) ?: return@action fail("Keys variable is not a list")
            val values = context.list(feature.config.string("values")) ?: return@action fail("Values variable is not a list")
            val size = minOf(keys.size, values.size)
            val output = linkedMapOf<String, ConfigValue>()
            repeat(size) { index -> output[keys[index].asText()] = values[index] }
            context.storeCodec(feature.resultVariable(), ConfigValue.ObjectValue(output))
        },
        action(
            id = "data.value.default_if_null",
            title = "Default if null",
            description = "Use a fallback value when a variable is missing or null",
            fields = listOf(
                FieldSchema.Variable("source", "Source variable", true),
                FieldSchema.Text("fallback", "Fallback value"),
                resultField(),
            ),
            behaviors = mapOf("fallback" to FieldBehavior(supportsVariables = true)),
        ) { feature, context ->
            val current = context.variables.get(feature.config.string("source"))
            val fallback = feature.config["fallback"] ?: ConfigValue.NullValue
            val output = if (current == null || current == ConfigValue.NullValue) fallback else current
            context.storeCodec(feature.resultVariable(), output)
        },
    )

    private fun textTransform(
        id: String,
        title: String,
        description: String,
        transform: (String) -> String,
    ): FeatureDefinition = action(
        id = id,
        title = title,
        description = description,
        fields = listOf(FieldSchema.Text("text", "Text", true), resultField()),
        behaviors = mapOf("text" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val input = feature.config.string("text")
        runCatching { transform(input) }.fold(
            onSuccess = { context.storeCodec(feature.resultVariable(), ConfigValue.StringValue(it)) },
            onFailure = { fail(it.message ?: "Conversion failed") },
        )
    }

    private fun valueTransform(
        id: String,
        title: String,
        description: String,
        allowMissing: Boolean = false,
        transform: (ConfigValue) -> ConfigValue,
    ): FeatureDefinition = action(
        id = id,
        title = title,
        description = description,
        fields = listOf(FieldSchema.Variable("source", "Source variable", !allowMissing), resultField()),
    ) { feature, context ->
        val stored = context.variables.get(feature.config.string("source"))
        if (stored == null && !allowMissing) return@action fail("Source variable does not exist")
        runCatching { transform(stored ?: ConfigValue.NullValue) }.fold(
            onSuccess = { context.storeCodec(feature.resultVariable(), it) },
            onFailure = { fail(it.message ?: "Conversion failed") },
        )
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
        keywords = setOf("data", "encode", "decode", "convert", "type", "collection"),
    )
) { feature, context -> block(feature, context) }

private fun resultField() = FieldSchema.Text("resultVariable", "Store result in variable", true)
private fun FeatureRef.resultVariable(): String = config.string("resultVariable")

private fun FeatureExecutionContext.storeCodec(name: String, value: ConfigValue): ActionExecutionResult {
    if (name.isBlank()) return fail("Destination variable is empty")
    variables.set(name, value)
    return ActionExecutionResult(true, value)
}

private fun FeatureExecutionContext.list(name: String): List<ConfigValue>? =
    (variables.get(name) as? ConfigValue.ListValue)?.value

private fun ConfigValue.typeName(): String = when (this) {
    ConfigValue.NullValue -> "null"
    is ConfigValue.StringValue -> "string"
    is ConfigValue.NumberValue -> "number"
    is ConfigValue.BooleanValue -> "boolean"
    is ConfigValue.ListValue -> "list"
    is ConfigValue.ObjectValue -> "object"
}

private fun ConfigValue.asText(): String = when (this) {
    ConfigValue.NullValue -> ""
    is ConfigValue.StringValue -> value
    is ConfigValue.NumberValue -> value.toString().removeSuffix(".0")
    is ConfigValue.BooleanValue -> value.toString()
    is ConfigValue.ListValue -> value.joinToString(",") { it.asText() }
    is ConfigValue.ObjectValue -> value.entries.joinToString(",") { (key, value) -> "$key=${value.asText()}" }
}

private fun ConfigValue.asNumberStrict(): Double = when (this) {
    is ConfigValue.NumberValue -> value
    is ConfigValue.StringValue -> value.trim().toDoubleOrNull() ?: error("Text is not numeric")
    is ConfigValue.BooleanValue -> if (value) 1.0 else 0.0
    else -> error("Value cannot be converted to a number")
}

private fun ConfigValue.asBoolean(): Boolean = when (this) {
    ConfigValue.NullValue -> false
    is ConfigValue.BooleanValue -> value
    is ConfigValue.NumberValue -> value != 0.0
    is ConfigValue.StringValue -> value.trim().let { it.equals("true", true) || it == "1" || it.equals("yes", true) || it.equals("on", true) }
    is ConfigValue.ListValue -> value.isNotEmpty()
    is ConfigValue.ObjectValue -> value.isNotEmpty()
}

private fun digestHex(algorithm: String, value: String): String =
    MessageDigest.getInstance(algorithm)
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun padBase64(value: String): String {
    val missing = (4 - value.length % 4) % 4
    return value + "=".repeat(missing)
}

private fun fail(message: String) = ActionExecutionResult(false, message = message)
