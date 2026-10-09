# MacroDroid / ShortX gap implementation — 2026-10-09

Source comparison:
- MacroDroid wiki: https://www.macrodroidforum.com/wiki/index.php/Constraint:_Trigger_Fired
- ShortX published protobuf contract: https://shortx-repo.github.io/ShortX-Pages/javadoc/tornaco/apps/shortx/core/proto/action/GetScreenOnTime.html
- YAuto native implementations: `core.condition.event_tag`, `android.condition.privileged_backend_available`, and `android.state.accessibility_service_access` already existed; do not re-add these as duplicate options.

## Added

1. `core.condition.trigger_fired` (MacroDroid-like): filters the runtime `event.type`, optional `event.source`, optional `event.fact_tag`. The exact source trigger instance is not inferable from event type alone. Existing saved rules and type IDs remain unchanged. Import of MacroDroid's source-specific Trigger Fired constraint still needs source-instance translation.
2. `android.screen_on_time.get`: read-only native Android UsageStats interactive-screen duration for today or the past 24 hours, output in milliseconds. Requires Usage Access. If transitions are unavailable, fail instead of silently returning 0 or misusing CPU uptime.
3. `android.state.screen_on_time` and `android.condition.screen_on_time`: compare interactive minutes within a configured range using the same implementation.
4. Chinese/English names, descriptions, field labels, permission metadata and calculation regression tests.

## Remaining correctness constraints

- These native functions are new and executable but **do not establish lossless ShortX GetScreenOnTime import parity**: ShortX `from=1` enum semantics and the original destination/context effects need proof before source payloads can be converted.
- UsageStats SCREEN_INTERACTIVE and SCREEN_NON_INTERACTIVE event history may be incomplete on OEM builds. The returned duration is an estimate based on observable transitions, not a guaranteed battery-settings screen-time metric.
- Event-type filtering is not exact MacroDroid per-trigger-object identity. Use optional source/tag filters and do not auto-convert unknown source Trigger Fired constraints.
- Independent unimplemented ShortX plugin, process, SystemUI chip and arbitrary icon protocol variants stay as compatibility nodes.
