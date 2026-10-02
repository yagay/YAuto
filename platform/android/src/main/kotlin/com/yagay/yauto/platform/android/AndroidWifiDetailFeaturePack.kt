package com.yagay.yauto.platform.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.numberOrNull
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.AccessRequirement
import com.yagay.yauto.core.registry.ActionExecutionResult
import com.yagay.yauto.core.registry.ConditionEvaluator
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDescriptor
import com.yagay.yauto.core.registry.FeatureId
import com.yagay.yauto.core.registry.FeatureKind
import com.yagay.yauto.core.registry.FeaturePack
import com.yagay.yauto.core.registry.FeatureRegistry
import com.yagay.yauto.core.registry.FieldSchema

class AndroidWifiDetailFeaturePack(context: Context) : FeaturePack {
    override val id: String = "android.wifi.detail"
    private val context = context.applicationContext
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivity = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun install(registry: FeatureRegistry) {
        registerConnectionInfo(registry)
        registerScanStart(registry)
        registerScanResults(registry)
        registerScanMatch(registry, FeatureKind.STATE, "android.state.wifi_scan_match")
        registerScanMatch(registry, FeatureKind.CONDITION, "android.condition.wifi_scan_match")
        registerScanEvent(registry)
    }

    private fun registerConnectionInfo(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wifi.connection.info"), FeatureKind.ACTION,
                "Get Wi-Fi connection details", "Store current Wi-Fi SSID, BSSID, RSSI, frequency, link speed and network validation details",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Variable("resultVariable", "Store connection object in variable", true)),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("wifi", "ssid", "bssid", "rssi", "frequency", "link speed"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val output = currentWifiConnectionObject()
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerScanStart(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wifi.scan.start"), FeatureKind.ACTION,
                "Start Wi-Fi scan", "Request a Wi-Fi scan; Android may throttle or reject repeated background scans",
                FeatureCategory.NETWORK,
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("wifi", "scan", "networks", "ssid"), ownerPackId = id,
            )
        ) { _, _ ->
            if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                return@registerAction ActionExecutionResult(false, message = userText("feature.wifi_scan_location_permission_missing"))
            }
            val ok = runCatching {
                @Suppress("DEPRECATION")
                wifi.startScan()
            }.getOrDefault(false)
            ActionExecutionResult(ok, message = if (ok) null else userText("feature.wifi_scan_start_failed"))
        }
    }

    private fun registerScanResults(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wifi.scan.results"), FeatureKind.ACTION,
                "Get Wi-Fi scan results", "Store the latest Android Wi-Fi scan results as structured objects",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Number("maxCount", "Maximum results", min = 1.0, max = 500.0),
                    FieldSchema.Variable("resultVariable", "Store scan result list in variable", true),
                ),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("wifi", "scan", "ssid", "bssid", "rssi", "frequency"), ownerPackId = id,
            )
        ) { feature, ctx ->
            val limit = (feature.config["maxCount"].numberOrNull()?.toInt() ?: 100).coerceIn(1, 500)
            val results = scanResults().sortedByDescending { it.level }.take(limit)
            val output = ConfigValue.ListValue(results.map(::scanResultObject))
            ctx.variables.set(feature.config.string("resultVariable"), output)
            ActionExecutionResult(true, output)
        }
    }

    private fun registerScanMatch(registry: FeatureRegistry, kind: FeatureKind, typeId: String) {
        val descriptor = FeatureDescriptor(
            FeatureId(typeId), kind,
            "Wi-Fi scan result matches", "Check whether the latest Wi-Fi scan contains a network matching SSID, BSSID, signal and frequency filters",
            FeatureCategory.NETWORK,
            fields = scanFilterFields() + FieldSchema.Toggle("value", "Match exists"),
            accessRequirements = setOf(AccessRequirement.LOCATION),
            keywords = setOf("wifi", "scan", "ssid", "bssid", "rssi"), ownerPackId = id,
        )
        val evaluator = ConditionEvaluator { feature, _ ->
            val found = scanResults().any { matchesWifiScan(feature.config, it) }
            found == feature.config.boolean("value", true)
        }
        if (kind == FeatureKind.STATE) registry.registerState(descriptor, evaluator) else registry.registerCondition(descriptor, evaluator)
    }

    private fun registerScanEvent(registry: FeatureRegistry) {
        registry.registerEvent(
            FeatureDescriptor(
                FeatureId("android.event.wifi_scan_results"), FeatureKind.EVENT,
                "Wi-Fi scan results available", "Run when Android reports updated Wi-Fi scan results and at least one result matches the configured filters",
                FeatureCategory.NETWORK,
                fields = scanFilterFields(),
                accessRequirements = setOf(AccessRequirement.LOCATION),
                keywords = setOf("wifi", "scan", "results", "ssid", "rssi"), ownerPackId = id,
            )
        ) { feature, ctx ->
            if (ctx.event.typeId != "android.event.wifi_scan_results") return@registerEvent false
            val results = (ctx.event.payload["results"] as? ConfigValue.ListValue)?.value.orEmpty()
            results.any { value ->
                val map = (value as? ConfigValue.ObjectValue)?.value ?: return@any false
                matchesWifiScanSnapshot(
                    feature.config.string("ssidContains"),
                    feature.config.string("bssid"),
                    feature.config["minRssi"].numberOrNull(),
                    feature.config["minFrequencyMhz"].numberOrNull(),
                    feature.config["maxFrequencyMhz"].numberOrNull(),
                    map["ssid"].stringValue(),
                    map["bssid"].stringValue(),
                    (map["rssi"] as? ConfigValue.NumberValue)?.value,
                    (map["frequencyMhz"] as? ConfigValue.NumberValue)?.value,
                )
            }
        }
    }

    private fun scanFilterFields() = listOf(
        FieldSchema.Text("ssidContains", "SSID contains"),
        FieldSchema.Text("bssid", "BSSID exact"),
        FieldSchema.Number("minRssi", "Minimum RSSI (dBm)", min = -127.0, max = 20.0),
        FieldSchema.Number("minFrequencyMhz", "Minimum frequency (MHz)", min = 1.0),
        FieldSchema.Number("maxFrequencyMhz", "Maximum frequency (MHz)", min = 1.0),
    )

    private fun currentWifiConnectionObject(): ConfigValue.ObjectValue {
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val info = caps?.transportInfo as? WifiInfo
        val fallback = runCatching {
            @Suppress("DEPRECATION")
            wifi.connectionInfo
        }.getOrNull()
        val selected = info ?: fallback
        val connected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return ConfigValue.ObjectValue(
            mapOf(
                "connected" to ConfigValue.BooleanValue(connected),
                "ssid" to ConfigValue.StringValue(normalizeWifiSsid(selected?.ssid)),
                "bssid" to ConfigValue.StringValue(selected?.bssid.orEmpty()),
                "rssi" to ConfigValue.NumberValue((selected?.rssi ?: Int.MIN_VALUE).toDouble()),
                "frequencyMhz" to ConfigValue.NumberValue((selected?.frequency ?: 0).toDouble()),
                "linkSpeedMbps" to ConfigValue.NumberValue((selected?.linkSpeed ?: 0).toDouble()),
                "validated" to ConfigValue.BooleanValue(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
                "metered" to ConfigValue.BooleanValue(caps != null && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)),
            )
        )
    }

    private fun scanResults(): List<ScanResult> {
        if (!hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) return emptyList()
        return runCatching { wifi.scanResults.orEmpty() }.getOrDefault(emptyList())
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}

