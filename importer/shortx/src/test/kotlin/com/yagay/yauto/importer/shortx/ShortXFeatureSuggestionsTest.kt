package com.yagay.yauto.importer.shortx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortXFeatureSuggestionsTest {
    @Test fun `device and app actions suggest canonical YAuto features`() {
        val path = "rule[0].action[0]"
        assertEquals("android.nfc.set", ShortXFeatureSuggestions.target("type.googleapis.com/tornaco.apps.shortx.core.proto.action.SetNFCEnabled", path))
        assertEquals("android.nfc.set", ShortXFeatureSuggestions.target("ToggleNFC", path))
        assertEquals("android.location.enabled.set", ShortXFeatureSuggestions.target("SetLocationEnabled", path))
        assertEquals("android.display.rotation.set", ShortXFeatureSuggestions.target("SetScreenRotate", path))
        assertEquals("android.display.screen_timeout.set", ShortXFeatureSuggestions.target("SetScreenTimeout", path))
        assertEquals("android.display.dark_mode.set", ShortXFeatureSuggestions.target("ToggleDarkMode", path))
        assertEquals("android.power.stay_awake.set", ShortXFeatureSuggestions.target("ScreenStayAwake", path))
        assertEquals("android.sync.master.set", ShortXFeatureSuggestions.target("SetMasterSync", path))
        assertEquals("android.tts.speak", ShortXFeatureSuggestions.target("TTS", path))
        assertEquals("android.screen.wake", ShortXFeatureSuggestions.target("ScreenOn", path))
        assertEquals("system.screen.sleep", ShortXFeatureSuggestions.target("SleepScreen", path))
        assertEquals("android.input.keyevent", ShortXFeatureSuggestions.target("InjectCombineKeyCode", path))
        assertEquals("android.screenshot.capture", ShortXFeatureSuggestions.target("Screenshot", path))
        assertEquals("android.media.transport", ShortXFeatureSuggestions.target("MediaPlayback", path))
        assertEquals("android.app.enabled.set", ShortXFeatureSuggestions.target("SetAppEnabledByPkg", path))
        assertEquals("android.app.inactive.set", ShortXFeatureSuggestions.target("SetAppInactiveByPkg", path))
        assertEquals("android.app.suspended.set", ShortXFeatureSuggestions.target("SetAppSuspendByPkg", path))
        assertEquals("android.app.package_info", ShortXFeatureSuggestions.target("GetAppInfo", path))
        assertEquals("android.display.brightness.set", ShortXFeatureSuggestions.target("SetAutoBrightness", path))
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
        assertEquals("accessibility.click_text", ShortXFeatureSuggestions.target("FindAndClickMatchedView", path))
        assertEquals("accessibility.click_view_id", ShortXFeatureSuggestions.target("FindAndClickViewById", path))
        assertEquals("android.toast.show", ShortXFeatureSuggestions.target("ShowToast", path))
        assertEquals("android.uri.open", ShortXFeatureSuggestions.target("OpenUrl", path))
        assertEquals("android.torch.set", ShortXFeatureSuggestions.target("ToggleFlashLight", path))
        assertEquals("android.dnd.set", ShortXFeatureSuggestions.target("ToggleDND", path))
        assertEquals("android.bluetooth.set", ShortXFeatureSuggestions.target("ToggleBT", path))
        assertEquals("android.wifi.set", ShortXFeatureSuggestions.target("ToggleWifi", path))
        assertEquals("android.airplane_mode.set", ShortXFeatureSuggestions.target("SetAPMModeEnabled", path))
        assertEquals("android.mobile_data.set", ShortXFeatureSuggestions.target("ToggleData", path))
        assertEquals("android.notification.show", ShortXFeatureSuggestions.target("PostNotification", path))
        assertEquals("android.notification.dismiss", ShortXFeatureSuggestions.target("RemoveNotificationForPackageByPkg", path))
        assertEquals("android.notification.open", ShortXFeatureSuggestions.target("ClickNotification", path))
        assertEquals("android.notification.action", ShortXFeatureSuggestions.target("ClickNotificationActionButton", path))
        assertEquals("android.vibrate", ShortXFeatureSuggestions.target("Vibrate", path))
        assertEquals("android.audio.ringer_mode.set", ShortXFeatureSuggestions.target("SetRingerMode", path))
        assertEquals("android.audio.volume.set", ShortXFeatureSuggestions.target("SetVolume", path))
        assertEquals("android.audio.volume.adjust", ShortXFeatureSuggestions.target("AdjustVolume", path))
        assertEquals("system.notifications.expand", ShortXFeatureSuggestions.target("ExpandNotification", path))
        assertEquals("android.sms.compose", ShortXFeatureSuggestions.target("SendSMS", path))
    }

    @Test fun `facts suggest matching runtime events`() {
        val path = "rule[0].fact[0]"
        assertEquals("android.event.airplane_mode_changed", ShortXFeatureSuggestions.target("APMStatusChanged", path))
        assertEquals("android.event.bluetooth_state", ShortXFeatureSuggestions.target("BTStatusChanged", path))
        assertEquals("android.event.dark_mode_changed", ShortXFeatureSuggestions.target("DarkModeStatusChanged", path))
        assertEquals("android.event.nfc_state_changed", ShortXFeatureSuggestions.target("NFCStatusChanged", path))
        assertEquals("android.event.nfc_tag", ShortXFeatureSuggestions.target("NFCTagDiscover", path))
        assertEquals("android.event.auto_rotate_changed", ShortXFeatureSuggestions.target("ScreenRotateTrigger", path))
        assertEquals("android.event.battery_changed", ShortXFeatureSuggestions.target("BatteryTemperatureChanged", path))
        assertEquals("android.event.power_connected", ShortXFeatureSuggestions.target("ChargerPlug", path))
        assertEquals("android.event.power_disconnected", ShortXFeatureSuggestions.target("ChargerUnplug", path))
        assertEquals("android.event.headset_changed", ShortXFeatureSuggestions.target("HeadsetPlug", path))
        assertEquals("android.event.package_added", ShortXFeatureSuggestions.target("AppAdded", path))
        assertEquals("android.event.package_removed", ShortXFeatureSuggestions.target("AppRemoved", path))
        assertEquals("android.event.package_replaced", ShortXFeatureSuggestions.target("AppUpdated", path))
        assertEquals("android.event.clipboard_changed", ShortXFeatureSuggestions.target("ClipboardContentChanged", path))
        assertEquals("android.event.notification_posted", ShortXFeatureSuggestions.target("NotificationPosted", path))
        assertEquals("android.event.notification_removed", ShortXFeatureSuggestions.target("NotificationRemoved", path))
        assertEquals("android.event.broadcast", ShortXFeatureSuggestions.target("Broadcast", path))
        assertEquals("android.event.phone_state_changed", ShortXFeatureSuggestions.target("CallStateChanged", path))
        assertEquals("android.event.shake", ShortXFeatureSuggestions.target("ShakeDevice", path))
        assertEquals("android.event.sensor_value", ShortXFeatureSuggestions.target("LightSensor", path))
        assertEquals("android.event.wifi_changed", ShortXFeatureSuggestions.target("WifiConnectedTo", path))
        assertEquals("android.event.network_changed", ShortXFeatureSuggestions.target("VPNConnected", path))
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
        assertEquals("android.condition.charging_source", ShortXFeatureSuggestions.target("PlugType", path))
        assertEquals("android.condition.notification_active", ShortXFeatureSuggestions.target("AppHasNotification", path))
        assertEquals("time.condition.time_window", ShortXFeatureSuggestions.target("TimeInRange", path))
        assertEquals("android.condition.device_locked", ShortXFeatureSuggestions.target("KeyguardIsLocked", path))
        assertEquals("android.condition.airplane_mode", ShortXFeatureSuggestions.target("RequireAPMMode", path))
        assertEquals("android.condition.wifi_network", ShortXFeatureSuggestions.target("RequireWifiConnected", path))
        assertEquals("android.condition.audio.ringer_mode", ShortXFeatureSuggestions.target("RequireRingerMode", path))
        assertEquals("android.condition.phone_call_state", ShortXFeatureSuggestions.target("IsRinging", path))
    }

    @Test fun `different semantics are deliberately not guessed`() {
        val action = "rule[0].action[0]"
        val condition = "rule[0].condition[0]"
        assertNull(ShortXFeatureSuggestions.target("AreaScreenshot", action))
        assertNull(ShortXFeatureSuggestions.target("SetHotSpotEnabled", action))
        assertNull(ShortXFeatureSuggestions.target("RequireScreenRotate", condition))
        assertNull(ShortXFeatureSuggestions.target("CurrentActivity", condition))
        assertNull(ShortXFeatureSuggestions.target("FlashlightIsOn", condition))
    }
}
