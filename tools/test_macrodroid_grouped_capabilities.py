"""MacroDroid-style feature grouping with executable paths and old-rule compatibility.

Keep behavior distinctions, runtime subscriptions and verified categories intact.
This is a wiring check; OEM LSPosed hook availability still needs phone tests.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def src(path):
    return (ROOT / path).read_text(encoding="utf-8")


class GroupedFeatureContracts(unittest.TestCase):
    def test_app_ops_start_finish_use_a_single_macro_category_and_real_hooks(self):
        event = src("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidShortXHookEventFeaturePack.kt")
        hooks = src("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/ShortXCompatHookCatalog.kt")
        emitter = src("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedObserverEventEmitter.kt")
        self.assertEqual(event.count('FeatureId("android.event.app_ops_operation")'), 1)
        self.assertIn('pickerCategory = FeaturePickerCategory.APPLICATIONS', event)
        self.assertIn('FieldSchema.Choice("phase", "Operation", true, listOf("any", "start", "finish"))', event)
        self.assertIn('FieldSchema.AppPicker("package"', event)
        self.assertIn('FieldSchema.Number("opCode"', event)
        self.assertIn('ctx.event.payload.string("phase")', event)
        self.assertIn('ctx.event.payload["opCode"].numberOrNull()', event)
        self.assertIn('methodNames = setOf("startOperation", "startOperationImpl")', hooks)
        self.assertIn('methodNames = setOf("finishOperation", "finishOperationImpl")', hooks)
        self.assertEqual(hooks.count('eventType = "android.event.app_ops_operation"'), 2)
        self.assertIn('extras["phase"]', emitter)
        self.assertIn('extras["opCode"]', emitter)
        self.assertIn('if (spec.payloadKind == ShortXHookPayloadKind.APP_OPS) 75L', emitter)
        self.assertIn('extras["callingUid"] = Binder.getCallingUid()', emitter)
        self.assertIn("ShortXObserverMethodSelection.select(", src(
            "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemServerHookInstaller.kt"
        ))

    def test_recent_app_actions_are_one_parameterized_picker_entry_with_old_aliases(self):
        code = src("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidShortXParityFeaturePack.kt")
        self.assertEqual(code.count('FeatureId("android.app.last_used.launch")'), 1)
        self.assertIn('FieldSchema.Choice("mode", "Recent app", true, listOf("latest", "older"))', code)
        self.assertIn('aliases = setOf("android.app.previous.launch", "android.app.next.launch")', code)
        self.assertIn('"android.app.previous.launch" to mapOf("mode" to ConfigValue.StringValue("latest"))', code)
        self.assertIn('"android.app.next.launch" to mapOf("mode" to ConfigValue.StringValue("older"))', code)
        self.assertNotIn("private fun registerRecentAppAction(", code)
        self.assertNotIn("private fun registerLastUsedApp(", code)
        self.assertIn("usage.queryUsageStats(", code)
        self.assertIn("not Android's live task stack", code)

    def test_target_ui_idle_uses_accessibility_activity_not_app_main_looper(self):
        bridge = src("platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityRuntimeBridge.kt")
        service = src("platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/YAutoAccessibilityService.kt")
        features = src("platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityFeaturePack.kt")
        self.assertIn("suspend fun awaitUiIdle(", bridge)
        self.assertIn("lastUiActivity", bridge)
        self.assertIn("YAutoAccessibilityService.current == null", bridge)
        self.assertIn("maxOf(started, last.updatedElapsedMs)", bridge)
        self.assertIn("AccessibilityRuntimeBridge.recordUiActivity(eventPackage)", service)
        self.assertLess(service.index("recordUiActivity(eventPackage)"),
                        service.index("shouldDispatchUiEvent(kind)"))
        self.assertIn('FeatureId("android.ui.wait_for_idle")', features)
        self.assertIn('aliases = setOf("accessibility.ui.wait_for_idle")', features)
        old = src("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidPluginWidgetRegistrations.kt")
        self.assertNotIn('FeatureId("android.ui.wait_for_idle")', old)
        self.assertNotIn("MessageQueue.IdleHandler", old)
        self.assertIn("FeaturePickerCategory.DEVICE_ACTIONS", features)
        self.assertIn("AccessibilityRuntimeBridge.awaitUiIdle(", features)
        self.assertNotIn("MessageQueue.IdleHandler", bridge)
        for language in ("values", "values-zh-rCN"):
            content = src(f"ui/design/src/main/res/{language}/macro_ui_idle_strings.xml")
            self.assertIn("feature_android_ui_wait_for_idle_field_quietms", content)
            self.assertIn("feature_android_ui_wait_for_idle_field_timeoutms", content)

    def test_bilingual_macro_category_titles_exist_and_no_duplicate_actions(self):
        for lang in ("values", "values-zh-rCN"):
            s = src(f"ui/design/src/main/res/{lang}/macrodroid_appops_strings.xml")
            for key in ("feature_android_event_app_ops_operation_title",
                        "feature_android_app_last_used_launch_title"):
                self.assertIn(key, s)
        taxonomy = src("core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeaturePickerCategory.kt")
        self.assertIn("MacroDroid's category taxonomy", taxonomy)
        self.assertIn("FeaturePickerCategory.APPLICATIONS", taxonomy)


if __name__ == "__main__":
    unittest.main()
