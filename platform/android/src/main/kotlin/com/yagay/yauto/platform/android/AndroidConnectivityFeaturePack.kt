package com.yagay.yauto.platform.android

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigMap
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.*

class AndroidConnectivityFeaturePack(context: Context) : FeaturePack {
    override val id = "android.connectivity"
    private val context = context.applicationContext
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetooth = context.applicationContext.getSystemService(BluetoothManager::class.java).adapter
    private val audio = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun install(registry: FeatureRegistry) {
        wifiState(registry, FeatureKind.STATE, "android.state.wifi_network")
        wifiState(registry, FeatureKind.CONDITION, "android.condition.wifi_network")
        networkProfile(registry, FeatureKind.STATE, "android.state.network_profile")
        networkProfile(registry, FeatureKind.CONDITION, "android.condition.network_profile")
        bluetoothAudioDevice(registry, FeatureKind.STATE, "android.state.bluetooth_audio_device")
        bluetoothAudioDevice(registry, FeatureKind.CONDITION, "android.condition.bluetooth_audio_device")
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
                    FieldSchema.Choice("changeType", "Change type", options = listOf("any", "state", "network", "rssi")),
                    FieldSchema.Choice("connected", "Connection", options = listOf("any", "connected", "disconnected")),
                    FieldSchema.Text("ssid", "SSID contains"),
                    FieldSchema.Number("minRssi", "Minimum signal (dBm)", min = -127.0, max = 0.0),
                    FieldSchema.Number("maxRssi", "Maximum signal (dBm)", min = -127.0, max = 0.0),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("wifi", "ssid", "signal", "rssi", "network"),
                ownerPackId = id,
                aliases = setOf(
                    "android.event.wifi_state_changed",
                    "android.event.wifi_network_state_changed",
                    "android.event.wifi_rssi_changed",
                ),
                aliasConfigDefaults = mapOf(
                    "android.event.wifi_state_changed" to mapOf("changeType" to ConfigValue.StringValue("state")),
                    "android.event.wifi_network_state_changed" to mapOf("changeType" to ConfigValue.StringValue("network")),
                    "android.event.wifi_rssi_changed" to mapOf("changeType" to ConfigValue.StringValue("rssi")),
                ),
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.wifi_changed") return@registerEvent false
            val wantedChangeType = feature.config.string("changeType", "any")
            if (wantedChangeType != "any" && ctx.event.payload.string("changeType") != wantedChangeType) {
                return@registerEvent false
            }
            matchesWifi(
                feature.config,
                WifiSnapshot(
                    connected = ctx.event.payload.boolean("connected"),
                    ssid = ctx.event.payload.string("ssid"),
                    bssid = ctx.event.payload.string("bssid"),
                    rssi = ctx.event.payload["rssi"].numberOrNull()?.toInt() ?: Int.MIN_VALUE,
                ),
                includeBssid = false,
            )
        }

        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.bluetooth_state"), FeatureKind.EVENT,
                "Bluetooth state changed", "Run when the Bluetooth adapter is enabled or disabled",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Choice("state", "State", options = listOf("any", "on", "off"))),
                accessRequirements = setOf(AccessRequirement.BLUETOOTH_CONNECT),
                keywords = setOf("bluetooth", "bt"),
                ownerPackId = id,
                aliases = setOf("android.event.bluetooth_state_changed"),
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
            FeatureId(typeId), kind, "Wi-Fi network", "Match the current Wi-Fi connection, SSID/BSSID and signal range",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Choice("connected", "Connection", options = listOf("any", "connected", "disconnected")),
                FieldSchema.Text("ssid", "SSID contains"),
                FieldSchema.Text("bssid", "BSSID exact"),
                FieldSchema.Number("minRssi", "Minimum signal (dBm)", min = -127.0, max = 0.0),
                FieldSchema.Number("maxRssi", "Maximum signal (dBm)", min = -127.0, max = 0.0),
            ),
            accessRequirements = setOf(AccessRequirement.LOCATION),
            keywords = setOf("wifi", "ssid", "bssid", "signal", "rssi", "network"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ -> matchesWifi(feature.config, currentWifi()) }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun networkProfile(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, "Active network", "Match the active network transport, internet validation and metered state",
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Toggle("connected", "Connected"),
                FieldSchema.Choice("transport", "Transport", options = listOf("any", "wifi", "cellular", "ethernet", "vpn")),
                FieldSchema.Choice("validated", "Internet validation", options = listOf("any", "yes", "no")),
                FieldSchema.Choice("metered", "Metered network", options = listOf("any", "yes", "no")),
            ),
            keywords = setOf("network", "internet", "wifi", "cellular", "ethernet", "vpn", "metered", "validated"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ -> matchesNetworkProfile(feature.config, currentNetwork()) }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun bluetoothAudioDevice(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind, "Bluetooth audio device", "Match a currently connected Bluetooth audio route by name or address",
            FeatureCategory.AUDIO,
            fields = listOf(
                FieldSchema.Toggle("connected", "Connected"),
                FieldSchema.Text("nameContains", "Device name contains"),
                FieldSchema.Text("address", "Device address exact"),
            ),
            keywords = setOf("bluetooth", "headset", "earbuds", "speaker", "audio device", "address"),
            ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            matchesBluetoothAudioDevice(feature.config, currentBluetoothAudioDevices())
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

    internal fun currentNetwork(): NetworkSnapshot {
        val network = connectivity.activeNetwork ?: return NetworkSnapshot(false)
        val caps = connectivity.getNetworkCapabilities(network) ?: return NetworkSnapshot(false)
        return NetworkSnapshot(
            connected = true,
            wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        )
    }

    internal fun currentBluetoothAudioDevices(): List<BluetoothAudioDeviceSnapshot> =
        runCatching {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).mapNotNull { device ->
                if (device.yautoHeadsetCategory() != "bluetooth") null
                else BluetoothAudioDeviceSnapshot(
                    name = device.productName?.toString().orEmpty(),
                    address = device.address.orEmpty(),
                )
            }
        }.getOrDefault(emptyList())

    private fun bluetoothEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return false
        return runCatching { bluetooth?.isEnabled == true }.getOrDefault(false)
    }

    private fun airplaneMode(): Boolean = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
}

