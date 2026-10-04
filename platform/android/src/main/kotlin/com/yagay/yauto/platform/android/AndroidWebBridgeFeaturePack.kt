package com.yagay.yauto.platform.android

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** WebDAV and persistent WebSocket automation using one shared OkHttp client. */
class AndroidWebBridgeFeaturePack : FeaturePack {
    override val id: String = "android.web_bridge"
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val sockets = WebSocketController(client)

    override fun install(registry: FeatureRegistry) {
        registerWebDav(registry)
        registerWebSockets(registry)
    }

    private fun registerWebDav(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.webdav.request"), FeatureKind.ACTION,
                "WebDAV request", "Send WebDAV and extended HTTP methods with optional Basic authentication",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("url", "URL", true),
                    FieldSchema.Choice("method", "Method", true, listOf("GET", "HEAD", "PROPFIND", "MKCOL", "PUT", "DELETE", "MOVE", "COPY", "PATCH")),
                    FieldSchema.Text("body", "Request body", multiline = true),
                    FieldSchema.Text("contentType", "Content type"),
                    FieldSchema.Text("username", "Username"),
                    FieldSchema.Text("password", "Password"),
                    FieldSchema.Text("headers", "Headers, one per line", multiline = true),
                    FieldSchema.Text("destination", "Destination URL for MOVE/COPY"),
                    FieldSchema.Choice("depth", "WebDAV Depth", options = listOf("", "0", "1", "infinity")),
                    FieldSchema.Duration("timeoutMs", "Timeout"),
                    FieldSchema.Variable("resultVariable", "Store response object"),
                ),
                keywords = setOf("webdav", "propfind", "mkcol", "move", "copy", "http", "network"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val url = feature.config.string("url").resolveVariables(ctx.variables).trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.webdav_url_invalid"))
            }
            val method = feature.config.string("method", "PROPFIND").uppercase()
            val timeout = (feature.config["timeoutMs"].numberOrNull() ?: 60_000.0).toLong().coerceIn(1_000L, 300_000L)
            val request = runCatching {
                val builder = Request.Builder().url(url)
                parseHeaders(feature.config.string("headers").resolveVariables(ctx.variables)).forEach { (name, value) -> builder.addHeader(name, value) }
                val username = feature.config.string("username").resolveVariables(ctx.variables)
                val password = feature.config.string("password").resolveVariables(ctx.variables)
                if (username.isNotBlank()) builder.header("Authorization", Credentials.basic(username, password))
                val destination = feature.config.string("destination").resolveVariables(ctx.variables).trim()
                if (destination.isNotBlank()) builder.header("Destination", destination)
                val depth = feature.config.string("depth").trim()
                if (depth.isNotBlank()) builder.header("Depth", depth)
                val bodyText = feature.config.string("body").resolveVariables(ctx.variables)
                val contentType = feature.config.string("contentType").ifBlank { "application/octet-stream" }.toMediaTypeOrNull()
                val body = when {
                    method in setOf("GET", "HEAD") -> null
                    bodyText.isNotEmpty() -> bodyText.toRequestBody(contentType)
                    method in setOf("PUT", "PATCH", "PROPFIND") -> ByteArray(0).toRequestBody(contentType)
                    else -> null
                }
                builder.method(method, body).build()
            }.getOrElse { return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            val output = runCatching {
                withContext(Dispatchers.IO) {
                    client.newBuilder().callTimeout(timeout, TimeUnit.MILLISECONDS).build().newCall(request).execute().use(::responseValue)
                }
            }.getOrElse { return@registerAction ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerWebSockets(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.websocket.connect"), FeatureKind.ACTION,
                "Connect WebSocket", "Open or replace a named WebSocket connection",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("connectionId", "Connection ID", true),
                    FieldSchema.Text("url", "ws:// or wss:// URL", true),
                    FieldSchema.Text("headers", "Headers, one per line", multiline = true),
                ),
                keywords = setOf("websocket", "ws", "wss", "socket", "persistent"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val connectionId = feature.config.string("connectionId").resolveVariables(ctx.variables).trim()
            val url = feature.config.string("url").resolveVariables(ctx.variables).trim()
            if (connectionId.isBlank() || (!url.startsWith("ws://") && !url.startsWith("wss://"))) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.websocket_invalid"))
            }
            val ok = sockets.connect(connectionId, url, parseHeaders(feature.config.string("headers").resolveVariables(ctx.variables)))
            ActionExecutionResult(ok, ConfigValue.BooleanValue(ok), if (ok) null else userText("feature.websocket_open_failed"))
        }
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.websocket.send"), FeatureKind.ACTION,
                "Send WebSocket message", "Send a text message over a named WebSocket connection",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("connectionId", "Connection ID", true),
                    FieldSchema.Text("message", "Message", true, multiline = true),
                ),
                fieldBehaviors = mapOf("message" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("websocket", "send", "message"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val id = feature.config.string("connectionId").resolveVariables(ctx.variables).trim()
            val message = feature.config.string("message").resolveVariables(ctx.variables)
            val ok = sockets.send(id, message)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(ok), if (ok) null else userText("feature.websocket_not_connected"))
        }
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.websocket.close"), FeatureKind.ACTION,
                "Close WebSocket", "Close a named WebSocket connection",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("connectionId", "Connection ID", true),
                    FieldSchema.Number("code", "Close code", min = 1000.0, max = 4999.0),
                    FieldSchema.Text("reason", "Reason"),
                ),
                keywords = setOf("websocket", "close", "disconnect"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val socketId = feature.config.string("connectionId").resolveVariables(ctx.variables).trim()
            val code = (feature.config["code"].numberOrNull() ?: 1000.0).toInt().coerceIn(1000, 4999)
            val reason = feature.config.string("reason").resolveVariables(ctx.variables)
            val ok = sockets.close(socketId, code, reason)
            ActionExecutionResult(ok, ConfigValue.BooleanValue(ok), if (ok) null else userText("feature.websocket_unknown"))
        }
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.websocket.messages.get"), FeatureKind.ACTION,
                "Get WebSocket messages", "Read or drain queued text messages for a named WebSocket connection",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("connectionId", "Connection ID", true),
                    FieldSchema.Toggle("drain", "Remove returned messages"),
                    FieldSchema.Variable("resultVariable", "Store message list", true),
                ),
                keywords = setOf("websocket", "messages", "queue", "receive"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val socketId = feature.config.string("connectionId").resolveVariables(ctx.variables).trim()
            val messages = sockets.messages(socketId, feature.config.boolean("drain", true))
            val output = ConfigValue.ListValue(messages.map(ConfigValue::StringValue))
            feature.config.string("resultVariable").trim().takeIf { it.isNotBlank() }?.let { ctx.variables.set(it, output) }
            ActionExecutionResult(true, output)
        }

        val evaluator = ConditionEvaluator { feature, ctx ->
            val socketId = feature.config.string("connectionId").resolveVariables(ctx.variables).trim()
            sockets.connected(socketId) == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.websocket_connected"), FeatureKind.STATE,
            "WebSocket connected", "Check whether a named YAuto WebSocket is currently connected",
            FeatureCategory.NETWORK,
            fields = listOf(FieldSchema.Text("connectionId", "Connection ID", true), FieldSchema.Toggle("value", "Connected")),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.websocket_connected"), kind = FeatureKind.CONDITION), evaluator)

        registerSocketEvent(registry, "android.event.websocket_open", "WebSocket opened", false)
        registerSocketEvent(registry, "android.event.websocket.message", "WebSocket message", true)
        registerSocketEvent(registry, "android.event.websocket.closed", "WebSocket closed", false)
        registerSocketEvent(registry, "android.event.websocket.failure", "WebSocket failure", true)
    }

    private fun registerSocketEvent(registry: FeatureRegistry, typeId: String, title: String, textFilter: Boolean) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId(typeId), FeatureKind.EVENT, title, "Run when a named YAuto WebSocket changes or receives data",
                FeatureCategory.NETWORK,
                fields = buildList {
                    add(FieldSchema.Text("connectionId", "Connection ID"))
                    if (textFilter) add(FieldSchema.Text("textContains", "Text contains"))
                },
                keywords = setOf("websocket", "event", "message", "network"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != typeId) return@registerEvent false
            val wantedId = feature.config.string("connectionId").resolveVariables(ctx.variables).trim()
            if (wantedId.isNotBlank() && ctx.event.payload.string("connectionId") != wantedId) return@registerEvent false
            if (textFilter) {
                val wantedText = feature.config.string("textContains").resolveVariables(ctx.variables)
                if (wantedText.isNotBlank() && !ctx.event.payload.string("text").contains(wantedText, ignoreCase = true)) return@registerEvent false
            }
            true
        }
    }

    private fun responseValue(response: Response): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
        mapOf(
            "code" to ConfigValue.NumberValue(response.code.toDouble()),
            "successful" to ConfigValue.BooleanValue(response.isSuccessful),
            "message" to ConfigValue.StringValue(response.message),
            "body" to ConfigValue.StringValue(response.body?.string().orEmpty()),
            "headers" to ConfigValue.ObjectValue(response.headers.toMultimap().mapValues { (_, values) -> ConfigValue.StringValue(values.joinToString("\n")) }),
        )
    )
}

