package com.yagay.yauto.platform.android

import com.yagay.yauto.core.capability.CapabilityIds
import com.yagay.yauto.core.capability.CapabilityRequest
import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.model.userText
import com.yagay.yauto.core.registry.*

class AndroidWifiPrivilegedFeaturePack : FeaturePack {
    override val id: String = "android.wifi_privileged"

    override fun install(registry: FeatureRegistry) {
        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wifi.network.connect"), FeatureKind.ACTION,
                "Connect Wi-Fi network", "Connect to a specified Wi-Fi network through Android's privileged Wi-Fi shell",
                FeatureCategory.NETWORK,
                fields = listOf(
                    FieldSchema.Text("ssid", "SSID", true),
                    FieldSchema.Choice("security", "Security", true, listOf("open", "owe", "wpa2", "wpa3")),
                    FieldSchema.Text("password", "Password"),
                    FieldSchema.Text("bssid", "BSSID"),
                    FieldSchema.Toggle("hidden", "Hidden SSID"),
                ),
                fieldBehaviors = mapOf(
                    "ssid" to FieldBehavior(supportsVariables = true),
                    "password" to FieldBehavior(supportsVariables = true),
                    "bssid" to FieldBehavior(supportsVariables = true),
                ),
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("wifi", "connect", "ssid", "network", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { feature, ctx ->
            val ssid = feature.config.string("ssid").resolveVariables(ctx.variables).trim()
            val security = feature.config.string("security", "wpa2")
            val password = feature.config.string("password").resolveVariables(ctx.variables)
            val bssid = feature.config.string("bssid").resolveVariables(ctx.variables).trim()
            if (ssid.isBlank() || security !in setOf("open", "owe", "wpa2", "wpa3") ||
                (bssid.isNotBlank() && !Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(bssid))
            ) return@registerAction ActionExecutionResult(false, message = userText("feature.wifi_network_input_invalid"))
            val passwordArg = if (security in setOf("wpa2", "wpa3")) " " + shellQuote(password) else ""
            val hiddenArg = if (feature.config.boolean("hidden")) " -h" else ""
            val bssidArg = if (bssid.isNotBlank()) " -b " + shellQuote(bssid) else ""
            val command = "cmd wifi connect-network ${shellQuote(ssid)} $security$passwordArg$hiddenArg$bssidArg"
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "android.wifi.network.connect",
                    payload = mapOf("command" to ConfigValue.StringValue(command)),
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }

        registry.registerAction(
            FeatureDescriptor(
                FeatureId("android.wifi.network.disconnect"), FeatureKind.ACTION,
                "Disconnect Wi-Fi network", "Disconnect the active Wi-Fi network through Android's privileged Wi-Fi shell",
                FeatureCategory.NETWORK,
                capabilities = setOf(CapabilityIds.PRIVILEGED_SHELL),
                keywords = setOf("wifi", "disconnect", "network", "root", "shizuku"),
                ownerPackId = id,
            )
        ) { _, ctx ->
            val result = ctx.capabilities.execute(
                CapabilityRequest(
                    capability = CapabilityIds.PRIVILEGED_SHELL,
                    operationId = "android.wifi.network.disconnect",
                    payload = mapOf("command" to ConfigValue.StringValue("cmd wifi disconnect")),
                )
            )
            ActionExecutionResult(result.success, result.value, result.message)
        }
    }
}
