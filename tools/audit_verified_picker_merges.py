#!/usr/bin/env python3
"""Enforce evidence-based picker merges against MacroDroid and ShortX APK title maps.

A category alone is NEVER sufficient to merge unrelated YAuto features.
Only entries whose two IDs match the same verified upstream action key
may appear as one picker option. All other entries remain independent.
"""
from __future__ import annotations
import argparse
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/UnifiedFeatureSpecs.kt"
APPROVED = ROOT / "tools/verified_picker_merges.csv"
MACRO = ROOT / "tools/macrodroid_reviewed_names.csv"
SHORTX = ROOT / "tools/shortx_verified_titles.csv"

SPEC_RE = re.compile(
    r'UnifiedFeatureSpec\(\s*"([^"]+)"\s*,\s*'
    r'TextR\.string\.([a-zA-Z0-9_]+)\s*,\s*'
    r'TextR\.string\.([a-zA-Z0-9_]+)\s*,\s*'
    r'listOf\(([\s\S]*?)\)\s*,?\s*\)',
)
ID_RE = re.compile(r'"([^"]+)"')

def csv_rows(path: Path):
    with path.open(encoding="utf-8", newline="") as stream:
        return list(csv.DictReader(stream))

def feature_key(feature_id: str) -> str:
    return "feature_" + re.sub(r"[^a-z0-9]+", "_", feature_id.lower()).strip("_") + "_title"

def published_groups(src: str) -> list[dict]:
    return [
        {"group_id": m.group(1), "title_key": m.group(2),
         "subtitle_key": m.group(3), "members": ID_RE.findall(m.group(4))}
        for m in SPEC_RE.finditer(src)
    ]

def upstream_pairs(source: str) -> dict[str, str]:
    if source == "macrodroid":
        rows = csv_rows(MACRO)
        return {r["yauto_resource_key"]: r["macrodroid_resource_key"] for r in rows}
    if source == "shortx":
        rows = csv_rows(SHORTX)
        return {r["yauto_resource_key"]: r["shortx_resource_key"] for r in rows}
    raise ValueError(f"Unsupported source {source}")

def audit(spec: str, approval_rows: list[dict]) -> dict:
    groups = published_groups(spec)
    all_mapped_ids: set[str] = set()
    declared = {}
    errors = []
    for row in approval_rows:
        group_id = row["group_id"]
        member_ids = row["member_ids"].split("|")
        if group_id in declared:
            errors.append(f"Duplicate approval {group_id}")
        if len(member_ids) < 2 or len(member_ids) != len(set(member_ids)):
            errors.append(f"Invalid members for {group_id}")
        if row["kind"] != "action":
            errors.append(f"Unexpected feature kind in {group_id}: {row['kind']}")
        if row["source"] not in {"macrodroid", "shortx"}:
            errors.append(f"Invalid upstream source for {group_id}")
            continue
        sources = upstream_pairs(row["source"])
        for member in member_ids:
            if member in all_mapped_ids:
                errors.append(f"Feature merged into multiple picker entries: {member}")
            all_mapped_ids.add(member)
            upstream = sources.get(feature_key(member))
            if upstream != row["source_key"]:
                errors.append(
                    f"{group_id}: {member} matches {upstream!r}, "
                    f"not {row['source']}:{row['source_key']}"
                )
        declared[group_id] = member_ids
    for group in groups:
        if group["group_id"] not in declared:
            errors.append(f"Unreviewed broad picker merge {group['group_id']}")
            continue
        if group["members"] != declared[group["group_id"]]:
            errors.append(
                f"{group['group_id']}: members differ from verified APK mapping"
            )
    for group_id in declared.keys() - {g["group_id"] for g in groups}:
        errors.append(f"Approval {group_id} has no picker implementation")
    if len(groups) != len({g["group_id"] for g in groups}):
        errors.append("Duplicate picker group IDs")

    return {
        "approved_merged_picker_entries": len(groups),
        "approved_concrete_feature_ids": len(all_mapped_ids),
        "old_candidate_group_count": 83,
        "old_candidate_member_references": 391,
        "removed_unverified_group_count": 83 - len(groups),
        "groups": groups,
        "evidence": approval_rows,
        "errors": errors,
        "note": "Other operations are independent picker options. IDs, categories and runtime behavior are untouched.",
    }

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", default="build/reports/verified_picker_merges.json")
    ap.add_argument("--fail-on-unsafe", action="store_true")
    args = ap.parse_args()
    result = audit(SPEC.read_text(encoding="utf-8"), csv_rows(APPROVED))
    destination = ROOT / args.output
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Verified picker entries: {result['approved_merged_picker_entries']}; "
          f"concrete IDs: {result['approved_concrete_feature_ids']}; "
          f"unsafe: {len(result['errors'])}; report: {destination}")
    if args.fail_on_unsafe and result["errors"]:
        raise SystemExit("\n".join(result["errors"]))
    return int(bool(result["errors"]))

if __name__ == "__main__":
    raise SystemExit(main())
