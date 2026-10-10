# YAuto privileged implementation audit — 2026-10-10

Target: `refactor/macrodroid-on-1003-20261008`.

## Runtime inventory
- Root: `platform/root` owns the bounded `su -c` shell backend; privileged feature packs live in `feature/standard/privileged` and `platform/android/AndroidPrivileged*FeaturePack`.
- LSPosed API 102: `platform/xposed` owns system_server, SystemUI, package-process observers, event bridges, and dynamic method hooks. Android pickers and capability executors live in `platform/android/AndroidLsposed*FeaturePack` and `AndroidShortX*FeaturePack`.
- ShortX observer catalog has 37 system_server specs. Most describe *observation*, not behavioral parity; ROM class/method names must be verified on the device.
- Privileged methods require the declared LSPosed scopes. Hook success in CI does not imply installation on every OEM ROM. Root/shizuku fallback only applies to actions whose capability/operation explicitly supports it.

## Fixed in this batch
- Added `android.lsposed.hook.disable_session` to stop a previously installed method interception without restarting the app process. A disabled replacement must run the original method. The physical hook remains installed until process restart.
- Installation now validates replacement return types before registering, isolates failures per overload, records successful vs failed overload counts, and cannot report success when no method is active. Session/token collisions are refused.
- Added JVM regression tests for active vs disabled sessions and token ownership.

## Still not equivalent to every ShortX/Tasker/MacroDroid privileged function
- Hooks catalogued without behavior replacement (e.g. third-party QS customization, `PROCESS_TEXT` injection, widget/service rewrites) remain intentionally observational/native alternatives. Global unsafe mutations are not enabled by default.
- ShortX plugins and undocumented Any/ParamsData are not implemented.
- Dynamic method Hook installation applies to *running* scoped app processes; boot, package scope changes, and OEM SystemUI often require process restart and device-specific testing.
- Root, LSPosed, Shizuku differ in UID, permissions and Android hidden-API behavior. Treat feature-picker presence, successful CI compilation and observer registration as distinct states.

## Additional constructor observer integration
- The existing `android.lsposed.hook.install_session` picker now selects **method** or **constructor** in one place. Constructor hooks only observe before/after, cannot replace constructor execution, and emit the existing `android.event.lsposed_method_called` event with `methodName=<init>`.
- Scoped app and running-process requirements remain unchanged. Event token gating and session disabling apply equally to constructor hooks. Java constructor signatures may differ between OEMs and must be checked on device.

## Persistent Hook crash-loop safe mode (2026-10-10)

The scoped app-process YAuto LSPosed runtime now persists Java fatal exceptions in the *target app's device-protected preferences*. Three fatal Java crashes in 120 seconds while its YAuto package hooks are armed quarantine those hooks at the next app launch. The signed management receiver stays available, and `android.lsposed.hook.crash_guard` can query or reset the strike count, exception class, and last Hook family. Re-enabling hooks requires restarting the target app after a reset. The original fatal exception is never swallowed.

Crash-loop correlation does **not** establish Hook causation: three unrelated fatal Java errors can also quarantine hooks. Native SIGSEGV/SIGABRT, SIGKILL, system_server startup crashes, and failures before Application.attach are not covered. This is a preventive opt-out, not an assurance that every OEM crash can be automatically rescued. OEM real-device testing is still required.

## MacroDroid / ShortX implementation families (2026-10-10)

YAuto now distinguishes an implementation **method family** (`__method`: `auto`, `macrodroid`, `shortx`) from the actual ShortX privilege backend (`__backend`: `auto`, `lsposed`, `root`, `shizuku`). Only feature descriptors declaring two *real, equivalent* action routes get the one-tap family picker. The backend chooser is conditional on ShortX selection; other fields remain common and old workflow IDs are retained. Previously saved tasks with explicit root/lsposed/shizuku backend and no method key continue using ShortX.

Initial fully routed actions: `accessibility.global_action` and `android.lsposed.system.operation`. MacroDroid uses Accessibility global actions. ShortX uses scoped LSPosed system operations and Root/Shizuku shell where supported. Automatic routing tries Accessibility and falls back to the privileged capability when needed. Explicit MacroDroid never elevates on failure. Operation mappings are bounded to equivalent semantics; not every operation has a non-root or a system_server Hook variant. The method selection framework does **not** imply full parity with every third-party ShortX action, importer, or plugin.

Additional equivalent action in the dual-method architecture: `android.app.launch`. MacroDroid uses the ordinary Android launcher Intent without Root or Accessibility; ShortX selects the Root/Shizuku `am start` route for the package manager-resolved launch component. This action intentionally does not advertise LSPosed because starting an activity and merely spawning a process are not equivalent semantics.

## Implementation grouping by Root requirement (2026-10-10 update)

The method selector now has exactly **two groups**: **No Root required** (Android public API, Accessibility, Shizuku started through wireless debugging) and **Root/LSPosed required** (Root and LSPosed). A second selector lists only implementations in the chosen group, with individual permission requirements and an explicit unique-implementation label for single-path features. Shizuku must be running and YAuto authorised; it can be started without root using wireless debugging. Choosing a family resets a stale incompatible backend in the editor.

The feature-level executor and generic capability wrapper both enforce groups. A no-root selected action never falls back to `root` or `lsposed`; a root-group action never falls back to Shizuku or Accessibility. Automatic routing survives only for historical tasks explicitly storing `__method=auto`; the new two-group editor defaults to no-root. The legacy `macrodroid` method maps to no-root; legacy `shortx` plus selected Shizuku maps to no-root; legacy `shortx` otherwise maps to root-required. Existing unedited tasks without `__method` preserve their previous backend selection semantics.

Per-operation parity remains limited to actual implemented paths. For example Android's accessibility Power dialog has no verified Root/LSPosed equivalent in this module. A requested unsupported combination is reported as an error rather than silently switching permission groups.
