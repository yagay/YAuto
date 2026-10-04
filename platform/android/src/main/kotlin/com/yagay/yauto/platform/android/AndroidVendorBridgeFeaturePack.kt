package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*
import java.util.UUID

object VendorBridgeRuntime {
    @Volatile private var emitter: RuntimeEventEmitter? = null

    fun attach(value: RuntimeEventEmitter?) {
        emitter = value
    }

    internal fun emit(event: RuntimeEvent) {
        emitter?.emit(event)
    }
}

class PebbleBridgeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("yauto_vendor_bridge", Context.MODE_PRIVATE)
        when (intent.action) {
            ACTION_CONNECTED -> {
                prefs.edit().putBoolean("pebble_connected", true).apply()
                VendorBridgeRuntime.emit(RuntimeEvent("android.event.pebble_connected", source = "pebble"))
            }
            ACTION_DISCONNECTED -> {
                prefs.edit().putBoolean("pebble_connected", false).apply()
                VendorBridgeRuntime.emit(RuntimeEvent("android.event.pebble_disconnected", source = "pebble"))
            }
            ACTION_RECEIVE -> {
                val data = intent.getByteArrayExtra("msg_data") ?: ByteArray(0)
                VendorBridgeRuntime.emit(
                    RuntimeEvent(
                        "android.event.pebble_data",
                        mapOf(
                            "uuid" to ConfigValue.StringValue(intent.getStringExtra("uuid").orEmpty()),
                            "transactionId" to ConfigValue.NumberValue(
                                intent.getIntExtra("transaction_id", -1).toDouble()
                            ),
                            "base64" to ConfigValue.StringValue(
                                android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
                            ),
                        ),
                        source = "pebble",
                    )
                )
            }
        }
    }

    companion object {
        const val ACTION_CONNECTED = "com.getpebble.action.PEBBLE_CONNECTED"
        const val ACTION_DISCONNECTED = "com.getpebble.action.PEBBLE_DISCONNECTED"
        const val ACTION_RECEIVE = "com.getpebble.action.app.RECEIVE"
    }
}

class AndroidVendorBridgeFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.vendor_bridges"
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("yauto_vendor_bridge", Context.MODE_PRIVATE)

    override fun install(registry: FeatureRegistry) {
        registerSamsungRoutines(registry)
        registerPebble(registry)
    }

    private fun registerSamsungRoutines(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.samsung_routines.list"),
                FeatureKind.ACTION,
                "List Samsung Routines",
                "Query manual routines exposed by Samsung Modes and Routines",
                FeatureCategory.COMPATIBILITY,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store routines", true)),
                keywords = setOf("samsung routines", "modes and routines", "bixby", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = querySamsungRoutines()
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.samsung_routines.control"),
                FeatureKind.ACTION,
                "Control Samsung Routine",
                "Start, stop or toggle a Samsung manual routine by UUID/name",
                FeatureCategory.COMPATIBILITY,
                fields = listOf(
                    FieldSchema.Choice("operation", "Operation", true, listOf("start", "stop", "toggle")),
                    FieldSchema.Text("routineUuid", "Routine UUID"),
                    FieldSchema.Text("routineName", "Routine name"),
                ),
                fieldBehaviors = mapOf(
                    "routineUuid" to FieldBehavior(supportsVariables = true),
                    "routineName" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("samsung routines", "modes and routines", "start routine", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val uuid = feature.config.string("routineUuid").resolveVariables(ctx.variables).trim()
            val name = feature.config.string("routineName").resolveVariables(ctx.variables).trim()
            if (uuid.isBlank() && name.isBlank()) return@registerAction ActionExecutionResult(false)
            val operation = feature.config.string("operation", "toggle")
            val method = when (operation) {
                "start" -> "start_manual_routine"
                "stop" -> "end_manual_routine"
                else -> "toggle_manual_routine"
            }
            val extras = Bundle().apply {
                if (uuid.isNotBlank()) {
                    putString("EXTRA_KEY_ROUTINE_UUID", uuid)
                    putString("routineUuid", uuid)
                    putString("routine_uuid", uuid)
                }
                if (name.isNotBlank()) {
                    putString("EXTRA_KEY_ROUTINE_NAME", name)
                    putString("routineName", name)
                    putString("routine_name", name)
                }
            }

            val providerOk = SAMSUNG_CALL_URIS.any { uri ->
                runCatching {
                    context.contentResolver.call(Uri.parse(uri), method, uuid.ifBlank { name }, extras)
                    true
                }.getOrDefault(false)
            }
            if (providerOk) return@registerAction ActionExecutionResult(true)

            val launched = runCatching {
                context.startActivity(
                    Intent().apply {
                        setClassName(
                            SAMSUNG_PACKAGE,
                            "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity",
                        )
                        action = method
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        extras.keySet().forEach { key -> putExtra(key, extras.getString(key)) }
                    }
                )
                true
            }.getOrDefault(false)
            ActionExecutionResult(launched)
        }
    }

    private fun registerPebble(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.pebble.notification"),
                FeatureKind.ACTION,
                "Pebble notification",
                "Send a legacy Pebble notification through the installed Pebble companion",
                FeatureCategory.COMPATIBILITY,
                fields = listOf(
                    FieldSchema.Text("title", "Title", true),
                    FieldSchema.Text("body", "Body", true, multiline = true),
                    FieldSchema.Text("sender", "Sender"),
                ),
                fieldBehaviors = mapOf(
                    "title" to FieldBehavior(supportsVariables = true),
                    "body" to FieldBehavior(supportsVariables = true),
                    "sender" to FieldBehavior(supportsVariables = true),
                ),
                keywords = setOf("pebble", "watch", "notification", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val title = feature.config.string("title").resolveVariables(ctx.variables)
            val body = feature.config.string("body").resolveVariables(ctx.variables)
            val sender = feature.config.string("sender", "YAuto").resolveVariables(ctx.variables)
            val data = "[{\"title\":\"" + jsonEscape(title) + "\",\"body\":\"" +
                jsonEscape(body) + "\"}]"
            runCatching {
                context.sendBroadcast(
                    Intent("com.getpebble.action.SEND_NOTIFICATION")
                        .putExtra("messageType", "PEBBLE_ALERT")
                        .putExtra("sender", sender)
                        .putExtra("notificationData", data)
                )
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.pebble.app_data.send"),
                FeatureKind.ACTION,
                "Send Pebble app data",
                "Send raw app data to a Pebble watch application",
                FeatureCategory.COMPATIBILITY,
                fields = listOf(
                    FieldSchema.Text("appUuid", "Pebble app UUID", true),
                    FieldSchema.Number("transactionId", "Transaction ID", min = 0.0),
                    FieldSchema.Text("base64", "Payload Base64", true, multiline = true),
                ),
                fieldBehaviors = mapOf("base64" to FieldBehavior(supportsVariables = true)),
                keywords = setOf("pebble", "app data", "watch", "macrodroid"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val uuidText = feature.config.string("appUuid").trim()
            val uuid = runCatching { UUID.fromString(uuidText) }.getOrNull()
                ?: return@registerAction ActionExecutionResult(false)
            val bytes = runCatching {
                android.util.Base64.decode(
                    feature.config.string("base64").resolveVariables(ctx.variables),
                    android.util.Base64.DEFAULT,
                )
            }.getOrNull() ?: return@registerAction ActionExecutionResult(false)
            val transaction = ((feature.config["transactionId"] as? ConfigValue.NumberValue)?.value ?: 1.0)
                .toInt().coerceAtLeast(0)
            runCatching {
                context.sendBroadcast(
                    Intent("com.getpebble.action.app.SEND")
                        .putExtra("uuid", uuid)
                        .putExtra("transaction_id", transaction)
                        .putExtra("msg_data", bytes)
                )
                ActionExecutionResult(true)
            }.getOrElse { ActionExecutionResult(false, message = userText("feature.operation_failed", it.message ?: it.javaClass.simpleName)) }
        }

        val connectedEvaluator = ConditionEvaluator { feature, _ ->
            prefs.getBoolean("pebble_connected", false) == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.pebble_connected"),
            FeatureKind.STATE,
            "Pebble connected",
            "Check legacy Pebble companion connection state",
            FeatureCategory.COMPATIBILITY,
            fields = listOf(FieldSchema.Toggle("value", "Connected")),
            keywords = setOf("pebble", "connected", "watch", "macrodroid"),
            ownerPackId = id,
        )
        registry.registerState(state, connectedEvaluator)
        registry.registerCondition(
            state.copy(id = FeatureId("android.condition.pebble_connected"), kind = FeatureKind.CONDITION),
            connectedEvaluator,
        )

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.pebble_connected"),
                FeatureKind.EVENT,
                "Pebble connected",
                "Run when the Pebble companion reports a connection",
                FeatureCategory.COMPATIBILITY,
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.pebble_connected" }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.pebble_disconnected"),
                FeatureKind.EVENT,
                "Pebble disconnected",
                "Run when the Pebble companion reports a disconnection",
                FeatureCategory.COMPATIBILITY,
                ownerPackId = id,
            )
        ) { _, ctx -> ctx.event.typeId == "android.event.pebble_disconnected" }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.pebble_data"),
                FeatureKind.EVENT,
                "Pebble app data",
                "Run when raw app data is received from a Pebble application",
                FeatureCategory.COMPATIBILITY,
                fields = listOf(FieldSchema.Text("appUuid", "Pebble app UUID")),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            ctx.event.typeId == "android.event.pebble_data" &&
                (feature.config.string("appUuid").isBlank() ||
                    ctx.event.payload.string("uuid").equals(feature.config.string("appUuid"), true))
        }
    }

    private fun querySamsungRoutines(): ConfigValue.ListValue {
        val seen = linkedSetOf<String>()
        val items = mutableListOf<ConfigValue>()
        SAMSUNG_QUERY_URIS.forEach { uri ->
            runCatching {
                context.contentResolver.query(Uri.parse(uri), null, null, null, null)?.use { cursor ->
                    val names = cursor.columnNames
                    while (cursor.moveToNext()) {
                        val row = buildMap<String, ConfigValue> {
                            names.forEachIndexed { index, column ->
                                val value = when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_INTEGER ->
                                        ConfigValue.NumberValue(cursor.getLong(index).toDouble())
                                    android.database.Cursor.FIELD_TYPE_FLOAT ->
                                        ConfigValue.NumberValue(cursor.getDouble(index))
                                    android.database.Cursor.FIELD_TYPE_STRING ->
                                        ConfigValue.StringValue(cursor.getString(index).orEmpty())
                                    android.database.Cursor.FIELD_TYPE_BLOB ->
                                        ConfigValue.StringValue(
                                            android.util.Base64.encodeToString(
                                                cursor.getBlob(index),
                                                android.util.Base64.NO_WRAP,
                                            )
                                        )
                                    else -> ConfigValue.NullValue
                                }
                                put(column, value)
                            }
                        }
                        val key = row.toString()
                        if (seen.add(key)) items += ConfigValue.ObjectValue(row)
                    }
                }
            }
        }
        return ConfigValue.ListValue(items)
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private companion object {
        const val SAMSUNG_PACKAGE = "com.samsung.android.app.routines"
        val SAMSUNG_QUERY_URIS = listOf(
            "content://com.samsung.android.app.routines.externalprovider/routine_list?routine_type=normal&condition_type=manual",
            "content://com.samsung.android.app.routines.domainmodel.routineinfoprovider/manual_enabled_routines",
            "content://com.samsung.android.app.routines.domainmodel.routinetestprovider/routine_uuid_list?condition_type=manual",
        )
        val SAMSUNG_CALL_URIS = listOf(
            "content://com.samsung.android.app.routines.externalprovider",
            "content://com.samsung.android.app.routines.domainmodel.routinetestprovider",
        )
    }
}
