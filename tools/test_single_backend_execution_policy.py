"""Guard YAuto automatic action routes against concurrent redundant backends.

Observation events may need OEM fallbacks; actions run one backend at a time.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]
def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class SingleBackendExecutionPolicyTest(unittest.TestCase):
    def test_capability_broker_sequential(self):
        s = read("core/capability/src/main/kotlin/com/yagay/yauto/core/capability/Capability.kt")
        self.assertIn('CapabilityBackendPriority.rank(it.id, privilegedFirst)', s)
        self.assertIn('if (!available) continue', s)
        self.assertIn('if (result.success || !request.allowFallback || request.preferredBackendId != null)', s)
        self.assertIn('for (backend in supported)', s)

    def test_legacy_auto_tries_privilege_first_and_stops_after_success(self):
        s = read("core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeatureMethodRouting.kt")
        self.assertIn('val privileged = shortx()', s)
        self.assertIn('if (succeeded(privileged) || !macroSupported) privileged else macrodroid()', s)
        self.assertIn('FeatureMethod.NO_ROOT -> if (macroSupported) macrodroid() else null', s)

    def test_manual_backend_is_pinned(self):
        s = read("core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeatureMethodRouting.kt")
        self.assertIn('val choices = preferred?.let { listOf(it) } ?: candidates', s)
        self.assertIn('if (succeeded(result)) return result', s)

    def test_global_action_routes_follow_priority(self):
        s = read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidLsposedFeaturePack.kt")
        self.assertIn('listOf("lsposed", "root")', s)
        self.assertIn('listOf("shizuku", "accessibility")', s)
        a = read("platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityFeaturePack.kt")
        self.assertIn('listOf("lsposed", "root")', a)
        self.assertIn('listOf("shizuku", "accessibility")', a)

    def test_idle_hardware_rules_do_not_forward_accessibility_keys(self):
        s = read("app/src/main/kotlin/com/yagay/yauto/AutomationRuntimeService.kt")
        self.assertIn('if (!hardwareKeyAutomationEnabled) return@setKeyListener', s)
        self.assertIn('hardwareKeyArbiter?.accept(event)', s)

if __name__ == "__main__":
    unittest.main()
