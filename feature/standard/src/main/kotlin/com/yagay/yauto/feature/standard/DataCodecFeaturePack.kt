package com.yagay.yauto.feature.standard

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.FeatureRef
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
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class DataCodecFeaturePack : FeaturePack {
    override val id: String = "standard.data.codec"
    private val delegate = DefinitionFeaturePack(id, DataCodecFeatures.definitions)
    override fun install(registry: FeatureRegistry) = delegate.install(registry)
}

private object DataCodecFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        textTransform("data.text.base64_encode", "Base64 encode", "Encode UTF-8 text as Base64") {
            Base64.getEncoder().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        },
        textTransform("data.text.base64_decode", "Base64 decode", "Decode Base64 into UTF-8 text") {
            String(Base64.getDecoder().decode(it), StandardCharsets.UTF_8)
        },
        textTransform("data.text.base64_url_encode", "Base64 URL encode", "Encode UTF-8 text using URL-safe Base64 without padding") {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(StandardCharsets.UTF_8))
        },
        textTransform("data.text.base64_url_decode", "Base64 URL decode", "Decode URL-safe Base64 into UTF-8 text") {
            String(Base64.getUrlDecoder().decode(padBase64(it)), StandardCharsets.UTF_8)
        },
        textTransform("data.text.url_encode", "URL encode", "Percent-encode text for use in a URL or form value") {
            URLEncoder.encode(it, StandardCharsets.UTF_8.name())
        },
        textTransform("data.text.url_decode", "URL decode", "Decode percent-encoded URL or form text") {
            URLDecoder.decode(it, StandardCharsets.UTF_8.name())
        },
        textTransform("data.text.hex_encode", "Hex encode", "Encode UTF-8 text as hexadecimal bytes") { input ->
            input.toByteArray(StandardCharsets.UTF_8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        },
        textTransform("data.text.hex_decode", "Hex decode", "Decode hexadecimal bytes into UTF-8 text") { input ->
            if (input.length % 2 != 0) throw IllegalArgumentException()
            val bytes = ByteArray(input.length / 2) { index ->
                input.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
            String(bytes, StandardCharsets.UTF_8)
        },
        textTransform("data.text.sha256", "SHA-256 hash", "Hash UTF-8 text with SHA-256") { digestHex("SHA-256", it) },
        textTransform("data.text.sha512", "SHA-512 hash", "Hash UTF-8 text with SHA-512") { digestHex("SHA-512", it) },
        action(
            "data.text.aes_gcm_encrypt", "AES-GCM encrypt", "Encrypt UTF-8 text with a password using AES-256-GCM and PBKDF2",
            listOf(
                FieldSchema.Text("text", "Text", true, multiline = true),
                FieldSchema.Text("password", "Password", true),
                resultField(),
            ),
            mapOf(
                "text" to FieldBehavior(supportsVariables = true),
                "password" to FieldBehavior(supportsVariables = true),
            ),
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val password = feature.config.string("password").resolveVariables(context.variables)
            if (password.isEmpty()) return@action operationFailedCodec(feature)
            runCatching { aesGcmEncrypt(text, password) }.fold(
                onSuccess = { context.storeCodec(feature.resultVariable(), ConfigValue.StringValue(it)) },
                onFailure = { operationFailedCodec(feature) },
            )
        },
        action(
            "data.text.aes_gcm_decrypt", "AES-GCM decrypt", "Decrypt text produced by YAuto AES-256-GCM encryption",
            listOf(
                FieldSchema.Text("text", "Encrypted text", true, multiline = true),
                FieldSchema.Text("password", "Password", true),
                resultField(),
            ),
            mapOf(
                "text" to FieldBehavior(supportsVariables = true),
                "password" to FieldBehavior(supportsVariables = true),
            ),
        ) { feature, context ->
            val text = feature.config.string("text").resolveVariables(context.variables)
            val password = feature.config.string("password").resolveVariables(context.variables)
            if (password.isEmpty()) return@action operationFailedCodec(feature)
            runCatching { aesGcmDecrypt(text, password) }.fold(
                onSuccess = { context.storeCodec(feature.resultVariable(), ConfigValue.StringValue(it)) },
                onFailure = { operationFailedCodec(feature) },
            )
        },
        valueTransform("data.value.type", "Value type", "Return the YAuto type name of a value variable") {
            ConfigValue.StringValue(it.typeName())
        },
        valueTransform("data.value.to_text", "Convert value to text", "Convert a typed value into readable text") {
            ConfigValue.StringValue(it.asText())
        },
        valueTransform("data.value.to_number", "Convert value to number", "Convert a number, boolean, or numeric text value into a number") {
            ConfigValue.NumberValue(it.asNumberStrict())
        },
        valueTransform("data.value.to_boolean", "Convert value to boolean", "Convert a value using automation-friendly truthiness rules") {
            ConfigValue.BooleanValue(it.asBoolean())
        },
        valueTransform("data.value.is_null", "Is value null", "Return true when a variable is missing or contains null", allowMissing = true) {
            ConfigValue.BooleanValue(it == ConfigValue.NullValue)
        },
        action(
            "data.list.zip", "Zip lists", "Pair items from two lists into two-item sublists",
            listOf(
                FieldSchema.Variable("left", "Left list", true),
                FieldSchema.Variable("right", "Right list", true),
                resultField(),
            ),
        ) { feature, context ->
            val left = context.listCodec(feature.config.string("left")) ?: return@action notListCodec()
            val right = context.listCodec(feature.config.string("right")) ?: return@action notListCodec()
            val output = (0 until minOf(left.size, right.size)).map { index ->
                ConfigValue.ListValue(listOf(left[index], right[index]))
            }
            context.storeCodec(feature.resultVariable(), ConfigValue.ListValue(output))
        },
        action(
            "data.list.enumerate", "Enumerate list", "Convert list items into objects containing index and value",
            listOf(FieldSchema.Variable("source", "List variable", true), resultField()),
        ) { feature, context ->
            val values = context.listCodec(feature.config.string("source")) ?: return@action notListCodec()
            val output = values.mapIndexed { index, value ->
                ConfigValue.ObjectValue(
                    mapOf(
                        "index" to ConfigValue.NumberValue(index.toDouble()),
                        "value" to value,
                    ),
                )
            }
            context.storeCodec(feature.resultVariable(), ConfigValue.ListValue(output))
        },
        action(
            "data.list.frequency", "Count list frequencies", "Count occurrences of each text representation in a list",
            listOf(FieldSchema.Variable("source", "List variable", true), resultField()),
        ) { feature, context ->
            val values = context.listCodec(feature.config.string("source")) ?: return@action notListCodec()
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
            "data.object.from_lists", "Build object from lists", "Create an object from parallel key and value lists",
            listOf(
                FieldSchema.Variable("keys", "Keys list", true),
                FieldSchema.Variable("values", "Values list", true),
                resultField(),
            ),
        ) { feature, context ->
            val keys = context.listCodec(feature.config.string("keys")) ?: return@action notListCodec()
            val values = context.listCodec(feature.config.string("values")) ?: return@action notListCodec()
            val output = linkedMapOf<String, ConfigValue>()
            repeat(minOf(keys.size, values.size)) { index -> output[keys[index].asText()] = values[index] }
            context.storeCodec(feature.resultVariable(), ConfigValue.ObjectValue(output))
        },
        action(
            "data.value.default_if_null", "Default if null", "Use a fallback value when a variable is missing or null",
            listOf(
                FieldSchema.Variable("source", "Source variable", true),
                FieldSchema.Text("fallback", "Fallback value"),
                resultField(),
            ),
            mapOf("fallback" to FieldBehavior(supportsVariables = true)),
        ) { feature, context ->
            val current = context.variables.get(feature.config.string("source"))
            val fallback = (feature.config["fallback"] ?: ConfigValue.NullValue).resolveVariables(context.variables)
            context.storeCodec(
                feature.resultVariable(),
                if (current == null || current == ConfigValue.NullValue) fallback else current,
            )
        },
    )

    private fun textTransform(
        id: String,
        title: String,
        description: String,
        transform: (String) -> String,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        listOf(FieldSchema.Text("text", "Text", true), resultField()),
        mapOf("text" to FieldBehavior(supportsVariables = true)),
    ) { feature, context ->
        val input = feature.config.string("text").resolveVariables(context.variables)
        runCatching { transform(input) }.fold(
            onSuccess = { context.storeCodec(feature.resultVariable(), ConfigValue.StringValue(it)) },
            onFailure = { operationFailedCodec(feature) },
        )
    }

    private fun valueTransform(
        id: String,
        title: String,
        description: String,
        allowMissing: Boolean = false,
        transform: (ConfigValue) -> ConfigValue,
    ): FeatureDefinition = action(
        id,
        title,
        description,
        listOf(FieldSchema.Variable("source", "Source variable", !allowMissing), resultField()),
    ) { feature, context ->
        val stored = context.variables.get(feature.config.string("source"))
        if (stored == null && !allowMissing) return@action operationFailedCodec(feature)
        runCatching { transform(stored ?: ConfigValue.NullValue) }.fold(
            onSuccess = { context.storeCodec(feature.resultVariable(), it) },
            onFailure = { operationFailedCodec(feature) },
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
    ),
) { feature, context -> block(feature, context) }

private fun resultField() = FieldSchema.Text("resultVariable", "Store result in variable", true)
private fun FeatureRef.resultVariable(): String = config.string("resultVariable")

private fun FeatureExecutionContext.storeCodec(name: String, value: ConfigValue): ActionExecutionResult {
    if (name.isBlank()) return ActionExecutionResult(false, message = userText("feature.destination_variable_empty"))
    variables.set(name, value)
    return ActionExecutionResult(true, value)
}

private fun FeatureExecutionContext.listCodec(name: String): List<ConfigValue>? =
    (variables.get(name) as? ConfigValue.ListValue)?.value

private fun notListCodec() = ActionExecutionResult(false, message = userText("feature.variable_not_list"))
private fun operationFailedCodec(feature: FeatureRef) =
    ActionExecutionResult(false, message = userText("feature.operation_failed", feature.id.value))

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
    is ConfigValue.StringValue -> value.trim().toDoubleOrNull() ?: throw IllegalArgumentException()
    is ConfigValue.BooleanValue -> if (value) 1.0 else 0.0
    else -> throw IllegalArgumentException()
}

