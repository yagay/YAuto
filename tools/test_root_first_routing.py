import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class RootPreferredRoutingTest(unittest.TestCase):
    def test_root_preference_applies_to_capability_broker(self):
        broker = read("core/capability/src/main/kotlin/com/yagay/yauto/core/capability/Capability.kt")
        graph = read("app/src/main/kotlin/com/yagay/yauto/AppGraph.kt")
        self.assertIn('val privilegedFirst = preferRootWhenAvailable()', broker)
        self.assertIn('"lsposed" -> if (privilegedFirst) 0', broker)
        self.assertIn('"root" -> if (privilegedFirst) 1', broker)
        self.assertIn('if (!available) continue', broker)
        self.assertIn('RuntimeSettingsPreferences.preferRootWhenAvailable(appContext)', graph)

    def test_shell_has_only_explicit_equivalent_operations(self):
        mapping = read("core/capability/src/main/kotlin/com/yagay/yauto/core/capability/ShellAccessibilityOperations.kt")
        for pattern in ('GLOBAL_ACTION ->', 'TAP ->', 'SWIPE ->', 'else -> null'):
            self.assertIn(pattern, mapping)
        for backend in ('root/RootBackend.kt', 'shizuku/ShizukuBackend.kt'):
            folder, file = backend.split('/')
            source = read('platform/' + folder + '/src/main/kotlin/com/yagay/yauto/platform/' + backend)
            self.assertIn('ShellAccessibilityOperations.command(request)', source)

    def test_global_actions_use_capability_broker_in_auto_mode(self):
        feature = read("platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityFeaturePack.kt")
        self.assertIn('if (preferredMethod == FeatureMethod.AUTO)', feature)
        xposed = read("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedBackend.kt")
        self.assertIn('ShellAccessibilityOperations.systemUiOperation(request)', xposed)
        self.assertIn('listOf("shizuku", "accessibility")', feature)
        self.assertIn('listOf("root", "lsposed")', feature)

if __name__ == "__main__":
    unittest.main()
