package com.yagay.yauto.importer.shortx

import com.yagay.yauto.core.importer.AutomationImporter
import com.yagay.yauto.core.importer.ImportInput
import com.yagay.yauto.core.importer.ImportResult

/**
 * Adds safe native-target hints without changing ShortX decoding semantics.
 * The original compatibility payload remains intact whenever a source message is not decoded.
 */
class EnhancedShortXImporter(
    private val delegate: ShortXImporter = ShortXImporter(),
) : AutomationImporter {
    override val id: String get() = delegate.id
    override val displayName: String get() = delegate.displayName

    override fun confidence(input: ImportInput): Int = delegate.confidence(input)

    override fun import(input: ImportInput): ImportResult {
        val result = delegate.import(input)
        if (result.issues.isEmpty()) return result
        return result.copy(
            issues = result.issues.map { issue ->
                if (issue.suggestedFeatureId != null) issue
                else issue.copy(
                    suggestedFeatureId = ShortXFeatureSuggestions.target(
                        sourceType = issue.sourceType,
                        sourcePath = issue.sourcePath,
                    )
                )
            }
        )
    }
}

internal object ShortXFeatureSuggestions {
    fun target(sourceType: String?, sourcePath: String): String? {
        val name = sourceType?.substringAfterLast('/')?.substringAfterLast('.')?.substringAfterLast('$').orEmpty()
        return when {
            ".action[" in sourcePath -> when (name) {
                "SetNFCEnabled" -> "android.nfc.set"
                "SetLocationEnabled" -> "android.location.enabled.set"
                "SetScreenRotate" -> "android.display.auto_rotate.set"
                "SetScreenTimeout" -> "android.display.screen_timeout.set"
                "SetDarkModeEnabled" -> "android.display.dark_mode.set"
                "StayAwake" -> "android.power.stay_awake.set"
                "TTS" -> "android.tts.speak"
                else -> null
            }
            ".fact[" in sourcePath -> when (name) {
                "DarkModeStatusChanged" -> "android.event.dark_mode_changed"
                "NFCStatusChanged" -> "android.event.nfc_state_changed"
                "ScreenRotate" -> "android.event.auto_rotate_changed"
                "BatteryLevelChanged", "BatteryTemperatureChanged" -> "android.event.battery_changed"
                "HeadsetPlug" -> "android.event.headset_changed"
                else -> null
            }
            ".condition[" in sourcePath -> when (name) {
                "BatteryPercent" -> "android.condition.battery_level"
                "IsHeadsetPlug" -> "android.condition.headset_connected"
                else -> null
            }
            else -> null
        }
    }
}
