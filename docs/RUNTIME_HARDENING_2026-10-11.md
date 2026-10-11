# YAuto runtime hardening — October 11, 2026

Target branch: `refactor/macrodroid-on-1003-20261008`.

## Implemented
- Android 12/13 exported system_server event broadcasts require an ephemeral token delivered over the signature-permission-protected ordered bridge. Android 14+ sender UID verification is retained. Pre-14 package-process events are rejected if they cannot be authenticated.
- External command receiver authenticates and forwards an explicit service Intent. Dispatch uses the long-lived runtime service scope rather than an already-finished BroadcastReceiver pending result.
- External command token SharedPreferences are excluded from cloud backup and device transfer.
- The primary overlay show action now executes on Main and returns the actual `WindowManager.addView` outcome; the shared lifecycle emits `show_failed` on failure. Other surface types still have asynchronous presentation contracts.
- Time, interval and sensor subscriptions reuse canonical enabled trigger selection and receive observable workspace updates. Non-observable repository mocks retain a slower compatibility polling fallback.
- FeaturePack/Schema static audit includes registration helper source files, not only files ending in `FeaturePack.kt`.
- Kotlin and Python regressions were added. JVM compilation does not prove every ROM/system_server Hook works.

## Remaining validation / implementation scope
- Verify Android 12/13 system bridge token handoff on devices; third-party package-process broadcast support on these OS versions needs a secure channel before re-enabling.
- Validate foreground-service start limits, system power management, exact-time scheduling and permission flows on representative OEM devices.
- Migrate remaining asynchronous overlay surface operations to explicit completion results when downstream actions need confirmation.
- Run end-to-end on-device UI navigation, gestures, Accessibility, LSPosed scope, Root and Shizuku tests. Current CI remains a JVM/source audit and debug build pipeline.
- Third-party importer source placeholders and advanced hooks are deliberately retained until lossless semantics can be verified; this work does not claim full ShortX/MacroDroid/Tasker parity.

## Automatic backend priority
- When the privileged-first setting is enabled (the default), supported actions select LSPosed, then Root, then Shizuku, then Accessibility.
- The setting retains its previous storage key to preserve user preferences. Explicit saved backend pins are not overridden.
- The priority is shared by the broker, feature health and editor permission guidance. LSPosed only advertises operations verified by its system bridge; it does not replace Accessibility tree inspection.

## ShortX-style privilege and physical-key source isolation (2026-10-11)

- Hardware-key learning now activates **one capture source at a time**: LSPosed handshake and release capture, then a bounded Root raw-key stage for OEM-only buttons, then Accessibility for any remaining deadline. The three observers are no longer launched concurrently by the learner. The editor suppresses automation triggers during learning.
- Runtime key dispatch still accepts Root raw events for explicitly learned OEM keys. Disabling Root globally whenever LSPosed exists would break devices whose special keys never emit Android KeyEvents. Runtime arbitration keeps cross-backend de-duplication and now defers the winning provider selection until delivery: an UP queued while DOWN is pending follows the DOWN owner.
- LSPosed PING is coalesced via a short positive/negative health cache; a transport timeout invalidates the cache. Root privilege probes retain bounded subprocesses and now cache a successful check for 30 seconds versus a failure for 3 seconds.
- Fallback applies only when a source cannot capture, and never re-runs a non-idempotent automation action after an ambiguous execution result. Raw EV_KEY identities remain hidden compatibility metadata, not Android KeyCode replacements.
- Full device validation (KernelSU, LSPosed scopes, OEM keys and accessibility) remains necessary. No persistent Root Shell pool was introduced because cross-command output isolation, process death and cancellation must be tested before replacing bounded single-use command execution.

- The LSPosed one-shot capture lease has an explicit STOP bridge operation; a local listener timeout no longer leaves system_server armed while the Root/Accessibility learner takes over. CI source tests now verify staged rather than parallel learning.
