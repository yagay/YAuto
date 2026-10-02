package com.yagay.yauto.importer.shortx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortXFeatureSuggestionsTest {
    @Test fun `device and app actions suggest native YAuto features`() {
        val path = "rule[0].action[0]"
        assertEquals("android.nfc.set", ShortXFeatureSuggestions.target("type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetNFCEnabled", path))
        assertEquals("android.location.enabled.set", ShortXFeatureSuggestions.target("tornaco.apps.shortx.core.proto.action.SetLocationEnabled", path))
        assertEquals("android.display.auto_rotate.set", ShortXFeatureSuggestions.target("SetScreenRotate", path))
        assertEquals("android.display.screen_timeout.set", ShortXFeatureSuggestions.target("SetScreenTimeout", path))
        assertEquals("android.display.dark_mode.set", ShortXFeatureSuggestions.target("SetDarkModeEnabled", path))
        assertEquals("android.power.stay_awake.set", ShortXFeatureSuggestions.target("ScreenStayAwake", path))
        assertEquals("android.tts.speak", ShortXFeatureSuggestions.target("TTS", path))
        assertEquals("android.screen.wake", ShortXFeatureSuggestions.target("ScreenOn", path))
        assertEquals("android.input.keyevent", ShortXFeatureSuggestions.target("InjectKeyCode", path))
        assertEquals("android.screenshot.capture", ShortXFeatureSuggestions.target("Screenshot", path))
        assertEquals("android.media.transport", ShortXFeatureSuggestions.target("MediaPlayback", path))
        assertEquals("android.app.enabled.set", ShortXFeatureSuggestions.target("SetAppEnabledByPkg", path))
        assertEquals("android.app.inactive.set", ShortXFeatureSuggestions.target("SetAppInactiveByPkg", path))
        assertEquals("android.app.suspended.set", ShortXFeatureSuggestions.target("SetAppSuspendByPkg", path))
        assertEquals("android.app.package_info", ShortXFeatureSuggestions.target("GetAppInfo", path))
        assertEquals("android.display.brightness.set", ShortXFeatureSuggestions.target("SetBrightness", path))
        assertEquals("android.wallpaper.set", ShortXFeatureSuggestions.target("SetWallpaper", path))
        assertEquals("android.clipboard.get", ShortXFeatureSuggestions.target("ReadClipboard", path))
        assertEquals("android.clipboard.set", ShortXFeatureSuggestions.target("WriteClipboard", path))
        assertEquals("android.http.request", ShortXFeatureSuggestions.target("HttpRequest", path))
        assertEquals("android.app.launch", ShortXFeatureSuggestions.target("LaunchAppByPkg", path))
        assertEquals("android.app.force_stop", ShortXFeatureSuggestions.target("StopAppByPkg", path))
        assertEquals("system.shell.execute", ShortXFeatureSuggestions.target("ShellCommand", path))
        assertEquals("accessibility.gesture.tap", ShortXFeatureSuggestions.target("InputTap", path))
        assertEquals("accessibility.gesture.swipe", ShortXFeatureSuggestions.target("InputSwipe", path))
        assertEquals("accessibility.input_text", ShortXFeatureSuggestions.target("InputText", path))
        assertEquals("accessibility.click_text", ShortXFeatureSuggestions.target("FindAndClickViewByText", path))
        assertEquals("accessibility.click_view_id", ShortXFeatureSuggestions.target("FindAndClickViewById", path))
        assertEquals("android.toast.show", ShortXFeatureSuggestions.target("ShowToast", path))
        assertEquals("android.uri.open", ShortXFeatureSuggestions.target("OpenUrl", path))
        assertEquals("android.torch.set", ShortXFeatureSuggestions.target("SetFlashLightEnabled", path))
        assertEquals("android.dnd.set", ShortXFeatureSuggestions.target("SetDNDEnabled", path))
        assertEquals("android.bluetooth.set", ShortXFeatureSuggestions.target("SetBTEnabled", path))
        assertEquals("android.wifi.set", ShortXFeatureSuggestions.target("SetWifiEnabled", path))
        assertEquals("android.airplane_mode.set", ShortXFeatureSuggestions.target("SetAPMModeEnabled", path))
        assertEquals("android.mobile_data.set", ShortXFeatureSuggestions.target("SetDataEnabled", path))
        assertEquals("android.notification.show", ShortXFeatureSuggestions.target("PostNotification", path))
        assertEquals("android.vibrate", ShortXFeatureSuggestions.target("Vibrate", path))
    }

    @Test fun `facts suggest matching change events`() {
        val path = "rule[0].fact[0]"
        assertEquals("android.event.dark_mode_changed", ShortXFeatureSuggestions.target("DarkModeStatusChanged", path))
        assertEquals("android.event.nfc_state_changed", ShortXFeatureSuggestions.target("NFCStatusChanged", path))
        assertEquals("android.event.auto_rotate_changed", ShortXFeatureSuggestions.target("ScreenRotateTrigger", path))
        assertEquals("android.event.battery_changed", ShortXFeatureSuggestions.target("BatteryLevelChanged", path))
        assertEquals("android.event.battery_changed", ShortXFeatureSuggestions.target("BatteryTemperatureChanged", path))
        assertEquals("android.event.headset_changed", ShortXFeatureSuggestions.target("HeadsetPlug", path))
        assertEquals("android.event.package_added", ShortXFeatureSuggestions.target("AppAdded", path))
        assertEquals("android.event.package_removed", ShortXFeatureSuggestions.target("AppRemoved", path))
        assertEquals("android.event.package_replaced", ShortXFeatureSuggestions.target("AppUpdated", path))
        assertEquals("android.event.clipboard_changed", ShortXFeatureSuggestions.target("ClipboardContentChanged", path))
    }

    @Test fun `conditions suggest compatible current states`() {
        val path = "rule[0].condition[0]"
        assertEquals("android.condition.battery_level", ShortXFeatureSuggestions.target("BatteryPercent", path))
        assertEquals("android.condition.battery_temperature", ShortXFeatureSuggestions.target("BatteryTemperature", path))
        assertEquals("android.condition.headset_connected", ShortXFeatureSuggestions.target("IsHeadsetPlug", path))
        assertEquals("android.condition.app_process_running", ShortXFeatureSuggestions.target("AppIsRunning", path))
        assertEquals("android.condition.app_process_running", ShortXFeatureSuggestions.target("ProcessIsRunning", path))
        assertEquals("android.condition.screen", ShortXFeatureSuggestions.target("ScreenIsOn", path))
        assertEquals("android.condition.memory_available", ShortXFeatureSuggestions.target("AvailableMemory", path))
        assertEquals("android.condition.charging_source", ShortXFeatureSuggestions.target("ChargeState", path))
        assertEquals("android.condition.app_foreground", ShortXFeatureSuggestions.target("CurrentActivity", path))
        assertEquals("android.condition.notification_active", ShortXFeatureSuggestions.target("AppHasNotification", path))
        assertEquals("time.condition.time_window", ShortXFeatureSuggestions.target("TimeInRange", path))
    }

    @Test fun `ambiguous toggles and different semantics are deliberately not guessed`() {
        assertNull(ShortXFeatureSuggestions.target("ToggleNFC", "rule[0].action[0]"))
        assertNull(ShortXFeatureSuggestions.target("ToggleDarkMode", "rule[0].action[0]"))
        assertNull(ShortXFeatureSuggestions.target("RequireScreenRotate", "rule[0].condition[0]"))
        assertNull(ShortXFeatureSuggestions.target("InjectCombineKeyCode", "rule[0].action[0]"))
        assertNull(ShortXFeatureSuggestions.target("AreaScreenshot", "rule[0].action[0]"))
    }
}
