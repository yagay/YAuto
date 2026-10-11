import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def code(name):
    return (ROOT / name).read_text(encoding="utf-8")


class WorkspaceMonitoringRegressionTest(unittest.TestCase):
    def test_canonical_active_events_respect_disabled_rules(self):
        source = code("core/storage/src/main/kotlin/com/yagay/yauto/core/storage/WorkspaceEventSubscriptions.kt")
        self.assertIn("fun WorkspaceData.activeActivationEvents()", source)
        self.assertIn("runtimeTriggerKey(automation, feature) !in disabledTriggerKeys", source)
        self.assertIn("activeActivationEvents().forEach", source)

    def test_configurable_sources_use_workspace_change_notifications(self):
        monitor = code("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/WorkspaceChangeMonitor.kt")
        self.assertIn("observable?.addListener", monitor)
        self.assertIn("subscription?.close()", monitor)
        for name in ("ConfiguredIntervalEventSource.kt", "ConfiguredShortXTimeEventSource.kt", "ConfiguredSensorEventSource.kt"):
            source = code("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/" + name)
            self.assertIn("watchWorkspaceChanges(workspace,", source)
            self.assertIn("activeActivationEvents()", source)

    def test_interval_changes_restart_existing_job(self):
        source = code("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/ConfiguredIntervalEventSource.kt")
        self.assertIn("rules[key] != running.rule", source)
        self.assertIn("running.job.cancel()", source)


if __name__ == "__main__":
    unittest.main()
