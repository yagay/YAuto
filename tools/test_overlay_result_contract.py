import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class OverlayResultContractTest(unittest.TestCase):
    def test_primary_overlay_returns_actual_window_attach_result(self):
        controller = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlaySurfaceController.kt").read_text()
        lifecycle = (ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlayWindowLifecycle.kt").read_text()
        primary = controller.split("suspend fun show(", 1)[1].split("fun showInput(", 1)[0]
        self.assertIn("withContext(Dispatchers.Main.immediate)", primary)
        self.assertIn("windows.attach(id, root, params, autoHideMs)", primary)
        self.assertNotIn("main.post {", primary)
        self.assertIn("fun attach(id: String, view: View, params: WindowManager.LayoutParams, timeoutMs: Long): Boolean", lifecycle)
        self.assertIn("SurfaceRuntimeBridge.emit(id, \"show_failed\"", lifecycle)


if __name__ == "__main__":
    unittest.main()
