package com.yagay.yauto.platform.android

import android.content.Context
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object WearRuntimeBridge {
    @Volatile private var emitter: RuntimeEventEmitter? = null

    fun attach(value: RuntimeEventEmitter?) {
        emitter = value
    }

    internal fun emit(event: RuntimeEvent) {
        emitter?.emit(event)
    }
}

class YAutoWearableListenerService : WearableListenerService() {
    override fun onMessageReceived(messageEvent: MessageEvent) {
        val bytes = messageEvent.data ?: ByteArray(0)
        WearRuntimeBridge.emit(
            RuntimeEvent(
                typeId = "android.event.wear_message",
                payload = mapOf(
                    "nodeId" to ConfigValue.StringValue(messageEvent.sourceNodeId.orEmpty()),
                    "path" to ConfigValue.StringValue(messageEvent.path.orEmpty()),
                    "text" to ConfigValue.StringValue(runCatching { bytes.toString(Charsets.UTF_8) }.getOrDefault("")),
                    "base64" to ConfigValue.StringValue(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                ),
                source = "wear.data_layer",
            )
        )
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        try {
            dataEvents.forEach { event ->
                if (event.type != DataEvent.TYPE_CHANGED) return@forEach
                val item = event.dataItem
                val dataMap = runCatching { DataMapItem.fromDataItem(item).dataMap }.getOrNull()
                WearRuntimeBridge.emit(
                    RuntimeEvent(
                        typeId = "android.event.wear_data_changed",
                        payload = buildMap {
                            put("nodeId", ConfigValue.StringValue(item.uri.host.orEmpty()))
                            put("path", ConfigValue.StringValue(item.uri.path.orEmpty()))
                            put("uri", ConfigValue.StringValue(item.uri.toString()))
                            dataMap?.keySet()?.forEach { key ->
                                val value = dataMap.get(key)
                                put("data." + key, wearValue(value))
                            }
                        },
                        source = "wear.data_layer",
                    )
                )
            }
        } finally {
            dataEvents.release()
        }
    }

    private fun wearValue(value: Any?): ConfigValue = when (value) {
        null -> ConfigValue.NullValue
        is Boolean -> ConfigValue.BooleanValue(value)
        is Byte -> ConfigValue.NumberValue(value.toDouble())
        is Short -> ConfigValue.NumberValue(value.toDouble())
        is Int -> ConfigValue.NumberValue(value.toDouble())
        is Long -> ConfigValue.NumberValue(value.toDouble())
        is Float -> ConfigValue.NumberValue(value.toDouble())
        is Double -> ConfigValue.NumberValue(value)
        is String -> ConfigValue.StringValue(value)
        is ByteArray -> ConfigValue.StringValue(Base64.encodeToString(value, Base64.NO_WRAP))
        is ArrayList<*> -> ConfigValue.ListValue(value.map(::wearValue))
        else -> ConfigValue.StringValue(value.toString())
    }
}

class AndroidWearFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.wear"
    private val context = context.applicationContext
    private val nodeClient = Wearable.getNodeClient(this.context)
    private val messageClient = Wearable.getMessageClient(this.context)
    private val dataClient = Wearable.getDataClient(this.context)

    override fun install(registry: FeatureRegistry) {
        registerNodes(registry)
        registerMessage(registry)
        registerData(registry)
        registerEvents(registry)
        registerConnectedCondition(registry)
    }

