package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class AndroidHttpFeaturePack : FeaturePack {
    override val id = "android.http"

    override fun install(registry: FeatureRegistry) {
        registerRequest(registry)
        registerDownload(registry)
        registerUpload(registry)
    }

    private fun registerRequest(registry: FeatureRegistry) {
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
            if (!isHttpUrl(url)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.http_url_required"))
            }
            val method = feature.config.string("method", "GET").uppercase()
            val headers = parseHeaders(feature.config.string("headers").resolveVariables(ctx.variables))
            val body = feature.config.string("body").resolveVariables(ctx.variables)
            val contentType = feature.config.string("contentType").resolveVariables(ctx.variables).ifBlank { "text/plain; charset=utf-8" }
            val connectTimeoutMs = feature.config.long("connectTimeoutMs", 10_000).coerceIn(1_000, 120_000).toInt()
            val readTimeoutMs = feature.config.long("readTimeoutMs", 20_000).coerceIn(1_000, 180_000).toInt()

            val result = withContext(Dispatchers.IO) {
                executeRequest(url, method, headers, body, contentType, connectTimeoutMs, readTimeoutMs)
            }
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result.value) }
            }
            result
        }
    }

    private fun registerDownload(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.http.download"), FeatureKind.ACTION,
                "HTTP download", "Download an HTTP or HTTPS response body to a local file with size, timeout and overwrite safeguards",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("url", "URL", true),
                    FieldSchema.Text("headers", "Headers (one Name: value per line)", multiline = true),
                    FieldSchema.Text("path", "Destination path", true),
                    FieldSchema.Toggle("overwrite", "Overwrite destination"),
                    FieldSchema.Toggle("createParents", "Create parent directories"),
                    FieldSchema.Number("maxBytes", "Maximum bytes", min = 1.0, max = MAX_DOWNLOAD_BYTES.toDouble()),
                    FieldSchema.Duration("connectTimeoutMs", "Connect timeout"),
                    FieldSchema.Duration("readTimeoutMs", "Read timeout"),
                    FieldSchema.Variable("resultVariable", "Store response object"),
                ),
                keywords = setOf("http", "https", "download", "file", "network"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val url = feature.config.string("url").resolveVariables(ctx.variables).trim()
            if (!isHttpUrl(url)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.http_url_required"))
            }
            val path = feature.config.string("path").resolveVariables(ctx.variables).trim()
            if (path.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_path_empty"))
            }
            val headers = parseHeaders(feature.config.string("headers").resolveVariables(ctx.variables))
            val connectTimeoutMs = feature.config.long("connectTimeoutMs", 10_000).coerceIn(1_000, 120_000).toInt()
            val readTimeoutMs = feature.config.long("readTimeoutMs", 60_000).coerceIn(1_000, 600_000).toInt()
            val maxBytes = httpDownloadLimit(feature.config["maxBytes"].numberOrNull())
            val overwrite = feature.config.boolean("overwrite")
            val createParents = feature.config.boolean("createParents", true)

            val result = withContext(Dispatchers.IO) {
                executeDownload(
                    url = url,
                    headers = headers,
                    destination = File(path),
                    overwrite = overwrite,
                    createParents = createParents,
                    maxBytes = maxBytes,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                )
            }
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result.value) }
            }
            result
        }
    }

    private fun registerUpload(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.http.upload"), FeatureKind.ACTION,
                "HTTP file upload", "Upload a local file with HTTP PUT and expose the server response as structured output",
                FeatureCategory.FILE,
                fields = listOf(
                    FieldSchema.Text("url", "URL", true),
                    FieldSchema.Text("headers", "Headers (one Name: value per line)", multiline = true),
                    FieldSchema.Text("path", "Local file path", true),
                    FieldSchema.Text("contentType", "Content-Type"),
                    FieldSchema.Number("maxBytes", "Maximum upload bytes", min = 1.0, max = MAX_DOWNLOAD_BYTES.toDouble()),
                    FieldSchema.Duration("connectTimeoutMs", "Connect timeout"),
                    FieldSchema.Duration("readTimeoutMs", "Read timeout"),
                    FieldSchema.Variable("resultVariable", "Store response object"),
                ),
                keywords = setOf("http", "https", "upload", "put", "file", "webdav"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val url = feature.config.string("url").resolveVariables(ctx.variables).trim()
            if (!isHttpUrl(url)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.http_url_required"))
            }
            val path = feature.config.string("path").resolveVariables(ctx.variables).trim()
            if (path.isBlank()) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.destination_path_empty"))
            }
            val headers = parseHeaders(feature.config.string("headers").resolveVariables(ctx.variables))
            val contentType = feature.config.string("contentType").resolveVariables(ctx.variables)
                .ifBlank { "application/octet-stream" }
            val maxBytes = httpDownloadLimit(feature.config["maxBytes"].numberOrNull())
            val connectTimeoutMs = feature.config.long("connectTimeoutMs", 10_000).coerceIn(1_000, 120_000).toInt()
            val readTimeoutMs = feature.config.long("readTimeoutMs", 60_000).coerceIn(1_000, 600_000).toInt()

            val result = withContext(Dispatchers.IO) {
                executeUpload(
                    url = url,
                    headers = headers,
                    source = File(path),
                    contentType = contentType,
                    maxBytes = maxBytes,
                    connectTimeoutMs = connectTimeoutMs,
                    readTimeoutMs = readTimeoutMs,
                )
            }
            if (result.success) {
                feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, result.value) }
            }
            result
        }
    }

    private fun executeRequest(
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
            val responseHeaders = responseHeaders(connection!!)
            val value = ConfigValue.ObjectValue(
                mapOf(
                    "statusCode" to ConfigValue.NumberValue(status.toDouble()),
                    "body" to ConfigValue.StringValue(responseBody),
                    "headers" to ConfigValue.ObjectValue(responseHeaders),
                    "url" to ConfigValue.StringValue(connection!!.url.toString()),
                )
            )
            ActionExecutionResult(status in 200..399, value, if (status in 200..399) null else userText("feature.http_status_failed", status))
        }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            .also { connection?.disconnect() }
    }

    private fun executeDownload(
        url: String,
        headers: Map<String, String>,
        destination: File,
        overwrite: Boolean,
        createParents: Boolean,
        maxBytes: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ActionExecutionResult {
        var connection: HttpURLConnection? = null
        var temporary: File? = null
        return runCatching {
            val target = destination.absoluteFile
            val parent = target.parentFile ?: throw IllegalArgumentException("Destination has no parent directory")
            if (createParents && !parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("Could not create destination directory")
            }
            require(parent.isDirectory) { "Destination directory does not exist" }
            if (target.exists() && !overwrite) {
                throw IllegalStateException("Destination already exists")
            }

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            val status = connection!!.responseCode
            if (status !in 200..299) {
                return@runCatching ActionExecutionResult(false, message = userText("feature.http_status_failed", status))
            }
            val declaredLength = connection!!.contentLengthLong
            require(declaredLength < 0 || declaredLength <= maxBytes) { "Response exceeds maximum bytes" }

            temporary = File(parent, ".${target.name}.part-${UUID.randomUUID()}")
            var written = 0L
            connection!!.inputStream.use { input ->
                temporary!!.outputStream().buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= maxBytes) { "Response exceeds maximum bytes" }
                        output.write(buffer, 0, count)
                    }
                }
            }

            if (target.exists() && !target.delete()) throw IllegalStateException("Could not replace destination")
            if (!temporary!!.renameTo(target)) {
                temporary!!.copyTo(target, overwrite = true)
                if (!temporary!!.delete()) temporary!!.deleteOnExit()
            }
            temporary = null

            val output = ConfigValue.ObjectValue(
                mapOf(
                    "statusCode" to ConfigValue.NumberValue(status.toDouble()),
                    "bytes" to ConfigValue.NumberValue(written.toDouble()),
                    "path" to ConfigValue.StringValue(target.absolutePath),
                    "headers" to ConfigValue.ObjectValue(responseHeaders(connection!!)),
                    "url" to ConfigValue.StringValue(connection!!.url.toString()),
                )
            )
            ActionExecutionResult(true, output)
        }.getOrElse { error ->
            ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
        }.also {
            runCatching { temporary?.delete() }
            connection?.disconnect()
        }
    }

    private fun executeUpload(
        url: String,
        headers: Map<String, String>,
        source: File,
        contentType: String,
        maxBytes: Long,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
    ): ActionExecutionResult {
        var connection: HttpURLConnection? = null
        return runCatching {
            val file = source.absoluteFile
            require(file.isFile && file.canRead()) { "Upload source is not a readable file" }
            val length = file.length()
            require(length in 0..maxBytes) { "Upload source exceeds maximum bytes" }

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "PUT"
                instanceFollowRedirects = true
                this.connectTimeout = connectTimeoutMs
                this.readTimeout = readTimeoutMs
                useCaches = false
                doOutput = true
                setFixedLengthStreamingMode(length)
                setRequestProperty("Content-Type", contentType)
                setRequestProperty("Accept-Encoding", "identity")
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }

            file.inputStream().buffered().use { input ->
                connection!!.outputStream.buffered().use { output ->
                    val buffer = ByteArray(32 * 1024)
                    var sent = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        sent += count
                        require(sent <= maxBytes) { "Upload source exceeds maximum bytes" }
                        output.write(buffer, 0, count)
                    }
                }
            }

            val status = connection!!.responseCode
            val stream = if (status >= 400) connection!!.errorStream else connection!!.inputStream
            val responseBody = stream?.use { readBounded(it, MAX_RESPONSE_BYTES) }.orEmpty()
            val value = ConfigValue.ObjectValue(
                mapOf(
                    "statusCode" to ConfigValue.NumberValue(status.toDouble()),
                    "bytes" to ConfigValue.NumberValue(length.toDouble()),
                    "path" to ConfigValue.StringValue(file.absolutePath),
                    "body" to ConfigValue.StringValue(responseBody),
                    "headers" to ConfigValue.ObjectValue(responseHeaders(connection!!)),
                    "url" to ConfigValue.StringValue(connection!!.url.toString()),
                )
            )
            ActionExecutionResult(
                status in 200..399,
                value,
                if (status in 200..399) null else userText("feature.http_status_failed", status),
            )
        }.getOrElse { error ->
            ActionExecutionResult(false, message = userText("feature.operation_failed", error.message ?: error.javaClass.simpleName))
        }.also { connection?.disconnect() }
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

    private fun responseHeaders(connection: HttpURLConnection): Map<String, ConfigValue> =
        connection.headerFields
            .filterKeys { it != null }
            .mapValues { (_, values) -> ConfigValue.StringValue(values.orEmpty().joinToString(", ")) }

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
        const val MAX_DOWNLOAD_BYTES = 1_073_741_824L
        val HEADER_NAME = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
    }
}

internal fun isHttpUrl(value: String): Boolean = value.startsWith("https://") || value.startsWith("http://")

internal fun httpDownloadLimit(value: Double?): Long =
    (value?.takeIf { it.isFinite() }?.toLong() ?: 104_857_600L).coerceIn(1L, 1_073_741_824L)
