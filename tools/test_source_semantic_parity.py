"""Prevent unsafe MacroDroid/ShortX source hints from masquerading as native capabilities."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHORTX_HINTS = ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/EnhancedShortXImporter.kt"
SHORTX_PARSER = ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/ShortXMappings.kt"
MACRO_HINTS = ROOT / "importer/macrodroid/src/main/kotlin/com/yagay/yauto/importer/macrodroid/MacroDroidFeatureSuggestions.kt"
COMMUNICATION = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidCommunicationFeaturePack.kt"
SMS_IMPLEMENTATION = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidPersonalDataFeaturePack.kt"
SURFACE = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidSurfaceFeaturePack.kt"
CONTROLLER = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlaySurfaceController.kt"


class SourceSemanticParityTests(unittest.TestCase):
    def test_direct_sms_is_distinct_from_composing_sms(self):
        native = SMS_IMPLEMENTATION.read_text(encoding="utf-8")
        compose = COMMUNICATION.read_text(encoding="utf-8")
        shortx = SHORTX_HINTS.read_text(encoding="utf-8")
        parser = SHORTX_PARSER.read_text(encoding="utf-8")
        self.assertIn('FeatureId("android.sms.send")', native)
        self.assertIn('Manifest.permission.SEND_SMS', native)
        self.assertIn('sendTextMessage(', native)
        self.assertIn('sendMultipartTextMessage(', native)
        self.assertIn('AccessRequirement.SMS', native)
        self.assertIn('FeatureId("android.sms.compose")', compose.replace('intentAction(registry, "android.sms.compose"', 'FeatureId("android.sms.compose")'))
        self.assertNotIn('FeatureId("android.sms.send")', compose)
        self.assertIn('"SendSMS" -> "android.sms.send"', shortx)
        self.assertIn('"SendSMS" -> "android.sms.send"', parser)
        self.assertNotIn('"SendSMS" -> "android.sms.compose"', shortx + parser)

    def test_stop_recorder_finalizes_captured_points(self):
        shortx = SHORTX_HINTS.read_text(encoding="utf-8")
        surface = SURFACE.read_text(encoding="utf-8")
        controller = CONTROLLER.read_text(encoding="utf-8")
        self.assertIn('"StopGestureRecording" -> "surface.gesture_recording.stop"', shortx)
        self.assertIn('FeatureId("surface.gesture_recording.stop")', surface)
        self.assertIn('fun stopGestureRecorder(', controller)
        self.assertIn('recorder.finishRecording()', controller)
        self.assertIn('SurfaceRuntimeBridge.emit(id, "gesture_recording_stopped")', controller)

    def test_shortx_incompatible_actions_keep_original_payload(self):
        hint = SHORTX_HINTS.read_text(encoding="utf-8")
        self.assertIn('"StartAppProcess", "StartAppProcessByPkg" -> null', hint)
        for source in (
            "SetStatusBarIcon", "RemoveStatusBarIcon",
            "ShowStatusBarChip", "HideStatusBarClip", "PluginAction",
        ):
            self.assertIn('"' + source + '" -> null', hint, source)
        self.assertNotIn('"StartAppProcess", "StartAppProcessByPkg" -> "android.app.launch"', hint)
        self.assertNotIn('"SetStatusBarIcon" -> "android.notification.ppn.show"', hint)
        self.assertIn('"AreaScreenshot" -> "android.screenshot.area_select"', hint)
        surface = SURFACE.read_text(encoding="utf-8")
        self.assertIn('FeatureId("android.screenshot.area_select")', surface)
        self.assertIn('onSelected: ((Int, Int, Int, Int) -> Unit)? = null', CONTROLLER.read_text(encoding="utf-8"))
        self.assertIn('"AreaScreenshot" -> noFieldAction(', SHORTX_PARSER.read_text(encoding="utf-8"))
        self.assertIn('"Toggle5G" -> "android.telephony.5g.toggle"', hint)
        native = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/Android5gToggleFeaturePack.kt").read_text(encoding="utf-8")
        self.assertIn('FeatureId("android.telephony.5g.toggle")', native)
        self.assertIn('parseAllowedNetworkTypes(', native)
        self.assertIn('planFiveGMode(', native)

    def test_service_control_replaces_activity_intent_hint_without_losing_legacy_stop(self):
        pack = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidShortXParityFeaturePack.kt").read_text(encoding="utf-8")
        hints = SHORTX_HINTS.read_text(encoding="utf-8")
        parser = SHORTX_PARSER.read_text(encoding="utf-8")
        self.assertIn('FeatureId("android.service.control")', pack)
        self.assertIn('aliases = setOf("android.service.stop")', pack)
        self.assertIn('"android.service.stop" to mapOf("mode" to ConfigValue.StringValue("stop"))', pack)
        self.assertIn('"StartService", "StopService" -> "android.service.control"', hints)
        self.assertIn('"StartService", "StopService" -> "android.service.control"', parser)
        self.assertNotIn('"StartService" -> "android.external.intent.invoke"', hints)
        self.assertNotIn('"StopService" -> "android.service.stop"', hints)
        self.assertIn('FieldSchema.Text("components"' , pack)
        self.assertIn('FieldSchema.Text("intentAction"' , pack)
        self.assertIn('FieldSchema.Text("dataUri"' , pack)

    def test_real_status_bar_bridge_and_area_selection_not_notification_fallback(self):
        xposed = (ROOT / "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/YAutoXposedModule.kt").read_text(encoding="utf-8")
        backend = (ROOT / "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedBackend.kt").read_text(encoding="utf-8")
        pack = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidStatusIconFeaturePack.kt").read_text(encoding="utf-8")
        surface = SURFACE.read_text(encoding="utf-8")
        for operation in ("STATUS_ICON_SET", "STATUS_ICON_REMOVE"):
            self.assertIn("SystemBridgeProtocol." + operation, xposed)
            self.assertIn("SystemBridgeProtocol." + operation, backend)
        self.assertIn('FeatureId("android.status_icon.control")', pack)
        self.assertIn('CapabilityIds.LSPOSED', pack)
        self.assertIn('"yauto_" + requestedSlot', xposed)
        self.assertIn('FeatureId("android.screenshot.area_select")', surface)
        self.assertIn('operationId = "accessibility.screenshot.capture"', surface)
        self.assertIn('withTimeoutOrNull(maxWait)', surface)
        self.assertIn('controller.hide(id)', surface)

    def test_shortx_scoped_status_icons_decode_without_overriding_systemui_slots(self):
        shortx = SHORTX_PARSER.read_text(encoding="utf-8")
        self.assertIn('"SetStatusBarIcon" -> nativeStatusBarIcon(', shortx)
        self.assertIn('"RemoveStatusBarIcon" -> nativeStatusBarIcon(', shortx)
        self.assertIn('"StopService" -> nativeStopServices(', shortx)
        self.assertIn('input.startsWith("yauto_")', shortx)
        self.assertIn('jsonBusinessKeysSafe(', shortx)

    def test_android_16_native_live_update_is_not_mislabelled_shortx_overlay(self):
        manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
        pack = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidLiveUpdateFeaturePack.kt").read_text(encoding="utf-8")
        shortx = SHORTX_HINTS.read_text(encoding="utf-8")
        self.assertIn('android.permission.POST_PROMOTED_NOTIFICATIONS', manifest)
        self.assertIn('FeatureId("android.notification.live_update.control")', pack)
        self.assertIn('liveUpdateRequestPromotion(', pack)
        self.assertIn('liveUpdatePromotionAllowed(manager)', pack)
        self.assertIn('liveUpdatePromotable(', pack)
        self.assertIn('manager.cancel(', pack)
        self.assertIn('"ShowStatusBarChip" -> null', shortx)
        self.assertIn('"HideStatusBarClip" -> null', shortx)
        self.assertNotIn('TYPE_APPLICATION_OVERLAY', pack)

    def test_shortx_service_intents_and_screen_time_convert_known_schema_only(self):
        mapper = SHORTX_PARSER.read_text(encoding="utf-8")
        pack = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidShortXParityFeaturePack.kt").read_text(encoding="utf-8")
        self.assertIn('"StartService" -> nativeJsonStartService(', mapper)
        self.assertIn('private fun nativeJsonStopServices(', mapper)
        self.assertIn('private fun shortXServiceExtraSafe(', mapper)
        self.assertIn('"intentExtrasJson" to ConfigValue.StringValue(extrasJson)', mapper)
        self.assertIn('"GetScreenOnTime" -> nativeGetScreenOnTime(', mapper)
        self.assertIn('"GetScreenOnTime" -> nativeJsonGetScreenOnTime(', mapper)
        self.assertIn('"resultVariable" to ConfigValue.StringValue("screenOnTime")', mapper)
        self.assertIn('FieldSchema.Text("intentExtrasJson"', pack)
        self.assertIn('serviceExtrasCommand(', pack)
        self.assertIn('"ShowGlobalActionsMenu" -> noFieldAction(', mapper)
        self.assertIn('"StopAudioRecording" -> noFieldAction(', mapper)

    def test_macro_screenshot_content_and_spotify_are_not_generic_triggers(self):
        macro = MACRO_HINTS.read_text(encoding="utf-8")
        self.assertIn('"ScreenshotContentTrigger" -> "android.event.screenshot_content"', macro)
        self.assertIn('FeatureId("android.event.screenshot_content")', (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidScreenshotContentFeaturePack.kt").read_text(encoding="utf-8"))
        self.assertIn('WorkspaceGatedEventSource(appGraph.workspace, setOf("android.event.screenshot_content"))', (ROOT / "app/src/main/kotlin/com/yagay/yauto/AutomationRuntimeService.kt").read_text(encoding="utf-8"))
        self.assertIn('"SpotifyTrigger" -> "android.event.spotify"', macro)
        self.assertIn('"ScreenTextAppearedTrigger" -> "android.event.screen_text_appeared"', macro)
        self.assertIn('"MediaTrackChangedTrigger" -> "android.event.media_track_changed"', macro)


if __name__ == "__main__":
    unittest.main()
