# YAuto unified navigation and back behavior

## Why the old behavior was wrong
- App-level `BackHandler` directly assigned `AppPage.HOME` whenever any non-home page was visible. This bypassed settings, diagnostics, variable drafts and editor nested destinations.
- Settings stored just one `RuntimeSettingsPage` and toolbar back rewrote it to `OVERVIEW`; the system back came from a different owner.
- Diagnostics used a manual switch statement instead of a reusable history model; picker had a second duplicate stack implementation.
- Multiple full-screen dialogs had their own back/dismiss implementation and many top app bars duplicated the same icon code.

## Shared contract
1. `ui/design/PageNavigation.kt` supplies a generic immutable navigation trail. `forward(destination)` pushes it; `back()` pops exactly one entry or returns null at the root. `rememberPageNavigation` persists enum-based screen trails across Activity recreation.
2. The app shell, settings screen, diagnostic screen and feature picker reuse the shared trail. The feature picker retains the public wrapper API but delegates its stack operations to the shared primitive.
3. `ui/design/PageNavigationUi.kt` supplies the common `PageBackButton` and `NavigationDialog`. Fullscreen picker, action tree and field-selection dialogs share the same `dismissOnBackPress=false` plus internal back delegate.
4. Editor, flow and variables screens now register their own child `BackHandler`, using exactly the same callback as the visible back arrow. A draft/subeditor/overlay is dismissed before its parent screen; root shell navigation gets control only after children finish.
5. Top-level selected automation and flow IDs (instead of stale full object instances) are saveable and resolved from the loaded workspace following recreation.
6. `ui/editor/PageNavigationTest` and `tools/test_navigation_architecture.py` guard against direct HOME jumps, mismatched callbacks, and newly forked full-screen dialogs. The latter runs in Android Fast Debug CI.

## Behavior
- Home → Settings → Permissions → system/gesture back: Settings; back again: Home.
- Home → Diagnostics → Source → Record → back: Source; back again: Diagnostics overview.
- Home → Variables → Edit draft → back: Variables, not Home.
- Automation/flow editor → feature picker → feature group → edit option → back: feature group; back again: picker parent; finally editor. The editor back returns to the previous app-level destination.
- Parent activity only handles returning to an earlier destination when no child handler is active.

## Boundaries
- A dedicated Android system Settings activity opened externally uses Android's own task stack (not YAuto's in-process navigation trail).
- Android AlertDialogs and system permission prompts own their own platform back policy, and are not converted into extra app pages.
- This consolidates navigation, shared back button and full-screen dialog scaffolding, not business-specific form state and view models. Behavior-specific screen components remain isolated deliberately.
- Automated checks validate contract, build and unit-level back history. On-device gestures (including OEM predictive back), keyboard focus and external activity return still need device verification.
