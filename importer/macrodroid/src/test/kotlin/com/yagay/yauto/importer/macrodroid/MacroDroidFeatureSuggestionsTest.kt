package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.SourceFeatureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroDroidFeatureSuggestionsTest {
    @Test fun `device controls suggest native YAuto features`() {
        val mapper = MacroDroidFeatureSuggestions.mapper
        assertEquals("android.nfc.set", mapper.targetId("SetNFCAction", SourceFeatureKind.ACTION))
        assertEquals("android.location.enabled.set", mapper.targetId("SetLocationModeAction", SourceFeatureKind.ACTION))
        assertEquals("android.display.auto_rotate.set", mapper.targetId("SetAutoRotateAction", SourceFeatureKind.ACTION))
        assertEquals("android.display.screen_timeout.set", mapper.targetId("SetScreenTimeoutAction", SourceFeatureKind.ACTION))
        assertEquals("android.display.dark_mode.set", mapper.targetId("DarkThemeAction", SourceFeatureKind.ACTION))
        assertEquals("android.power.battery_saver.set", mapper.targetId("BatterySaverAction", SourceFeatureKind.ACTION))
        assertEquals("android.power.stay_awake.set", mapper.targetId("KeepAwakeAction", SourceFeatureKind.ACTION))
        assertEquals("android.tts.speak", mapper.targetId("SpeakTextAction", SourceFeatureKind.ACTION))
        assertEquals("android.screen.wake", mapper.targetId("ScreenOnAction", SourceFeatureKind.ACTION))
        assertEquals("android.input.keyevent", mapper.targetId("InputKeyEventAction", SourceFeatureKind.ACTION))
        assertEquals("android.screenshot.capture", mapper.targetId("TakeScreenshotAction", SourceFeatureKind.ACTION))
    }

    @Test fun `device state sources suggest event and condition features`() {
        val mapper = MacroDroidFeatureSuggestions.mapper
        assertEquals("android.event.nfc_state_changed", mapper.targetId("NFCStateTrigger", SourceFeatureKind.EVENT))
        assertEquals("android.event.auto_rotate_changed", mapper.targetId("AutoRotateChangeTrigger", SourceFeatureKind.EVENT))
        assertEquals("android.event.dark_mode_changed", mapper.targetId("DarkThemeTrigger", SourceFeatureKind.EVENT))
        assertEquals("android.event.battery_changed", mapper.targetId("BatteryLevelTrigger", SourceFeatureKind.EVENT))
        assertEquals("android.event.battery_changed", mapper.targetId("BatteryTemperatureTrigger", SourceFeatureKind.EVENT))
        assertEquals("android.event.headset_changed", mapper.targetId("HeadphonesTrigger", SourceFeatureKind.EVENT))
        assertEquals("android.condition.location_enabled", mapper.targetId("LocationModeConstraint", SourceFeatureKind.CONDITION))
        assertEquals("android.condition.nfc_enabled", mapper.targetId("NFCStateConstraint", SourceFeatureKind.CONDITION))
        assertEquals("android.condition.battery_temperature", mapper.targetId("BatteryTemperatureConstraint", SourceFeatureKind.CONDITION))
        assertEquals("android.condition.headset_connected", mapper.targetId("HeadphonesConnectionConstraint", SourceFeatureKind.CONDITION))
    }

    @Test fun `existing mappings still fall through to original mapper`() {
        assertEquals(
            "core.delay",
            MacroDroidFeatureSuggestions.mapper.targetId("PauseAction", SourceFeatureKind.ACTION),
        )
    }
}
