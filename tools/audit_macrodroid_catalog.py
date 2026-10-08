#!/usr/bin/env python3
"""Audit every YAuto feature title and its category without changing stable IDs.

Reports are deterministic and CI-friendly. This deliberately distinguishes duplicate
display labels from duplicate IDs: a state and constraint may share a sensible label.
"""
from collections import defaultdict
from pathlib import Path
import argparse
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "ui/design/src/main/res"
TITLE = re.compile(r"^feature_(.+)_title$")
ID = re.compile(r'\b(?:FeatureId|FeatureTypeId)\s*\(\s*"([^"]+)"')
KIND = re.compile(r"^feature_(?:android|core|system|surface|variable|data|file)_(event|state|condition)_(.+)_title$")

def strings(directory):
    result = {}
    files = sorted(directory.glob("*.xml"))
    for file in files:
        root = ET.parse(file).getroot()
        for el in root.findall("string"):
            key = el.get("name")
            if not key:
                continue
            value = "".join(el.itertext()).strip()
            if key in result:
                raise ValueError(f"Duplicate resource key {key}: {result[key][1]} and {file}")
            result[key] = (value, str(file.relative_to(ROOT)))
    return result, files

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="build/reports/macrodroid_catalog_audit.json")
    parser.add_argument("--fail-on-missing", action="store_true")
    args = parser.parse_args()
    zh, zh_files = strings(RES / "values-zh-rCN")
    en, en_files = strings(RES / "values")
    feature_keys = sorted(k for k in set(zh) | set(en) if TITLE.match(k))
    missing_zh = sorted(set(feature_keys) - set(zh))
    missing_en = sorted(set(feature_keys) - set(en))
    missing_names = sorted(k for k in feature_keys if k in zh and not zh[k][0])
    labels = defaultdict(list)
    for key in feature_keys:
        if key in zh and zh[key][0]:
            labels[zh[key][0]].append(key)
    duplicated_labels = {k:v for k,v in labels.items() if len(v)>1}
    event_names = {k:v for k,v in duplicated_labels.items()
                   if any("_event_" in x for x in v)
                   and any(("_state_" in x or "_condition_" in x or "_set_" in x) for x in v)}
    # IDs with the same text may be valid siblings; report them for semantic review.
    source_files = sorted((ROOT / "feature").rglob("*.kt")) + sorted((ROOT / "platform").rglob("*.kt"))
    definitions = sorted(set(m.group(1) for p in source_files for m in ID.finditer(p.read_text(encoding="utf-8"))))
    # Validate the complete semantic category vocabulary against picker resources.
    category_source = (ROOT / "core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeaturePickerCategory.kt").read_text(encoding="utf-8")
    picker_source = (ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeaturePickerCatalog.kt").read_text(encoding="utf-8")
    enum_match = re.search(r"enum class FeaturePickerCategory\s*\{([^}]+)\}", category_source)
    if enum_match is None:
        raise RuntimeError("Cannot find FeaturePickerCategory enum")
    category_ids = {part.strip() for part in enum_match.group(1).split(",") if part.strip()}
    category_mappings = set(re.findall(r"FeaturePickerCategory\.([A-Z_]+)\s*->", picker_source))
    category_keys = set(re.findall(r"TextR\.string\.(macro_category_[a-z_]+)", picker_source))
    missing_category_mappings = sorted(category_ids - category_mappings)
    missing_category_en = sorted(category_keys - set(en))
    missing_category_zh = sorted(category_keys - set(zh))
    report = {
        "semantic_picker_categories": len(category_ids),
        "unmapped_picker_categories": missing_category_mappings,
        "missing_picker_category_english": missing_category_en,
        "missing_picker_category_chinese": missing_category_zh,
        "chinese_xml_files_scanned": len(zh_files),
        "english_xml_files_scanned": len(en_files),
        "feature_title_keys": len(feature_keys),
        "definition_ids_found_by_simple_pattern": len(definitions),
        "missing_chinese_titles": missing_zh,
        "missing_english_titles": missing_en,
        "empty_chinese_titles": missing_names,
        "shared_chinese_display_names": duplicated_labels,
        "potential_event_state_name_collisions": event_names,
        "notes": [
            "A missing explicit string does not always mean a feature is untranslated: runtime fallback and phrase resources exist.",
            "Two distinct features sharing a title may be correct, especially state/condition counterparts.",
            "Classification needs checking against actual descriptor.kind and descriptor.id, not label alone.",
        ],
    }
    target = ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Scanned {len(zh_files)} zh-CN XML and {len(en_files)} default XML files; "
          f"{len(feature_keys)} feature title resource keys")
    print(f"Missing: zh={len(missing_zh)}, en={len(missing_en)}; "
          f"shared labels={len(duplicated_labels)}; event/state collisions={len(event_names)}")
    print(f"Detailed report: {target}")
    if missing_category_mappings or missing_category_en or missing_category_zh:
        print("ERROR: picker categories or translations are missing", file=sys.stderr)
        return 1
    if args.fail_on_missing and (missing_zh or missing_en or missing_names):
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
