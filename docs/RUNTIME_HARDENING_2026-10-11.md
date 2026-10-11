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
