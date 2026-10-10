"""UI contract: automatic implementation, explicit different operations only."""
import csv
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UNIFIED = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/UnifiedFeatureConfigEditor.kt"
GENERIC = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt"
SUMMARY = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/AutoImplementationSummary.kt"
GROUPS = ROOT / "tools/verified_picker_merges.csv"


class UnifiedClickSelectorTests(unittest.TestCase):
    def test_verified_group_inventory_stable(self):
        with GROUPS.open(encoding="utf-8", newline="") as stream:
            groups = list(csv.DictReader(stream))
        counts = [len(group["member_ids"].split("|")) for group in groups]
        self.assertEqual(len(groups), 34)
        self.assertEqual(counts.count(2), 31)
        self.assertEqual(counts.count(3), 2)
        self.assertEqual(counts.count(5), 1)

    def test_distinct_operations_are_direct_clickable(self):
        code = UNIFIED.read_text(encoding="utf-8")
        selector = code.split("private fun UnifiedFeatureSelector(", 1)[1].split(
            "internal fun resolveUnifiedMemberId(", 1)[0]
        self.assertNotIn("DropdownMenu(", selector)
        self.assertIn("FilterChip(", selector)
        self.assertIn("RadioButton(selected = selected, onClick = null)", selector)
        self.assertIn("onSelect(item)", selector)

    def test_automatic_implementations_do_not_expose_choice_controls(self):
        unified = UNIFIED.read_text(encoding="utf-8")
        generic = GENERIC.read_text(encoding="utf-8")
        summary = SUMMARY.read_text(encoding="utf-8")
        self.assertIn("if (automatic) null", unified)
        self.assertIn("isAutomaticImplementationGroup()", unified)
        self.assertIn("autoUnifiedMemberId(", unified)
        self.assertIn("descriptor = selectedItem.descriptor", unified)
        self.assertIn("key(selectedItem.descriptor.id.value)", unified)
        self.assertNotIn("BackendChoiceEditor(", generic)
        self.assertNotIn("MethodChoiceEditor(", generic)
        self.assertIn("AutoImplementationSummary(", generic)
        self.assertNotIn("RadioButton(", summary)
        self.assertNotIn("FilterChip(", summary)

    def test_regular_business_parameters_still_use_one_tap_controls(self):
        code = GENERIC.read_text(encoding="utf-8")
        self.assertIn("field is FieldSchema.Choice -> Column(", code)
        self.assertIn("RadioButton(", code)
        self.assertIn("field is FieldSchema.Toggle -> Row(", code)
        self.assertIn("Switch(", code)


if __name__ == "__main__":
    unittest.main()
