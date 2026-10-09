package com.yagay.yauto.ui.editor

import androidx.annotation.StringRes
import com.yagay.yauto.ui.design.R as TextR

/**
 * An approved one-entry / multiple-implementation picker operation.
 *
 * Do not merge actions merely because they share a category, noun, or similar
 * wording. Each pair is verified against ONE MacroDroid resource key, falling
 * back to ONE ShortX key when MacroDroid has no matching action.
 *
 * The stable feature ID is always preserved when the user saves a choice.
 * Evidence: tools/verified_picker_merges.csv; enforced by the CI audit.
 */
internal data class UnifiedFeatureSpec(
    val id: String,
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val memberIds: List<String>,
)

internal val UNIFIED_FEATURE_SPECS = listOf(
    // MacroDroid action_clipboard: the same clipboard-writing operation.
    UnifiedFeatureSpec(
        "clipboard_write",
        TextR.string.verified_family_clipboard_write,
        TextR.string.verified_family_clipboard_write_subtitle,
        listOf("android.clipboard.set", "android.clipboard.write"),
    ),
    // ShortX ui.action.read.clipboard: MacroDroid has no validated match for both IDs.
    UnifiedFeatureSpec(
        "clipboard_read",
        TextR.string.verified_family_clipboard_read,
        TextR.string.verified_family_clipboard_read_subtitle,
        listOf("android.clipboard.get", "android.clipboard.read"),
    ),
    // MacroDroid action_take_screenshot: alternative implementations of one action.
    UnifiedFeatureSpec(
        "screenshot_capture",
        TextR.string.verified_family_screenshot_capture,
        TextR.string.verified_family_screenshot_capture_subtitle,
        listOf("android.screen.screenshot", "android.screenshot.capture"),
    ),
    // MacroDroid 5.67.8: assets/ai/actions/StopWatchAction.yaml -> m_option.
    UnifiedFeatureSpec(
        "stopwatch",
        TextR.string.verified_family_stopwatch,
        TextR.string.verified_family_stopwatch_subtitle,
        listOf("android.stopwatch.start", "android.stopwatch.pause", "android.stopwatch.reset"),
    ),
    // MacroDroid 5.67.8: assets/ai/actions/FileOperationAllFilesAction.yaml -> option.
    UnifiedFeatureSpec(
        "file_operations",
        TextR.string.verified_family_file_operations,
        TextR.string.verified_family_file_operations_subtitle,
        listOf("file.copy", "file.move", "file.delete", "file.mkdir", "file.list"),
    ),
    // MacroDroid 5.67.8: assets/ai/actions/VibrateAction.yaml -> m_vibratePattern.
    UnifiedFeatureSpec(
        "vibration_pattern",
        TextR.string.verified_family_vibration_pattern,
        TextR.string.verified_family_vibration_pattern_subtitle,
        listOf("android.vibration.vibrate", "android.vibration.pattern"),
    ),
    // MacroDroid 5.67.8: assets/ai/actions/RebootAction.yaml -> option.
    UnifiedFeatureSpec(
        "device_power",
        TextR.string.verified_family_device_power,
        TextR.string.verified_family_device_power_subtitle,
        listOf("android.device.reboot", "android.device.shutdown"),
    ),
    // MacroDroid 5.67.8: assets/ai/actions/RecordVideoAction.yaml -> option.
    UnifiedFeatureSpec(
        "camera_video",
        TextR.string.verified_family_camera_video,
        TextR.string.verified_family_camera_video_subtitle,
        listOf("android.camera.video.start", "android.camera.video.stop"),
    ),
    // MacroDroid 5.67.8: assets/ai/actions/RecordMicrophoneAction.yaml -> m_secondsToRecordFor.
    UnifiedFeatureSpec(
        "audio_recording",
        TextR.string.verified_family_audio_recording,
        TextR.string.verified_family_audio_recording_subtitle,
        listOf("android.audio.record.start", "android.audio.record.stop"),
    ),
    // MacroDroid 5.67.8: assets/ai/triggers/ScreenOnOffTrigger.yaml -> m_screenOn.
    UnifiedFeatureSpec(
        "screen_power_events",
        TextR.string.verified_family_screen_power_events,
        TextR.string.verified_family_screen_power_events_subtitle,
        listOf("android.event.screen_on", "android.event.screen_off"),
    ),
    // MacroDroid 5.67.8: assets/ai/triggers/ApplicationInstalledRemovedTrigger.yaml -> m_updated.
    UnifiedFeatureSpec(
        "package_install_events",
        TextR.string.verified_family_package_install_events,
        TextR.string.verified_family_package_install_events_subtitle,
        listOf("android.event.package_added", "android.event.package_removed", "android.event.package_replaced"),
    ),
    // MacroDroid 5.67.8: assets/ai/triggers/NotificationTrigger.yaml -> m_option.
    UnifiedFeatureSpec(
        "notification_received_cleared",
        TextR.string.verified_family_notification_received_cleared,
        TextR.string.verified_family_notification_received_cleared_subtitle,
        listOf("android.event.notification_posted", "android.event.notification_removed"),
    ),
    // MacroDroid 5.67.8: assets/ai/triggers/ExternalPowerTrigger.yaml -> m_powerConnected.
    UnifiedFeatureSpec(
        "power_connection_events",
        TextR.string.verified_family_power_connection_events,
        TextR.string.verified_family_power_connection_events_subtitle,
        listOf("android.event.power_connected", "android.event.power_disconnected"),
    ),
    // MacroDroid 5.67.8: assets/ai/actions/SetWifiAction.yaml -> m_state.
    UnifiedFeatureSpec(
        "wifi_control",
        TextR.string.verified_family_wifi_control,
        TextR.string.verified_family_wifi_control_subtitle,
        listOf("android.wifi.set", "android.wifi.network.connect"),
    ),
    // Same approved MacroDroid source key: constraint_music_active; method chosen as a parameter.
    UnifiedFeatureSpec(
        "music_activity_condition",
        TextR.string.verified_family_music_activity,
        TextR.string.verified_family_music_activity_subtitle,
        listOf("android.condition.music_active", "android.condition.audio.music_active"),
    ),
    // Same approved MacroDroid source key: constraint_music_active; method chosen as a parameter.
    UnifiedFeatureSpec(
        "music_activity_state",
        TextR.string.verified_family_music_activity,
        TextR.string.verified_family_music_activity_subtitle,
        listOf("android.state.music_active", "android.state.audio.music_active"),
    ),
    // Same approved MacroDroid source key: constraint_device_orientation; method chosen as a parameter.
    UnifiedFeatureSpec(
        "device_orientation_condition",
        TextR.string.verified_family_device_orientation,
        TextR.string.verified_family_device_orientation_subtitle,
        listOf("android.condition.orientation", "android.condition.reference.orientation"),
    ),
    // Same approved MacroDroid source key: constraint_device_orientation; method chosen as a parameter.
    UnifiedFeatureSpec(
        "device_orientation_state",
        TextR.string.verified_family_device_orientation,
        TextR.string.verified_family_device_orientation_subtitle,
        listOf("android.state.orientation", "android.state.reference.orientation"),
    ),
)
