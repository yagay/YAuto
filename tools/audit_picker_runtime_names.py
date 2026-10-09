#!/usr/bin/env python3
"""Audit literal runtime feature descriptors against the EN/zh-CN picker titles.

Direct title gaps are *candidates*: YAuto may use a verified source-title map
or a localized phrase when a direct XML key is missing. Do not auto-translate
or rename a runtime feature ID from this report.
"""
from __future__ import annotations

import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

from audit_picker_duplicate_titles import RES, effective_titles, kind, read_titles

ROOT = Path(__file__).resolve().parents[1]
KOTLIN_ROOTS = (
    ROOT / "platform/android/src/main/kotlin",
    ROOT / "platform/accessibility/src/main/kotlin",
    ROOT / "feature/standard/src/main/kotlin",
)
LITERAL_ID = re.compile(r'FeatureId\(\s*"([a-zA-Z0-9._-]+)"\s*\)')
KIND = re.compile(r'FeatureKind\.(ACTION|EVENT|STATE|CONDITION)')
CATEGORY = re.compile(r'FeatureCategory\.([A-Z_]+)')
ALIAS = re.compile(r'aliases\s*=\s*setOf\(([^)]*)\)', re.S)
QUOTED = re.compile(r'"([^"]+)"')


def descriptor_arguments(source: str):
    """Extract balanced FeatureDescriptor(...) constructor arguments.

    Ignore parentheses inside Kotlin strings so nested schema constructors
    do not cause a premature end. Dynamic FeatureIds are left unclassified.
    """
    needle = "FeatureDescriptor("
    offset = 0
    while (start := source.find(needle, offset)) >= 0:
        pos = start + len("FeatureDescriptor")
        opening = pos
        depth = 0
        quoted = False
        escaped = False
        finish = None
        for i in range(opening, len(source)):
            c = source[i]
            if quoted:
                if escaped:
                    escaped = False
                elif c == "\\":
                    escaped = True
                elif c == '"':
                    quoted = False
                continue
            if c == '"':
                quoted = True
            elif c == "(":
                depth += 1
            elif c == ")":
                depth -= 1
                if depth == 0:
                    finish = i
                    break
        if finish is None:
            break
        yield source[opening + 1:finish]
        offset = finish + 1


def extract_descriptor_records(source: str, source_path: str) -> list[dict]:
    records = []
    for block in descriptor_arguments(source):
        fid = LITERAL_ID.search(block)
        if not fid:
            continue
        feature_id = fid.group(1)
        found_kind = KIND.search(block)
        found_category = CATEGORY.search(block)
        alias_part = ALIAS.search(block)
        aliases = QUOTED.findall(alias_part.group(1)) if alias_part else []
        determined = found_kind.group(1).lower() if found_kind else kind(feature_id.replace(".", "_"))
        if determined == "trigger":
            determined = "event"
        if determined == "constraint":
            determined = "condition"
        records.append({
            "feature_id": feature_id,
            "kind": determined,
            "category": found_category.group(1) if found_category else None,
            "aliases": aliases,
            "source": source_path,
        })
    return records


def scan_sources() -> list[dict]:
    records = []
    for root in KOTLIN_ROOTS:
        if not root.exists():
            continue
        for path in root.rglob("*.kt"):
            records.extend(extract_descriptor_records(
                path.read_text(encoding="utf-8"), path.relative_to(ROOT).as_posix()))
    return records


def title_key(feature_id: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", feature_id.lower()).strip("_")


def audit(records: list[dict], en: dict[str, str], zh: dict[str, str]) -> dict:
    en_names = effective_titles(en)
    zh_names = effective_titles(zh)
    aliases = {alias for record in records for alias in record["aliases"]}
    concrete = [record for record in records if record["feature_id"] not in aliases]
    lookup = {}
    for locale, resources in (("en", en_names), ("zh_cn", zh_names)):
        missing = []
        collisions = defaultdict(list)
        for record in concrete:
            feature = title_key(record["feature_id"])
            title = resources.get(feature)
            if title is None:
                missing.append(record["feature_id"])
            elif record["category"] is not None:
                collisions[(record["kind"], record["category"], title)].append(record["feature_id"])
        duplicate_groups = [
            {"kind": kind, "category": cat, "title": title, "feature_ids": sorted(set(ids))}
            for (kind, cat, title), ids in collisions.items() if len(set(ids)) > 1
        ]
        lookup[locale] = {
            "without_direct_title_key": sorted(set(missing)),
            "without_direct_title_key_count": len(set(missing)),
            "same_kind_category_title_candidates": duplicate_groups,
        }
    return {
        "literal_descriptors_checked": len(records),
        "unique_literal_feature_ids": len({r["feature_id"] for r in records}),
        "registered_compatibility_aliases": sorted(aliases),
        "en": lookup["en"],
        "zh_cn": lookup["zh_cn"],
        "scope": "Only literal FeatureDescriptor(FeatureId(...)) declarations; dynamically registered features are not counted.",
        "warning": "No direct XML title does NOT prove missing translation: source-aligned title or phrase fallback may supply it.",
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--output", default="build/reports/picker_runtime_title_coverage.json")
    args = p.parse_args()
    records = scan_sources()
    result = audit(records, read_titles(RES / "values"), read_titles(RES / "values-zh-rCN"))
    dst = ROOT / args.output
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Runtime literal descriptors: {result['literal_descriptors_checked']}; "
          f"unique IDs: {result['unique_literal_feature_ids']}; "
          f"source aliases: {len(result['registered_compatibility_aliases'])}")
    for language in ("en", "zh_cn"):
        print(f"  {language} no direct title key candidates: "
              f"{result[language]['without_direct_title_key_count']}; "
              f"same-kind/category candidates: "
              f"{len(result[language]['same_kind_category_title_candidates'])}")
    print(f"Report: {dst}")


if __name__ == "__main__":
    main()
