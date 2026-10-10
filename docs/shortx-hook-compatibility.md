# ShortX Hook Compatibility Map

This document tracks YAuto's clean-room compatibility implementation based on observed ShortX hook responsibilities. It records class/method identities and equivalent YAuto behavior; it does not copy ShortX implementation code.

## system_server hooks

| ShortX hook family | Observed Android targets | YAuto status |
| --- | --- | --- |
| AMSHook | ActivityManagerService.start/systemReady, Lifecycle.onStart, shell command | systemReady observer migrated; YAuto system-operation bridge covers privileged commands |
| BackNavHooks | BackNavigationController.startBackNavigation/onBackNavigationDone | Migrated as back-navigation start/finish events |
| InputManagerHook | InputManagerService.filterInputEvent, NativeInputManagerService.setInputFilterEnabled, InputManagerCallback/PhoneWindowManager interceptKeyBeforeQueueing/interceptKeyBeforeDispatching, SingleKeyGestureDetector, KeyCombinationManager | Core KeyEvent path migrated; dynamic KeyEvent argument lookup; ACTION_UP key learning; native input-filter event; Root raw input remains fallback |
| AppWidgetServiceHook | AppWidgetServiceImpl.startListening | Observer migrated; YAuto widget features remain native |
| NotificationUsageStatsHook | registerPostedByApp/registerUpdatedByApp/registerRemovedByApp/registerDismissedByUser | Migrated as LSPosed notification lifecycle events |
| ActivityTaskSupervisorHook | cleanUpRemovedTask/cleanUpRemovedTaskLocked/realStartActivityLocked | Task removal already present; real activity start migrated |
| ProcessListHook | handleProcessStartedLocked/removeLruProcessLocked | Migrated as process start/stop events |
| ActivityRecordHook | activityResumed/activityResumedLocked/activityStopped/activityStoppedLocked/setState | Resume/stop observer compatibility migrated; existing lifecycle hook remains available |
| VPNHook | Vpn.updateState | Migrated as VPN state event |
| ClipboardServiceHook | clipboardAccessAllowed, ClipboardImpl.getPrimaryClip | Explicit YAuto-UID-only clipboard bridge; native clipboard features remain primary |
| ActivityStarterHook | ActivityStarter.executeRequest | Migrated as activity-start-request event |
| ActiveServiceHook | ActiveServices.updateScreenStateLocked | Migrated as service screen-state event |
| DisplayRotationHook | DisplayRotation.OrientationListener.onProposedRotationChanged | Migrated as proposed rotation event |
| IMEServiceHook | hideCurrentInputLocked/showCurrentInputLocked/startInputOrWindowGainedFocus | Migrated as IME show/hide/input-start events |
| WMServiceHook | WindowManagerService.notifyFocusChanged, WindowState.reportFocusChangedSerialized/systemReady | Focus observer migrated |
| DisplayPolicyHook | DisplayPolicy.addWindowLw | Migrated as window-added event |
| AccManagerHook | Accessibility user/proxy/UI automation policy methods | Explicit YAuto-UID-only accessibility bridge; disabled by default |
| ActivityMetricsLoggerHook | notifyActivityLaunched | Migrated as activity-launched event |
| ShortcutServiceHook | getShortcuts/requestPinItem/startShortcut | Pin/start observers migrated; YAuto native shortcut actions remain primary |
| TaskOrganizerControllerHooks | task-root back interception | Existing back-navigation/runtime hooks cover the automation event path |
| ActivityClientControllerHooks | onBackPressed | Existing back-navigation/runtime hooks cover the automation event path |
| StatusBarManagerServiceHook | setIcon | Migrated as status-bar icon event |
| ComputerEngineHook | getServiceInfo/queryIntentActivities, PROCESS_TEXT injection | Catalogued; YAuto native component/intent query features remain primary to avoid global package-query mutation |
| ASPHook | AccessibilitySecurityPolicy / AccessibilityWindowManager / AbstractAccessibilityServiceConnection window-access checks | Explicit YAuto-UID-only accessibility bridge; disabled by default |

## zygote / framework hooks

