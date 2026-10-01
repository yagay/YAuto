# YAuto

YAuto is a clean-room Android automation platform focused on long-term maintainability:

- **Macro-style editing**: simple Trigger / Action / Constraint presentation.
- **Task-style composition**: reusable Flows, named inputs/outputs and typed variables.
- **System automation**: replaceable Android / Accessibility / Shizuku / Root / LSPosed / SystemUI backends.
- **Import compatibility**: isolated MacroDroid, ShortX and Tasker importers with compatibility reports.
- **Diagnostics first**: every execution has a trace ID; Root and LSPosed diagnostics can be attached to bug reports.

The project is a **modular monolith**. Stable contracts live in `core/*`; optional functionality is registered as a FeaturePack, Importer or DiagnosticCollector. A feature can be added or removed without editing the rule engine or UI navigation.

## Architecture

```text
app/                    Composition root only
core/
  model/                Pure Kotlin persisted domain model
  logging/              Execution trace protocol
  diagnostics/          Diagnostic collector protocol
  capability/           Backend selection/fallback contracts
  registry/             Feature descriptors + FeaturePack registry
  engine/               Structured AST execution + Flow invocation
  storage/              Versioned documents + migrations
  importer/             Import SPI + compatibility report
feature/
  standard/             Platform-neutral actions/conditions
importer/
  macrodroid/           MacroDroid export adapter
  shortx/               ShortX rule/protobuf adapter
  tasker/               Tasker XML adapter
platform/
  android/              Normal Android API backend/features
  root/                 Root shell + Root diagnostics
  shizuku/              Shizuku boundary (no core dependency on SDK)
  xposed/               LSPosed/SystemUI boundary + log collector
ui/
  design/               Shared Compose components
  home/                 Main navigation
  editor/               Macro-style automation editor
  diagnostics/          Diagnostic status/export surface
```

## Stable contracts

Once released, these are treated as compatibility contracts:

1. `FeatureRef.typeId`
2. Feature `schemaVersion`
3. persisted Action AST shape
4. backup `formatVersion`
5. importer source IDs
6. capability IDs
7. execution trace fields

Renaming a Kotlin class must never break an existing automation.

## Import policy

Importers are intentionally isolated from the YAuto model. They parse the source format, translate into YAuto IDs, preserve unknown source payloads, and produce a compatibility report. Unknown items are **never silently dropped**.

Tasker XML and MacroDroid JSON have structural import support. ShortX protobuf Rule/RuleList/RuleSetList structure is read without embedding ShortX source code; unknown `Any` payloads are preserved for later per-feature mapping.

## Diagnostics

YAuto records:

- execution / automation / flow / node / feature IDs
- selected backend and fallback attempts
- inputs/outputs summaries
- duration and errors
- import trace and compatibility issues
- Android environment
- Root/SU diagnostics
- LSPosed logs when root access allows reading them
- relevant logcat excerpts for SystemUI/system_server/zygote/YAuto

Sensitive collectors are explicit and bounded; diagnostics are not uploaded automatically.

## Build

Target: API 37, min API 31, JDK 17, Gradle 9.4.1, AGP 9.2.0.

```bash
gradle :app:assembleDebug
```

CI publishes a fixed-name `YAuto-debug.apk` artifact.

No source code, icons or proprietary resources from MacroDroid, Tasker or ShortX are included.
