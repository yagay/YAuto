# ShortX native importer / implementation-method expansion (2026-10-09)

The published ShortX 1.11 protobuf contract confirms these distinct action families:
- MediaPlayback (action #1) has seven explicit enum modes. All seven now convert to native YAuto android.media.transport, choosing command from the enum. Unknown enum values and unrecognized business fields remain compatibility nodes.
- NoAction (optional decorative icon #1) converts to the existing core.noop, with the icon preserved in source.raw rather than being misrepresented as executable.
- SetVolume (Android stream type #1, stream index #2) now converts for supported types to android.audio.volume.set. YAuto's *single* volume option selects unit=percent or unit=index. Existing percent rules remain unchanged; imported indices are never converted through percentages and are checked against the device's min/max at execution. Unsupported streams and malformed payloads remain compatibility nodes.

Source:
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/MediaPlayback.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/MediaPlaybackAction.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/NoAction.html
- https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/SetVolume.html

Updated 2026-10-10: non-plugin app-process requests and YAuto-scoped SystemUI chips/status icons now have native implementations (see SHORTX_NON_PLUGIN_IMPLEMENTATION_2026-10-09.md), but still need OEM device testing. Arbitrary third-party status slots, process lifetimes, undocumented art formats, source context side effects, unknown Any variants and ShortXPluginAction/ParamsData execution are not supported. Structure actions with source-only metadata, unrecognized fields or invalid timing remain lossless compatibility nodes instead of silently changing semantics.

## 2026-10-10: JSON control-flow parity without duplicate picker functions

- Protobuf ConditionOperator NONE (2) now converts into YAuto's existing PredicateNode.None instead of remaining a compatibility wrapper. MVEL (3) still stays source-preserved.
- Verified JSON ShortX 1.11 control-flow nodes: sequential IfThenElse / WhileLoop, no-field StopAllActions, current-scope BreakActionExecute, SetFunctionReturnValue, ExecuteFunction, FromDA. Native conversions reuse existing YAuto ActionNode structures and stable flow IDs, without introducing redundant feature IDs or menu entries.
- Unrepresentable async/loop timing, non-default action metadata, source IDs/notes, MVEL expressions, unknown keys or duplicate parameter names remain complete compatibility nodes. Nested unknown actions continue to be represented within converted safe branches.
- Protocol reference: https://github.com/ShortX-Repo/ShortX-Files/blob/main/skills/references/actions.md . Debug unit tests are not OEM/runtime validation.

## 2026-10-10: ShowRecentApps source mode precision

ShortX 1.11's `ShowRecentApps.state` uses OnOffToggle (On=0, Off=1, Toggle=2). The existing YAuto `accessibility.recents.show` opens the Android overview, so **only On=0** is a proven native mapping for JSON or protobuf. Off/Toggle, unknown fields or malformed mode encodings stay source-preserved; YAuto does not emulate Close/Toggle with Back, Home or a guessed global action. This preserves MacroDroid-first distinct function naming and avoids a duplicate action/ID.

## Full source inventory, not incremental anecdotal checks (2026-10-10)

`tools/shortx_reference_actions.csv` snapshots the 193 published ShortX 1.11 action-type headings. `tools/audit_reference_parity.py` records field-decoder branch presence for protobuf and JSON, structural converters, suggestion-only entries, and unmapped/non-action entries. The report also includes reviewed MacroDroid source-name pairs, evidence-backed picker groups, and whether each source-derived system_server observer has a picker event. It runs in the Fast Debug workflow and is published as `full_source_parity_inventory.json` alongside other audits. **A decoder branch is not proof of complete field mapping, permissions, run-time behavior or ROM compatibility.** This is the work queue for completing missing functionality in larger batches, not a declaration of feature parity.

ShortX observer installation is now fail-isolated per method: one incompatible OEM hook no longer aborts remaining observer registration or falsely marks its hook as installed. The error is logged and the method can be retried at a later subscription update.

## 2026-10-10 verified native conversion batch: device and UI actions

Shared `ShortXVerifiedBatchMappings` converts ShortX binary and JSON modes only for features already implemented by YAuto: `Toggle5G` (On/Off/Toggle, slot 0/1), three-pulse `Vibrate` (YAuto waveform), all six `ScrollViewTo` positions, and actual `LockDeviceNow` via Accessibility's lock-screen global action. For `ShowHideInsets`, only exact JSON status/navigation types are mapped to YAuto's global policy control; input-method/overlay/display-cutout and unverified packed protobuf variants remain compatibility nodes. Unsupported values, non-equivalent output variables, and unknown business fields are not guessed. These actions reuse existing MacroDroid-aligned picker entries and stable IDs instead of creating duplicates. Device-only LSPosed and UI behavior remains subject to Android permissions, OEM implementations and real-device tests.

Additional verified conversions: `SetBrightness` maps the documented 0–255 value into existing YAuto percent with exact 8-bit round-trip; `ClickTile` maps only non-long-click custom tile components, never a system tile name or long-press that the YAuto shell command cannot represent; protobuf `ShowHideInsets` handles strict unpacked and packed status/navigation enum subsets, while JSON Show modes affecting only some system bars are retained as raw source. Repeated enum decoding is shared in ProtoFields.

Application-management parity batch: Source-typed `StopApp/StopAppByPkg`, `SetAppEnabled/*ByPkg`, `SetAppSuspend/*ByPkg`, and `SetAppInactive/*ByPkg` now map **single explicit package targets** with user IDs through YAuto's existing native actions. These actions have an optional `userId` parameter; existing YAuto tasks still use `current`, while an imported ShortX user ID is carried to `am`/`pm --user`. Multitarget apps, package-set references, unknown profile IDs or unsupported fields remain lossless source compatibility nodes instead of silently dropping target apps.

## 2026-10-10: clipboard, Wi-Fi disconnect, and exact auto-brightness behavior

- No-field ShortX ReadClipboard imports into YAuto `android.clipboard.read` with `resultVariable=clipboardContent`, preserving its documented context key. Android clipboard privacy restrictions still apply.
- No-field DisconnectCurrentWifi imports into the existing privileged `android.wifi.network.disconnect` action, not a Wi-Fi disable action.
- ToggleAutoBrightness imports into the *same* `android.display.brightness.set` picker using `mode=toggle_auto` and live Android Settings.System brightness mode.
- SetAutoBrightness false uses `mode=manual_keep`, preserving the existing screen brightness level rather than silently changing it to a fixed percentage. True continues to use `mode=auto`. Invalid protobuf bool values or unknown JSON business fields stay compatibility nodes.
- The brightness executor now detects unsuccessful Android Settings writes rather than blindly reporting success. Device settings policy and OEM behavior still require device validation.

## 2026-10-10: Reject misleading Source UI hints

The ShortX source reference defines StartLastApp as recent-task fast switching and StartPreviousApp/StartNextApp as task-stack movement. YAuto's existing last/previous/next app features use UsageStats launch history instead, so automatic suggestions have been removed until true task navigation exists. Likewise, ShortX WaitForIdle detects target UI stability while YAuto's current implementation only observes its own main Looper; ShowClipboardView is a clipboard-history manager, not a universal-copy overlay; and global gesture recorder and StopCurrentApp actions have state/target requirements not represented by YAuto's corresponding picker features. FindAndClickMatchedView also lacks the explicit text selector needed by the plain text-click action. These source actions are kept as compatibility nodes with raw payloads and no false native suggestion. Existing YAuto feature IDs and stored rules remain unchanged.

## 2026-10-10: larger verified ShortX import batch

- Converted directly into existing YAuto executors (with conservative field validation): ToggleWifi, ToggleBT, ToggleNFC, ToggleLocation, ToggleDarkMode, SetHotSpotEnabled, InputText, InputTap, InputSwipe, WriteClipboard (text only), ReplaceRegex, AdjustVolume. Both binary protobuf and JSON are handled through one shared batch mapper.
- The Wi-Fi/Bluetooth/NFC shell toggles now read current Android global setting and fail when state cannot be proven; the location toggle uses `cmd location is-location-enabled`, and dark-theme toggle uses `cmd uimode night` only if explicit light/dark state can be read, never guesses while automatic night mode is active. Existing on/off and mode values stay backward compatible.
- InputTap/InputSwipe accept only finite, non-negative numeric literal coordinates, protecting variable-expression payloads for future source-aware execution. WriteClipboard file-path payloads remain raw compatibility. `AdjustVolume` now uses global AudioManager.adjustVolume rather than silently acting on the media stream.
- Native conversions remain incomplete for many advanced ShortX actions, particularly stateful UI / SystemUI Hook functionality. Do not interpret decoder count as complete behavior parity.

## 2026-10-10: explicit app targets and simple UI conversions

- Converted `LaunchAppByPkg`, `RemoveTasks`, `RemoveTasksByPkg`, `StartActivityIntentUri`, `EnableUniversalCopy`, `EnableViewIdViewer`, `ParseQRCode`, and text-only `ShowDanmu` using existing YAuto runtime features.
- The app and task converters preserve default Android user only, reject unresolved package sets or duplicate/foreign users, and retain all source data in compatibility nodes otherwise.
- `ParseQRCode` now uses a small `textOnly` option in the existing QR decoder to write ShortX's documented `qrCodeText` context variable without altering YAuto's existing structured-object output defaults.
- Inspector themes other than default, special Danmu icons and unsupported source metadata are not silently dropped.
