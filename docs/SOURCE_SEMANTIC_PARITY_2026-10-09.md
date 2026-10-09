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

Do not claim complete feature coverage or lossless task import without original exported rules and device-level verification. Important remaining work includes an actual arbitrary-process start mechanism, SystemUI icon/Chip controls, screenshot-content OCR trigger source, Spotify-specific filters, cropped screenshots, verified ShortX plug-in protocol, reversible 5G toggle and complete ShortX native protobuf decoding. Root/Shizuku/LSPosed capabilities require real-device verification.

No existing stable feature ID or saved task is migrated or overwritten. Unsupported source variants keep their original compatibility payload.
