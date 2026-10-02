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
                "ScreenOnAction" -> "android.screen.wake"
                "InputKeyEventAction" -> "android.input.keyevent"
                "TakeScreenshotAction" -> "android.screenshot.capture"
                "ControlMediaAction" -> "android.media.transport"
                "SetAlarmClockAction" -> "android.alarm.set"
                "AddCalendarEntryAction" -> "android.calendar.event.add"

                // Verified against MacroDroid 5.67.8 class names. These remain hints only until
                // every behaviorally relevant source field has a lossless decoder.
                "ClearAppDataAction" -> "android.app.data.clear"
                "KillBackgroundAppAction" -> "android.app.background.kill"
                "DisableAppAction" -> "android.app.enabled.set"
                "DisplayDensityAction" -> "android.display.density.set"
                "FontScaleAction" -> "android.display.font_scale.set"
                "ForceScreenRotationAction" -> "android.display.rotation.set"
                "RebootAction" -> "android.device.reboot"
                "SecureSettingsAction", "SystemSettingAction" -> "android.settings.value.put"
                "GetInstalledAppsAction" -> "android.app.installed.list"
                "UpdateClipboardAction" -> "android.clipboard.set"
                "JsonParseAction" -> "data.json.parse"
                "JsonOutputAction" -> "data.json.stringify"
                "UDPCommandAction" -> "android.network.udp.send"
                "OpenFileAction" -> "android.file.open"
                "SetWallpaperAction" -> "android.wallpaper.set"
                "ReadFileAction" -> "file.read_text"
                "WriteToFileAction" -> "file.write_text"
                "SetAutoSyncAction" -> "android.sync.master.set"
                "LaunchHomeScreenAction" -> "android.home.launch"
                else -> null
            }
            SourceFeatureKind.EVENT -> when (sourceType) {
                "AutoRotateChangeTrigger" -> "android.event.auto_rotate_changed"
                "BatterySaverTrigger" -> "android.event.power_save_changed"
                "DarkThemeTrigger" -> "android.event.dark_mode_changed"
                "NFCStateTrigger" -> "android.event.nfc_state_changed"
                "BatteryLevelTrigger", "BatteryTemperatureTrigger" -> "android.event.battery_changed"
                "HeadphonesTrigger" -> "android.event.headset_changed"
                else -> null
            }
            SourceFeatureKind.CONDITION -> when (sourceType) {
                "AutoRotateConstraint" -> "android.condition.auto_rotate"
                "BatterySaverStateConstraint" -> "android.condition.power_save"
                "DarkThemeConstraint" -> "android.condition.dark_mode"
                "LocationModeConstraint" -> "android.condition.location_enabled"
                "NFCStateConstraint" -> "android.condition.nfc_enabled"
                "BatteryLevelConstraint" -> "android.condition.battery_level"
                "BatteryTemperatureConstraint" -> "android.condition.battery_temperature"
                "HeadphonesConnectionConstraint" -> "android.condition.headset_connected"
                "AppEnabledConstraint" -> "android.condition.app_enabled"
                "ApplicationInstalledConstraint" -> "android.condition.app_installed"
                "TimeSinceBootConstraint" -> "android.condition.uptime_range"
                "TimeOfDayConstraint" -> "time.condition.time_window"
                "SystemSettingConstraint" -> "android.condition.setting_matches"
                "IpAddressConstraint" -> "android.condition.local_address_match"
                "IsRootedConstraint", "ShizukuStateConstraint" -> "android.condition.privileged_backend_available"
                "AutoSyncConstraint" -> "android.condition.master_sync"
                "DeviceLockedConstraint" -> "android.condition.device_locked"
                "MusicActiveConstraint" -> "android.condition.music_active"
                "PriorityModeConstraint" -> "android.condition.dnd_filter"
                else -> null
            }
            SourceFeatureKind.STATE -> null
        } ?: MacroDroidMappings.mapper.targetId(sourceType, kind)
    }
}
