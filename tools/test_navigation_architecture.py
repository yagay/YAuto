"""Regression guard: YAuto pages share a pop-one-level navigation contract."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]


def source(relative: str) -> str:
    return (ROOT / relative).read_text(encoding="utf-8")


class SharedNavigationArchitectureTest(unittest.TestCase):
    def test_main_and_settings_use_shared_navigation_state(self):
        for path in (
            "app/src/main/kotlin/com/yagay/yauto/YAutoAppScreen.kt",
            "app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsScreen.kt",
        ):
            value = source(path)
            self.assertIn("rememberPageNavigation(", value, path)
            self.assertIn("navigation.back()", value, path)
            self.assertIn("BackHandler(", value, path)
            self.assertNotRegex(value, r"page\s*=\s*(?:AppPage\.HOME|RuntimeSettingsPage\.OVERVIEW)")

    def test_nested_page_stacks_share_one_model(self):
        for path in (
            "ui/diagnostics/src/main/kotlin/com/yagay/yauto/ui/diagnostics/DiagnosticsScreen.kt",
            "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeaturePickerNavigator.kt",
        ):
            value = source(path)
            self.assertIn("PageNavigation.", value, path)
        self.assertIn(
            "val previous = navigation.back()",
            source("ui/diagnostics/src/main/kotlin/com/yagay/yauto/ui/diagnostics/DiagnosticsScreen.kt"),
        )

    def test_editors_have_same_toolbar_and_system_back_contract(self):
        for path in (
            "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/AutomationEditorScreen.kt",
            "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FlowEditorScreen.kt",
            "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GlobalVariablesScreen.kt",
        ):
            value = source(path)
            self.assertIn("BackHandler(onBack = ::navigateBack)", value, path)
            self.assertIn("PageBackButton(onBack = ::navigateBack)", value, path)

    def test_fullscreen_selection_dialogs_use_shared_back_policy(self):
        for path in (
            "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeaturePicker.kt",
            "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/ActionTreeEditor.kt",
            "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickers.kt",
        ):
            value = source(path)
            self.assertIn("NavigationDialog(", value, path)
            self.assertNotIn("Dialog(onDismissRequest", value, path)


if __name__ == "__main__":
    unittest.main()
