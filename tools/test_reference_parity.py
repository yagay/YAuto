import unittest
from audit_reference_parity import inventory

class FullReferenceParityInventoryTests(unittest.TestCase):
    def test_every_source_action_is_accounted_for_not_just_feature_titles(self):
        report = inventory()
        self.assertEqual(report["shortx_source_types"], 193)
        self.assertEqual(report["shortx_source_types"],
                         report["shortx_field_decoder_present"] +
                         report["shortx_hint_only"] + report["shortx_unmapped_or_nonaction"])
        self.assertGreaterEqual(report["shortx_hint_only"], 60)
        self.assertGreater(report["verified_batch_source_types"], 5)

    def test_hints_are_not_treated_as_native_implementations(self):
        rows = {row["source_type"]: row for row in inventory()["rows"]}
        self.assertEqual(rows["ToggleWifi"]["status"], "hint_only")
        self.assertEqual(rows["ActionAsyncMode"]["status"], "unmapped_or_nonaction")
        self.assertEqual(rows["NoAction"]["status"], "field_decoder_present")
        self.assertEqual(rows["SetBrightness"]["status"], "field_decoder_present")
        self.assertTrue(rows["SetBrightness"]["verified_batch_binary"])
        self.assertEqual(rows["IfThenElse"]["status"], "field_decoder_present")

    def test_shortx_hook_specs_are_registered_picker_events(self):
        report = inventory()
        self.assertEqual(report["hook_events_without_picker_registration"], [])
        self.assertGreaterEqual(report["hook_observer_specifications"], 30)

    def test_macrodroid_first_merge_approvals_are_independent_of_names(self):
        report = inventory()
        self.assertGreaterEqual(report["macrodroid_reviewed_name_pairs"], 150)
        self.assertGreaterEqual(report["approved_picker_merge_groups"], 34)

if __name__ == "__main__":
    unittest.main()
