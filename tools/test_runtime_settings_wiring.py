"""Guards for the persisted settings to runtime wiring (not ornamental switches)."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class RuntimeSettingsWiringTest(unittest.TestCase):
    def test_boot_option_is_consulted_by_boot_receiver(self):
        receiver = read("app/src/main/kotlin/com/yagay/yauto/BootReceiver.kt")
        prefs = read("app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsPreferences.kt")
        self.assertIn("RuntimeSettingsPreferences.startAtBoot(context)", receiver)
        self.assertIn('getBoolean(KEY_START_AT_BOOT, true)', prefs)
        self.assertIn("setStartAtBoot(context", read("app/src/main/kotlin/com/yagay/yauto/RuntimeExtraSettings.kt"))

    def test_log_controls_reach_real_storage(self):
        graph = read("app/src/main/kotlin/com/yagay/yauto/AppGraph.kt")
        prefs = read("app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsPreferences.kt")
        trace = read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/FileExecutionTracer.kt")
        ui = read("app/src/main/kotlin/com/yagay/yauto/RuntimeExtraSettings.kt")
        self.assertIn("RuntimeSettingsPreferences.traceLevel(appContext)", graph)
        self.assertIn("RuntimeSettingsPreferences.logSizeMb(appContext)", graph)
        self.assertIn("persistentTracer.clear()", graph)
        self.assertIn("traceStore.clear()", graph)
        self.assertIn("lock.withLock", trace)
        self.assertIn("maxBytesProvider()", trace)
        self.assertIn("graph.clearExecutionLogs()", ui)
        self.assertIn("confirmClear", ui)
        self.assertIn("TraceLevel.DEBUG.name", prefs)
        self.assertIn("getInt(KEY_LOG_SIZE_MB, 2)", prefs)

    def test_editor_defaults_are_consumed_by_both_choosers(self):
        editor = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt")
        chooser = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickers.kt")
        ui = read("app/src/main/kotlin/com/yagay/yauto/RuntimeExtraSettings.kt")
        self.assertIn("EditorDisplayPreferences.showAdvancedByDefault(editorContext)", editor)
        self.assertIn("EditorDisplayPreferences.showSystemAppsByDefault(context)", chooser)
        self.assertIn("EditorDisplayPreferences.setShowAdvancedByDefault", ui)
        self.assertIn("EditorDisplayPreferences.setShowSystemAppsByDefault", ui)
        self.assertIn("FeatureVisibilityPreferences.setShowRootExclusive", ui)

    def test_new_pages_are_reachable_with_the_shared_back_stack(self):
        screen = read("app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsScreen.kt")
        for page in ("RUNTIME", "EDITOR", "LOGGING"):
            self.assertIn(f"RuntimeSettingsPage.{page}", screen)
            self.assertIn(f"navigateTo(RuntimeSettingsPage.{page})", screen)
        self.assertIn("rememberPageNavigation(RuntimeSettingsPage.OVERVIEW)", screen)
        self.assertIn("navigation.back()", screen)

if __name__ == "__main__":
    unittest.main()
