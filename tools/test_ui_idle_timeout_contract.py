"""Regression for UI-idle target-app detection and strict configured timeout."""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

class UiIdleTimeoutContract(unittest.TestCase):
    def test_accessibility_runtime_callbacks_fail_isolated(self):
        source = (ROOT / "platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityRuntimeBridge.kt").read_text()
        self.assertIn("private inline fun dispatchToRuntime(", source)
        for kind in ("window", "key", "ui", "fingerprint"):
            self.assertIn(f'dispatchToRuntime("{kind}")', source)
        self.assertIn('Runtime callback failed:', source)

    def test_timeout_is_not_extended_to_quiet_period(self):
        source = (ROOT / "platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityRuntimeBridge.kt").read_text()
        self.assertIn("val total = timeoutMs.coerceIn(100L, 60_000L)", source)
        self.assertNotIn("timeoutMs.coerceIn(quiet,", source)
        self.assertIn("lastUiActivity", source)
        self.assertIn("YAutoAccessibilityService.current == null", source)
        self.assertIn("deadline - SystemClock.elapsedRealtime()", source)

if __name__ == "__main__":
    unittest.main()
