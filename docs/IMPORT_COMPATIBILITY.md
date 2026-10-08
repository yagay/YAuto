# Import compatibility

YAuto registers the three compatibility importers lazily in the main app. They are constructed only when the user opens an import path, so normal startup does not pay the parsing cost.

- MacroDroid 5.67.8: JSON macro/action-block structure, triggers, actions and constraints are imported. Lossless field-level mappings become native YAuto nodes; recognized but not-yet-lossless variants remain compatibility nodes with APK-verified canonical target hints.
- ShortX 1.11: JSON rules and protobuf Rule/RuleList/RuleSetList containers are recognized. Verified protobuf/JSON payload layouts become native YAuto nodes; unknown or unsafe `Any` payload variants are preserved losslessly and receive canonical target hints when known.
- Tasker 6.6.20: XML Profile/Task/Scene-related structure and supported control flow are imported. Verified action/context layouts become native YAuto nodes; unknown codes or parameter variants remain compatibility nodes with target hints when known.

Every unsupported or unsafe conversion creates a compatibility issue with its source path. Nothing is silently discarded.

The Android CI runs the MacroDroid, ShortX and Tasker importer test suites in addition to core/platform tests so compatibility regressions fail the pull request.
