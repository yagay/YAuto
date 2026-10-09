import unittest
from audit_picker_duplicate_titles import kind, effective_titles, read_titles, RES, inspect

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
    def test_real_chinese_resources_are_scanned(self):
        en=read_titles(RES/"values")
        zh=read_titles(RES/"values-zh-rCN")
        self.assertGreater(inspect(en)["resource_feature_titles"],100)
        self.assertGreater(inspect(zh)["resource_feature_titles"],100)
if __name__=="__main__":
    unittest.main()
