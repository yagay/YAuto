#!/usr/bin/env python3
"""Audit parameter-choice text within source-approved unified picker entries.

One top-level function title can cover multiple implementations, but the
configuration dropdown must never show identical mode/operation labels.
"""
from __future__ import annotations
import argparse
import json
import re
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

from audit_picker_duplicate_titles import RES, effective_titles, read_titles
from audit_verified_picker_merges import APPROVED, csv_rows

ROOT = Path(__file__).resolve().parents[1]


def locale_strings(folder: Path) -> dict[str, str]:
    results = {}
    for path in sorted(folder.glob("*.xml")):
        for elem in ET.parse(path).getroot().findall("string"):
            key = elem.get("name")
            if key:
                if key in results:
                    raise ValueError(f"Duplicate string {key} ({path})")
                results[key] = "".join(elem.itertext()).strip()
    return results


def normalized_id(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "_", value.lower()).strip("_")


def method_labels(group: dict, resources: dict[str, str],
                  effective: dict[str, str]) -> list[dict]:
    rows = []
    for feature_id in group["member_ids"].split("|"):
        normalized = normalized_id(feature_id)
        explicit = resources.get(f"feature_variant_{normalized}")
        translated_title = effective.get(normalized)
        rows.append({
            "id": feature_id,
            "method_label": explicit or translated_title,
            "has_explicit_method_label": bool(explicit),
            "has_direct_title_resource": bool(translated_title),
        })
    return rows


def inspect(groups: list[dict], strings: dict[str, str]) -> dict:
    titles = {k: v for k, v in strings.items() if k.endswith("_title")}
    names = effective_titles(titles)
    repeated = []
    unidentified = []
    for group in groups:
        rows = method_labels(group, strings, names)
        by_label = defaultdict(list)
        for row in rows:
            if row["method_label"]:
                by_label[row["method_label"]].append(row["id"])
            else:
                unidentified.append({"group": group["group_id"], "id": row["id"]})
        for label, ids in by_label.items():
            if len(ids) > 1:
                repeated.append({"group": group["group_id"], "label": label,
                                 "duplicate_member_ids": ids})
    return {
        "source_verified_groups": len(groups),
        "member_refs": sum(len(g["member_ids"].split("|")) for g in groups),
        "duplicate_parameter_labels": repeated,
        "missing_direct_method_or_title": unidentified,
    }


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--output", default="build/reports/picker_parameter_labels.json")
    p.add_argument("--fail-on-duplicates", action="store_true")
    a = p.parse_args()
    groups = csv_rows(APPROVED)
    data = {lang: inspect(groups, locale_strings(RES / folder))
            for lang, folder in (("en", "values"), ("zh_cn", "values-zh-rCN"))}
    dst = ROOT / a.output
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for locale, info in data.items():
        print(f"{locale} unified method label audit: {info['source_verified_groups']} groups, "
              f"{info['member_refs']} member IDs, "
              f"duplicate labels {len(info['duplicate_parameter_labels'])}, "
              f"missing direct labels {len(info['missing_direct_method_or_title'])}")
        for item in info["duplicate_parameter_labels"]:
            print(f"  duplicate {locale} / {item['group']} / {item['label']}: {', '.join(item['duplicate_member_ids'])}")
    print(f"Report: {dst}")
    if a.fail_on_duplicates and any(info["duplicate_parameter_labels"] for info in data.values()):
        raise SystemExit("Duplicate parameter labels found in unified feature editor")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
