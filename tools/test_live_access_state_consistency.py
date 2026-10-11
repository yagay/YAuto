"""Regression: permissions, backend status and feature readiness must be live and truthful."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main/kotlin/com/yagay/yauto"

def read(name):
    return (APP / name).read_text(encoding="utf-8")

class LiveAccessStatusTests(unittest.TestCase):
    def test_one_live_probe_serves_editor_settings_health(self):
        graph = read("AppGraph.kt")
        probe = read("RuntimeAccessStatusRepository.kt")
        editor = read("FeaturePermissionBridge.kt")
        settings = read("RuntimeSettingsScreen.kt")
        health = read("FeatureHealthScanner.kt")
        assert "val accessStatus by lazy" in graph
        assert "graph.accessStatus" in editor
        assert "graph.accessStatus.status.collectAsState()" in settings
        assert "accessStatus.refresh(needed, force = true)" in health
        assert "PermissionAvailability.UNKNOWN" in probe
        assert "xposed.isAvailable(environment)" in probe
        assert "rootShell.isAvailable()" in probe
        assert "YAutoAccessibilityService.current != null" in probe

    def test_shizuku_never_authorized_while_root_probe_unknown(self):
        probe = read("RuntimeAccessStatusRepository.kt")
        settings = read("RuntimeSettingsScreen.kt")
        assert "PermissionAvailability.GRANTED -> PermissionAvailability.NOT_GRANTED" in probe
        assert "PermissionAvailability.UNKNOWN -> PermissionAvailability.UNKNOWN" in probe
        assert "PermissionAvailability.NOT_GRANTED -> check { shizuku.hasPermission() }" in probe
        assert "when (actual[com.yagay.yauto.core.registry.AccessRequirement.ROOT])" in settings

    def test_permission_grant_vs_connected_accessibility_is_separate(self):
        center = read("PermissionGrantCenter.kt")
        probe = read("RuntimeAccessStatusRepository.kt")
        assert "Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES" in center
        assert "runtime_access_accessibility_enabled_unbound" in center
        assert "YAutoAccessibilityService.current != null" in probe

    def test_unverified_feature_cannot_claim_executed_success(self):
        health = read("FeatureHealthScanner.kt")
        labels = read("FeatureHealthUiText.kt")
        screen = read("YAutoAppScreen.kt")
        assert "UNKNOWN" in health
        assert "accessAvailability" in health
        assert "feature_health_authorized_unverified" in labels
        assert "RefreshFeatureHealthOnEditorOpen" in screen
        picker = read("EditorFeatureHealthRefresh.kt")
        assert "FeatureHealthStatus.UNKNOWN -> FeatureAvailabilityTone.UNSUPPORTED" in picker
        assert "if (scanning) emptyMap()" in picker

if __name__ == "__main__":
    unittest.main()
