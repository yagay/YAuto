"""Prevent unsafe MacroDroid/ShortX source hints from masquerading as native capabilities."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHORTX_HINTS = ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/EnhancedShortXImporter.kt"
SHORTX_PARSER = ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/ShortXMappings.kt"
MACRO_HINTS = ROOT / "importer/macrodroid/src/main/kotlin/com/yagay/yauto/importer/macrodroid/MacroDroidFeatureSuggestions.kt"
COMMUNICATION = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidCommunicationFeaturePack.kt"
SURFACE = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidSurfaceFeaturePack.kt"
CONTROLLER = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlaySurfaceController.kt"


class SourceSemanticParityTests(unittest.TestCase):
    def test_direct_sms_is_distinct_from_composing_sms(self):
        native = COMMUNICATION.read_text(encoding="utf-8")
        shortx = SHORTX_HINTS.read_text(encoding="utf-8")
        parser = SHORTX_PARSER.read_text(encoding="utf-8")
        self.assertIn('FeatureId("android.sms.send")', native)
        self.assertIn('Manifest.permission.SEND_SMS', native)
        self.assertIn('sendTextMessage(', native)
        self.assertIn('sendMultipartTextMessage(', native)
        self.assertIn('AccessRequirement.SMS', native)
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
            "ShowStatusBarChip", "HideStatusBarClip", "PluginAction", "Toggle5G", "AreaScreenshot",
        ):
            self.assertIn('"' + source + '" -> null', hint, source)
        self.assertNotIn('"StartAppProcess", "StartAppProcessByPkg" -> "android.app.launch"', hint)
        self.assertNotIn('"SetStatusBarIcon" -> "android.notification.ppn.show"', hint)

    def test_macro_screenshot_content_and_spotify_are_not_generic_triggers(self):
        macro = MACRO_HINTS.read_text(encoding="utf-8")
        self.assertIn('"ScreenshotContentTrigger" -> null', macro)
        self.assertIn('"SpotifyTrigger" -> null', macro)
        self.assertIn('"ScreenTextAppearedTrigger" -> "android.event.screen_text_appeared"', macro)
        self.assertIn('"MediaTrackChangedTrigger" -> "android.event.media_track_changed"', macro)


if __name__ == "__main__":
    unittest.main()
