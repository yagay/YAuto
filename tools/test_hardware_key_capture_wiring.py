"""Regression checks for the hardware-key capture life cycle.

Protect one-active-learner source selection and authenticated LSPosed capture;
runtime OEM Hook behavior still requires device testing.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

def source(path):
    return (ROOT / path).read_text(encoding="utf-8")

class HardwareKeyCaptureWiringTest(unittest.TestCase):
    def test_key_learning_uses_one_source_at_a_time(self):
        s = source("app/src/main/kotlin/com/yagay/yauto/HardwareKeyCaptureCoordinator.kt")
        self.assertIn("graph.xposed.beginHardwareKeyCapture(totalMs)", s)
        self.assertIn("graph.xposed.endHardwareKeyCapture()", s)
        self.assertIn("graph.hardwareKeys.captureRawKey(rootBudget)", s)
        self.assertIn("AccessibilityRuntimeBridge.awaitNextKey(remainingMs())", s)
        self.assertLess(s.index("graph.xposed.beginHardwareKeyCapture(totalMs)"),
                        s.index("graph.hardwareKeys.captureRawKey(rootBudget)"))
        self.assertLess(s.index("graph.hardwareKeys.captureRawKey(rootBudget)"),
                        s.index("AccessibilityRuntimeBridge.awaitNextKey(remainingMs())"))
        self.assertNotIn("launchCapture {", s)
        self.assertNotIn("if (runCatching { graph.rootShell.isAvailable() }", s)
        c = source("app/src/main/kotlin/com/yagay/yauto/DeviceHardwareKeyCatalog.kt")
        method = c.split("suspend fun captureRawKey(", 1)[1].split("private suspend fun resolveAndroidKeyCode", 1)[0]
        self.assertNotIn("rootShell.isAvailable()", method)
        self.assertIn("rootShell.run(RAW_KEY_CAPTURE_COMMAND", method)
        self.assertIn("key.action == KeyEvent.ACTION_UP", s)
        self.assertIn("HardwareKeyCaptureSession.mutex.withLock", s)
        self.assertIn("HardwareKeyCaptureSession.begin()", s)
        self.assertIn("HardwareKeyCaptureSession.end()", s)
        self.assertIn("RootHardwareKeyEventSource.pauseReadersForLearning()", s)
        self.assertIn("RootHardwareKeyEventSource.resumeReadersAfterLearning()", s)
        runtime = source("app/src/main/kotlin/com/yagay/yauto/AutomationRuntimeService.kt")
        self.assertIn("HardwareKeyEventArbiter(", runtime)
        self.assertIn("if (HardwareKeyCaptureSession.active) return", runtime)
        self.assertIn("if (!hardwareKeyAutomationEnabled) return@setKeyListener", runtime)
        self.assertIn("val activeIds = workspace.runtimeEventFeatureIds()", runtime)
        self.assertNotIn("hardwareKeyDedup.put(", runtime)
        listener = source("app/src/main/kotlin/com/yagay/yauto/RootHardwareKeyEventSource.kt")
        self.assertIn("data.activeActivationEvents()", listener)
        self.assertIn("val choices = learnedMappings[event.evKey].orEmpty()", listener)
        self.assertIn("val mapped = eligible.distinct().singleOrNull() ?: return", listener)
        self.assertIn("if (myGeneration == generation)", listener)
        self.assertIn("while (myGeneration == generation && started.get() && listening)", listener)
        self.assertIn("Thread.sleep(backoffMs)", listener)
        self.assertIn("backoffMs = (backoffMs * 2).coerceAtMost(60_000L)", listener)
        self.assertIn("if (mappings.isNotEmpty() && !learningPaused)", listener)
        self.assertIn("activeReaders.add(this)", listener)
        self.assertIn("activeReaders.remove(this)", listener)
        self.assertIn("if (raw.linuxEvKey > 0 && raw.keyCode <= 0)", s)

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
        self.assertIn("HARDWARE_KEY_CAPTURE_STOP", backend)
        self.assertIn("HARDWARE_KEY_CAPTURE_STOP", bridge)

    def test_hook_handshake_reports_actual_compatible_callbacks(self):
        installer = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemServerHookInstaller.kt")
        reg = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedSystemBridgeRegistration.kt")
        self.assertIn("fun installShortXInputHooks(context: Context, classLoader: ClassLoader): Int", installer)
        self.assertIn('installedHooks.count { it.startsWith("shortx-input|") }', installer)
        self.assertIn("require(installedCount > 0)", reg)
        self.assertIn('response.putInt("inputHookCount", installedCount)', reg)

if __name__ == "__main__":
    unittest.main()
