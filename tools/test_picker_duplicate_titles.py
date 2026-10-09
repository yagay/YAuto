import unittest
from audit_picker_duplicate_titles import kind, effective_titles, read_titles, RES, inspect, registered_alias_ids

class DuplicateTitleInventoryTests(unittest.TestCase):
    def test_resource_precedence_prefers_explicit_display_override(self):
        keys={"feature_android_state_music_active_title":"Raw",
              "macro_feature_android_state_music_active_title":"Macro",
              "feature_display_android_state_music_active_title":"Display"}
        self.assertEqual(effective_titles(keys)["android_state_music_active"],"Display")
    def test_separate_kinds_are_not_duplicate_groups(self):
        sample={"feature_android_state_wifi_title":"Network",
                "feature_android_condition_wifi_title":"Network"}
        self.assertEqual(inspect(sample)["possible_same_kind_duplicate_groups"],0)
    def test_old_alias_ids_are_not_in_current_picker(self):
        aliases = registered_alias_ids()
        self.assertIn("android_vibrate_cancel", aliases)
        self.assertIn("android_condition_data_saver_status", aliases)
        example = {
            "feature_android_vibrate_cancel_title": "Cancel vibration",
            "feature_android_vibration_cancel_title": "Cancel vibration",
        }
        self.assertEqual(inspect(example)["unresolved_candidate_groups"], 0)

    def test_data_saver_compatibility_alias_keeps_canonical_meaning(self):
        en = read_titles(RES / "values")
        zh = read_titles(RES / "values-zh-rCN")
        for resource_map in (en, zh):
            for kind in ("state", "condition"):
                canonical = resource_map["feature_display_android_" + kind + "_data_saver_title"]
                compatibility = resource_map["feature_display_android_" + kind + "_data_saver_status_title"]
                self.assertEqual(canonical, compatibility)

    def test_real_chinese_resources_are_scanned(self):
        en=read_titles(RES/"values")
        zh=read_titles(RES/"values-zh-rCN")
        self.assertGreater(inspect(en)["resource_feature_titles"],100)
        self.assertGreater(inspect(zh)["resource_feature_titles"],100)
        unresolved = [g for g in inspect(zh)["groups"] if not g["approved_as_one_parameterized_entry"]]
        self.assertEqual(unresolved, [], "Unapproved same-role Chinese title collisions")
if __name__=="__main__":
    unittest.main()
