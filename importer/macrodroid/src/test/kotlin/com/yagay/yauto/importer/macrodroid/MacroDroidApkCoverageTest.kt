package com.yagay.yauto.importer.macrodroid

import com.yagay.yauto.core.importer.SourceFeatureKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MacroDroidApkCoverageTest {
    private val mapper = MacroDroidFeatureSuggestions.mapper

    @Test fun `MacroDroid 5_67_8 actions reuse existing YAuto capabilities`() {
        val expected = mapOf(
            "SetAirplaneModeAction" to "android.airplane_mode.set",
            "SetBluetoothAction" to "android.bluetooth.set",
            "SetDataAction" to "android.mobile_data.set",
            "SetWifiAction" to "android.wifi.set",
            "CameraFlashLightAction" to "android.torch.set",
            "MuteMicrophoneAction" to "android.audio.microphone_mute.set",
            "SpeakerPhoneAction" to "android.audio.speakerphone.set",
            "VibrateAction" to "android.vibrate",
            "VolumeIncrementDecrementAction" to "android.audio.volume.adjust",
            "PlaySoundAction" to "android.audio.play",
            "ShareTextAction" to "android.share.text",
            "ShellScriptAction" to "system.shell.execute",
            "ClearNotificationsAction" to "android.notification.dismiss_all",
            "HttpRequestAction" to "android.http.request",
            "LaunchHomeScreenAction" to "android.home.launch",
            "SetAutoSyncAction" to "android.sync.master.set",
        )
        expected.forEach { (source, target) ->
            assertEquals(source, target, mapper.targetId(source, SourceFeatureKind.ACTION))
        }
    }

    @Test fun `MacroDroid triggers reuse matching YAuto events`() {
        val expected = mapOf(
            "BootTrigger" to "android.event.boot",
            "AirplaneModeTrigger" to "android.event.airplane_mode_changed",
            "ClipboardChangeTrigger" to "android.event.clipboard_changed",
            "DeviceUnlockedTrigger" to "android.event.user_present",
            "GPSEnabledTrigger" to "android.event.location_mode_changed",
            "IncomingSMSTrigger" to "android.event.sms_received",
            "IncomingCallTrigger" to "android.event.phone_state_changed",
            "IntentReceivedTrigger" to "android.event.broadcast",
            "NFCTrigger" to "android.event.nfc_tag",
            "NetworkTransportChangeTrigger" to "android.event.network_changed",
            "LightSensorTrigger" to "android.event.sensor_value",
            "ShakeDeviceTrigger" to "android.event.shake",
            "WifiConnectionTrigger" to "android.event.wifi_changed",
        )
        expected.forEach { (source, target) ->
            assertEquals(source, target, mapper.targetId(source, SourceFeatureKind.EVENT))
        }
    }

    @Test fun `MacroDroid constraints reuse matching YAuto conditions`() {
        val expected = mapOf(
            "AirplaneModeConstraint" to "android.condition.airplane_mode",
            "BrightnessConstraint" to "android.condition.brightness",
            "DayOfWeekConstraint" to "time.condition.weekday",
            "ExternalPowerConstraint" to "android.condition.charging_source",
            "InCallConstraint" to "android.condition.phone_call_state",
            "NotificationPresentConstraint" to "android.condition.notification_active",
            "NotificationVolumeConstraint" to "android.condition.audio.stream_volume",
            "ScreenOnOffConstraint" to "android.condition.screen",
            "SpeakerPhoneConstraint" to "android.condition.audio.speakerphone",
            "VpnConstraint" to "android.condition.network_profile",
            "WifiConstraint" to "android.condition.wifi_network",
        )
        expected.forEach { (source, target) ->
            assertEquals(source, target, mapper.targetId(source, SourceFeatureKind.CONDITION))
        }
    }
}
