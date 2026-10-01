# Import compatibility

Import support is intentionally adapter-based.

- MacroDroid: JSON structure, triggers/actions/constraints preserved; common mappings can be added by source class name.
- Tasker: XML Profile/Task structure is imported; Task actions and contexts are preserved by code when not mapped.
- ShortX: JSON rule shape and protobuf outer Rule/RuleList/RuleSetList structure are recognized. Protobuf `Any` payloads are preserved in Base64 until a per-type mapper is implemented.

Every unsupported item creates a compatibility issue with its source path. Nothing is silently discarded.
