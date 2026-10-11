# ShortX settings parity implementation (2026-10-11)

Branch: `refactor/macrodroid-on-1003-20261008`. Debug only; no release build.

## Implemented

- Master rule enable/disable uses the existing workspace-backed `AutomationRuntime.setRuntimeEnabled`. Disabling cancels running automation jobs instead of just hiding rules.
- Optional emergency power-key detector counts 12 POWER releases in 10s from the selected runtime key observation; a durable safety latch blocks new dispatches and disables the master runtime. Settings offer explicit recovery. Android Emergency SOS and OEM input policies may interfere; **never rely on this as a guaranteed kill switch**.
- Optional KeyCode/scan-code Toast reports the arbiter-selected input source. LSPosed and Accessibility observers are subscribed only when a rule or diagnostics require them.
- Root Shell supports a bounded custom absolute `/su` path or `su`; shared libsu is restricted to vetted read-only commands. Mutating operations are still bounded independent child processes.
- The previously dormant learned OEM-key Root input source is now registered and uses the same configured Root binary. Raw Linux EV_KEY remains internal.
- Shizuku is not offered in the backend settings page when granted Root is detected; any authorization click rechecks Root. The capability broker additionally gates Shizuku at execution.
- Local application-set, Shell command, JavaScript, MVEL and BeanShell snippet library: edit, copy, delete, versioned JSON export/import. Imports are validated before being committed and do not execute code.
- Appearance now has system/light/dark modes, Android dynamic colors, and configurable colorful home tiles/section headers, all applied from the shared theme layer.
- Android developer pointer-location switch reads and modifies the real system setting using only the Root backend.
- All new controls have English and Simplified Chinese strings and a runtime wiring audit.

## Existing corresponding facilities reused

- Automation variables and reusable flows, test center, debugger, logging, diagnostics.
- Three QS tiles, SystemUI status-bar chip feature pack, screenshot OCR, MVEL, BeanShell and JavaScript engines.
- Workspaces already have versioned JSON backup and restore. Library JSON is a separate backup to preserve legacy workspace backup format.
- A read-only records center displays filtered persisted TRIGGER/ACTION/ERROR events (including after restart) from the existing rotating JSONL tracer, with JSON export. No second recording database is created.

## Still not equivalent to full ShortX

- SystemUI Shell engine: **not implemented**. Existing YAuto uses a scoped LSPosed SystemUI bridge. Granting entire `com.android.shell` or SystemUI Root was intentionally not introduced.
- Application sets and code snippets are locally reusable via copy/import/export; they are not yet directly addressable by feature configuration references.
- No complete ShortX audio recording record browser, gesture recorder/browser, or reusable gesture library. The new execution timeline is limited to the existing trace categories and bounded persisted log history.
- No Danmu overlay engine, WebDAV sync/profile, official workday calendar subscription, full extension install manager, independent OCR/SMS process controls, or OEM-specific QS tile optimization.
- Runtime safety, especially POWER key observation on OxygenOS, KernelSU and LSPosed, remains **device-test required**; CI only verifies JVM code and Debug build.

Do not claim the above missing features are present merely because their names appear in a comparison or a planned settings section.