private fun ConfigValue.asBoolean(): Boolean = when (this) {
    ConfigValue.NullValue -> false
    is ConfigValue.BooleanValue -> value
    is ConfigValue.NumberValue -> value != 0.0
    is ConfigValue.StringValue -> value.trim().let {
        it.equals("true", true) || it == "1" || it.equals("yes", true) || it.equals("on", true)
    }
    is ConfigValue.ListValue -> value.isNotEmpty()
    is ConfigValue.ObjectValue -> value.isNotEmpty()
}

private fun digestHex(algorithm: String, value: String): String =
    MessageDigest.getInstance(algorithm)
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private const val AES_GCM_PREFIX = "yauto:aesgcm:v1:"
private const val AES_GCM_ITERATIONS = 120_000
private const val AES_GCM_TAG_BITS = 128
private const val AES_GCM_KEY_BITS = 256

internal fun aesGcmEncrypt(
    plaintext: String,
    password: String,
    random: SecureRandom = SecureRandom(),
): String {
    require(password.isNotEmpty())
    val salt = ByteArray(16).also(random::nextBytes)
    val nonce = ByteArray(12).also(random::nextBytes)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, deriveAesKey(password, salt), GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
    val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
    val encoder = Base64.getUrlEncoder().withoutPadding()
    return AES_GCM_PREFIX + listOf(salt, nonce, encrypted).joinToString(":") { encoder.encodeToString(it) }
}

internal fun aesGcmDecrypt(encoded: String, password: String): String {
    require(password.isNotEmpty() && encoded.startsWith(AES_GCM_PREFIX))
    val parts = encoded.removePrefix(AES_GCM_PREFIX).split(':')
    require(parts.size == 3)
    val decoder = Base64.getUrlDecoder()
    val salt = decoder.decode(padBase64(parts[0]))
    val nonce = decoder.decode(padBase64(parts[1]))
    val ciphertext = decoder.decode(padBase64(parts[2]))
    require(salt.size == 16 && nonce.size == 12 && ciphertext.size >= 16)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, deriveAesKey(password, salt), GCMParameterSpec(AES_GCM_TAG_BITS, nonce))
    return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
}

private fun deriveAesKey(password: String, salt: ByteArray): SecretKeySpec {
    val spec = PBEKeySpec(password.toCharArray(), salt, AES_GCM_ITERATIONS, AES_GCM_KEY_BITS)
    return try {
        val encoded = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        SecretKeySpec(encoded, "AES")
    } finally {
        spec.clearPassword()
    }
}

private fun padBase64(value: String): String {
    val missing = (4 - value.length % 4) % 4
    return value + "=".repeat(missing)
}
