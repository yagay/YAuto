# MacroDroid / ShortX semantic-parity fixes — 2026-10-09

Base: `refactor/macrodroid-on-1003-20261008`, commit `2ea18bec`.

## Implemented in this batch

- Reused and strengthened existing native `android.sms.send` in AndroidPersonalDataFeaturePack alongside `android.sms.compose`: Android SmsManager, explicit SEND_SMS check, selected subscription, multipart texts, length/number validation. Avoided registering a duplicate Feature ID. Success means **submitted to the telephony service**, not carrier delivery. Carrier billing and Android SMS permission restrictions apply.
- Added `surface.gesture_recording.stop` and a recorder-specific stop API. Ending a recorder commits any pending valid points, closes its surface, and emits `gesture_recording_stopped`. It is keyed by Surface ID and does not close unrelated overlays.
- Corrected ShortX `SendSMS` import hints to target direct sending rather than SMS compose. Unverified protobuf/JSON fields remain compatibility nodes until decoded losslessly; a hint does **not** mean conversion is complete.
- Suppressed unsafe ShortX hints for `StartAppProcess`, `StartAppProcessByPkg`, `SetStatusBarIcon`, `RemoveStatusBarIcon`, `ShowStatusBarChip`, `HideStatusBarClip`, `PluginAction`, `Toggle5G`, `AreaScreenshot`. No unrelated Activity, notification, overlay, Locale plugin, mobile mode, or full-screen capture is misrepresented as equivalent.
- Suppressed unsafe MacroDroid hints for `ScreenshotContentTrigger` and `SpotifyTrigger`. Existing `ScreenTextAppearedTrigger` and generic media track events retain their original, narrower identity.
- Added Kotlin import-suggestion regression tests and a pre-Gradle Python source-parity guard in Android Fast Debug CI.

## Still not equivalent

Do not claim complete feature coverage or lossless task import without original exported rules and device-level verification. Important remaining work includes an actual arbitrary-process start mechanism, SystemUI icon/Chip controls, Spotify-specific filters, cropped screenshots, verified ShortX plug-in protocol, reversible 5G toggle and complete ShortX native protobuf decoding. Root/Shizuku/LSPosed capabilities require real-device verification.

No existing stable feature ID or saved task is migrated or overwritten. Unsupported source variants keep their original compatibility payload.

## Next batch: actual screenshot-content OCR trigger

- Registered `android.event.screenshot_content` with an explicit text query; this is **screen-image OCR**, not accessibility node text.
- Gated the OCR source to workspaces containing enabled rules of this type; no polling without relevant enabled rules.
- Every 10 seconds, when Accessibility screenshot access is available, YAuto recognizes text on-device with ML Kit. The source only emits events when a specific configured search text changes from absent to present. Changes to unrelated on-screen content do not retrigger matching rules.
- Event payload contains the configured match and no complete OCR transcript, reducing leakage of other screen text into logs.
- MacroDroid `ScreenshotContentTrigger` suggests this feature ID, while its original compatibility payload remains until all source parameters have been decoded losslessly.
- Device permissions, OCR accuracy, protected/secure displays, power usage and Android background limits must be tested on real devices.

## Next batch: reversible 5G switch

- New native action `android.telephony.5g.toggle` with toggle, enable and disable methods and SIM slot configuration.
- Parse the allowed-network-types response returned by Android's phone shell service and change only the NR technology bit. Unknown technologies are rejected without changing modem settings.
- Save the pre-disable mask for each SIM slot and restore it only if other network technologies remain unchanged.
- Check the phone service's completion text, because the shell command can return exit status 0 while reporting failure.
- ShortX `Toggle5G` now points to the dedicated action, but unverified source payloads remain as compatibility nodes. ROM and carrier capabilities need real-device verification.

## Next batch: start/stop service control

- Combined start, foreground start and stop into one native `android.service.control` action with a click-selectable `mode` parameter and an explicit component/user ID.
- Preserved the old `android.service.stop` ID as an alias whose default operation is **stop**, so existing tasks retain their behavior.
- Replaced the incorrect ShortX `StartService` hint targeting Activity-intent invocation; ShortX `StartService` and `StopService` now point to Android Service control.
- Root/Shizuku command validation rejects malformed component/user ID and reports service-shell error output; Android service export and foreground restrictions remain enforced by Android.
- Unsupported ShortX original payload variants remain compatibility nodes until proven lossless.

## Consolidated reference-backed coverage batch

Source contracts were checked against the published ShortX protobuf API:
- StartService: AndroidIntent = 1, userId = 2, isForegroundService = 3. https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/StartService.html
- StopService: repeated AppComponent services = 1. https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/StopService.html
- AreaScreenshot: no user-defined business fields. https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/AreaScreenshot.html
- SetStatusBarIcon: slot = 1, icon = 2. https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/SetStatusBarIcon.html
- PluginAction: ShortXPluginAction = 1 and ParamsDataWrapper = 2; not a Locale plugin bundle. https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/PluginAction.html

Native improvements:
- Added `android.screenshot.area_select`: display a real interactive rectangle selector, translate overlay-relative coordinates into screen coordinates, dismiss the selector, then use Accessibility screenshot capture to crop to PNG and optionally store the absolute path in a variable. Cancellation/timeout closes the selector.
- Metadata-only ShortX AreaScreenshot protobuf and JSON actions now convert natively into this action; unknown extra fields keep the original compatibility node. The original ShortX output folder and further side effects have not been verified.
- Added `android.status_icon.control` via existing, authenticated LSPosed system_server bridge to the actual `StatusBarManager.setIcon/removeIcon` API, with allowlisted Android drawable resources and an enforced `yauto_` slot prefix. No notification or overlay impersonation. Some OEM implementations can still reject the hidden method.
- ShortX SetStatusBarIcon and RemoveStatusBarIcon stay compatibility nodes because arbitrary ShortX icon strings and slots cannot be losslessly mapped to the restricted Android icon set.
- Improved `android.service.control` to support action/data for explicit start intents, up to 32 stop targets, and strict pre-execution validation. Existing `android.service.stop` alias stays mapped to `stop`.
- Removed duplicated/dead ShortX hint arms for ShowStatusBarChip and ShowDrawBoard. ShowStatusBarChip remains unsupported until the true SystemUI chip protocol and appearance are known.

Not yet fully equivalent: ShortX exact plugin protocol, general arbitrary-process starting, arbitrary status icon art, actual SystemUI chip operation, multi-component StopService protobuf AppComponent parsing, complete StartService AndroidIntent schema/extras, and OEM/device verification. No global parity claim is justified.
