package com.yagay.yauto.platform.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.RuntimeEvent
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*
import java.util.concurrent.atomic.AtomicReference

object TetheringRuntimeState {
    data class Snapshot(
        val interfaces: Set<String> = emptySet(),
        val types: Set<String> = emptySet(),
        val wifiApState: Int = -1,
    ) {
        val active: Boolean get() = interfaces.isNotEmpty() || wifiApState == WIFI_AP_STATE_ENABLED || wifiApState == WIFI_AP_STATE_ENABLING
        fun active(type: String): Boolean = when (type) {
            "any" -> active
            "wifi" -> "wifi" in types || wifiApState == WIFI_AP_STATE_ENABLED || wifiApState == WIFI_AP_STATE_ENABLING
            else -> type in types
        }
    }

    private val current = AtomicReference(Snapshot())
    fun snapshot(): Snapshot = current.get()
    fun update(value: Snapshot) { current.set(value) }

    private const val WIFI_AP_STATE_ENABLING = 12
    private const val WIFI_AP_STATE_ENABLED = 13
}

class TetheringEventSource(context: Context) : AndroidEventSource {
    override val id: String = "android.tethering"
    private val context = context.applicationContext
    private var receiver: BroadcastReceiver? = null

    override fun start(emitter: RuntimeEventEmitter) {
        if (receiver != null) return
        val next = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                val previous = TetheringRuntimeState.snapshot()
                val interfaces = extractInterfaces(intent).ifEmpty { previous.interfaces }
                val wifiState = intent.getIntExtra("wifi_state", intent.getIntExtra("wifiApState", previous.wifiApState))
                val types = interfaces.map(::tetherType).filter { it != "other" }.toSet()
                val snapshot = TetheringRuntimeState.Snapshot(interfaces, types, wifiState)
                TetheringRuntimeState.update(snapshot)
                emitter.emit(
                    RuntimeEvent(
                        typeId = "android.event.tethering_changed",
                        payload = mapOf(
                            "active" to ConfigValue.BooleanValue(snapshot.active),
                            "interfaces" to ConfigValue.ListValue(snapshot.interfaces.sorted().map(ConfigValue::StringValue)),
                            "types" to ConfigValue.ListValue(snapshot.types.sorted().map(ConfigValue::StringValue)),
                            "wifiActive" to ConfigValue.BooleanValue(snapshot.active("wifi")),
                            "usbActive" to ConfigValue.BooleanValue(snapshot.active("usb")),
                            "bluetoothActive" to ConfigValue.BooleanValue(snapshot.active("bluetooth")),
                            "wifiApState" to ConfigValue.NumberValue(snapshot.wifiApState.toDouble()),
                            "action" to ConfigValue.StringValue(intent.action.orEmpty()),
                        ),
                        source = id,
                    )
                )
            }
        }
        receiver = next
        val filter = IntentFilter().apply {
            addAction("android.net.conn.TETHER_STATE_CHANGED")
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED")
            addAction("android.net.wifi.WIFI_AP_STATE_CHANGED_ACTION")
        }
        context.registerReceiver(next, filter)
    }

    override fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun extractInterfaces(intent: Intent): Set<String> = buildSet {
        intent.extras?.keySet().orEmpty().forEach { key ->
            if (!key.contains("tether", ignoreCase = true) && !key.contains("active", ignoreCase = true)) return@forEach
            when (val value = intent.extras?.get(key)) {
                is ArrayList<*> -> value.filterIsInstance<String>().forEach(::add)
                is Array<*> -> value.filterIsInstance<String>().forEach(::add)
                is String -> if (value.contains("wlan", true) || value.contains("usb", true) || value.contains("rndis", true) || value.contains("bnep", true)) add(value)
            }
        }
    }
}

class AndroidTetheringFeaturePack : FeaturePack {
    override val id: String = "android.tethering"

    override fun install(registry: FeatureRegistry) {
        val types = listOf("any", "wifi", "usb", "bluetooth")
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.tethering_changed"), FeatureKind.EVENT,
                "Tethering changed", "Run when Android reports hotspot or USB/Bluetooth tethering changes",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("type", "Tethering type", true, types),
                    FieldSchema.Choice("state", "State", true, listOf("any", "active", "inactive")),
                ),
                keywords = setOf("tether", "hotspot", "usb tether", "bluetooth tether", "wifi hotspot"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.tethering_changed") return@registerEvent false
            val type = feature.config.string("type", "any")
            val active = when (type) {
                "wifi" -> ctx.event.payload.boolean("wifiActive")
                "usb" -> ctx.event.payload.boolean("usbActive")
                "bluetooth" -> ctx.event.payload.boolean("bluetoothActive")
                else -> ctx.event.payload.boolean("active")
            }
            when (feature.config.string("state", "any")) {
                "active" -> active
                "inactive" -> !active
                else -> true
            }
        }

        val evaluator = ConditionEvaluator { feature, _ ->
            val active = TetheringRuntimeState.snapshot().active(feature.config.string("type", "any"))
            active == feature.config.boolean("value", true)
        }
        val state = FeatureDescriptor(
            FeatureId("android.state.tethering"), FeatureKind.STATE,
            "Tethering active", "Check whether a selected tethering type is currently active",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Choice("type", "Tethering type", true, types),
                FieldSchema.Toggle("value", "Active"),
            ),
            keywords = setOf("tether", "hotspot", "usb tether", "bluetooth tether"),
            ownerPackId = id,
        )
        registry.registerState(state, evaluator)
        registry.registerCondition(state.copy(id = FeatureId("android.condition.tethering"), kind = FeatureKind.CONDITION), evaluator)
    }
}

private fun tetherType(iface: String): String {
    val value = iface.lowercase()
    return when {
        value.startsWith("wlan") || value.startsWith("ap") || "softap" in value -> "wifi"
        value.startsWith("rndis") || value.startsWith("usb") || value.startsWith("ncm") -> "usb"
        value.startsWith("bnep") || value.startsWith("bt-pan") -> "bluetooth"
        else -> "other"
    }
}
