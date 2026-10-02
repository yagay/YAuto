package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import com.yagay.yauto.core.model.userText

class AndroidHttpFeaturePack : FeaturePack {
    override val id = "android.http"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.http.request"), FeatureKind.ACTION,
                "HTTP request", "Send an HTTP request and expose status, body and response headers as structured output",
                FeatureCategory.SCRIPT,
                fields = listOf(
                    FieldSchema.Choice("method", "Method", true, listOf("GET", "POST", "PUT", "DELETE", "HEAD")),
                    FieldSchema.Text("url", "URL", true),
                    FieldSchema.Text("headers", "Headers (one Name: value per line)", multiline = true),
                    FieldSchema.Text("body", "Request body", multiline = true),
                    FieldSchema.Text("contentType", "Content-Type"),
                    FieldSchema.Duration("connectTimeoutMs", "Connect timeout"),
                    FieldSchema.Duration("readTimeoutMs", "Read timeout"),
                    FieldSchema.Variable("resultVariable", "Store response object"),
                ),
                keywords = setOf("http", "https", "api", "webhook", "request"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val url = feature.config.string("url").resolveVariables(ctx.variables).trim()
            if (!(url.startsWith("https://") || url.startsWith("http://"))) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.http_url_required"))
            }
            val method = feature.config.string("method", "GET").uppercase()
            val headers = parseHeaders(feature.config.string("headers").resolveVariables(ctx.variables))
            val body = feature.config.string("body").resolveVariables(ctx.variables)
            val contentType = feature.config.string("contentType").resolveVariables(ctx.variables).ifBlank { "text/plain; charset=utf-8" }
            val connectTimeoutMs = feature.config.long("connectTimeoutMs", 10_000).coerceIn(1_000, 120_000).toInt()
            val readTimeoutMs = feature.config.long("readTimeoutMs", 20_000).coerceIn(1_000, 180_000).toInt()

            val result = withContext(Dispatchers.IO) {
                execute(url, method, headers, body, contentType, connectTimeoutMs, readTimeoutMs)
            }
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result.value) }
            }
            result
        }
    }

    private fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String,
        contentType: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ActionExecutionResult {
        var connection: HttpURLConnection? = null
        return runCatching {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                instanceFollowRedirects = true
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
                if (body.isNotEmpty() && method in setOf("POST", "PUT", "DELETE")) {
                    doOutput = true
                    if (getRequestProperty("Content-Type").isNullOrBlank()) setRequestProperty("Content-Type", contentType)
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val status = connection!!.responseCode
            val stream = if (status >= 400) connection!!.errorStream else connection!!.inputStream
            val responseBody = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) }.orEmpty()
            val responseHeaders = connection!!.headerFields
                .filterKeys { it != null }
                .mapValues { (_, values) -> values.orEmpty().joinToString(", ") }
                .mapValues { ConfigValue.StringValue(it.value) }
            val value = ConfigValue.ObjectValue(
                mapOf(
                    "statusCode" to ConfigValue.NumberValue(status.toDouble()),
                    "body" to ConfigValue.StringValue(responseBody),
                    "headers" to ConfigValue.ObjectValue(responseHeaders),
                    "url" to ConfigValue.StringValue(connection!!.url.toString()),
                )
            )
            ActionExecutionResult(status in 200..399, value, if (status in 200..399) null else "HTTP $status")
        }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            .also { connection?.disconnect() }
    }

    private fun parseHeaders(raw: String): Map<String, String> = buildMap {
        raw.lineSequence().forEach { line ->
            val split = line.indexOf(':')
            if (split > 0) {
                val name = line.substring(0, split).trim()
                val value = line.substring(split + 1).trim()
                if (name.matches(HEADER_NAME)) put(name, value)
            }
        }
    }

    private fun readBounded(input: java.io.InputStream, limit: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) {
                val remaining = limit - output.size()
                if (remaining > 0) output.write(buffer, 0, remaining)
                break
            }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}