internal data class WifiSnapshot(val connected: Boolean, val ssid: String, val bssid: String, val rssi: Int)
internal data class NetworkSnapshot(
    val connected: Boolean,
    val wifi: Boolean = false,
    val cellular: Boolean = false,
    val ethernet: Boolean = false,
    val vpn: Boolean = false,
    val validated: Boolean = false,
    val metered: Boolean = false,
)
internal data class BluetoothAudioDeviceSnapshot(val name: String, val address: String)

internal fun matchesWifi(config: ConfigMap, current: WifiSnapshot, includeBssid: Boolean = true): Boolean {
    val connectionMatch = when (config.string("connected", "any")) {
        "connected" -> current.connected
        "disconnected" -> !current.connected
        else -> true
    }
    if (!connectionMatch) return false
    val ssid = config.string("ssid")
    val bssid = config.string("bssid")
    if (ssid.isNotBlank() && !current.ssid.contains(ssid, ignoreCase = true)) return false
    if (includeBssid && bssid.isNotBlank() && !current.bssid.equals(bssid, ignoreCase = true)) return false
    val minRssi = config["minRssi"].numberOrNull()
    val maxRssi = config["maxRssi"].numberOrNull()
    if (minRssi != null || maxRssi != null) {
        if (!current.connected || current.rssi == Int.MIN_VALUE) return false
        if (minRssi != null && maxRssi != null && minRssi > maxRssi) return false
        if (minRssi != null && current.rssi < minRssi) return false
        if (maxRssi != null && current.rssi > maxRssi) return false
    }
    return true
}

internal fun matchesNetworkProfile(config: ConfigMap, current: NetworkSnapshot): Boolean {
    val expectedConnected = config.boolean("connected", true)
    if (current.connected != expectedConnected) return false
    if (!expectedConnected) return true
    val transportMatches = when (config.string("transport", "any")) {
        "wifi" -> current.wifi
        "cellular" -> current.cellular
        "ethernet" -> current.ethernet
        "vpn" -> current.vpn
        else -> true
    }
    if (!transportMatches) return false
    if (!matchesTriState(config.string("validated", "any"), current.validated)) return false
    return matchesTriState(config.string("metered", "any"), current.metered)
}

internal fun matchesBluetoothAudioDevice(config: ConfigMap, devices: List<BluetoothAudioDeviceSnapshot>): Boolean {
    val nameContains = config.string("nameContains").trim()
    val address = config.string("address").trim()
    val matchingConnected = devices.any { device ->
        (nameContains.isBlank() || device.name.contains(nameContains, ignoreCase = true)) &&
            (address.isBlank() || device.address.equals(address, ignoreCase = true))
    }
    return matchingConnected == config.boolean("connected", true)
}

private fun matchesTriState(mode: String, value: Boolean): Boolean = when (mode) {
    "yes" -> value
    "no" -> !value
    else -> true
}

private fun String?.cleanSsid(): String = this.orEmpty().removePrefix("\"").removeSuffix("\"").takeUnless { it == WifiManager.UNKNOWN_SSID }.orEmpty()
