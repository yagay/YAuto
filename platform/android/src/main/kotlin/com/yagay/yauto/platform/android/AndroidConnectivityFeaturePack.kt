package com.yagay.yauto.platform.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidConnectivityFeaturePack(context: Context) : FeaturePack {
    override val id = "android.connectivity"
    private val context = context.applicationContext
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetooth = context.applicationContext.getSystemService(BluetoothManager::class.java).adapter

    override fun install(registry: FeatureRegistry) {
        wifiState(registry, FeatureKind.STATE, "android.state.wifi_network")
        wifiState(registry, FeatureKind.CONDITION, "android.condition.wifi_network")
        booleanState(
            registry, FeatureKind.STATE, "android.state.bluetooth_enabled", "Bluetooth enabled", ::bluetoothEnabled,
            setOf(AccessRequirement.BLUETOOTH_CONNECT),
        )
        booleanState(
            registry, FeatureKind.CONDITION, "android.condition.bluetooth_enabled", "Bluetooth enabled", ::bluetoothEnabled,
            setOf(AccessRequirement.BLUETOOTH_CONNECT),
        )
        booleanState(registry, FeatureKind.STATE, "android.state.airplane_mode", "Airplane mode", ::airplaneMode)
        booleanState(registry, FeatureKind.CONDITION, "android.condition.airplane_mode", "Airplane mode", ::airplaneMode)

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.wifi_changed"), FeatureKind.EVENT,
                "Wi-Fi network changed", "Run when Android reports a Wi-Fi connection or signal state change",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Choice("connected", "Connection", options = listOf("any", "connected", "disconnected")),
                    FieldSchema.Text("ssid", "SSID contains"),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("wifi", "ssid", "network"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.wifi_changed") return@registerEvent false
            val connected = ctx.event.payload.boolean("connected")
            val connectionMatch = when (feature.config.string("connected", "any")) {
                "connected" -> connected
                "disconnected" -> !connected
                else -> true
            }
            val ssid = feature.config.string("ssid")
            connectionMatch && (ssid.isBlank() || ctx.event.payload.string("ssid").contains(ssid, ignoreCase = true))
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.bluetooth_state"), FeatureKind.EVENT,
                "Bluetooth state changed", "Run when the Bluetooth adapter is enabled or disabled",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Choice("state", "State", options = listOf("any", "on", "off"))),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("bluetooth", "bt"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.bluetooth_state") return@registerEvent false
            when (feature.config.string("state", "any")) {
                "on" -> ctx.event.payload.boolean("enabled")
                "off" -> !ctx.event.payload.boolean("enabled")
                else -> true
            }
        }
    }

    private fun wifiState(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, "Wi-Fi network", "Match the current Wi-Fi connection and optional SSID/BSSID",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Choice("connected", "Connection", options = listOf("any", "connected", "disconnected")),
                FieldSchema.Text("ssid", "SSID contains"),
                FieldSchema.Text("bssid", "BSSID exact"),
            ),
            accessRequirements = setOf(AccessRequirement.LOCATION),
            keywords = setOf("wifi", "ssid", "bssid", "network"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val current = currentWifi()
            val connectionMatch = when (feature.config.string("connected", "any")) {
                "connected" -> current.connected
                "disconnected" -> !current.connected
                else -> true
            }
            val ssid = feature.config.string("ssid")
            val bssid = feature.config.string("bssid")
            connectionMatch && (ssid.isBlank() || current.ssid.contains(ssid, true)) && (bssid.isBlank() || current.bssid.equals(bssid, true))
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun booleanState(
        registry: FeatureRegistry,
        kind: FeatureKind,
        typeId: String,
        title: String,
        query: () -> Boolean,
        accessRequirements: Set<AccessRequirement> = emptySet(),
    ) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, title, "Evaluate current Android connectivity state", FeatureCategory.NETWORK,
            fields = listOf(FieldSchema.Toggle("value", "Enabled / on")),
            accessRequirements = accessRequirements,
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ -> runCatching { query() == feature.config.boolean("value", true) }.getOrDefault(false) }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    internal fun currentWifi(): WifiSnapshot {
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!connected) return WifiSnapshot(false, "", "", Int.MIN_VALUE)

        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) caps.transportInfo as? WifiInfo else null
        @Suppress("DEPRECATION")
        val fallback = runCatching { wifi.connectionInfo }.getOrNull()
        val selected = info ?: fallback
        return WifiSnapshot(
            true,
            selected?.ssid.cleanSsid(),
            selected?.bssid.orEmpty(),
            selected?.rssi ?: Int.MIN_VALUE,
        )
    }

    private fun bluetoothEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)
    }

    private fun airplaneMode(): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    internal data class WifiSnapshot(val connected: Boolean, val ssid: String, val bssid: String, val rssi: Int)
}

private fun String?.cleanSsid(): String = this.orEmpty().removePrefix("\"").removeSuffix("\"").takeUnless { it == WifiManager.UNKNOWN_SSID }.orEmpty()
