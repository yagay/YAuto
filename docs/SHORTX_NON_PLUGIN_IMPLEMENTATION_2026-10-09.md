# ShortX non-plugin execution integration — 2026-10-09

Target branch: `refactor/macrodroid-on-1003-20261008`.

## Implementations added

### StartAppProcess / StartAppProcessByPkg
- Native YAuto action: `android.app.process.start`.
- Protobuf `StartAppProcess.appPkg=1`, `pkgSets=2`; `StartAppProcessByPkg.pkgAndUsers=1`. JSON source variants with unambiguous package/user field layouts are mapped. Supports multi-package execution with per-request Android user ID, plus YAuto package-set variable lists.
- Instead of opening an Activity or spawning an unrelated shell command, the authenticated LSPosed system_server bridge looks up ApplicationInfo for the requested Android user, then invokes `ActivityManagerInternal.startProcess(processName, info, ...)` on AOSP-compatible ROMs. Inputs are bounded and checked, and unsupported ROM signatures return an explicit failure.
- The result means AMS accepted the *request*, not that the app will keep running or its background restrictions can be bypassed. AOSP may reclaim idle processes. Multiple user IDs in one imported action cannot be represented by one YAuto action; preserve raw ShortX payload instead of silently discarding a user.

### SystemUI chip and click/long-click actions
- New `android.status_chip.control` YAuto action. On systemui-scoped LSPosed, hook `PhoneStatusBarView.onFinishInflate/onAttachedToWindow` and add a compact view **inside that actual status-bar ViewGroup**, not as an overlay or notification. Support text, Android framework drawables, and a bounded local PNG (64 KiB, max 128x128).
- A signature-permission-gated ordered SystemUI command receiver handles show/hide. `android.event.status_chip_interaction` emits click/long-click events carrying the chip ID. YAuto can configure separate rules based on either gesture.
- For ordinary ShortX ShowStatusBarChip actions (without callbacks) and HideStatusBarClip, the protobuf/JSON importer now creates native actions when all source fields are understood.
- For ShowStatusBarChip with clickAction #3 / longClickAction #4, **when directly contained in an imported rule**, YAuto generates additional, disabled-state-preserving automations with matching chip ID and gesture, and imports each nested action using the existing mappings or compatibility nodes. Unsupported source artwork and malformed nested Any messages remain raw source nodes. The global `shortx` hide command removes the currently active imported chip.
- Actual SystemUI ROM names, layout and policies vary. SystemUI may not expose these precise classes/methods. An acknowledgement means the command was accepted into an injected SystemUI process, not definitive on-screen rendering. Only one active chip is displayed by this implementation; native ShortX source semantics may differ by version.

### Status icon control
- Existing `android.status_icon.control` retains `built_in` behavior for older tasks, and adds a click-selectable `android_drawable` method accepting validated framework resource names. It uses the actual StatusBarManager icon slot in system_server.
- Slots remain `yauto_`-scoped: stock Android status bar slots cannot be overwritten. ShortX icon payloads with exact `android:drawable/name` can be imported into either built-in or framework drawable method. Unrecognized/third-party icon resources are **not** falsely treated as identical.
- Custom PNG rendering is supported by the real SystemUI chip, not arbitrary stock status icon slots.

## Important limits and safety
- Plugins are specifically excluded from this batch.
- LSPosed must scope **android** for process requests/icons and **com.android.systemui** for chip controls. Scope changes normally require the target process to restart. Hooks can fail on customized Android 12–16+ ROMs.
- The source protocols do not promise arbitrary Zygote process lifetime, OS status-slot ownership, or full OEM visual parity.
- The ShortX implicit context-data side effects and art formats not documented in the public protobuf remain compatibility nodes. The real Android system may decline process start without a running component.
- CI compile and tests do not establish that these privileged operations work on each ROM. Validate especially SystemUI crashes/safe-mode behavior before enabling the module system-wide.

Primary contracts:
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/StartAppProcess.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/StartAppProcessByPkg.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/ShowStatusBarChip.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/HideStatusBarClip.html
