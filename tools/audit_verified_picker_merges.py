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
import zipfile
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
        if row["kind"] not in {"action", "event"}:
            errors.append(f"Unsupported picker kind in {group_id}: {row['kind']}")
        if row["source"] not in {"macrodroid", "shortx", "macrodroid_apk"}:
            errors.append(f"Invalid upstream source for {group_id}")
            continue
        if row["source"] == "macrodroid_apk":
            if not row["source_key"].startswith("assets/ai/") or not row["source_key"].endswith(".yaml"):
                errors.append(f"Invalid MacroDroid asset in {group_id}")
            if not row.get("option_field"):
                errors.append(f"Missing MacroDroid option field in {group_id}")
        elif row.get("option_field"):
            errors.append(f"An upstream title key must not have an APK mode field: {group_id}")
        sources = upstream_pairs(row["source"]) if row["source"] != "macrodroid_apk" else {}
        for member in member_ids:
            if member in all_mapped_ids:
                errors.append(f"Feature merged into multiple picker entries: {member}")
            all_mapped_ids.add(member)
            derived_kind = ("event" if ".event." in member else "condition" if ".condition." in member
                            else "state" if ".state." in member else "action")
            if derived_kind != row["kind"]:
                errors.append(f"Mixed feature kinds in {group_id}: {member}")
            if row["source"] != "macrodroid_apk":
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

def verify_macro_apk_sources(approval_rows: list[dict], apk_path: Path) -> list[str]:
    """Validate reviewed action/trigger YAML source and mode fields in MacroDroid APK."""
    errors = []
    with zipfile.ZipFile(apk_path) as apk:
        for row in approval_rows:
            if row["source"] != "macrodroid_apk":
                continue
            asset = row["source_key"]
            try:
                yaml = apk.read(asset).decode("utf-8")
            except KeyError:
                errors.append(f"Missing source YAML in MacroDroid APK: {asset}")
                continue
            option = re.escape(row["option_field"])
            if not re.search(rf"(?m)^\s*{option}\??:", yaml):
                errors.append(f"Missing option {row['option_field']} in {asset}")
    return errors


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--output", default="build/reports/verified_picker_merges.json")
    ap.add_argument("--fail-on-unsafe", action="store_true")
    ap.add_argument("--macrodroid-apk", type=Path, help="Optional MacroDroid APK to verify YAML modes")
    args = ap.parse_args()
    evidence = csv_rows(APPROVED)
    result = audit(SPEC.read_text(encoding="utf-8"), evidence)
    if args.macrodroid_apk:
        result["errors"].extend(verify_macro_apk_sources(evidence, args.macrodroid_apk))
        result["apk_verified"] = str(args.macrodroid_apk)
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