private class WebSocketController(private val client: OkHttpClient) {
    private data class Connection(
        val socket: WebSocket,
        val messages: ConcurrentLinkedQueue<String>,
        @Volatile var connected: Boolean = false,
    )

    private val connections = ConcurrentHashMap<String, Connection>()

    fun connect(id: String, url: String, headers: Map<String, String>): Boolean = runCatching {
        connections.remove(id)?.socket?.cancel()
        val request = Request.Builder().url(url).apply { headers.forEach { (name, value) -> addHeader(name, value) } }.build()
        val queue = ConcurrentLinkedQueue<String>()
        lateinit var socket: WebSocket
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connections[id]?.connected = true
                AdvancedParityRuntimeBridge.emit("android.event.websocket_open", id, "", response.code, "")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                queue.add(text)
                while (queue.size > 1000) queue.poll()
                AdvancedParityRuntimeBridge.emit("android.event.websocket.message", id, text, 0, "")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                AdvancedParityRuntimeBridge.emit("android.event.websocket_closed", id, "", code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connections.remove(id)
                AdvancedParityRuntimeBridge.emit("android.event.websocket_closed", id, "", code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connections.remove(id)
                AdvancedParityRuntimeBridge.emit("android.event.websocketfailure", id, t.message.orEmpty(), response?.code ?: 0, t.javaClass.simpleName)
            }
        }
        socket = client.newWebSocket(request, listener)
        connections[id] = Connection(socket, queue)
        true
    }.getOrDefault(false)

