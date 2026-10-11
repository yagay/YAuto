"""Ensure Spotify source and ShortX area screenshot match native capabilities."""
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]

class SourceModeParityTest(unittest.TestCase):
    def test_spotify_broadcast_source_is_real_and_workspace_gated(self):
        src = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/SpotifyBroadcastEventSource.kt").read_text()
        catalog_sources = (ROOT / "app/src/main/kotlin/com/yagay/yauto/RuntimeEventSourceCatalog.kt").read_text()
        catalog = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidMediaSessionEventFeaturePack.kt").read_text()
        macro = (ROOT / "importer/macrodroid/src/main/kotlin/com/yagay/yauto/importer/macrodroid/MacroDroidFeatureSuggestions.kt").read_text()
        self.assertIn("com.spotify.music.playbackstatechanged", src)
        self.assertIn("com.spotify.music.metadatachanged", src)
        self.assertIn("lastPlaying == playing", src)
        self.assertIn("signature == lastTrackSignature", src)
        self.assertIn('setOf("android.event.spotify")', catalog_sources)
        self.assertIn("SpotifyBroadcastEventSource(context)", catalog_sources)
        self.assertIn('FeatureId("android.event.spotify")', catalog)
        for mode in ("playback_started", "playback_stopped", "song_changed"):
            self.assertIn(mode, catalog)
        self.assertIn('"SpotifyTrigger" -> "android.event.spotify"', macro)

    def test_area_screenshot_suggests_interactive_selection_without_guessing_coordinates(self):
        hint = (ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/EnhancedShortXImporter.kt").read_text()
        native = (ROOT / "platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityBackend.kt").read_text()
        self.assertIn('"AreaScreenshot" -> "android.screenshot.area_select"', hint)
        self.assertIn("AccessibilityOperations.CAPTURE_SCREENSHOT ->", native)
        self.assertIn("Bitmap.createBitmap(bitmap, left, top, w, h)", native)
        for key in ('"x"', '"y"', '"width"', '"height"'):
            self.assertIn(key, native)

if __name__ == "__main__":
    unittest.main()
