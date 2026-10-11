package com.yagay.yauto

import android.content.Context
import com.yagay.yauto.platform.android.*
import com.yagay.yauto.platform.accessibility.AccessibilityRuntimeBridge

/**
 * Single runtime event source catalog. The service owns the lifecycle; this file owns
 * registrations and subscription gating. Adding a source does not expand Service.onCreate().
 */
internal fun registerRuntimeEventSources(
    context: Context,
    appGraph: AppGraph,
    registerSource: (String, () -> AndroidEventSource) -> Unit,
) {
        registerSource("system-broadcast") { SystemBroadcastEventSource(context) }
        registerSource("reference-completion-broadcast") { ReferenceCompletionBroadcastEventSource(context) }
        registerSource("network") { NetworkEventSource(context) }
        registerSource("tethering") { TetheringEventSource(context) }
        registerSource("network-profile") { NetworkProfileEventSource(context) }
        registerSource("wifi-scan") { WifiScanEventSource(context) }
        registerSource("clipboard") { ClipboardEventSource(context) }
        registerSource("device-setting") { DeviceSettingEventSource(context) }
        registerSource("reference-runtime-signals") { ReferenceRuntimeSignalEventSource(context) }
        registerSource("audio-device") { AudioDeviceEventSource(context) }
        registerSource("audio-focus") { AudioFocusEventSource() }
        registerSource("bluetooth-device") { BluetoothDeviceEventSource(context) }
        registerSource("communication") { CommunicationEventSource(context) }
        registerSource("sim-subscriptions") { SubscriptionChangeEventSource(context) }
        registerSource("configured-weather") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.weather_changed")) { ConfiguredWeatherEventSource(context, appGraph.workspace) } }
        registerSource("midi-device") { MidiDeviceEventSource(context) }
        registerSource("usb-device") { UsbDeviceEventSource(context) }
        registerSource("personal-data") { PersonalDataEventSource(context) }
        registerSource("personal-changes") { PersonalChangeEventSource(context) }
        registerSource("configured-logcat") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.system_log_entry")) { ConfiguredLogcatEventSource(appGraph.workspace) } }
        registerSource("configured-ble") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.ble_advertisement", "android.event.ble_scan_failed")) { ConfiguredBleEventSource(context, appGraph.workspace) } }
        registerSource("configured-cell-tower") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.cell_tower_changed")) { ConfiguredCellTowerEventSource(context, appGraph.workspace) } }
        registerSource("configured-broadcast") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.broadcast")) { ConfiguredBroadcastEventSource(context, appGraph.workspace) } }
        registerSource("configured-sensor") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.sensor_value", "android.event.shake")) { ConfiguredSensorEventSource(context, appGraph.workspace) } }
        registerSource("configured-significant-motion") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.significant_motion")) { ConfiguredSignificantMotionEventSource(context, appGraph.workspace) } }
        registerSource("configured-location") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.geofence_transition", "android.event.location_update")) { ConfiguredLocationEventSource(context, appGraph.workspace) } }
        registerSource("configured-interval") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.interval")) { ConfiguredIntervalEventSource(appGraph.workspace) } }
        registerSource("configured-file") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.file_changed")) { ConfiguredFileEventSource(appGraph.workspace) } }
        registerSource("spotify") {
            WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.spotify")) {
                SpotifyBroadcastEventSource(context)
            }
        }
        registerSource("screenshot-content-ocr") {
            WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.screenshot_content")) {
                ScreenshotContentOcrEventSource(appGraph.workspace)
            }
        }
        registerSource("media-store") { MediaStoreEventSource(context) }
        registerSource("http-server") { HttpServerEventSource() }
        registerSource("stopwatch") { StopwatchEventSource() }
        registerSource("configured-data-usage") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.data_usage_threshold")) { ConfiguredDataUsageEventSource(context, appGraph.workspace) } }
        registerSource("runtime-parity") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.cpu_availability", "android.event.screen_on_duration", "android.event.user_present_first_after_boot")) { ConfiguredRuntimeParityEventSource(context, appGraph.workspace) } }
        registerSource("shortx-time") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.alarm_time", "android.event.fixed_in_period", "android.event.random_in_period")) { ConfiguredShortXTimeEventSource(appGraph.workspace) } }
        registerSource("sound-level") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.sound_level")) { ConfiguredSoundLevelEventSource(context, appGraph.workspace) } }
        registerSource("locale-plugin-events") { WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.plugin_locale")) { ConfiguredLocalePluginEventSource(context, appGraph.workspace) } }
        registerSource("macrodroid-residual") { MacroDroidResidualEventSource(context) }
        registerSource("hinge-angle") { HingeAngleEventSource(context) }
        registerSource("activity-recognition") {
            WorkspaceGatedEventSource(
                appGraph.workspace,
                setOf(
                    "android.event.activity_recognition",
                    "android.event.sleep_classification",
                    "android.event.sleep_transition",
                ),
            ) { ActivityRecognitionEventSource(context) }
        }
        registerSource("usage-foreground") {
            WorkspaceGatedEventSource(
                appGraph.workspace,
                setOf("android.event.app_foreground", "android.event.app_background"),
            ) {
                UsageStatsForegroundEventSource(
                    context = context,
                    primarySourceAvailable = { AccessibilityRuntimeBridge.currentWindow() != null },
                )
            }
        }
}
