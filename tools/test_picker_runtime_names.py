import unittest

from audit_picker_runtime_names import (
    audit, descriptor_arguments, extract_descriptor_records, title_key
)

class RuntimeNameCoverageTests(unittest.TestCase):
    def test_nested_kotlin_descriptor_and_alias(self):
        source = '''FeatureDescriptor(
            FeatureId("android.vibration.cancel"), FeatureKind.ACTION,
            "Cancel vibration", FeatureCategory.DEVICE,
            fields = listOf(FieldSchema.Choice("x", "X", true, listOf("a", "b"))),
            aliases = setOf("android.vibrate.cancel"),
        )'''
        data = extract_descriptor_records(source, "test.kt")
        self.assertEqual(len(data), 1)
        self.assertEqual(data[0]["aliases"], ["android.vibrate.cancel"])
        self.assertEqual(data[0]["kind"], "action")
        self.assertEqual(data[0]["category"], "DEVICE")

    def test_name_audit_keeps_aliases_out_of_picker_collision_list(self):
        sample = '''FeatureDescriptor(
            FeatureId("android.vibration.cancel"), FeatureKind.ACTION, "Cancel",
            FeatureCategory.DEVICE, aliases = setOf("android.vibrate.cancel"),
        )'''
        records = extract_descriptor_records(sample, "test.kt")
        en = {
            "feature_android_vibration_cancel_title": "Stop vibration",
            "feature_android_vibrate_cancel_title": "Stop vibration",
        }
        result = audit(records, en, en)
        self.assertEqual(result["unique_literal_feature_ids"], 1)
        self.assertEqual(result["registered_compatibility_aliases"], ["android.vibrate.cancel"])
        self.assertEqual(result["en"]["without_direct_title_key_count"], 0)
        self.assertEqual(result["zh_cn"]["without_direct_or_source_aligned_title_count"], 0)
        self.assertEqual(result["en"]["same_kind_category_title_candidates"], [])

    def test_runtime_id_key_is_unchanged(self):
        self.assertEqual(title_key("android.condition.reference.audio_mode"),
                         "android_condition_reference_audio_mode")

if __name__ == "__main__":
    unittest.main()
