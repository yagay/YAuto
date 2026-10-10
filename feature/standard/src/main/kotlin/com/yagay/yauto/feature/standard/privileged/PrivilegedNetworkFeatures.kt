package com.yagay.yauto.feature.standard.privileged

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema

internal object PrivilegedNetworkFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        toggleFeature(
            "android.wifi.set",
            "Wi-Fi",
            "Turn Wi-Fi on or off using Android's svc/cmd Wi-Fi service",
            "svc wifi",
            "wifi_on",
            setOf("wifi", "wi-fi", "network"),
        ),
        toggleFeature(
            "android.mobile_data.set",
            "Mobile data",
            "Turn mobile data connectivity on or off using Android's phone service",
            "svc data",
            "mobile_data",
            setOf("mobile data", "cellular", "data"),
        ),
        toggleFeature(
            "android.bluetooth.set",
            "Bluetooth",
            "Turn Bluetooth on or off using Android's Bluetooth manager shell command",
            "svc bluetooth",
            "bluetooth_on",
            setOf("bluetooth", "bt"),
        ),
        toggleFeature(
            "android.nfc.set",
            "NFC",
            "Turn NFC on or off using Android's NFC service",
            "svc nfc",
            "nfc_on",
            setOf("nfc", "near field communication"),
        ),
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.airplane_mode.set",
                "Airplane mode",
                "Set the global airplane mode flag and broadcast the corresponding Android state change",
                FeatureCategory.NETWORK,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                keywords = setOf("airplane", "flight mode"),
                behaviors = mapOf("enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
            )
        ) { feature, _ ->
            val enabled = feature.config.boolean("enabled", true)
            val flag = if (enabled) "1" else "0"
            "settings put global airplane_mode_on $flag && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enabled"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.location.enabled.set",
                "Location services",
                "Turn the Android master location switch on or off for the current user",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Toggle("enabled", "Enabled"),
                    FieldSchema.Toggle("toggleCurrent", "Toggle current state"),
                ),
                keywords = setOf("location", "gps", "location services"),
                behaviors = mapOf("enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
            )
        ) { feature, _ ->
            if (feature.config.boolean("toggleCurrent")) {
                """state=${'$'}(cmd location is-location-enabled); case "${'$'}state" in true) cmd location set-location-enabled false ;; false) cmd location set-location-enabled true ;; *) echo "Location state unavailable" >&2; exit 1 ;; esac"""
            } else {
                "cmd location set-location-enabled ${feature.config.boolean("enabled", true)}"
            }
        },
    )

    private fun toggleFeature(
        id: String,
        title: String,
        description: String,
        commandPrefix: String,
        stateKey: String,
        keywords: Set<String>,
    ): FeatureDefinition = privilegedCommandFeature(
        privilegedDescriptor(
            id,
            title,
            description,
            FeatureCategory.NETWORK,
            fields = listOf(
                FieldSchema.Toggle("enabled", "Enabled"),
                FieldSchema.Toggle("toggleCurrent", "Toggle current state"),
            ),
            keywords = keywords,
            behaviors = mapOf("enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
        )
    ) { feature, _ ->
        if (feature.config.boolean("toggleCurrent")) {
            """state=${'$'}(settings get global $stateKey); case "${'$'}state" in 1) $commandPrefix disable ;; 0) $commandPrefix enable ;; *) echo "State for $stateKey unavailable" >&2; exit 1 ;; esac"""
        } else {
            "$commandPrefix ${if (feature.config.boolean("enabled", true)) "enable" else "disable"}"
        }
    }
}
