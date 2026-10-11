import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]

def read(path):
    return (ROOT / path).read_text(encoding="utf-8")

class OverlayResultContractTest(unittest.TestCase):
    def test_all_surface_presentations_await_window_attach(self):
        controller = read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlaySurfaceController.kt")
        lifecycle = read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlayWindowLifecycle.kt")
        names = re.findall(r"    suspend fun (show\w*)\(", controller)
        self.assertGreaterEqual(len(names), 21)
        for name in names:
            region = controller.split("suspend fun " + name + "(", 1)[1].split("\n    }", 1)[0]
            self.assertNotIn("main.post {", region, name)
            self.assertTrue("presentOnMain(id)" in region or "withContext(Dispatchers.Main.immediate)" in region or
                            (name == "showSidebar" and "showButtonGrid(" in region), name)
            self.assertNotIn("return true\n", region, name)
        self.assertIn("return attach(id, view, params, timeoutMs)", lifecycle)
        self.assertIn("SurfaceRuntimeBridge.emit(id, \"show_failed\"", lifecycle)
        self.assertIn("suspend fun hideConfirmed", controller)
        self.assertIn("suspend fun hideAllConfirmed", controller)

    def test_add_surface_returns_actual_window_result(self):
        controller = read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlaySurfaceController.kt")
        self.assertIn("): Boolean = windows.add(id, view,", controller)
        self.assertIn("private suspend fun presentOnMain(", controller)

if __name__ == "__main__":
    unittest.main()
