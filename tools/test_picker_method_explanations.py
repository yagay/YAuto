"""Regression: implementation explanations are per concrete method and locale."""
import unittest
from pathlib import Path

from audit_picker_method_explanations import inspect
from audit_picker_method_labels import locale_strings
from audit_picker_duplicate_titles import RES
from audit_verified_picker_merges import APPROVED, csv_rows

ROOT = Path(__file__).resolve().parents[1]
EDITOR = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/UnifiedFeatureConfigEditor.kt"
RESOLVER = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeatureTextResources.kt"
GENERIC = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt"


class FeatureMethodExplanationsTests(unittest.TestCase):
    def test_all_73_methods_have_specific_bilingual_explanations(self):
        groups = csv_rows(APPROVED)
        self.assertEqual(len(groups), 34)
        for locale in ("values", "values-zh-rCN"):
            report = inspect(groups, locale_strings(RES / locale))
            self.assertEqual(report["expected_method_explanations"], 73)
            self.assertEqual(report["missing"], [])
            self.assertEqual(report["identical_explanations_within_group"], [])

    def test_mode_switch_updates_selected_description_in_two_choice_group(self):
        source = EDITOR.read_text(encoding="utf-8")
        self.assertIn("methodDescription(selectedItem)", source)
        self.assertIn("textResolver.implementationDescription(item.descriptor.id.value)", source)
        self.assertNotIn("text = stringResource(unifiedSelectorHintRes(", source)

    def test_multi_choice_rows_explain_each_method_separately(self):
        source = EDITOR.read_text(encoding="utf-8")
        self.assertIn("methodDescription(item)?.let { description ->", source)
        self.assertIn("if (group.members.size == 2)", source)
        self.assertIn("onSelect(item)", source)
        self.assertIn("selectedMemberId = it.descriptor.id.value", source)

    def test_feature_config_exposes_only_clickable_permissions(self):
        editor = GENERIC.read_text(encoding="utf-8")
        summary = (GENERIC.parent / "AutoImplementationSummary.kt").read_text(encoding="utf-8")
        contract = (GENERIC.parent / "FeaturePermissionGateway.kt").read_text(encoding="utf-8")
        registry = (ROOT / "core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeatureAccessMetadata.kt").read_text(encoding="utf-8")
        app = (ROOT / "app/src/main/kotlin/com/yagay/yauto/YAutoAppScreen.kt").read_text(encoding="utf-8")
        bridge = (ROOT / "app/src/main/kotlin/com/yagay/yauto/FeaturePermissionBridge.kt").read_text(encoding="utf-8")
        self.assertIn("AutoImplementationSummary(descriptor, initial)", editor)
        self.assertNotIn("AccessRequirementBadges(descriptor)", editor)
        self.assertNotIn("BackendChoiceEditor(", editor)
        self.assertNotIn("MethodChoiceEditor(", editor)
        self.assertIn("mandatoryAccessRequirements()", summary)
        self.assertIn("option.requirements.count", summary)
        self.assertIn("permissionsForFeature(", summary)
        self.assertIn("LocalFeaturePermissionGateway.current", summary)
        self.assertIn("gateway?.request(permission)", summary)
        self.assertNotIn("implementation_optimal_automatic", summary)
        self.assertNotIn("implementation_path_permission_format", summary)
        self.assertIn("fun FeatureDescriptor.mandatoryAccessRequirements()", registry)
        self.assertIn("FeaturePermissionGateway", contract)
        self.assertIn("LocalFeaturePermissionGateway provides permissionGateway", app)
        self.assertIn("rememberLauncherForActivityResult", bridge)
        self.assertIn("graph.shizuku.requestPermission", bridge)

    def test_feature_picker_does_not_show_no_root_as_permission(self):
        catalog = (GENERIC.parent / "FeaturePickerRows.kt").read_text(encoding="utf-8")
        self.assertIn("permissionsForFeature(descriptor, null)", catalog)
        self.assertNotIn("implementation_group_no_root", catalog)
        self.assertNotIn("implementation_group_root_required", catalog)
        self.assertIn("if (requirements.isEmpty()) return \"\"", catalog)
        self.assertIn("return localizedList(permissions)", catalog)
        self.assertIn("if (requirements.isEmpty()) return", (GENERIC.parent / "AutoImplementationSummary.kt").read_text(encoding="utf-8"))

    def test_backend_permission_explanations_cover_each_kind_in_both_locales(self):
        for locale in ("values", "values-zh-rCN"):
            strings = locale_strings(RES / locale)
            for backend in ("auto", "root", "shizuku", "lsposed", "accessibility", "usage_stats"):
                for kind in ("action", "event", "condition", "state"):
                    key = f"feature_backend_{backend}_{kind}_format"
                    self.assertIn(key, strings, f"Missing {locale}/{key}")
                    self.assertIn("%1$s", strings[key])
            for key in ("feature_backend_auto_paths_format", "feature_backend_additional_requirements_format"):
                self.assertIn(key, strings)
            self.assertNotEqual(
                strings["feature_backend_root_action_format"],
                strings["feature_backend_shizuku_action_format"],
            )

    def test_resource_resolver_has_distinct_description_key(self):
        source = RESOLVER.read_text(encoding="utf-8")
        self.assertIn("fun implementationDescription(featureId: String)", source)
        self.assertIn('resource("feature_variant_${resourceKey(featureId)}_description")', source)

    def test_power_menu_methods_are_explained_differently(self):
        zh = locale_strings(RES / "values-zh-rCN")
        accessibility = zh["feature_variant_android_global_actions_show_description"]
        privileged = zh["feature_variant_android_device_power_menu_show_description"]
        self.assertIn("无障碍", accessibility)
        self.assertIn("Root", privileged)
        self.assertNotEqual(accessibility, privileged)


if __name__ == "__main__":
    unittest.main()
