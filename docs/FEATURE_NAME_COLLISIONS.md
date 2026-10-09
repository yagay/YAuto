# YAuto feature name collision audit

Display names are not feature IDs. MacroDroid and ShortX verified reference
resources remain unchanged for import compatibility, comparison, and search.
An optional `feature_display_<normalized feature id>_title` title takes
priority only for reviewed YAuto presentation problems.

## Scope

- 35 repeated zh-CN title groups were seen across 206 reference-labelled entries.
- Most state/constraint pairs intentionally reuse the same predicate name.
- Action vs condition/event collisions are ambiguous and get distinct titles.
- Clipboard read/write and screenshot actions with identical labels receive
  distinct presentation labels; their runtime implementations remain independent.
- "屏幕自动选转" is an existing reference typo. The user-facing override is
  "自动旋转", without modifying the recorded source label.
- Distinct same-kind state or constraint IDs may still have equivalent meanings.
  They are reported for semantic review; do not merge IDs or change saved rules.

## Audit

Run:

```sh
python3 -m unittest discover -s tools -p 'test_feature_name_collisions.py'
python3 tools/audit_feature_name_collisions.py --fail-on-unsafe
```

CI produces `build/reports/feature_name_collisions.json`, including raw
collisions, display-time remaining collisions, intentional state/constraint
pairs, and pending same-kind semantic review. Approved original APK names are
also checked by `tools/audit_macrodroid_catalog.py`.

## Safety boundaries

No feature ID, category, runtime implementation, importer, serialized rule,
or MacroDroid/ShortX reference is modified by this display-only change.
