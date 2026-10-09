# Feature-specific explanations for unified implementation methods

All 34 source-approved functional groups (73 concrete method IDs) have
bilingual `feature_variant_<normalized_feature_id>_description` resources.

The picker still presents one functional entry and switches methods by one tap:

- When the function has two implementations, both chips remain visible and
  the explanation below them updates to match the selected implementation.
- When the function has three or more implementations, each selectable row
  presents its own implementation explanation beneath its method label.
- The older generic text about choosing an operation/check was removed from
  the selector rather than reused as if it explained every method.
- The backend implementation guide now introduces the current feature-specific
  operation, followed by accurate backend availability, permission and
  restart characteristics.

No functional ID, serialized rule config, executor, importer, root, LSPosed or
accessibility contract was changed. Editing an old rule selects its saved
method and displays that method explanation.

Every newly approved merge must add both EN and zh-CN method descriptions;
`tools/audit_picker_method_explanations.py --fail-on-missing-or-duplicates`
blocks CI if a method is missing or two methods in one group have identical
explanations.
