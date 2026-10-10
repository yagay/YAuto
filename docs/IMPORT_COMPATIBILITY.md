# Import compatibility

YAuto registers the three compatibility importers lazily in the main app. They are constructed only when the user opens an import path, so normal startup does not pay the parsing cost.

- MacroDroid 5.67.8: JSON macro/action-block structure, triggers, actions and constraints are imported. Lossless field-level mappings become native YAuto nodes; recognized but not-yet-lossless variants remain compatibility nodes with APK-verified canonical target hints.
- ShortX 1.11: JSON rules and protobuf Rule/RuleList/RuleSetList containers are recognized. Verified protobuf/JSON payload layouts become native YAuto nodes; unknown or unsafe `Any` payload variants are preserved losslessly and receive canonical target hints when known.
- Tasker 6.6.20: XML Profile/Task/Scene-related structure and supported control flow are imported. Verified action/context layouts become native YAuto nodes; unknown codes or parameter variants remain compatibility nodes with target hints when known.

Every unsupported or unsafe conversion creates a compatibility issue with its source path. Nothing is silently discarded.

The Android CI runs the MacroDroid, ShortX and Tasker importer test suites in addition to core/platform tests so compatibility regressions fail the pull request.

## 2026-10-10 MacroDroid disabled action and control block safety

- Disabled MacroDroid actions are not discarded during import. They remain disabled compatibility actions retaining original JSON, notes, and intentional disabled state.
- A disabled If/IfConfirmedThen or Loop start preserves the entire balanced block, including nested branches/loops, as a single inert compatibility node; unmatched closing markers keep the remaining input inert. This avoids inadvertently running children outside their disabled control structure.
- Native behavior of enabled actions, existing feature IDs, method selection, and importer source-format mapping remain unchanged. Dedicated regression tests exercise disabled ordinary actions, nested control blocks, and truncated blocks.
- Disabled constraints still follow MacroDroid's runtime filter and are not included as executable predicates; this is not a byte-for-byte re-export facility.
