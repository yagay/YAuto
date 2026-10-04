package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AndroidHttpServerFeaturePack : FeaturePack {
    override val id: String = "android.http_server"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.http_server.start"), FeatureKind.ACTION,
                "Start HTTP server", "Start YAuto's embedded HTTP/Webhook server",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("port", "Port", true, min = 1024.0, max = 65535.0),
                    FieldSchema.Choice("bind", "Bind address", true, listOf("localhost", "all")),
                    FieldSchema.Number("responseTimeoutMs", "Automation response timeout", min = 100.0, max = 60_000.0),
                    FieldSchema.Number("defaultStatus", "Default status", min = 100.0, max = 599.0),
                    FieldSchema.Text("defaultBody", "Default response body", multiline = true),
                ),
                keywords = setOf("http server", "webhook", "listener", "api", "request"),
                ownerPackId = id,
            )
        ) { feature, _ ->
            val port = (feature.config["port"].numberOrNull() ?: 8765.0).toInt()
            val bind = feature.config.string("bind", "localhost")
            val timeout = (feature.config["responseTimeoutMs"].numberOrNull() ?: 10_000.0).toLong().coerceIn(100L, 60_000L)
            val status = (feature.config["defaultStatus"].numberOrNull() ?: 200.0).toInt().coerceIn(100, 599)
            val body = feature.config.string("defaultBody")
            val ok = HttpServerRuntime.start(port, bind == "all", timeout, status, body)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(ok), if (ok) null else userText("feature.http_server_start_failed"))
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.http_server.stop"), FeatureKind.ACTION,
                "Stop HTTP server", "Stop YAuto's embedded HTTP/Webhook server",
                FeatureCategory.NETWORK,
                keywords = setOf("http server", "webhook", "stop"),
                ownerPackId = id,
            )
        ) { _, _ ->
            HttpServerRuntime.stop()
            ActionExecutionResult(true)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.http_server.respond"), FeatureKind.ACTION,
                "Respond to HTTP request", "Complete a pending HTTP/Webhook request using its request ID",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("requestId", "Request ID", true),
                    FieldSchema.Number("status", "HTTP status", min = 100.0, max = 599.0),
                    FieldSchema.Text("contentType", "Content type"),
                    FieldSchema.Text("body", "Response body", multiline = true),
                ),
                fieldBehaviors = mapOf(
                    "requestId" to FieldBehavior(supportsVariables = true),
                    "body" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("http", "webhook", "response", "server"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val id = feature.config.string("requestId").resolveVariables(ctx.variables).trim()
            val status = (feature.config["status"].numberOrNull() ?: 200.0).toInt().coerceIn(100, 599)
            val contentType = feature.config.string("contentType").ifBlank { "text/plain; charset=utf-8" }
            val body = feature.config.string("body").resolveVariables(ctx.variables)
            val ok = HttpServerRuntime.respond(id, status, contentType, body)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(ok), if (ok) null else userText("feature.http_server_request_unknown"))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.http_server_request"), FeatureKind.EVENT,
                "HTTP server request", "Run when YAuto receives an inbound HTTP/Webhook request",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("method", "Method", true, listOf("any", "GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")),
                    FieldSchema.Text("path", "Path contains"),
                    FieldSchema.Text("bodyContains", "Body contains"),
                ),
                keywords = setOf("http server", "webhook", "request", "incoming", "api"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.http_server_request") return@registerEvent false
            val method = feature.config.string("method", "any")
            if (method != "any" && ctx.event.payload.string("method") != method) return@registerEvent false
            val path = feature.config.string("path")
            if (path.isNotBlank() && !ctx.event.payload.string("path").contains(path, ignoreCase = true)) return@registerEvent false
            val body = feature.config.string("bodyContains")
            body.isBlank() || ctx.event.payload.string("body").contains(body, ignoreCase = true)
        }

        val evaluator = ConditionEvaluator { feature, _ ->
            HttpServerRuntime.running.get() == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.http_server_running"), FeatureKind.STATE,
            "HTTP server running", "Check whether YAuto's embedded HTTP server is running",
            FeatureCategory.NETWORK,
            fields = listOf(FieldSchema.Toggle("value", "Running")),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.http_server_running"), kind = FeatureKind.CONDITION), evaluator)
    }
}

