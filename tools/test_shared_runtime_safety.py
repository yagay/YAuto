"""Cross-module guardrails for action process IO and cancellation boundaries."""
from pathlib import Path
import unittest

ROOT=Path(__file__).resolve().parents[1]
def read(path): return (ROOT/path).read_text(encoding="utf-8")

class SharedRuntimeSafetyTest(unittest.TestCase):
    def test_local_shell_uses_shared_bounded_runner(self):
        s=read("platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidPowerUserFeaturePack.kt")
        self.assertIn('BoundedProcessRunner.run(listOf("/system/bin/sh", "-c", command), timeoutMs)',s)
        self.assertNotIn('bufferedReader().use { it.readText() }',s)
        self.assertNotIn('process.waitFor(timeoutMs',s)

    def test_diagnostics_never_consume_cancellation(self):
        s=read("core/diagnostics/src/main/kotlin/com/yagay/yauto/core/diagnostics/Diagnostics.kt")
        self.assertGreaterEqual(s.count('catch (cancelled: CancellationException) { throw cancelled }'),2)

    def test_health_refresh_survives_individual_scan_failure(self):
        s=read("app/src/main/kotlin/com/yagay/yauto/FeatureHealthScanner.kt")
        self.assertIn('val scanRequests = Channel<Unit>(Channel.CONFLATED)',s)
        self.assertIn('catch (error: Exception) {',s)
        self.assertIn('Feature scan failed',s)
        self.assertNotIn('if (scanJob?.isActive == true) return',s)

    def test_cancel_previous_cancels_before_handoff_lock(self):
        s=read("core/runtime/src/main/kotlin/com/yagay/yauto/core/runtime/RuntimeConflictCoordinator.kt")
        self.assertLess(s.index("jobs.cancel(key)"),s.index("locks.computeIfAbsent(key) { Mutex() }.withLock {\n                    if (ticket"))
        self.assertIn('ticket != sequence.get()',s)

    def test_action_retry_must_be_explicit(self):
        s=read("core/engine/src/main/kotlin/com/yagay/yauto/core/engine/AutomationEngine.kt")
        self.assertIn('retrySafeOnFailure = result.retrySafeOnFailure',s)
        self.assertIn('attempt < maxAttempts && retrySafeOnFailure',s)

if __name__=="__main__": unittest.main()
