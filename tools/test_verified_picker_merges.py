import unittest
from audit_verified_picker_merges import SPEC, APPROVED, audit, csv_rows, feature_key, published_groups

class VerifiedPickerMergeTests(unittest.TestCase):
    def test_only_proven_source_matched_actions_are_merged(self):
        report = audit(SPEC.read_text(encoding="utf-8"), csv_rows(APPROVED))
        self.assertEqual(report["errors"], [])
        self.assertEqual(report["approved_merged_picker_entries"], 3)
        self.assertEqual(report["approved_concrete_feature_ids"], 6)

    def test_unverified_merge_is_rejected(self):
        source = SPEC.read_text(encoding="utf-8")
        new_group = '''\n    UnifiedFeatureSpec(
        "unrelated_actions", TextR.string.verified_family_clipboard_read,
        TextR.string.verified_family_clipboard_read_subtitle,
        listOf("android.app.enabled.set", "android.app.background.kill"),
    ),\n'''
        altered = source.replace("\n)", new_group + "\n)", 1)
        report = audit(altered, csv_rows(APPROVED))
        self.assertTrue(any("unrelated_actions" in x for x in report["errors"]))

    def test_same_source_key_is_required(self):
        rows = csv_rows(APPROVED)
        altered = [{**r} for r in rows]
        altered[0]["member_ids"] = "android.clipboard.set|android.app.background.kill"
        report = audit(SPEC.read_text(encoding="utf-8"), altered)
        self.assertTrue(any("not macrodroid" in x for x in report["errors"]))

    def test_cross_kind_conditions_and_triggers_never_merge_by_name(self):
        all_members = [feature for group in published_groups(SPEC.read_text(encoding="utf-8"))
                       for feature in group["members"]]
        self.assertFalse(any(".event." in k or ".condition." in k or ".state." in k for k in all_members))
        self.assertFalse(any("android.audio.volume." in k for k in all_members))

    def test_feature_resource_key_matches_reviewed_apk_rows(self):
        self.assertEqual(feature_key("android.screen.screenshot"),
                         "feature_android_screen_screenshot_title")

if __name__ == "__main__":
    unittest.main()
