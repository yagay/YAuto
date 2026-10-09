# MacroDroid and ShortX parity follow-up — 2026-10-09

Verified source references:
- MacroDroid Trigger Fired constraint: https://www.macrodroidforum.com/wiki/index.php/Constraint:_Trigger_Fired
- ShortX GetScreenOnTime protobuf: https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/GetScreenOnTime.html

## Implemented without duplicate IDs

- **MacroDroid-inspired trigger fired condition:** new `core.condition.trigger_fired` can inspect current `event.type` and optionally `event.source` / `event.fact_tag`. It does not misrepresent source-specific MacroDroid trigger identity.
- **ShortX-like screen-on time:** YAuto already implemented `android.screen_on_time.get`, `android.state.screen_on_time` and `android.condition.screen_on_time` with `from=last_screen_off` or `from=system_ready`. Rather than create three duplicate IDs, this update adds `from=today` and `from=last_24_hours` to those **existing** native implementations. Old rules are preserved. The new periods use Android UsageStats on/off events with explicit Usage Access requirements.
- A new pure duration calculator has regression tests for event transitions, duplicates and incomplete history; unknown records return unavailable rather than an invented zero.
- Bilingual new option labels and diagnostic text are supplied in resources. The existing feature titles and descriptions are retained.

## Remaining

- The ShortX `GetScreenOnTime` protobuf `from` enum-to-config mapping and source output semantics are not proven equivalent to YAuto. Source data should be preserved in compatibility nodes until verified.
- MacroDroid trigger-instance IDs cannot be automatically recovered from event type alone.
- Full MacroDroid/ShortX parity including proprietary plugins and arbitrary SystemUI hooks still requires separate work and real-device validation.
