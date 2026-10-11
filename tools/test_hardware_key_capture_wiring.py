"""Regression checks for the hardware-key capture life cycle.

Protect against re-introducing serial Root/LSPosed startup or unauthenticated
capture broadcasts; runtime OEM Hook behavior still requires device testing.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

def source(path):
    return (ROOT / path).read_text(encoding="utf-8")

class HardwareKeyCaptureWiringTest(unittest.TestCase):
    def test_root_capture_is_parallel_with_lsposed_handshake(self):
        s = source("app/src/main/kotlin/com/yagay/yauto/HardwareKeyCaptureCoordinator.kt")
        self.assertIn("launchCapture { graph.hardwareKeys.captureRawKey(timeoutMs) }", s)
        self.assertIn("jobs += launch {", s)
        self.assertNotIn("if (runCatching { graph.rootShell.isAvailable() }", s)
        c = source("app/src/main/kotlin/com/yagay/yauto/DeviceHardwareKeyCatalog.kt")
        method = c.split("suspend fun captureRawKey(", 1)[1].split("private suspend fun resolveAndroidKeyCode", 1)[0]
        self.assertNotIn("rootShell.isAvailable()", method)
        self.assertIn("rootShell.run(RAW_KEY_CAPTURE_COMMAND", method)
        self.assertIn("observed.action == KeyEvent.ACTION_UP", s)
        self.assertIn("HardwareKeyCaptureSession.mutex.withLock", s)
        self.assertIn("HardwareKeyCaptureSession.active = true", s)
        self.assertIn("HardwareKeyCaptureSession.active = false", s)
        runtime = source("app/src/main/kotlin/com/yagay/yauto/AutomationRuntimeService.kt")
        self.assertIn("HardwareKeyEventArbiter(", runtime)
        self.assertIn("if (HardwareKeyCaptureSession.active) return", runtime)
        self.assertIn("if (!hardwareKeyAutomationEnabled) return@setKeyListener", runtime)
        self.assertIn("val activeIds = workspace.runtimeEventFeatureIds()", runtime)
        self.assertNotIn("hardwareKeyDedup.put(", runtime)
        listener = source("app/src/main/kotlin/com/yagay/yauto/RootHardwareKeyEventSource.kt")
        self.assertIn("data.activeActivationEvents()", listener)
        self.assertIn("val mapped = learnedMappings[event.evKey] ?: return", listener)

    def test_lsposed_capture_uses_authenticated_event_envelope(self):
        s = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedHardwareKeyEventHandler.kt")
        method = s.split("private fun emitHardwareKeyCapture(", 1)[1]
        self.assertIn("broadcastXposedRuntimeEvent(", method)
        self.assertNotIn("context.sendBroadcast(", method)
        backend = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedBackend.kt")
        bridge = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemBridgeRegistration.kt")
        capture = backend.split("suspend fun beginHardwareKeyCapture(", 1)[1].split("override fun supports", 1)[0]
        self.assertIn("XposedEventAuth.EXTRA_TOKEN, systemEventAuthToken", capture)
        self.assertIn("XposedEventAuth.publish(token)", bridge)
        self.assertIn("requestTimeoutMs = 5_000L", capture)

    def test_hook_handshake_reports_actual_compatible_callbacks(self):
        installer = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemServerHookInstaller.kt")
        reg = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemBridgeRegistration.kt")
        self.assertIn("fun installShortXInputHooks(context: Context, classLoader: ClassLoader): Int", installer)
        self.assertIn('installedHooks.count { it.startsWith("shortx-input|") }', installer)
        self.assertIn("require(installedCount > 0)", reg)
        self.assertIn('response.putInt("inputHookCount", installedCount)', reg)

if __name__ == "__main__":
    unittest.main()