    private fun registerNodes(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wear.nodes.query"),
                FeatureKind.ACTION,
                "Query Wear OS nodes",
                "Return connected Wear OS Data Layer nodes",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store nodes", true)),
                keywords = setOf("wear os", "android wear", "watch", "nodes", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val nodes = withContext(Dispatchers.IO) {
                runCatching {
                    Tasks.await(nodeClient.connectedNodes, 10, TimeUnit.SECONDS)
                }.getOrDefault(emptyList())
            }
            val output = ConfigValue.ListValue(
                nodes.map { node ->
                    ConfigValue.ObjectValue(
                        mapOf(
                            "id" to ConfigValue.StringValue(node.id),
                            "name" to ConfigValue.StringValue(node.displayName.orEmpty()),
                            "nearby" to ConfigValue.BooleanValue(node.isNearby),
                        )
                    )
                }
            )
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerMessage(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wear.message.send"),
                FeatureKind.ACTION,
                "Send Wear OS message",
                "Send an ephemeral Data Layer message to one or all connected Wear OS nodes",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("nodeId", "Node ID (blank = all connected)"),
                    FieldSchema.Text("path", "Message path", true),
                    FieldSchema.Text("payload", "Text / Base64 payload", multiline = true),
                    FieldSchema.Choice("encoding", "Encoding", options = listOf("text", "base64")),
                    FieldSchema.Variable("resultVariable", "Store sent message IDs"),
                ),
                fieldBehaviors = mapOf(
                    "nodeId" to FieldBehavior(supportsVariables = true),
                    "path" to FieldBehavior(supportsVariables = true),
                    "payload" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("wear os", "android wear", "message", "watch", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val path = normalizePath(feature.config.string("path").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val payloadRaw = feature.config.string("payload").resolveVariables(ctx.variables)
            val bytes = if (feature.config.string("encoding", "text") == "base64") {
                runCatching { Base64.decode(payloadRaw, Base64.DEFAULT) }.getOrNull()
                    ?: return@registerAction ActionExecutionResult(false)
            } else payloadRaw.toByteArray(Charsets.UTF_8)
            val requested = feature.config.string("nodeId").resolveVariables(ctx.variables).trim()
            val nodes = withContext(Dispatchers.IO) {
                if (requested.isNotBlank()) listOf(requested)
                else runCatching {
                    Tasks.await(nodeClient.connectedNodes, 10, TimeUnit.SECONDS).map { it.id }
                }.getOrDefault(emptyList())
            }
            if (nodes.isEmpty()) return@registerAction ActionExecutionResult(false)
            val ids = withContext(Dispatchers.IO) {
                nodes.mapNotNull { nodeId ->
                    runCatching {
                        Tasks.await(messageClient.sendMessage(nodeId, path, bytes), 15, TimeUnit.SECONDS)
                    }.getOrNull()
                }
            }
            val output = ConfigValue.ListValue(ids.map { ConfigValue.NumberValue(it.toDouble()) })
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(ids.isNotEmpty(), output)
        }
    }

    private fun registerData(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wear.data.put"),
                FeatureKind.ACTION,
                "Put Wear OS data item",
                "Create or update a synchronized Wear OS Data Layer data item",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("path", "Data path", true),
                    FieldSchema.Text("payload", "Payload", multiline = true),
                    FieldSchema.Variable("resultVariable", "Store data URI"),
                ),
                fieldBehaviors = mapOf(
                    "path" to FieldBehavior(supportsVariables = true),
                    "payload" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("wear os", "android wear", "data item", "watch"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val path = normalizePath(feature.config.string("path").resolveVariables(ctx.variables))
                ?: return@registerAction ActionExecutionResult(false)
            val request = PutDataMapRequest.create(path).apply {
                dataMap.putString("payload", feature.config.string("payload").resolveVariables(ctx.variables))
                dataMap.putLong("timestamp", System.currentTimeMillis())
            }.asPutDataRequest().setUrgent()
            val item = withContext(Dispatchers.IO) {
                runCatching { Tasks.await(dataClient.putDataItem(request), 15, TimeUnit.SECONDS) }.getOrNull()
            } ?: return@registerAction ActionExecutionResult(false)
            val output = ConfigValue.StringValue(item.uri.toString())
            feature.config.string("resultVariable").trim().takeIf(String::isNotBlank)?.let {
                ctx.variables.set(it, output)
            }
            ActionExecutionResult(true, output)
        }
    }

    private fun registerEvents(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.wear_message"),
                FeatureKind.EVENT,
                "Wear OS message",
                "Run when the phone receives a Wear OS Data Layer message",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("path", "Path"),
                    FieldSchema.Text("nodeId", "Node ID"),
                ),
                keywords = setOf("wear os", "android wear", "message", "watch", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.wear_message") return@registerEvent false
            matches(feature, ctx.event.payload)
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.wear_data_changed"),
                FeatureKind.EVENT,
                "Wear OS data changed",
                "Run when a Wear OS Data Layer item changes",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Text("path", "Path"),
                    FieldSchema.Text("nodeId", "Node ID"),
                ),
                keywords = setOf("wear os", "android wear", "data changed", "watch"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.wear_data_changed") return@registerEvent false
            matches(feature, ctx.event.payload)
        }
    }

    private fun registerConnectedCondition(registry: FeatureRegistry) {
        val evaluator = ConditionEvaluator { feature, _ ->
            val expectedNode = feature.config.string("nodeId").trim()
            val nodes = withContext(Dispatchers.IO) {
                runCatching {
                    Tasks.await(nodeClient.connectedNodes, 10, TimeUnit.SECONDS)
                }.getOrDefault(emptyList())
            }
            if (expectedNode.isBlank()) nodes.isNotEmpty()
            else nodes.any { it.id == expectedNode || it.displayName.equals(expectedNode, true) }
        }
        val descriptor = FeatureDescriptor(
            FeatureId("android.condition.wear_connected"),
            FeatureKind.CONDITION,
            "Wear OS connected",
            "Check whether any or a selected Wear OS node is connected",
            FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Text("nodeId", "Node ID / display name")),
            keywords = setOf("wear os", "android wear", "connected", "watch"),
            ownerPackId = id,
        )
        registry.registerCondition(descriptor, evaluator)
        registry.registerState(
            descriptor.copy(id = FeatureId("android.state.wear_connected"), kind = FeatureKind.STATE),
            evaluator,
        )
    }

    private fun matches(
        feature: com.yagay.yauto.core.model.FeatureRef,
        payload: Map<String, ConfigValue>,
    ): Boolean {
        val path = feature.config.string("path").trim()
        val nodeId = feature.config.string("nodeId").trim()
        return (path.isBlank() || payload.string("path") == path) &&
            (nodeId.isBlank() || payload.string("nodeId") == nodeId)
    }

    private fun normalizePath(raw: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        val path = if (value.startsWith('/')) value else "/" + value
        return path.takeIf { it.length <= 512 && !it.contains("..") }
    }
}
