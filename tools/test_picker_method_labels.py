import unittest
from audit_picker_method_labels import inspect, method_labels, locale_strings, RES, csv_rows, APPROVED

class PickerMethodLabelTests(unittest.TestCase):
    def test_detect_same_title_inside_one_group(self):
        group = {"group_id": "clipboard_write",
                 "member_ids": "android.clipboard.set|android.clipboard.write"}
        strings = {
            "macro_feature_android_clipboard_set_title": "Write Clipboard",
            "macro_feature_android_clipboard_write_title": "Write Clipboard",
        }
        self.assertEqual(len(inspect([group], strings)["duplicate_parameter_labels"]), 1)

    def test_distinct_method_parameters_fix_duplicate_options(self):
        group = {"group_id": "clipboard_write",
                 "member_ids": "android.clipboard.set|android.clipboard.write"}
        strings = {
            "macro_feature_android_clipboard_set_title": "Write Clipboard",
            "macro_feature_android_clipboard_write_title": "Write Clipboard",
            "feature_variant_android_clipboard_set": "Set clipboard text",
            "feature_variant_android_clipboard_write": "Write clipboard contents",
        }
        report = inspect([group], strings)
        self.assertEqual(report["duplicate_parameter_labels"], [])
        self.assertEqual(report["missing_direct_method_or_title"], [])

    def test_all_real_unified_options_have_bilingual_method_names(self):
        groups = csv_rows(APPROVED)
        for locale in ("values", "values-zh-rCN"):
            report = inspect(groups, locale_strings(RES / locale))
            self.assertEqual(report["duplicate_parameter_labels"], [])
            self.assertEqual(report["missing_direct_method_or_title"], [])

    def test_reports_unknown_method_fallback_as_candidate(self):
        group = {"group_id": "test", "member_ids": "android.test.a|android.test.b"}
        self.assertEqual(len(inspect([group], {})["missing_direct_method_or_title"]), 2)

if __name__=="__main__":
    unittest.main()
