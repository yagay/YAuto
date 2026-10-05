package com.yagay.yauto.platform.android

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.logging.TraceEvent
import com.yagay.yauto.core.logging.TraceKind
import com.yagay.yauto.core.logging.TraceLevel
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.ExecutionId
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class AndroidMacroDroidParityFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.macrodroid.parity"
    private val context = context.applicationContext

    override fun install(registry: FeatureRegistry) {
        registerLogWrite(registry)
        registerLogExport(registry)
        registerCrypto(registry)
        registerCollectionRemove(registry)
    }

    private fun registerLogWrite(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.log.write"), FeatureKind.ACTION,
                "Write YAuto log",
                "Write a custom message into YAuto execution tracing",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Choice("level", "Level", options = listOf("debug", "info", "warning", "error")),
                    FieldSchema.Text("message", "Message", true, multiline = true),
                ),
                fieldBehaviors = mapOf("message" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("log", "trace", "debug", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val message = feature.config.string("message").resolveVariables(ctx.variables)
            if (message.isBlank()) return@registerAction ActionExecutionResult(false)
            val level = when (feature.config.string("level", "info")) {
                "debug" -> TraceLevel.DEBUG
                "warning" -> TraceLevel.WARN
                "error" -> TraceLevel.ERROR
                else -> TraceLevel.INFO
            }
            ctx.tracer.record(
                TraceEvent(
                    executionId = ctx.executionId,
                    kind = TraceKind.ACTION,
                    level = level,
                    timestampEpochMs = System.currentTimeMillis(),
                    message = message,
                    nodeId = ctx.nodeId,
                    featureId = feature.typeId,
                    backendId = "yauto.log",
                    success = true,
                )
            )
            ActionExecutionResult(true)
        }
    }

    private fun registerLogExport(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.log.export"), FeatureKind.ACTION,
                "Export system/YAuto log",
                "Export recent logcat output to Downloads using Root or Shizuku",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Number("lines", "Maximum lines", min = 1.0, max = 20000.0),
                    FieldSchema.Text("fileName", "File name"),
                    FieldSchema.Variable("resultVariable", "Store exported URI"),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                implementationOptions = privilegedOptions(),
                keywords = setOf("export log", "logcat", "diagnostic", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val lines = (feature.config["lines"].numberOrNull() ?: 3000.0).toInt().coerceIn(1, 20000)
            val result = shellResult(ctx, "logcat -d -v threadtime -t " + lines)
            if (!result.success) return@registerAction ActionExecutionResult(false, result.value, result.message)
            val text = stdout(result)
            val requested = feature.config.string("fileName").resolveVariables(ctx.variables).trim()
            val fileName = sanitizeFileName(
                requested.ifBlank {
                    "YAuto-log-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"
                }
            )
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/YAuto/Logs")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                },
            ) ?: return@registerAction ActionExecutionResult(false)
            val ok = runCatching {
                resolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(text) }
                    ?: error("Unable to open output")
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
                true
            }.getOrElse {
                runCatching { resolver.delete(uri, null, null) }
                false
            }
            if (!ok) return@registerAction ActionExecutionResult(false)
            val output = ConfigValue.StringValue(uri.toString())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerCrypto(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("data.text.crypto"), FeatureKind.ACTION,
                "Encrypt / decrypt text",
                "Encrypt or decrypt text with AES-256-GCM using a password-derived key",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Choice("operation", "Operation", true, listOf("encrypt", "decrypt")),
                    FieldSchema.Text("text", "Text", true, multiline = true),
                    FieldSchema.Text("password", "Password", true),
                    FieldSchema.Variable("resultVariable", "Store result", true),
                    FieldSchema.Variable("successVariable", "Store success"),
                ),
                fieldBehaviors = mapOf(
                    "text" to FieldBehavior(supportsVariables = true),
                    "password" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("encrypt", "decrypt", "aes", "crypto", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val password = feature.config.string("password").resolveVariables(ctx.variables)
            if (password.isEmpty()) return@registerAction ActionExecutionResult(false)
            val result = runCatching {
                if (feature.config.string("operation", "encrypt") == "decrypt") decrypt(text, password)
                else encrypt(text, password)
            }
            val success = result.isSuccess
            feature.config.string("successVariable").trim().takeIf(String::isNotBlank)
                ?.let { ctx.variables.set(it, ConfigValue.BooleanValue(success)) }
            if (!success) {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", result.exceptionOrNull()?.message ?: "crypto"),
                )
            }
            val output = ConfigValue.StringValue(result.getOrThrow())
            val target = feature.config.string("resultVariable").trim()
            if (target.isBlank()) return@registerAction ActionExecutionResult(false)
            ctx.variables.set(target, output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerCollectionRemove(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("variable.collection.remove"), FeatureKind.ACTION,
                "Remove collection entry",
                "Remove a list index/value or object key from a runtime variable",
                FeatureCategory.VARIABLE,
                fields = listOf(
                    FieldSchema.Variable("name", "Collection variable", true),
                    FieldSchema.Choice("mode", "Mode", true, listOf("list_index", "list_value", "object_key")),
                    FieldSchema.Text("keyOrValue", "Index / value / key", true),
                ),
                fieldBehaviors = mapOf("keyOrValue" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("array", "dictionary", "remove entry", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val name = feature.config.string("name").trim()
            val key = feature.config.string("keyOrValue").resolveVariables(ctx.variables)
            val current = ctx.variables.get(name) ?: return@registerAction ActionExecutionResult(false)
            val output = when (feature.config.string("mode")) {
                "list_index" -> {
                    val list = (current as? ConfigValue.ListValue)?.value?.toMutableList()
                        ?: return@registerAction ActionExecutionResult(false)
                    val index = key.toIntOrNull() ?: return@registerAction ActionExecutionResult(false)
                    if (index !in list.indices) return@registerAction ActionExecutionResult(false)
                    list.removeAt(index)
                    ConfigValue.ListValue(list)
                }
                "list_value" -> {
                    val list = (current as? ConfigValue.ListValue)?.value?.toMutableList()
                        ?: return@registerAction ActionExecutionResult(false)
                    val index = list.indexOfFirst { valueText(it) == key }
                    if (index < 0) return@registerAction ActionExecutionResult(false)
                    list.removeAt(index)
                    ConfigValue.ListValue(list)
                }
                "object_key" -> {
                    val map = (current as? ConfigValue.ObjectValue)?.value?.toMutableMap()
                        ?: return@registerAction ActionExecutionResult(false)
                    if (map.remove(key) == null) return@registerAction ActionExecutionResult(false)
                    ConfigValue.ObjectValue(map)
                }
                else -> return@registerAction ActionExecutionResult(false)
            }
            ctx.variables.set(name, output)
            ActionExecutionResult(true, output)
        }
    }

    private suspend fun shellResult(ctx: FeatureExecutionContext, command: String) =
        ctx.capabilities.execute(
            CapabilityRequest(
                capability = CapabilityIds.PRIVILEGED_SHELL,
                operationId = "system.shell.execute",
                payload = mapOf("command" to ConfigValue.StringValue(command)),
            )
        )

    private fun stdout(result: com.yagay.yauto.core.capability.CapabilityResult): String =
        ((result.value as? ConfigValue.ObjectValue)?.value?.get("stdout") as? ConfigValue.StringValue)?.value.orEmpty()

    private fun privilegedOptions() = listOf(
        FeatureImplementationOption("root", setOf(AccessRequirement.ROOT)),
        FeatureImplementationOption("shizuku", setOf(AccessRequirement.SHIZUKU)),
    )

    private fun sanitizeFileName(raw: String): String =
        raw.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().take(120)
            .ifBlank { "YAuto-log.txt" }
            .let { if (it.endsWith(".txt", true)) it else it + ".txt" }

    private fun encrypt(text: String, password: String): String {
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val key = deriveKey(password, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return "YAG1:" + listOf(salt, iv, encrypted).joinToString(":") {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }
    }

    private fun decrypt(text: String, password: String): String {
        val parts = text.split(':')
        require(parts.size == 4 && parts[0] == "YAG1") { "Invalid encrypted text" }
        val decoder = Base64.getUrlDecoder()
        val salt = decoder.decode(parts[1])
        val iv = decoder.decode(parts[2])
        val encrypted = decoder.decode(parts[3])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun valueText(value: ConfigValue): String = when (value) {
        ConfigValue.NullValue -> ""
        is ConfigValue.StringValue -> value.value
        is ConfigValue.NumberValue -> value.value.toString().removeSuffix(".0")
        is ConfigValue.BooleanValue -> value.value.toString()
        is ConfigValue.ListValue -> value.value.joinToString(",") { valueText(it) }
        is ConfigValue.ObjectValue -> value.value.toString()
    }
}
