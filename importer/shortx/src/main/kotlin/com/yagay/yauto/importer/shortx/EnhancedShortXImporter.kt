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

/**
 * APK-verified ShortX 1.11 source-type catalogue mapped onto existing canonical YAuto features.
 * These are hints only: a compatibility node keeps the complete source payload until a field-level
 * decoder proves that the conversion is lossless.
 */
internal object ShortXFeatureSuggestions {
    fun target(sourceType: String?, sourcePath: String): String? {
        val name = sourceType?.substringAfterLast('/')?.substringAfterLast('.')?.substringAfterLast('$').orEmpty()
        return when {
            ".action[" in sourcePath -> action(name)
            ".fact[" in sourcePath -> fact(name)
            ".condition[" in sourcePath -> condition(name)
            else -> null
        }
    }

    private fun action(name: String): String? = when (name) {
        "SetNFCEnabled", "ToggleNFC" -> "android.nfc.set"
        "SetLocationEnabled", "ToggleLocation" -> "android.location.enabled.set"
        "SetScreenRotate" -> "android.display.rotation.set"
        "SetScreenTimeout" -> "android.display.screen_timeout.set"
        "SetDarkModeEnabled", "ToggleDarkMode" -> "android.display.dark_mode.set"
        "StayAwake", "ScreenStayAwake" -> "android.power.stay_awake.set"
        "SetMasterSync" -> "android.sync.master.set"
        "TTS" -> "android.tts.speak"
        "WakeupScreen", "ScreenOn" -> "android.screen.wake"
        "SleepScreen", "LockDeviceNow" -> "system.screen.sleep"
        "InjectKeyCode", "InjectCombineKeyCode" -> "android.input.keyevent"
        "TakeScreenshot", "Screenshot" -> "android.screenshot.capture"
        "MediaPlaybackAction", "MediaPlayback" -> "android.media.transport"
        "SetAppEnabled", "SetAppEnabledByPkg", "DisableApp", "DisableAppByPkg" -> "android.app.enabled.set"
        "SetAppInactive", "SetAppInactiveByPkg" -> "android.app.inactive.set"
        "SetAppSuspend", "SetAppSuspendByPkg" -> "android.app.suspended.set"
        "GetAppInfo" -> "android.app.package_info"
        "ScreenBrightness", "SetBrightness", "SetAutoBrightness", "ToggleAutoBrightness", "ContinuousAdjustBrightness" -> "android.display.brightness.set"
        "SetWallpaper" -> "android.wallpaper.set"
        "ReadClipboard" -> "android.clipboard.get"
        "WriteClipboard" -> "android.clipboard.set"
        "HttpRequest" -> "android.http.request"
        "LaunchApp", "LaunchAppByPkg" -> "android.app.launch"
        "StopApp", "StopAppByPkg", "StopCurrentApp" -> "android.app.force_stop"
        "ShellCommand" -> "system.shell.execute"
        "InputTap" -> "accessibility.gesture.tap"
        "InputSwipe" -> "accessibility.gesture.swipe"
        "InputText" -> "accessibility.input_text"
        "FindAndClickViewByText", "FindAndClickMatchedView" -> "accessibility.click_text"
        "FindAndClickViewById" -> "accessibility.click_view_id"
        "ShowToast" -> "android.toast.show"
        "OpenUrl" -> "android.uri.open"
        "SetFlashLightEnabled", "ToggleFlashLight" -> "android.torch.set"
        "SetDNDEnabled", "ToggleDND" -> "android.dnd.set"
        "SetBTEnabled", "ToggleBT" -> "android.bluetooth.set"
        "SetWifiEnabled", "ToggleWifi" -> "android.wifi.set"
        "SetAPMModeEnabled" -> "android.airplane_mode.set"
        "SetDataEnabled", "ToggleData" -> "android.mobile_data.set"
        "PostNotification" -> "android.notification.show"
        "RemoveNotification", "RemoveNotificationForPackage", "RemoveNotificationForPackageByPkg" -> "android.notification.dismiss"
        "ClickNotification" -> "android.notification.open"
        "ClickNotificationActionButton" -> "android.notification.action"
        "Vibrate" -> "android.vibrate"
        "SetRingerMode" -> "android.audio.ringer_mode.set"
        "SetVolume" -> "android.audio.volume.set"
        "AdjustVolume", "ContinuousAdjustVolume" -> "android.audio.volume.adjust"
        "PlayRingtone" -> "android.audio.play"
        "ExpandNotification" -> "system.notifications.expand"
        "SendSMS" -> "android.sms.compose"
        "ShareContent" -> "android.share.text"
        "Delay" -> "core.delay"
        "KillProcessByName" -> "android.process.kill_by_name"
        "StartAppProcess", "StartAppProcessByPkg" -> "android.app.launch"
        "ShowOverlayButton" -> "surface.overlay.show"
        "HideOverlayButton" -> "surface.overlay.hide"
        "SelectScreenArea" -> "surface.region_selector.show"
        "AreaScreenshot" -> "accessibility.screenshot.capture"
        "DrawBoard", "ShowDrawBoard" -> "surface.draw_board.show"
        "ShowPieMenu", "PieMenu" -> "surface.pie.show"
        "ShowSidebar", "Sidebar" -> "surface.sidebar.show"
        "ShowFloatButton", "FloatingButton" -> "surface.bubble.show"
        "ExecuteMVEL" -> "script.mvel.execute"
        else -> ShortXMappings.suggestedActionFeature(name)
    }

