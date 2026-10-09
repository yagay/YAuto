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

    def test_backend_guide_uses_the_current_feature_context(self):
        source = GENERIC.read_text(encoding="utf-8")
        self.assertIn("resolver.implementationDescription(descriptor.id.value)", source)
        self.assertIn("?: resolver.description(descriptor)", source)

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