internal fun matchesWifiScan(config: com.yagay.yauto.core.model.ConfigMap, result: ScanResult): Boolean =
    matchesWifiScanSnapshot(
        config.string("ssidContains"),
        config.string("bssid"),
        config["minRssi"].numberOrNull(),
        config["minFrequencyMhz"].numberOrNull(),
        config["maxFrequencyMhz"].numberOrNull(),
        result.SSID.orEmpty(),
        result.BSSID.orEmpty(),
        result.level.toDouble(),
        result.frequency.toDouble(),
    )

internal fun matchesWifiScanSnapshot(
    ssidContains: String,
    bssid: String,
    minRssi: Double?,
    minFrequencyMhz: Double?,
    maxFrequencyMhz: Double?,
    actualSsid: String,
    actualBssid: String,
    actualRssi: Double?,
    actualFrequencyMhz: Double?,
): Boolean {
    if (ssidContains.isNotBlank() && !actualSsid.contains(ssidContains, ignoreCase = true)) return false
    if (bssid.isNotBlank() && !actualBssid.equals(bssid, ignoreCase = true)) return false
    if (minRssi != null && (actualRssi == null || actualRssi < minRssi)) return false
    if (minFrequencyMhz != null && maxFrequencyMhz != null && minFrequencyMhz > maxFrequencyMhz) return false
    if (minFrequencyMhz != null && (actualFrequencyMhz == null || actualFrequencyMhz < minFrequencyMhz)) return false
    if (maxFrequencyMhz != null && (actualFrequencyMhz == null || actualFrequencyMhz > maxFrequencyMhz)) return false
    return true
}

internal fun scanResultObject(result: ScanResult): ConfigValue.ObjectValue = ConfigValue.ObjectValue(
    mapOf(
        "ssid" to ConfigValue.StringValue(result.SSID.orEmpty()),
        "bssid" to ConfigValue.StringValue(result.BSSID.orEmpty()),
        "rssi" to ConfigValue.NumberValue(result.level.toDouble()),
        "frequencyMhz" to ConfigValue.NumberValue(result.frequency.toDouble()),
        "channelWidth" to ConfigValue.NumberValue(result.channelWidth.toDouble()),
        "capabilities" to ConfigValue.StringValue(result.capabilities.orEmpty()),
        "timestampUs" to ConfigValue.NumberValue(result.timestamp.toDouble()),
    )
)

internal fun normalizeWifiSsid(value: String?): String =
    value.orEmpty().removePrefix("\"").removeSuffix("\"").takeUnless { it == WifiManager.UNKNOWN_SSID }.orEmpty()

private fun ConfigValue?.stringValue(): String = (this as? ConfigValue.StringValue)?.value.orEmpty()
