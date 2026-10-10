package com.yagay.yauto.feature.standard.privileged

import com.yagay.yauto.core.model.ConfigValue
import com.yagay.yauto.core.model.boolean
import com.yagay.yauto.core.model.long
import com.yagay.yauto.core.model.string
import com.yagay.yauto.core.registry.FeatureCategory
import com.yagay.yauto.core.registry.FeatureDefinition
import com.yagay.yauto.core.registry.FieldBehavior
import com.yagay.yauto.core.registry.FieldSchema

internal object PrivilegedDisplayPowerFeatures {
    val definitions: List<FeatureDefinition> = listOf(
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.display.auto_rotate.set",
                "Auto-rotate",
                "Enable or disable Android automatic screen rotation",
                FeatureCategory.DISPLAY,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                keywords = setOf("rotation", "auto rotate", "orientation"),
                behaviors = mapOf("enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
            )
        ) { feature, _ ->
            "settings put system accelerometer_rotation ${if (feature.config.boolean("enabled", true)) 1 else 0}"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.display.screen_timeout.set",
                "Screen timeout",
                "Set the Android screen-off timeout",
                FeatureCategory.DISPLAY,
                fields = listOf(FieldSchema.Duration("timeoutMs", "Screen timeout", true)),
                keywords = setOf("screen timeout", "sleep timeout", "display timeout"),
                behaviors = mapOf("timeoutMs" to FieldBehavior(defaultValue = ConfigValue.NumberValue(30_000.0))),
            )
        ) { feature, _ ->
            val timeoutMs = feature.config.long("timeoutMs", 30_000L)
            require(timeoutMs in 1_000L..86_400_000L) { "Screen timeout must be between 1 second and 24 hours" }
            "settings put system screen_off_timeout $timeoutMs"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.display.dark_mode.set",
                "Dark theme",
                "Set Android system night mode to light, dark or automatic",
                FeatureCategory.DISPLAY,
                fields = listOf(FieldSchema.Choice("mode", "Mode", true, listOf("light", "dark", "auto", "toggle"))),
                keywords = setOf("dark mode", "night mode", "theme"),
                behaviors = mapOf("mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("auto"))),
            )
        ) { feature, _ ->
            if (feature.config.string("mode", "auto") == "toggle") {
                return@privilegedCommandFeature """current=${'
                "dark" -> "yes"
                "auto" -> "auto"
                else -> error("Invalid dark theme mode")
            }
            "cmd uimode night $mode"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.power.battery_saver.set",
                "Battery saver",
                "Turn Android low-power mode on or off",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                keywords = setOf("battery saver", "power saver", "low power"),
                behaviors = mapOf("enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
            )
        ) { feature, _ ->
            "cmd power set-mode ${if (feature.config.boolean("enabled", true)) 1 else 0}"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.power.stay_awake.set",
                "Stay awake while charging",
                "Control which charging sources keep the screen awake",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("mode", "Stay awake mode", true, listOf("off", "all", "usb", "ac", "wireless")),
                ),
                keywords = setOf("stay awake", "keep screen on", "charging"),
                behaviors = mapOf("mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("off"))),
            )
        ) { feature, _ ->
            val argument = when (feature.config.string("mode", "off")) {
                "off" -> "false"
                "all" -> "true"
                "usb" -> "usb"
                "ac" -> "ac"
                "wireless" -> "wireless"
                else -> error("Invalid stay-awake mode")
            }
            "svc power stayon $argument"
        },
    )
}
}(cmd uimode night); case "${'
                "dark" -> "yes"
                "auto" -> "auto"
                else -> error("Invalid dark theme mode")
            }
            "cmd uimode night $mode"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.power.battery_saver.set",
                "Battery saver",
                "Turn Android low-power mode on or off",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                keywords = setOf("battery saver", "power saver", "low power"),
                behaviors = mapOf("enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
            )
        ) { feature, _ ->
            "cmd power set-mode ${if (feature.config.boolean("enabled", true)) 1 else 0}"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.power.stay_awake.set",
                "Stay awake while charging",
                "Control which charging sources keep the screen awake",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("mode", "Stay awake mode", true, listOf("off", "all", "usb", "ac", "wireless")),
                ),
                keywords = setOf("stay awake", "keep screen on", "charging"),
                behaviors = mapOf("mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("off"))),
            )
        ) { feature, _ ->
            val argument = when (feature.config.string("mode", "off")) {
                "off" -> "false"
                "all" -> "true"
                "usb" -> "usb"
                "ac" -> "ac"
                "wireless" -> "wireless"
                else -> error("Invalid stay-awake mode")
            }
            "svc power stayon $argument"
        },
    )
}
}current" in *"Night mode: yes"*) cmd uimode night no ;; *"Night mode: no"*) cmd uimode night yes ;; *) echo "Cannot safely toggle non-explicit night mode" >&2; exit 1 ;; esac"""
            }
            val mode = when (feature.config.string("mode", "auto")) {
                "light" -> "no"
                "dark" -> "yes"
                "auto" -> "auto"
                else -> error("Invalid dark theme mode")
            }
            "cmd uimode night $mode"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.power.battery_saver.set",
                "Battery saver",
                "Turn Android low-power mode on or off",
                FeatureCategory.DEVICE,
                fields = listOf(FieldSchema.Toggle("enabled", "Enabled")),
                keywords = setOf("battery saver", "power saver", "low power"),
                behaviors = mapOf("enabled" to FieldBehavior(defaultValue = ConfigValue.BooleanValue(true))),
            )
        ) { feature, _ ->
            "cmd power set-mode ${if (feature.config.boolean("enabled", true)) 1 else 0}"
        },
        privilegedCommandFeature(
            privilegedDescriptor(
                "android.power.stay_awake.set",
                "Stay awake while charging",
                "Control which charging sources keep the screen awake",
                FeatureCategory.DEVICE,
                fields = listOf(
                    FieldSchema.Choice("mode", "Stay awake mode", true, listOf("off", "all", "usb", "ac", "wireless")),
                ),
                keywords = setOf("stay awake", "keep screen on", "charging"),
                behaviors = mapOf("mode" to FieldBehavior(defaultValue = ConfigValue.StringValue("off"))),
            )
        ) { feature, _ ->
            val argument = when (feature.config.string("mode", "off")) {
                "off" -> "false"
                "all" -> "true"
                "usb" -> "usb"
                "ac" -> "ac"
                "wireless" -> "wireless"
                else -> error("Invalid stay-awake mode")
            }
            "svc power stayon $argument"
        },
    )
}