| Hook family | Observed target | YAuto status |
| --- | --- | --- |
| ContextImplHook | ContextImpl.checkCallingPermission | Explicit permission bridge for YAuto caller only and a fixed permission allowlist; disabled by default |
| InputConnHook | RemoteInputConnectionImpl.commitText | Migrated for any explicitly scoped app as input-text-committed event |
| RenderNodeHook | RenderNode.addAnimator exception suppression | Catalogued, not enabled globally because changing rendering failures for every app is unsafe |
| RuntimeInitHook | RuntimeInit.LoggingHandler.uncaughtException | Existing YAuto diagnostics/logging covers the same maintenance goal |

## package hooks

| Package/family | Observed target | YAuto status |
| --- | --- | --- |
| SystemUI | QSTileHost/QSHostAdapter, CustomTile.handleClick/getTileLabel, TileQueryHelper.addTile, LightBarTransitionsController.setIconsDark, PhoneStatusBarView/MiuiPhoneStatusBarView | QS click, tile discovery, icon darkness and status-bar-ready observers migrated; YAuto's own tile controller remains primary for label/icon control |
| NFC service | NfcService.NfcServiceHandler.dispatchTagEndpoint | Migrated as system NFC-tag event; requires com.android.nfc scope |
| MediaProvider | MediaProvider/MediaDocumentsProvider and file-change listener paths | Clean-room insert/update/delete observer migrated; requires media-provider scope |
| Telephony provider | SmsProvider.onCreate/provider operations | Clean-room SMS provider mutation observer migrated; requires telephony-provider scope |
| IME/app process | RemoteInputConnectionImpl.commitText | Migrated through scoped package hook |

## Behavior-changing hooks

ShortX contains hooks that may return early or replace Android framework results. YAuto does not enable these globally. The compatibility action `android.shortx.compat.behavior.set` currently supports:

- `accessibility_access`: only YAuto's Binder UID can receive the targeted accessibility-policy boolean overrides.
- `clipboard_access`: only YAuto's Binder UID can bypass the hooked clipboard access boolean.
- `permission_bridge`: only YAuto's Binder UID and a fixed allowlist of ShortX-observed privileged permissions can receive `PERMISSION_GRANTED` from the hooked ContextImpl path.

These switches reset with system_server and are intended to be enabled explicitly by an automation (for example after boot). Scope changes still require the normal LSPosed restart/reboot.

## Present but not automatically duplicated

ShortX also contains behavior that injects shortcuts/service info, mutates widget responses, overrides custom tile labels/icons, and suppresses RenderNode animator exceptions. YAuto keeps those targets in `ShortXCompatHookCatalog` but prefers existing native YAuto functionality where it already provides the same user-facing capability. A behavior-changing replacement should only be added when the native route cannot achieve the required result, to avoid unnecessary global framework mutation.

## Scope strategy

- `system` and `com.android.systemui` remain fixed recommended scopes.
- `android.event.nfc_tag_system` adds `com.android.nfc`.
- `android.event.media_provider_changed` adds AOSP and Google MediaProvider package candidates.
- `android.event.sms_provider_changed` adds `com.android.providers.telephony`.
- Generic scoped app hooks such as input-text commit use the event's AppPicker target as the requested LSPosed scope.

## Subscription-scoped ShortX observers (2026-10-10)

The authenticated system_server bridge now installs its four core observer families only when their corresponding event types are subscribed. ShortX-derived catalog hooks also install only the requested observer specifications, instead of registering all reflected methods when one observer is needed. Subsequent subscriptions add hooks incrementally; unsubscription gates dispatch but physical unhook requires process restart. Original Android methods still execute without replacing their results.

The LSPosed scope recommendation now ignores disabled automations, disabled categories and disabled action nodes. Named flows remain in the scope inventory for manually executed flows, and existing granted scopes are not automatically revoked. The Xposed module has a dedicated JVM unit test in the Fast Debug workflow. This is a clean-room YAuto lifecycle improvement based on ShortX hook categories, not guaranteed ROM parity.

## 2026-10-10: external Quick Settings tile labels

A new `android.qs_tile.custom_label` action enables or clears a label for one explicit third-party CustomTile component in a scoped SystemUI process. It hooks the zero-argument `CustomTile.getTileLabel` method and only overrides that component's result after the original method executes. Commands require the signature-gated YAuto receiver. System/stock tiles, icons and other applications remain untouched. The command fails when the AOSP CustomTile method is unavailable; OEM runtime testing is still required, and these in-memory overrides reset when SystemUI restarts.
