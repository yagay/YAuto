# Source feature coverage: Spotify and area screenshots (2026-10-09)

## Spotify

- A new native event ID `android.event.spotify` supports modes: playback_started, playback_stopped, song_changed and any.
- Dedicated broadcasts: `com.spotify.music.playbackstatechanged` and `com.spotify.music.metadatachanged`. YAuto registers its Spotify receiver only when an enabled automation subscribes.
- Track, artist and album filters are available, with case-sensitivity selection.
- Spotify must be configured to emit device status broadcasts; some modern builds may not emit them. Broadcasts are not cryptographically authenticated and cannot be treated as security proofs.
- MacroDroid `SpotifyTrigger` now gets a canonical target **suggestion** only. Source-specific mode fields are not assumed; raw original payload stays in a compatibility node.

## ShortX AreaScreenshot

- Existing native `accessibility.screenshot.capture` already implements `x`, `y`, `width`, `height` region cropping, PNG output and result variable.
- ShortX `AreaScreenshot` now gets this matching **hint**; until its protobuf layout is verified, the import retains the complete source record rather than guessing rectangle parameters.

## Outstanding

- ScreenshotContentTrigger still needs actual OCR-capture event processing, not an accessibility text alias.
- Reversible 5G, native process starts, status-bar icons and ShortX plug-in payload decoding remain separate gaps.
- Actual OEM / Spotify app versions and task imports require device-level verification; passing Debug CI does not prove full parity.
