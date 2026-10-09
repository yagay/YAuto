#!/usr/bin/env python3
"""Regression coverage for semantic duplicates and localized display overrides."""
import unittest
from audit_feature_name_collisions import BASE, audit, feature_kind, resources

class FeatureNameCollisionTests(unittest.TestCase):
    def test_all_display_overrides_have_bilingual_text(self):
        en = resources(BASE / "values")
        zh = resources(BASE / "values-zh-rCN")
        keys = {key for key in en if key.startswith("feature_display_")} | {
            key for key in zh if key.startswith("feature_display_")}
        self.assertGreaterEqual(len(keys), 13)
        self.assertTrue(all(en.get(k) and zh.get(k) for k in keys))

    def test_original_vendor_terms_are_not_rewritten(self):
        en = resources(BASE / "values")
        zh = resources(BASE / "values-zh-rCN")
        self.assertEqual(zh["macro_feature_android_app_enabled_set_title"], "应用启用/禁用")
        self.assertEqual(zh["macro_feature_android_event_torch_state_changed_filtered_title"], "手电筒开/关")
        self.assertEqual(zh["macro_feature_android_condition_auto_rotate_title"], "屏幕自动选转")
        self.assertEqual(zh["feature_display_android_condition_auto_rotate_title"], "自动旋转")
        self.assertNotEqual(zh["feature_display_android_torch_set_title"],
                            zh["feature_display_android_event_torch_state_changed_filtered_title"])

    def test_no_cross_role_collisions_after_disambiguation(self):
        result = audit(resources(BASE / "values"), resources(BASE / "values-zh-rCN"))
        self.assertGreater(result["original_collision_groups"], 0)
        self.assertEqual(result["unsafe_collisions"], [])
        self.assertEqual(result["missing_locale_paired_overrides"], [])

    def test_trigger_and_state_classification(self):
        self.assertEqual(feature_kind("android_event_camera_in_use_changed"), "event")
        self.assertEqual(feature_kind("android_condition_dark_mode"), "condition")
        self.assertEqual(feature_kind("android_state_dark_mode"), "state")
        self.assertEqual(feature_kind("android_torch_set"), "action")

if __name__ == "__main__":
    unittest.main()
