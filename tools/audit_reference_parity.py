#!/usr/bin/env python3
"""Full ShortX action-type visibility audit; a source hint is NOT native parity."""
from __future__ import annotations

import argparse
import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "tools/shortx_reference_actions.csv"
MAPPINGS = ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/ShortXMappings.kt"
STRUCTURAL = ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/ShortXStructuralMappings.kt"
HINTS = ROOT / "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/EnhancedShortXImporter.kt"
MACRO = ROOT / "tools/macrodroid_reviewed_names.csv"
MERGES = ROOT / "tools/verified_picker_merges.csv"
HOOKS = ROOT / "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/ShortXCompatHookCatalog.kt"
HOOK_EVENTS = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidShortXHookEventFeaturePack.kt"


def names_in_branches(source: str) -> set[str]:
    """Only 12-space Kotlin when branches, not string occurrences or hint targets."""
    return set(re.findall(r'^ {12}"([A-Za-z][A-Za-z0-9_]*)"(?:\s*,\s*"[A-Za-z][A-Za-z0-9_]*")*\s*->', source, re.M))


def inventory() -> dict:
    with CATALOG.open(encoding="utf-8", newline="") as f:
        catalog = list(csv.DictReader(f))
    types = [row["source_type"] for row in catalog]
    native = MAPPINGS.read_text(encoding="utf-8")
    structural = STRUCTURAL.read_text(encoding="utf-8")
    hints = HINTS.read_text(encoding="utf-8")
    binary = names_in_branches(native.split("fun nativeAction(", 1)[1].split("fun nativeFact(", 1)[0])
    json_actions = names_in_branches(native.split("private fun nativeJsonAction(", 1)[1].split("private fun jsonFact", 1)[0])
    structure_names = names_in_branches(structural.split("fun convert(", 1)[1])
    # Hints can be helpful to guide manual migration but are not executable imports.
    hint_names = set(re.findall(r'"([A-Za-z][A-Za-z0-9_]*)"', hints.split("private fun action(name", 1)[1].split("private fun fact(name", 1)[0]))
    with MACRO.open(encoding="utf-8", newline="") as f:
        macro_rows = list(csv.DictReader(f))
    with MERGES.open(encoding="utf-8", newline="") as f:
        groups = list(csv.DictReader(f))
    hook_source = HOOKS.read_text(encoding="utf-8")
    hook_events = HOOK_EVENTS.read_text(encoding="utf-8")
    hooks = dict(re.findall(r'id = "([^"]+)"[\s\S]*?eventType = "([^"]+)"', hook_source.split("val systemServerObservers:", 1)[1].split("val behaviorHooks:", 1)[0]))
    unknown_hooks = sorted(set(hooks.values()) - set(re.findall(r'event\(registry, "([^"]+)"', hook_events)))
    rows = []
    for name in types:
        binary_native = name in binary
        json_native = name in json_actions
        structural_native = name in structure_names
        hint = name in hint_names
        if binary_native or json_native or structural_native:
            status = "field_decoder_present"
        elif hint:
            status = "hint_only"
        else:
            status = "unmapped_or_nonaction"
        rows.append({
            "source_type": name,
            "binary_decoder_declared": binary_native,
            "json_decoder_declared": json_native,
            "structural_decoder_declared": structural_native,
            "has_suggestion_only": hint and status == "hint_only",
            "status": status,
        })
    return {
        "reference": "ShortX-Repo/ShortX-Files main skills/references/actions.md (2026-10-10 snapshot)",
        "shortx_source_types": len(types),
        "shortx_binary_decoder_branches": sum(r["binary_decoder_declared"] for r in rows),
        "shortx_json_decoder_branches": sum(r["json_decoder_declared"] for r in rows),
        "shortx_structural_decoder_branches": sum(r["structural_decoder_declared"] for r in rows),
        "shortx_field_decoder_present": sum(r["status"] == "field_decoder_present" for r in rows),
        "shortx_hint_only": sum(r["status"] == "hint_only" for r in rows),
        "shortx_unmapped_or_nonaction": sum(r["status"] == "unmapped_or_nonaction" for r in rows),
        "macrodroid_reviewed_name_pairs": len(macro_rows),
        "macrodroid_unique_reviewed_keys": len({r["macrodroid_resource_key"] for r in macro_rows}),
        "approved_picker_merge_groups": len(groups),
        "hook_observer_specifications": len(hooks),
        "hook_events_without_picker_registration": unknown_hooks,
        "rows": rows,
        "warning": "Decoder branch presence does not prove field-level parity, runtime correctness, or OEM Hook compatibility; suggestions are not native support.",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="build/reports/full_source_parity_inventory.json")
    parser.add_argument("--fail-on-unsafe", action="store_true")
    args = parser.parse_args()
    report = inventory()
    path = ROOT / args.output
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("ShortX source types:", report["shortx_source_types"],
          "declared decoder:", report["shortx_field_decoder_present"],
          "hint only:", report["shortx_hint_only"],
          "not mapped:", report["shortx_unmapped_or_nonaction"],
          "Hook specs:", report["hook_observer_specifications"])
    unsafe = report["shortx_source_types"] != 193 or bool(report["hook_events_without_picker_registration"])
    if args.fail_on_unsafe and unsafe:
        raise SystemExit("Incomplete ShortX snapshot or Hook event registration: " + str(report["hook_events_without_picker_registration"]))
    return int(unsafe)


if __name__ == "__main__":
    raise SystemExit(main())
