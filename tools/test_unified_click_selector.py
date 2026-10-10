"""The unified feature editor exposes every reviewed method with one tap.

The remaining generic FieldSchema.Choice, backend selectors and toggles
already use directly clickable rows/controls and must not regress into
dropdown-only selectors.
"""
import csv
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
EDITOR = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/UnifiedFeatureConfigEditor.kt"
GENERIC = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt"
GROUPS = ROOT / "tools/verified_picker_merges.csv"


class UnifiedClickSelectorTests(unittest.TestCase):
    def test_every_verified_group_has_visible_method_options(self):
        with GROUPS.open(encoding="utf-8", newline="") as stream:
            groups = list(csv.DictReader(stream))
        counts = [len(group["member_ids"].split("|")) for group in groups]
        self.assertEqual(len(groups), 34)
        self.assertEqual(counts.count(2), 31)
        self.assertEqual(counts.count(3), 2)
        self.assertEqual(counts.count(5), 1)

    def test_unified_methods_switch_directly_without_dropdown(self):
        code = EDITOR.read_text(encoding="utf-8")
        selector = code.split("private fun UnifiedFeatureSelector(", 1)[1].split(
            "internal fun resolveUnifiedMemberId(", 1)[0]
        self.assertNotIn("DropdownMenu(", selector)
        self.assertNotIn("OutlinedButton(", selector)
        self.assertIn("if (group.members.size == 2)", selector)
        self.assertIn("FilterChip(", selector)
        self.assertIn(".selectable(", selector)
        self.assertIn("onSelect(item)", selector)
        self.assertIn("RadioButton(selected = selected, onClick = null)", selector)

    def test_switch_keeps_concrete_feature_id_and_changes_parameter_schema(self):
        code = EDITOR.read_text(encoding="utf-8")
        self.assertIn("selectedMemberId = it.descriptor.id.value", code)
        self.assertIn("key(selectedItem.descriptor.id.value)", code)
        self.assertIn("descriptor = selectedItem.descriptor", code)
        self.assertIn("initial?.takeIf { it.typeId == selectedItem.descriptor.id.value }", code)
        self.assertIn("onSave = onSave", code)

    def test_existing_generic_choices_and_backend_are_clickable(self):
        code = GENERIC.read_text(encoding="utf-8")
        selectors = (GENERIC.parent / "ImplementationMethodEditor.kt").read_text(encoding="utf-8")
        self.assertIn("BackendChoiceEditor(", code)
        self.assertIn("internal fun BackendChoiceEditor(", selectors)
        self.assertIn("Modifier.fillMaxWidth().clickable(enabled = enabled)", selectors)
        self.assertIn("field is FieldSchema.Choice -> Column(", code)
        self.assertIn("RadioButton(", selectors)
        self.assertIn("field is FieldSchema.Toggle -> Row(", code)
        self.assertIn("Switch(", code)
        self.assertIn("internal fun MethodChoiceEditor(", selectors)
        self.assertIn("FilterChip(", selectors)


if __name__ == "__main__":
    unittest.main()
