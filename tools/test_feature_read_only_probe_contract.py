"""Bulk feature health must not mistake authorization for verified execution."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class FeatureReadOnlyProbeContractTest(unittest.TestCase):
    def test_only_state_and_condition_are_eligible(self):
        code = read("app/src/main/kotlin/com/yagay/yauto/FeatureReadOnlyInterfaceProbe.kt")
        self.assertIn("kind != FeatureKind.STATE && kind != FeatureKind.CONDITION", code)
        self.assertIn('featureId.startsWith("android.state.")', code)
        self.assertIn('featureId.startsWith("android.condition.")', code)
        self.assertIn('if (kind != FeatureKind.STATE && kind != FeatureKind.CONDITION) return null', code)
        self.assertNotIn("registerAction(", code)
        self.assertNotIn("sendBroadcast(", code)
        self.assertNotIn("RuntimeFeatureTester", code)
        self.assertNotIn("su -c", code)

    def test_scan_requires_manual_invocation_not_automatic_action_execution(self):
        code = read("app/src/main/kotlin/com/yagay/yauto/FeatureHealthScanner.kt")
        self.assertIn("fun requestReadOnlyVerification()", code)
        self.assertIn("scanRequests.trySend(ScanRequest(readOnly = true))", code)
        self.assertIn("scanRequests.trySend(ScanRequest(readOnly = false))", code)
        self.assertIn("FeatureProbeStatus.NOT_TESTABLE", code)
        self.assertIn("FeatureProbeStatus.SKIPPED", code)
        self.assertIn("withTimeoutOrNull(2_000L)", code)
        self.assertIn("probeCache[key] = checked", code)
        self.assertIn("FeatureProbeStatus.RESPONDED", code)
        self.assertIn("fun requestFeatureVerification(featureId: String)", code)
        self.assertIn("singleId", code)

    def test_primary_one_tap_runs_actual_read_only_query_and_labels_readiness_separately(self):
        screen = read("app/src/main/kotlin/com/yagay/yauto/FeatureHealthScreen.kt")
        settings = read("app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsScreen.kt")
        main = read("app/src/main/kotlin/com/yagay/yauto/MainActivity.kt")
        scanner = read("app/src/main/kotlin/com/yagay/yauto/FeatureHealthScanner.kt")
        self.assertIn("Button(\\n                        onClick = scanner::requestReadOnlyVerification", screen)
        self.assertIn("onClick = { scanner.requestScan(force = true) }", screen)
        self.assertIn("graph.featureHealth.requestReadOnlyVerification()", settings)
        self.assertIn("graph.featureHealth.requestReadOnlyVerification()", main)
        self.assertIn("if (enabled) requestReadOnlyVerification()", scanner)
        self.assertIn('put("scanType",', screen)
        self.assertIn('put("hasRealInterfaceEvidence",', screen)
        self.assertIn("feature_health_readiness_only_warning", screen)

    def test_interface_evidence_is_exportable_and_not_mislabeled_as_actual_trigger(self):
        screen = read("app/src/main/kotlin/com/yagay/yauto/FeatureHealthScreen.kt")
        self.assertIn('put("schemaVersion", 2)', screen)
        self.assertIn('put("probeStatus", item.probe?.status?.name)', screen)
        self.assertIn('put("probeInterface", item.probe?.interfaceName)', screen)
        self.assertIn('put("probeObservedValue", item.probe?.observedValue)', screen)
        self.assertIn('TextR.string.feature_health_probe_read_only', screen)
        self.assertIn('TextR.string.feature_health_probe_one', screen)
        self.assertIn('scanner.requestFeatureVerification(item.featureId)', screen)
        assert "FeatureHealthItem" in read("app/src/main/kotlin/com/yagay/yauto/FeatureHealthScanner.kt")

if __name__ == "__main__":
    unittest.main()
