"""Check lifecycle and backpressure contracts in externally-triggered modules."""
from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
def read(path): return (ROOT/path).read_text(encoding="utf-8")

class ExternalSourceLifecycleTest(unittest.TestCase):
    def test_http_server_bounded_worker_and_pending_cleanup(self):
        s=read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidHttpServerFeaturePack.kt")
        self.assertIn("ThreadPoolExecutor(",s)
        self.assertIn("ArrayBlockingQueue<Runnable>(64)",s)
        self.assertIn("RejectedExecutionException",s)
        self.assertIn("pending.remove(requestId, deferred)",s)
        self.assertNotIn("Executors.newCachedThreadPool()",s)

    def test_camera_capture_releases_on_cancel(self):
        s=read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidCameraCaptureFeaturePack.kt")
        self.assertIn("withContext(NonCancellable + Dispatchers.Main.immediate)",s)
        self.assertIn("camera = nextCamera",s)
        self.assertIn("recorder = MediaRecorder()",s)

    def test_biometric_pending_result_removed_on_cancellation(self):
        s=read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidBiometricFeaturePack.kt")
        self.assertIn("finally {",s)
        self.assertIn("BiometricRuntime.remove(token)",s)

    def test_no_unbounded_hardware_ingress(self):
        s=read("app/src/main/kotlin/com/yagay/yauto/RuntimeEventDispatcher.kt")
        self.assertIn("Channel<Pair<RuntimeEvent, Boolean>>(512)",s)
        self.assertIn("CoroutineStart.UNDISPATCHED",s)
        self.assertNotIn("Channel.UNLIMITED",s)

if __name__=="__main__": unittest.main()
