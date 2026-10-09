#!/usr/bin/env python3
"""Review duplicate picker titles without rewriting MacroDroid/ShortX reference translations.

Only cross-role collisions (ACTION vs EVENT/STATE/CONDITION, EVENT vs state/
condition) and duplicate ACTION titles fail CI. STATE/CONDITION equivalents
are expected. Other same-role aliases require semantic review, not auto-merge.
"""
from __future__ import annotations
import argparse
import json
from collections import defaultdict
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "ui/design/src/main/res"
PREFIXES = ("macro_feature_", "shortx_feature_")
OVERRIDE_PREFIX = "feature_display_"


def resources(folder: Path) -> dict[str, str]:
    result = {}
    for path in sorted(folder.glob("*.xml")):
        for node in ET.parse(path).getroot().findall("string"):
            key = node.get("name")
            if not key:
                continue
            if key in result:
                raise ValueError(f"Duplicate resource key: {key} ({path})")
            result[key] = "".join(node.itertext()).strip()
    return result


def feature_kind(suffix: str) -> str:
    if "_event_" in suffix:
        return "event"
    if "_state_" in suffix:
        return "state"
    if "_condition_" in suffix:
        return "condition"
    return "action"


def audit(english: dict[str, str], chinese: dict[str, str]) -> dict:
    features = {}
    for prefix in PREFIXES:
        for key in chinese:
            if key.startswith(prefix) and key.endswith("_title"):
                suffix = key[len(prefix):-len("_title")]
                features.setdefault(suffix, []).append((prefix, key))

    raw_groups = defaultdict(list)
    shown_groups = defaultdict(list)
    missing_overrides = []
    for suffix, sources in sorted(features.items()):
        sources.sort(key=lambda x: PREFIXES.index(x[0]))
        source_key = sources[0][1]
        override_key = OVERRIDE_PREFIX + suffix + "_title"
        kind = feature_kind(suffix)
        title = chinese[source_key]
        raw_groups[title].append({"feature": suffix, "kind": kind, "source": source_key})
        if override_key in english or override_key in chinese:
            if not english.get(override_key) or not chinese.get(override_key):
                missing_overrides.append(override_key)
        display = chinese.get(override_key, title)
        shown_groups[display].append({"feature": suffix, "kind": kind, "source": source_key})

    def collisions(groups):
        return [{"title": title, "features": items}
                for title, items in sorted(groups.items())
                if len(items) > 1]

    def risk(items):
        kinds = [row["kind"] for row in items]
        if "action" in kinds and (len(items) > 1):
            return "conflicting_action_title"
        if "event" in kinds and len(items) > 1:
            return "conflicting_trigger_title"
        if len(set(kinds)) == 1:
            return "same_kind_semantic_review"
        # A state and its constraint often describe the same predicate.
        return "state_constraint_pair"

    raw = collisions(raw_groups)
    displayed = collisions(shown_groups)
    classified = [{**group, "classification": risk(group["features"])} for group in displayed]
    unsafe = [group for group in classified
              if group["classification"] in {"conflicting_action_title", "conflicting_trigger_title"}]
    pending = [group for group in classified
               if group["classification"] == "same_kind_semantic_review"]
    return {
        "features_with_macro_or_shortx_labels": len(features),
        "original_collision_groups": len(raw),
        "display_collision_groups": len(displayed),
        "unsafe_collisions": unsafe,
        "unresolved_same_kind_aliases": pending,
        "intentional_state_constraint_groups":
            [group for group in classified if group["classification"] == "state_constraint_pair"],
        "missing_locale_paired_overrides": sorted(set(missing_overrides)),
        "approved_display_overrides": sorted(key for key in chinese if
            key.startswith(OVERRIDE_PREFIX) and key.endswith("_title")),
        "original_duplicate_details": raw,
        "note": "No feature IDs or executable behavior were changed. Same-kind alias review is advisory.",
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="build/reports/feature_name_collisions.json")
    parser.add_argument("--fail-on-unsafe", action="store_true")
    args = parser.parse_args()
    result = audit(resources(BASE / "values"), resources(BASE / "values-zh-rCN"))
    output = ROOT / args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Picker labels scanned: {result['features_with_macro_or_shortx_labels']}; "
          f"raw duplicates: {result['original_collision_groups']}; "
          f"remaining groups: {result['display_collision_groups']}; "
          f"unsafe: {len(result['unsafe_collisions'])}; "
          f"same-kind review: {len(result['unresolved_same_kind_aliases'])}")
    print(f"Report: {output}")
    if result["missing_locale_paired_overrides"]:
        raise SystemExit("Missing EN/zh-CN title override pair: " +
                         ", ".join(result["missing_locale_paired_overrides"]))
    if args.fail_on_unsafe and result["unsafe_collisions"]:
        raise SystemExit("Unsafe feature title collisions found; see JSON report.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
