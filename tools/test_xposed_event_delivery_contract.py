"""Prevent regressions where LSPosed connected but every runtime event is silently lost.

The October 2026 OxygenOS incident was caused by system_server broadcasts
arriving with senderUid=-1. Hook installation and PING both passed while
the receiver discarded valid KeyEvents. All new LSPosed event transports must
authenticate unknown senders and expose real delivery/denial diagnostics.
"""
import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
XPOSED = ROOT / "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed"


def source(path):
    return (ROOT / path).read_text(encoding="utf-8")


class XposedEventDeliveryContractTest(unittest.TestCase):
    def test_unknown_uid_system_sender_is_token_authenticated_and_never_blanket_allowed(self):
        receiver = source(str((XPOSED / "XposedSystemEventRuntimeBridge.kt").relative_to(ROOT)))
        self.assertIn("uid == Process.INVALID_UID", receiver)
        self.assertIn("XposedEventAuth.acceptLegacy(", receiver)
        self.assertIn("authenticatedUnknownSystemSender = authenticatedUnknownSystemSender", receiver)
        self.assertIn('source == "lsposed.system_server"', receiver)
        self.assertIn("return source == \"lsposed.system_server\" && authenticatedUnknownSystemSender", receiver)
        self.assertNotIn("if (uid < 0) return true", receiver)

    def test_system_receiver_diagnoses_both_success_and_rejection_not_only_hardware_keys(self):
        receiver = source(str((XPOSED / "XposedSystemEventRuntimeBridge.kt").relative_to(ROOT)))
        action = receiver.split("class XposedSystemEventReceiver", 1)[1].split(
            "internal object XposedEventSenderVerifier", 1
        )[0]
        self.assertGreaterEqual(action.count("XposedEventTransportHealth.onRejected("), 2)
        self.assertIn("XposedEventTransportHealth.onAccepted(eventType)", action)
        self.assertIn("XposedEventTransportHealth.onForwarded(type)", receiver)
        self.assertLess(action.index("XposedEventTransportHealth.onAccepted(eventType)"),
                        action.index("XposedSystemEventRuntimeBridge.dispatch(intent)"))
        self.assertNotIn("if (isKeyCapture) XposedEventTransportHealth.onRejected(", action)

    def test_other_auth_required_ipc_uses_a_registered_session_token(self):
        method = source(str((XPOSED / "XposedHookRuntimeBridge.kt").relative_to(ROOT)))
        self.assertIn("sessionTokens[sessionId] != token", method)
        self.assertIn("sessionId.isBlank() || token.isBlank()", method)
        self.assertIn('XposedEventTransportHealth.onAccepted("android.event.lsposed_method_called")', method)
        self.assertIn("XposedEventTransportHealth.onRejected(", method)
        self.assertIn('XposedEventTransportHealth.onForwarded("android.event.lsposed_method_called")', method)

    def test_both_event_broadcasters_report_failed_submission_without_logging_secrets(self):
        for filename in ("XposedRuntimeEventBroadcaster.kt", "MethodHookEventEmitter.kt"):
            with self.subTest(filename=filename):
                s = (XPOSED / filename).read_text(encoding="utf-8")
                self.assertIn("XposedEventSendFailureReporter.report(", s)
                self.assertIn(".onFailure { error ->", s)
                self.assertNotIn("runCatching { context.sendBroadcast(intent) }\n}", s)
        reporter = (XPOSED / "XposedEventTransportHealth.kt").read_text(encoding="utf-8")
        self.assertIn("Event broadcast submission failed:", reporter)
        self.assertNotIn("eventToken", reporter)
        self.assertNotIn("EXTRA_TOKEN", reporter)

    def test_permission_status_must_not_claim_event_delivery(self):
        s = source("platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedBackend.kt")
        self.assertIn("XposedEventTransportHealth.snapshot()", s)
        self.assertIn("delivery.rejected", s)
        self.assertIn("delivery.accepted", s)
        self.assertIn("delivery.forwarded", s)
        self.assertIn("No runtime listener handoff has been observed yet", s)
        self.assertIn("Connection, installed Hooks, and broadcast acceptance", s)

    def test_new_sender_uid_checks_require_centralized_auth_review(self):
        sources = list(XPOSED.glob("*.kt"))
        callers = sorted(p.name for p in sources if "getSentFromUid()" in p.read_text(encoding="utf-8"))
        self.assertEqual(["XposedSystemEventRuntimeBridge.kt"], callers)


if __name__ == "__main__":
    unittest.main()
