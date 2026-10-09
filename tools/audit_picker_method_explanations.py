#!/usr/bin/env python3
"""Guard feature-specific explanations for all verified picker implementation methods.

Every selectable implementation has a reviewed localized explanation, and the
different methods of the same function cannot reuse identical text.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from audit_picker_method_labels import locale_strings, normalized_id
from audit_picker_duplicate_titles import RES
from audit_verified_picker_merges import APPROVED, csv_rows

ROOT = Path(__file__).resolve().parents[1]


def inspect(groups: list[dict], resources: dict[str, str]) -> dict:
    missing = []
    duplicate_in_group = []
    for group in groups:
        by_text: dict[str, list[str]] = {}
        for feature_id in group["member_ids"].split("|"):
            key = f"feature_variant_{normalized_id(feature_id)}_description"
            explanation = resources.get(key, "").strip()
            if not explanation:
                missing.append({"group": group["group_id"], "feature_id": feature_id, "key": key})
                continue
            by_text.setdefault(explanation.casefold(), []).append(feature_id)
        for explanation, ids in by_text.items():
            if len(ids) > 1:
                duplicate_in_group.append({
                    "group": group["group_id"], "explanation": explanation, "member_ids": ids,
                })
    return {
        "verified_groups": len(groups),
        "expected_method_explanations": sum(len(g["member_ids"].split("|")) for g in groups),
        "missing": missing,
        "identical_explanations_within_group": duplicate_in_group,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="build/reports/picker_method_explanations.json")
    parser.add_argument("--fail-on-missing-or-duplicates", action="store_true")
    args = parser.parse_args()
    groups = csv_rows(APPROVED)
    result = {
        locale: inspect(groups, locale_strings(RES / folder))
        for locale, folder in (("en", "values"), ("zh_cn", "values-zh-rCN"))
    }
    destination = ROOT / args.output
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for language, report in result.items():
        print(f"{language}: {report['verified_groups']} groups, "
              f"{report['expected_method_explanations']} method descriptions, "
              f"missing {len(report['missing'])}, "
              f"same-group duplicates {len(report['identical_explanations_within_group'])}")
    print(f"Report: {destination}")
    if args.fail_on_missing_or_duplicates and any(
        report["missing"] or report["identical_explanations_within_group"]
        for report in result.values()
    ):
        raise SystemExit("Missing or repeated localized implementation explanations")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