class HttpServerEventSource : AndroidEventSource {
    override val id: String = "android.http_server"
    override fun start(emitter: RuntimeEventEmitter) { HttpServerRuntime.emitter = emitter }
    override fun stop() { HttpServerRuntime.emitter = null; HttpServerRuntime.stop() }
}

private data class HttpResponse(val status: Int, val contentType: String, val body: String)

private object HttpServerRuntime {
    val running = AtomicBoolean(false)
    @Volatile var emitter: RuntimeEventEmitter? = null
    private val executor = Executors.newCachedThreadPool()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<HttpResponse>>()
    @Volatile private var server: ServerSocket? = null
    @Volatile private var responseTimeoutMs: Long = 10_000L
    @Volatile private var defaultStatus: Int = 200
    @Volatile private var defaultBody: String = ""

    @Synchronized
    fun start(port: Int, bindAll: Boolean, timeoutMs: Long, status: Int, body: String): Boolean {
        if (port !in 1024..65535) return false
        stop()
        return runCatching {
            val address = InetAddress.getByName(if (bindAll) "0.0.0.0" else "127.0.0.1")
            val socket = ServerSocket(port, 50, address)
            responseTimeoutMs = timeoutMs
            defaultStatus = status
            defaultBody = body
            server = socket
            running.set(true)
            executor.execute {
                while (running.get()) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    executor.execute { handle(client) }
                }
            }
            true
        }.getOrDefault(false)
    }

    @Synchronized
    fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        pending.values.forEach { if (!it.isCompleted) it.complete(HttpResponse(503, "text/plain; charset=utf-8", "Server stopped")) }
        pending.clear()
    }

    fun respond(requestId: String, status: Int, contentType: String, body: String): Boolean {
        val deferred = pending.remove(requestId) ?: return false
        return deferred.complete(HttpResponse(status, contentType, body))
    }

    private fun handle(socket: Socket) {
        socket.use { client ->
            runCatching {
                client.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size < 2) return
                val method = parts[0].uppercase()
                val rawTarget = parts[1]
                val headers = linkedMapOf<String, String>()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val sep = line.indexOf(':')
                    if (sep > 0) headers[line.substring(0, sep).trim()] = line.substring(sep + 1).trim()
                }
                val contentLength = headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value?.toIntOrNull()?.coerceIn(0, 1_048_576) ?: 0
                val chars = CharArray(contentLength)
                var read = 0
                while (read < contentLength) {
                    val n = reader.read(chars, read, contentLength - read)
                    if (n <= 0) break
                    read += n
                }
                val body = String(chars, 0, read)
                val requestId = UUID.randomUUID().toString()
                val deferred = CompletableDeferred<HttpResponse>()
                pending[requestId] = deferred
                emitter?.emit(
                    RuntimeEvent(
                        typeId = "android.event.http_server_request",
                        payload = mapOf(
                            "requestId" to ConfigValue.StringValue(requestId),
                            "method" to ConfigValue.StringValue(method),
                            "target" to ConfigValue.StringValue(rawTarget),
                            "path" to ConfigValue.StringValue(rawTarget.substringBefore('?')),
                            "query" to ConfigValue.StringValue(rawTarget.substringAfter('?', "")),
                            "body" to ConfigValue.StringValue(body),
                            "remoteAddress" to ConfigValue.StringValue(client.inetAddress?.hostAddress.orEmpty()),
                            "headers" to ConfigValue.ObjectValue(headers.mapValues { ConfigValue.StringValue(it.value) }),
                        ),
                        source = "android.http_server",
                    )
                )
                val response = runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(responseTimeoutMs) { deferred.await() }
                } ?: HttpResponse(defaultStatus, "text/plain; charset=utf-8", defaultBody)
                pending.remove(requestId)
                writeResponse(client, response)
            }
        }
    }

    private fun writeResponse(socket: Socket, response: HttpResponse) {
        val bytes = response.body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (response.status) {
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
            409 -> "Conflict"; 500 -> "Internal Server Error"; 503 -> "Service Unavailable"
            else -> "Response"
        }
        val output = socket.getOutputStream()
        val headers = buildString {
            append("HTTP/1.1 ${response.status} $reason\\r\\n")
            append("Content-Type: ${response.contentType}\\r\\n")
            append("Content-Length: ${bytes.size}\\r\\n")
            append("Connection: close\\r\\n\\r\\n")
        }.toByteArray(StandardCharsets.UTF_8)
        output.write(headers)
        output.write(bytes)
        output.flush()
    }
}
