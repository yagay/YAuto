import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


def read(path):
    return (ROOT / path).read_text(encoding="utf-8")


class RuntimeIngressSecurityTest(unittest.TestCase):
    def test_external_commands_are_queued_on_service(self):
        receiver = read("app/src/main/kotlin/com/yagay/yauto/ExternalCommandReceiver.kt")
        service = read("app/src/main/kotlin/com/yagay/yauto/AutomationRuntimeService.kt")
        self.assertIn("startExternalCommand(", receiver)
        self.assertNotIn("goAsync()", receiver)
        self.assertIn("ACTION_EXTERNAL_COMMAND", service)
        self.assertIn("RuntimeEventDispatcher(runtime, scope).dispatch(", service)

    def test_legacy_broadcast_is_authenticated(self):
        receiver = read("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemEventRuntimeBridge.kt")
        self.assertIn("XposedEventAuth.acceptLegacy(", receiver)
        self.assertIn("source != \"lsposed.system_server\"", receiver)
        self.assertIn("MessageDigest.isEqual(", receiver)
        self.assertIn("XposedEventAuth.publish(token)", read("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemBridgeRegistration.kt"))

    def test_external_secret_excluded_from_backup(self):
        for name in ("backup_rules.xml", "data_extraction_rules.xml"):
            self.assertIn("yauto_external_commands.xml", read("app/src/main/res/xml/" + name))


if __name__ == "__main__":
    unittest.main()
