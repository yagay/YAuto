package com.yagay.yauto.importer.shortx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortXFeatureSuggestionsTest {
    @Test fun `device actions suggest native YAuto features`() {
        val path = "rule[0].action[0]"
        assertEquals("android.nfc.set", ShortXFeatureSuggestions.target("type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetNFCEnabled", path))
        assertEquals("android.location.enabled.set", ShortXFeatureSuggestions.target("tornaco.apps.shortx.core.proto.action.SetLocationEnabled", path))
        assertEquals("android.display.auto_rotate.set", ShortXFeatureSuggestions.target("SetScreenRotate", path))
        assertEquals("android.display.screen_timeout.set", ShortXFeatureSuggestions.target("SetScreenTimeout", path))
        assertEquals("android.display.dark_mode.set", ShortXFeatureSuggestions.target("SetDarkModeEnabled", path))
        assertEquals("android.power.stay_awake.set", ShortXFeatureSuggestions.target("StayAwake", path))
        assertEquals("android.tts.speak", ShortXFeatureSuggestions.target("TTS", path))
        assertEquals("android.screen.wake", ShortXFeatureSuggestions.target("WakeupScreen", path))
        assertEquals("android.input.keyevent", ShortXFeatureSuggestions.target("InjectKeyCode", path))
        assertEquals("android.screenshot.capture", ShortXFeatureSuggestions.target("TakeScreenshot", path))
        assertEquals("android.media.transport", ShortXFeatureSuggestions.target("MediaPlaybackAction", path))
    }

    @Test fun `facts suggest matching change events`() {
        val path = "rule[0].fact[0]"
        assertEquals("android.event.dark_mode_changed", ShortXFeatureSuggestions.target("DarkModeStatusChanged", path))
        assertEquals("android.event.nfc_state_changed", ShortXFeatureSuggestions.target("NFCStatusChanged", path))
        assertEquals("android.event.auto_rotate_changed", ShortXFeatureSuggestions.target("ScreenRotate", path))
        assertEquals("android.event.battery_changed", ShortXFeatureSuggestions.target("BatteryLevelChanged", path))
        assertEquals("android.event.battery_changed", ShortXFeatureSuggestions.target("BatteryTemperatureChanged", path))
        assertEquals("android.event.headset_changed", ShortXFeatureSuggestions.target("HeadsetPlug", path))
    }

    @Test fun `conditions suggest compatible current states`() {
        val path = "rule[0].condition[0]"
        assertEquals("android.condition.battery_level", ShortXFeatureSuggestions.target("BatteryPercent", path))
        assertEquals("android.condition.headset_connected", ShortXFeatureSuggestions.target("IsHeadsetPlug", path))
    }

    @Test fun `ambiguous toggles and different semantics are deliberately not guessed`() {
        assertNull(ShortXFeatureSuggestions.target("ToggleNFC", "rule[0].action[0]"))
        assertNull(ShortXFeatureSuggestions.target("ToggleDarkMode", "rule[0].action[0]"))
        assertNull(ShortXFeatureSuggestions.target("RequireScreenRotate", "rule[0].condition[0]"))
        assertNull(ShortXFeatureSuggestions.target("InjectCombineKeyCode", "rule[0].action[0]"))
        assertNull(ShortXFeatureSuggestions.target("AreaScreenshot", "rule[0].action[0]"))
    }
}
