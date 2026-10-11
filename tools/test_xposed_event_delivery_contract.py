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
        self.assertIn('userText("diagnostics.xposed.transport_title")', s)
        self.assertIn('"diagnostics.xposed.transport_message"', s)

    def test_diagnostics_includes_a_real_authenticated_system_server_round_trip(self):
        protocol = (XPOSED / "SystemBridgeProtocol.kt").read_text(encoding="utf-8")
        system = (XPOSED / "XposedSystemBridgeRegistration.kt").read_text(encoding="utf-8")
        bridge = (XPOSED / "XposedSystemEventRuntimeBridge.kt").read_text(encoding="utf-8")
        backend = (XPOSED / "XposedBackend.kt").read_text(encoding="utf-8")

        self.assertIn('EVENT_DELIVERY_PROBE = "event_delivery.probe"', protocol)
        self.assertIn('EVENT_DELIVERY_PROBE_TYPE = "yauto.transport.probe"', protocol)
        self.assertIn("SystemBridgeProtocol.EVENT_DELIVERY_PROBE -> {", system)
        self.assertIn("XposedEventAuth.publish(token)", system)
        self.assertIn("broadcastXposedRuntimeEvent(", system)
        self.assertIn("mapOf(\"nonce\" to nonce)", system)
        self.assertIn("XposedEventAuth.EXTRA_TOKEN, systemEventAuthToken", backend)
        self.assertIn("transportProbeMutex.withLock", backend)
        self.assertIn("XposedSystemEventRuntimeBridge.awaitTransportProbe(nonce", backend)
        self.assertIn("probeSystemEventDelivery()", backend)
        self.assertIn("diagnostics.xposed.transport_message", backend)
        english = source("ui/design/src/main/res/values/lsposed_transport_strings.xml")
        chinese = source("ui/design/src/main/res/values-zh-rCN/lsposed_transport_strings.xml")
        for field in ("runtime_diagnostics_xposed_transport_title",
                      "runtime_diagnostics_xposed_transport_pass",
                      "runtime_diagnostics_xposed_transport_fail",
                      "runtime_diagnostics_xposed_transport_message"):
            self.assertIn(field, english)
            self.assertIn(field, chinese)
        self.assertIn("expected.nonce ==", bridge)
        self.assertIn("transportProbe.compareAndSet(expected, null)", bridge)
        self.assertIn("XposedEventTransportHealth.onForwarded(type)", bridge)
        self.assertNotIn('EVENT_DELIVERY_PROBE_TYPE = "android.event.', protocol)

    def test_new_sender_uid_checks_require_centralized_auth_review(self):
        sources = list(XPOSED.glob("*.kt"))
        callers = sorted(p.name for p in sources if "getSentFromUid()" in p.read_text(encoding="utf-8"))
        self.assertEqual(["XposedSystemEventRuntimeBridge.kt"], callers)


if __name__ == "__main__":
    unittest.main()
