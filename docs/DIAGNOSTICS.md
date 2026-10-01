# Diagnostics

YAuto debugging has two layers.

## Execution trace

Every run receives an `ExecutionId`. Events include automation, flow, node, feature, backend, result and duration. This is the first source for logic bugs.

## System diagnostics

Collectors are independent modules. The initial set includes Root environment/logcat and LSPosed file logs through a privileged command runner. Future collectors can add Shizuku, SystemUI Binder health, crash tombstone summaries or ROM-specific diagnostics without changing the engine.

Diagnostics are local and bounded. They are not uploaded automatically.
