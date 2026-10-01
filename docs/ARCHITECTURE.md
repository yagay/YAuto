# Architecture

## Rule: dependencies point inward

`ui`, `platform`, `feature` and `importer` depend on `core`; core never imports app-specific or vendor-specific code.

## Three extension registries

### FeaturePack
Owns Trigger/State/Condition/Action descriptors and executors. Removing a pack removes its UI catalog entries automatically.

### AutomationImporter
Owns one foreign format. It must preserve unknown payloads and produce a compatibility report. Importers never write directly to storage.

### DiagnosticCollector
Owns one diagnostic source. Collectors are bounded and opt-in; Root and LSPosed are not hard dependencies of the engine.

## Compatibility

Persisted rules use stable string IDs, never Kotlin class names. Configuration changes increment per-feature `schemaVersion` and migrate independently.

## Privileged backends

Features request a capability. `CapabilityBroker` tries registered backends in priority order and records attempts. Root/Shizuku/LSPosed can be replaced without changing action definitions.

## Foreign imports

Foreign formats are translated through the import SPI. Unsupported source nodes become compatibility placeholders, never silently disappear. A later mapper can replace placeholders with native YAuto features.
