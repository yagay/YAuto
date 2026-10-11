"""Regression: available Android notification / tile data must be selectable, not manual-only."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class LiveSystemFieldPickerWiringTest(unittest.TestCase):
    def test_editor_injects_live_picker_both_routes(self):
        shell = read("app/src/main/kotlin/com/yagay/yauto/YAutoAppScreen.kt")
        self.assertIn("val liveSystemPicker = remember(context, graph)", shell)
        self.assertEqual(2, shell.count("LocalLiveSystemFieldPicker provides liveSystemPicker"))
        picker = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickers.kt")
        self.assertIn("liveSystemLoader?.load(descriptorId, fieldKey, allValues)", picker)
        self.assertIn("liveSystemLoader, refreshSequence", picker)

    def test_notification_values_from_real_active_and_captured_history(self):
        source = read("app/src/main/kotlin/com/yagay/yauto/LiveAndroidFieldPicker.kt")
        self.assertIn("NotificationControlBridge.current()", source)
        self.assertIn("controller?.snapshots()", source)
        self.assertIn("controller?.history()", source)
        self.assertIn('"actionTitle" -> notification.actionTitles', source)
        self.assertNotIn('"actionTitle" -> historical', source)
        self.assertIn('"package" -> addValue(notification.packageName, appName)', source)
        self.assertIn("manager.notificationChannels", source)
        self.assertIn("manager.notificationChannelGroups", source)
        self.assertIn("if (controller == null && !isOwnedChannel)", source)

    def test_picker_backed_parameters_select_device_value_first(self):
        editor = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickers.kt")
        self.assertIn('var manualEntry by remember(descriptorId, field.key, picker)', editor)
        self.assertIn('if (manualEntry && allowManualInput)', editor)
        self.assertIn('selectedOption?.takeIf { it.value == value }?.label', editor)
        self.assertIn('TextR.string.live_picker_enter_manually', editor)
        self.assertIn('TextR.string.live_picker_choose_from_device', editor)
        self.assertIn('selectedOption = option', editor)
        self.assertIn('manualEntry = false', editor)

    def test_installed_app_fields_offer_picker_before_manual_package(self):
        editor = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickers.kt")
        installed = editor.split("internal fun InstalledAppField(", 1)[1].split("private fun InstalledAppDialog(", 1)[0]
        self.assertIn("var manualEntry by remember(descriptorId, field.key)", installed)
        self.assertIn("if (manualEntry)", installed)
        self.assertIn("TextR.string.live_picker_enter_manually", installed)
        self.assertIn("TextR.string.feature_picker_select_installed_app", installed)
        self.assertIn("TextR.string.live_picker_choose_from_device", installed)

    def test_quick_settings_click_uses_live_device_selector(self):
        source = read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidShortXParityFeaturePack.kt")
        self.assertIn('FeatureId("android.qs_tile.click")', source)
        self.assertIn('FieldSchema.Text("component", "Tile component package/class", true)', source)
        picker = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickers.kt")
        self.assertIn('descriptor.id.value.contains("qs_tile", ignoreCase = true)', picker)
        self.assertIn('"component" -> if (descriptor.id.value.contains("qs_tile", ignoreCase = true))', picker)
        loader = read("app/src/main/kotlin/com/yagay/yauto/LiveAndroidFieldPicker.kt")
        self.assertIn('deviceTiles.load().entries.mapNotNull { entry ->', loader)

    def test_external_qs_components_from_actual_declared_services(self):
        source = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickerData.kt")
        self.assertIn("Intent(TileService.ACTION_QS_TILE)", source)
        self.assertIn('service.permission != "android.permission.BIND_QUICK_SETTINGS_TILE"', source)
        manifest = read("app/src/main/AndroidManifest.xml")
        self.assertIn("android.service.quicksettings.action.QS_TILE", manifest)

    def test_root_and_lsposed_query_external_qs_inventory_without_toggling_tiles(self):
        inventory = read("app/src/main/kotlin/com/yagay/yauto/DeviceQuickSettingsInventory.kt")
        self.assertIn("xposed.quickSettingsTileSpecs()", inventory)
        self.assertIn("rootShell.isAvailable()", inventory)
        self.assertIn("settings get secure sysui_qs_tiles", inventory)
        self.assertIn("cmd package query-services --components --user current -a ", inventory)
        self.assertIn("parseRootTileComponents(output.stdout)", inventory)
        self.assertIn("PackageManager.MATCH_DISABLED_COMPONENTS", inventory)
        self.assertIn("(installed + rootInstalled).distinctBy", inventory)
        self.assertIn("service.permission != \"android.permission.BIND_QUICK_SETTINGS_TILE\"", inventory)
        self.assertNotIn("statusbar click", inventory)

    def test_notification_apps_allow_both_installed_and_observed_choices(self):
        editor = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt")
        self.assertIn("NotificationAppField(descriptorId, field, value, enabled, onValue)", editor)
        source = read("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/SystemFieldPickers.kt")
        self.assertIn("InstalledAppField(descriptorId, field, value, enabled, onValue)", source)
        self.assertIn('fieldKey = "package"', source)

if __name__ == "__main__":
    unittest.main()
