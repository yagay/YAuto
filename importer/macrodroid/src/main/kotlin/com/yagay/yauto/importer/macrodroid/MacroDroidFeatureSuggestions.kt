package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.SourceFeatureKind
import com.yagay.yauto.core.importer.SourceFeatureMapper

/**
 * Suggested mappings for source features whose payload is not yet decoded losslessly.
 * Import keeps the original compatibility node and only exposes the native YAuto target as a hint.
 */
object MacroDroidFeatureSuggestions {
    val mapper = SourceFeatureMapper { sourceType, kind ->
        when (kind) {
            SourceFeatureKind.ACTION -> when (sourceType) {
                "SetNFCAction" -> "android.nfc.set"
                "SetLocationModeAction" -> "android.location.enabled.set"
                "SetAutoRotateAction" -> "android.display.auto_rotate.set"
                "SetScreenTimeoutAction" -> "android.display.screen_timeout.set"
                "DarkThemeAction" -> "android.display.dark_mode.set"
                "BatterySaverAction" -> "android.power.battery_saver.set"
                "KeepAwakeAction" -> "android.power.stay_awake.set"
                "SpeakTextAction" -> "android.tts.speak"
                else -> null
            }
            SourceFeatureKind.EVENT -> when (sourceType) {
                "AutoRotateChangeTrigger" -> "android.event.auto_rotate_changed"
                "BatterySaverTrigger" -> "android.event.power_save_changed"
                "DarkThemeTrigger" -> "android.event.dark_mode_changed"
                "NFCStateTrigger" -> "android.event.nfc_state_changed"
                else -> null
            }
            SourceFeatureKind.CONDITION -> when (sourceType) {
                "AutoRotateConstraint" -> "android.condition.auto_rotate"
                "BatterySaverStateConstraint" -> "android.condition.power_save"
                "DarkThemeConstraint" -> "android.condition.dark_mode"
                "LocationModeConstraint" -> "android.condition.location_enabled"
                "NFCStateConstraint" -> "android.condition.nfc_enabled"
                else -> null
            }
            SourceFeatureKind.STATE -> null
        } ?: MacroDroidMappings.mapper.targetId(sourceType, kind)
    }
}