    private fun fact(name: String): String? = when (name) {
        "APMStatusChanged" -> "android.event.airplane_mode_changed"
        "BTStatusChanged" -> "android.event.bluetooth_state"
        "BatteryLevelChanged", "BatteryTemperatureChanged" -> "android.event.battery_changed"
        "ChargerPlug" -> "android.event.power_connected"
        "ChargerUnplug" -> "android.event.power_disconnected"
        "ClipboardContentChanged" -> "android.event.clipboard_changed"
        "DarkModeStatusChanged" -> "android.event.dark_mode_changed"
        "LocationStatusChanged" -> "android.event.location_mode_changed"
        "NFCStatusChanged" -> "android.event.nfc_state_changed"
        "NFCTagDiscover" -> "android.event.nfc_tag"
        "ScreenRotate", "ScreenRotateTrigger" -> "android.event.orientation_changed"
        "ScreenOn" -> "android.event.screen_on"
        "ScreenOff" -> "android.event.screen_off"
        "UserPresent" -> "android.event.user_present"
        "HeadsetPlug" -> "android.event.headset_changed"
        "AppAdded" -> "android.event.package_added"
        "AppRemoved" -> "android.event.package_removed"
        "AppUpdated" -> "android.event.package_replaced"
        "NotificationPosted" -> "android.event.notification_posted"
        "NotificationRemoved" -> "android.event.notification_removed"
        "Broadcast" -> "android.event.broadcast"
        "CallStateChanged" -> "android.event.phone_state_changed"
        "ShakeDevice" -> "android.event.shake"
        "AccelerometerSensor", "LightSensor", "ProximitySensor", "SensorValueTrend" -> "android.event.sensor_value"
        "WifiConnectedTo", "WifiDisconnectedFrom", "WifiStatusChanged", "ConnectedWifiSignalLevelChanged" -> "android.event.wifi_changed"
        "VPNConnected", "VPNDisconnected" -> "android.event.network_changed"
        "MethodHook" -> "android.event.lsposed_method_called"
        "FingerprintGesture", "FingerprintGestureTrigger" -> "android.event.fingerprint_gesture"
        "ScreenTextAppeared", "ScreenContentTrigger" -> "android.event.screen_text_appeared"
        "ActivityStarted" -> "android.event.activity_lifecycle"
        "AppProcessStarted" -> "android.event.app_process_started"
        "AppProcessRemoved" -> "android.event.app_background"
        "MediaStoreInsert" -> "android.event.media_store_inserted"
        "MediaStoreDelete" -> "android.event.media_store_deleted"
        else -> null
    }

    private fun condition(name: String): String? = when (name) {
        "BatteryPercent", "BatteryLevel" -> "android.condition.battery_level"
        "BatteryTemperature" -> "android.condition.battery_temperature"
        "IsHeadsetPlug" -> "android.condition.headset_connected"
        "AppIsRunning", "AppIsNotRunning", "ProcessIsRunning" -> "android.condition.app_process_running"
        "ScreenIsOn" -> "android.condition.screen"
        "AvailableMemory" -> "android.condition.memory_available"
        "ChargeState", "PlugState", "PlugType" -> "android.condition.charging_source"
        "AppHasNotification" -> "android.condition.notification_active"
        "TimeInRange", "TheXXTimeToday", "TheXXTimeTodayScope" -> "time.condition.time_window"
        "KeyguardIsLocked" -> "android.condition.device_locked"
        "RequireAPMMode" -> "android.condition.airplane_mode"
        "RequireWifiConnected", "RequireWifiDisconnected", "ConnectedWifiSignal" -> "android.condition.wifi_network"
        "RequireRingerMode" -> "android.condition.audio.ringer_mode"
        "ScreenStayAwake" -> "android.condition.stay_awake_while_charging"
        "FlashlightIsOn" -> "android.condition.torch_on"
        "MatchMVEL" -> "script.mvel.condition"
        "IsInCall", "IsRinging" -> "android.condition.phone_call_state"
        "FoldAngle", "FoldAngleCondition", "HingeAngle" -> "android.condition.hinge_angle"
        "RequireScreenRotate" -> "android.condition.reference.display_rotation"
        "ScreenOrientationIsPort" -> "android.condition.reference.orientation"
        "EvaluateScreenOnTime" -> "android.condition.screen_on_time"
        "True" -> "core.boolean"
        "False" -> "core.boolean"
        else -> null
    }
}