    fun send(id: String, text: String): Boolean = connections[id]?.takeIf { it.connected }?.socket?.send(text) ?: false
    fun close(id: String, code: Int, reason: String): Boolean {
        val connection = connections[id] ?: return false
        val accepted = connection.socket.close(code, reason.take(123))
        if (!accepted) connections.remove(id)
        return accepted
    }
    fun connected(id: String): Boolean = connections[id]?.connected == true
    fun messages(id: String, drain: Boolean): List<String> {
        val queue = connections[id]?.messages ?: return emptyList()
        if (!drain) return queue.toList()
        val result = ArrayList<String>()
        while (true) result += queue.poll() ?: break
        return result
    }
}

object AdvancedParityRuntimeBridge {
    @Volatile private var emitter: RuntimeEventEmitter? = null
    fun attach(value: RuntimeEventEmitter?) { emitter = value }
    fun emit(typeId: String, connectionId: String, text: String, code: Int, reason: String) {
        emitter?.emit(
            RuntimeEvent(
                typeId = typeId,
                payload = mapOf(
                    "connectionId" to ConfigValue.StringValue(connectionId),
                    "text" to ConfigValue.StringValue(text),
                    "code" to ConfigValue.NumberValue(code.toDouble()),
                    "reason" to ConfigValue.StringValue(reason),
                ),
                source = "android.web_bridge",
            )
        )
    }
}

internal fun parseHeaders(raw: String): Map<String, String> = buildMap {
    raw.lineSequence().forEach { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return@forEach
        val name = line.substring(0, separator).trim()
        val value = line.substring(separator + 1).trim()
        if (name.matches(Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]+")) && value.indexOf('\r') < 0 && value.indexOf('\n') < 0) put(name, value)
    }
}
