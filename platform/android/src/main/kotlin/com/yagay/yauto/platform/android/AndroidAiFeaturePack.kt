package com.yagay.yauto.platform.android

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class AndroidAiFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.ai"
    private val context = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    override fun install(registry: FeatureRegistry) {
        registerLlm(registry)
        registerTextTransform(registry, "ai.text.summarize", "Summarize text", "Summarize text using an OpenAI-compatible chat endpoint") { text, _ ->
            "Summarize the following text while preserving important facts:\n\n$text"
        }
        registerTextTransform(registry, "ai.text.proofread", "Proofread text", "Proofread text using an OpenAI-compatible chat endpoint") { text, _ ->
            "Proofread the following text. Correct grammar, spelling and punctuation while preserving meaning. Return only the corrected text:\n\n$text"
        }
        registerTextTransform(registry, "ai.text.rewrite", "Rewrite text", "Rewrite text according to an instruction using an OpenAI-compatible chat endpoint", extra = "instruction") { text, extra ->
            "Rewrite the following text according to this instruction: $extra\n\n$text"
        }
        registerTextTransform(registry, "ai.text.translate", "Translate text", "Translate text to a target language using an OpenAI-compatible chat endpoint", extra = "targetLanguage") { text, extra ->
            "Translate the following text to $extra. Preserve meaning and formatting. Return only the translation:\n\n$text"
        }
        registerImageDescription(registry)
        registerImageGeneration(registry)
    }

    private fun registerLlm(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("ai.llm.query"), FeatureKind.ACTION,
                "LLM query", "Send a prompt to an OpenAI-compatible chat-completions endpoint",
                FeatureCategory.ADVANCED,
                fields = connectionFields() + listOf(
                    FieldSchema.Text("systemPrompt", "System prompt", multiline = true),
                    FieldSchema.Text("prompt", "Prompt", true, multiline = true),
                    FieldSchema.Number("temperature", "Temperature", min = 0.0, max = 2.0),
                    FieldSchema.Variable("resultVariable", "Store response text"),
                ),
                fieldBehaviors = mapOf(
                    "systemPrompt" to FieldBehavior(supportsVariables = true),
                    "prompt" to FieldBehavior(supportsVariables = true),
                    "apiKey" to FieldBehavior(supportsVariables = true, advanced = true),
                ),
                keywords = setOf("ai", "llm", "chat", "openai compatible", "prompt"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val prompt = feature.config.string("prompt").resolveVariables(ctx.variables)
            if (prompt.isBlank()) return@registerAction ActionExecutionResult(false, message = userText("feature.ai_prompt_empty"))
            executeChat(feature, ctx, prompt, feature.config.string("systemPrompt").resolveVariables(ctx.variables))
        }
    }

    private fun registerTextTransform(
        registry: FeatureRegistry,
        featureId: String,
        title: String,
        description: String,
        extra: String? = null,
        prompt: (String, String) -> String,
    ) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId(featureId), FeatureKind.ACTION,
                title, description, FeatureCategory.ADVANCED,
                fields = connectionFields() + buildList {
                    add(FieldSchema.Text("text", "Text", true, multiline = true))
                    if (extra != null) add(FieldSchema.Text(extra, if (extra == "targetLanguage") "Target language" else "Rewrite instruction", true, multiline = extra != "targetLanguage"))
                    add(FieldSchema.Variable("resultVariable", "Store response text"))
                },
                fieldBehaviors = buildMap {
                    put("text", FieldBehavior(supportsVariables = true))
                    if (extra != null) put(extra, FieldBehavior(supportsVariables = true))
                    put("apiKey", FieldBehavior(supportsVariables = true, advanced = true))
                },
                keywords = setOf("ai", "llm", "text", "language", "automation"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val text = feature.config.string("text").resolveVariables(ctx.variables)
            val extraValue = extra?.let { feature.config.string(it).resolveVariables(ctx.variables) }.orEmpty()
            if (text.isBlank() || (extra != null && extraValue.isBlank())) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.ai_input_empty"))
            }
            executeChat(feature, ctx, prompt(text, extraValue), "")
        }
    }

    private fun registerImageDescription(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("ai.image.describe"), FeatureKind.ACTION,
                "Describe image", "Describe an image using an OpenAI-compatible multimodal chat endpoint",
                FeatureCategory.ADVANCED,
                fields = connectionFields() + listOf(
                    FieldSchema.Text("image", "Image path or content URI", true),
                    FieldSchema.Text("prompt", "Image prompt", multiline = true),
                    FieldSchema.Variable("resultVariable", "Store response text"),
                ),
                fieldBehaviors = mapOf(
                    "image" to FieldBehavior(supportsVariables = true),
                    "prompt" to FieldBehavior(supportsVariables = true),
                    "apiKey" to FieldBehavior(supportsVariables = true, advanced = true),
                ),
                keywords = setOf("ai", "image", "vision", "describe", "multimodal"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val raw = feature.config.string("image").resolveVariables(ctx.variables).trim()
            val bytes = runCatching { readImageBytes(raw) }.getOrNull()
                ?: return@registerAction ActionExecutionResult(false, message = userText("feature.ai_image_unreadable"))
            if (bytes.size > 10 * 1024 * 1024) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.ai_image_too_large"))
            }
            val mime = imageMime(raw)
            val dataUrl = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
            val prompt = feature.config.string("prompt").resolveVariables(ctx.variables).ifBlank { "Describe this image in detail." }
            executeChat(feature, ctx, prompt, "", dataUrl)
        }
    }

    private fun registerImageGeneration(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("ai.image.generate"),
                FeatureKind.ACTION,
                "Generate AI image",
                "Generate an image through an OpenAI-compatible Images endpoint and optionally save it to a file",
                FeatureCategory.ADVANCED,
                fields = listOf(
                    FieldSchema.Text("endpoint", "Images endpoint", true),
                    FieldSchema.Text("apiKey", "API key"),
                    FieldSchema.Text("model", "Model", true),
                    FieldSchema.Text("prompt", "Prompt", true, multiline = true),
                    FieldSchema.Choice(
                        "size",
                        "Image size",
                        options = listOf("256x256", "512x512", "1024x1024", "1024x1792", "1792x1024"),
                    ),
                    FieldSchema.Choice("quality", "Quality", options = listOf("default", "standard", "hd")),
                    FieldSchema.Choice("responseFormat", "Response format", options = listOf("url", "b64_json")),
                    FieldSchema.Duration("timeoutMs", "Request timeout"),
                    FieldSchema.Text("outputPath", "Optional output image path"),
                    FieldSchema.Variable("resultVariable", "Store generated-image object"),
                ),
                fieldBehaviors = mapOf(
                    "apiKey" to FieldBehavior(supportsVariables = true, advanced = true),
                    "prompt" to FieldBehavior(supportsVariables = true),
                    "outputPath" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("ai", "image generation", "text to image", "openai compatible", "tasker"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val endpoint = feature.config.string("endpoint").resolveVariables(ctx.variables).trim()
            val apiKey = feature.config.string("apiKey").resolveVariables(ctx.variables).trim()
            val model = feature.config.string("model").resolveVariables(ctx.variables).trim()
            val prompt = feature.config.string("prompt").resolveVariables(ctx.variables)
            if ((!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) || model.isBlank() || prompt.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.ai_config_invalid"))
            }
            val responseFormat = feature.config.string("responseFormat", "url").takeIf { it in setOf("url", "b64_json") } ?: "url"
            val body = buildJsonObject {
                put("model", JsonPrimitive(model))
                put("prompt", JsonPrimitive(prompt))
                put("size", JsonPrimitive(feature.config.string("size", "1024x1024")))
                put("response_format", JsonPrimitive(responseFormat))
                feature.config.string("quality", "default").takeIf { it != "default" }?.let {
                    put("quality", JsonPrimitive(it))
                }
            }.toString()
            val timeout = ((feature.config["timeoutMs"].numberOrNull() ?: 180_000.0).toLong()).coerceIn(1_000L, 900_000L)
            val client = OkHttpClient.Builder().callTimeout(timeout, TimeUnit.MILLISECONDS).build()
            val request = Request.Builder()
                .url(endpoint)
                .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer " + apiKey) }
                .build()
            val generated = runCatching {
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) error("HTTP " + response.code + ": " + raw.take(1000))
                        val item = json.parseToJsonElement(raw).jsonObject["data"]
                            ?.jsonArray?.firstOrNull()?.jsonObject
                            ?: error("No generated image in response")
                        val url = item["url"]?.jsonPrimitive?.content.orEmpty()
                        val base64 = item["b64_json"]?.jsonPrimitive?.content.orEmpty()
                        if (url.isBlank() && base64.isBlank()) error("Image response contains neither url nor b64_json")
                        GeneratedImageResult(
                            url = url,
                            base64 = base64,
                            revisedPrompt = item["revised_prompt"]?.jsonPrimitive?.content.orEmpty(),
                        )
                    }
                }
            }.getOrElse {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                )
            }

            val outputPath = feature.config.string("outputPath").resolveVariables(ctx.variables).trim()
            val savedPath = if (outputPath.isBlank()) "" else runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = if (generated.base64.isNotBlank()) {
                        Base64.getDecoder().decode(generated.base64)
                    } else {
                        client.newCall(Request.Builder().url(generated.url).get().build()).execute().use { response ->
                            if (!response.isSuccessful) error("Image download HTTP " + response.code)
                            response.body?.bytes() ?: error("Generated image body is empty")
                        }
                    }
                    val file = File(outputPath)
                    file.parentFile?.mkdirs()
                    file.writeBytes(bytes)
                    file.absolutePath
                }
            }.getOrElse {
                return@registerAction ActionExecutionResult(
                    false,
                    message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName),
                )
            }

            val output = ConfigValue.ObjectValue(
                buildMap {
                    put("url", ConfigValue.StringValue(generated.url))
                    put("path", ConfigValue.StringValue(savedPath))
                    put("revisedPrompt", ConfigValue.StringValue(generated.revisedPrompt))
                    if (savedPath.isBlank() && generated.base64.isNotBlank()) {
                        put("base64", ConfigValue.StringValue(generated.base64))
                    }
                }
            )
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun connectionFields(): List<FieldSchema> = listOf(
        FieldSchema.Text("endpoint", "Chat-completions endpoint", true),
        FieldSchema.Text("apiKey", "API key"),
        FieldSchema.Text("model", "Model", true),
        FieldSchema.Duration("timeoutMs", "Request timeout"),
    )

    private suspend fun executeChat(
        feature: com.yagay.yauto.core.model.FeatureRef,
        ctx: FeatureExecutionContext,
        prompt: String,
        systemPrompt: String,
        imageDataUrl: String? = null,
    ): ActionExecutionResult {
        val endpoint = feature.config.string("endpoint").resolveVariables(ctx.variables).trim()
        val model = feature.config.string("model").resolveVariables(ctx.variables).trim()
        val apiKey = feature.config.string("apiKey").resolveVariables(ctx.variables).trim()
        if ((!endpoint.startsWith("https://") && !endpoint.startsWith("http://")) || model.isBlank()) {
            return ActionExecutionResult(false, message = userText("feature.ai_config_invalid"))
        }
        val temperature = (feature.config["temperature"].numberOrNull() ?: 0.3).coerceIn(0.0, 2.0)
        val userContent = if (imageDataUrl == null) {
            JsonPrimitive(prompt)
        } else {
            buildJsonArray {
                add(buildJsonObject { put("type", JsonPrimitive("text")); put("text", JsonPrimitive(prompt)) })
                add(buildJsonObject {
                    put("type", JsonPrimitive("image_url"))
                    put("image_url", buildJsonObject { put("url", JsonPrimitive(imageDataUrl)) })
                })
            }
        }
        val messages = buildJsonArray {
            if (systemPrompt.isNotBlank()) add(buildJsonObject {
                put("role", JsonPrimitive("system"))
                put("content", JsonPrimitive(systemPrompt))
            })
            add(buildJsonObject {
                put("role", JsonPrimitive("user"))
                put("content", userContent)
            })
        }
        val body = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("messages", messages)
            put("temperature", JsonPrimitive(temperature))
        }.toString()
        val timeout = ((feature.config["timeoutMs"].numberOrNull() ?: 120_000.0).toLong()).coerceIn(1_000L, 600_000L)
        val client = OkHttpClient.Builder()
            .callTimeout(timeout, TimeUnit.MILLISECONDS)
            .build()
        val request = Request.Builder()
            .url(endpoint)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .build()
        val responseText = runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("HTTP ${response.code}: ${raw.take(1000)}")
                    extractChatText(raw)
                }
            }
        }.getOrElse {
            return ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName))
        }
        val output = ConfigValue.StringValue(responseText)
        feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
        return ActionExecutionResult(true, output)
    }

    private fun extractChatText(raw: String): String {
        val root = json.parseToJsonElement(raw).jsonObject
        root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.let { content ->
                if (content is JsonPrimitive) return content.content
                if (content is JsonArray) {
                    return content.mapNotNull { part ->
                        (part as? JsonObject)?.get("text")?.jsonPrimitive?.content
                    }.joinToString("\n")
                }
            }
        root["output_text"]?.jsonPrimitive?.content?.let { return it }
        error("No assistant text in response")
    }

    private fun readImageBytes(raw: String): ByteArray {
        val input = if (raw.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(raw)) ?: error("Unable to open content URI")
        } else {
            File(if (raw.startsWith("file://")) Uri.parse(raw).path.orEmpty() else raw).inputStream()
        }
        return input.use { it.readBytes() }
    }

    private fun imageMime(raw: String): String {
        val ext = raw.substringBefore('?').substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "image/jpeg"
    }
}


private data class GeneratedImageResult(
    val url: String,
    val base64: String,
    val revisedPrompt: String,
)
