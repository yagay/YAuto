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
    # A present Chinese XML key may still have an English value, which the old
    # missing-key audit did not catch. Compare values, not only names.
    english_copy_titles = sorted(
        key for key, (value, _) in zh.items()
        if TITLE.match(key)
        and key in en
        and value == en[key][0]
        and re.search(r"[A-Za-z]{3}", value)
        and not re.search(r"[\u4e00-\u9fff]", value)
    )
    english_copy_phrases = sorted(
        key for key, (value, _) in zh.items()
        if key.startswith("feature_phrase_")
        and key in en
        and value == en[key][0]
        and re.search(r"[A-Za-z]{3}", value)
        and not re.search(r"[\u4e00-\u9fff]", value)
    )
    # Only intentionally language-neutral terms may remain untranslated in the
    # expansion vocabulary. A named allowlist prevents newly added English defaults
    # from silently appearing in the Chinese feature picker.
    neutral_term_file = ROOT / "tools/allowed_untranslated_technical_terms.txt"
    neutral_terms = {
        line.strip() for line in neutral_term_file.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    }
    copied_expansion_keys = {
        key for key, (value, filename) in zh.items()
        if filename.endswith("values-zh-rCN/expansion_feature_phrases.xml")
        and key in en and value == en[key][0]
    }
    unapproved_english_expansion = sorted(copied_expansion_keys - neutral_terms)
    obsolete_neutral_exceptions = sorted(neutral_terms - copied_expansion_keys)
    english_copy_by_file = defaultdict(int)
    for key in english_copy_titles + english_copy_phrases:
        english_copy_by_file[zh[key][1]] += 1
    source_files = sorted((ROOT / "feature").rglob("*.kt")) + sorted((ROOT / "platform").rglob("*.kt"))
    # Check all naming layers, including approved MacroDroid/ShortX terminology
    # and YAuto source aliases, instead of checking only missing XML keys.
    chinese_title_keys = sorted(
        key for key in zh
        if ((key.startswith(("feature_", "macro_feature_", "shortx_feature_")) and key.endswith("_title"))
            or key.startswith("source_feature_name_"))
    )
    english_only_chinese_titles = sorted(
        key for key in chinese_title_keys
        if re.search(r"[A-Za-z]{3}", zh[key][0])
        and not re.search(r"[\u3400-\u9fff]", zh[key][0])
    )
    # Report source descriptors at risk of showing their raw English title.
    # These patterns are deliberately conservative; the reported list is an
    # investigation aid because dynamic feature packs also generate descriptors.
    explicit_source_titles = {}
    named_descriptor = re.compile(
        r'FeatureDescriptor\(\s*id\s*=\s*FeatureId\("([^"]+)"\)\s*,\s*'
        r'kind\s*=\s*FeatureKind\.[A-Z]+\s*,\s*title\s*=\s*"([^"]+)"',
        flags=re.S,
    )
    positional_descriptor = re.compile(
        r'FeatureDescriptor\(\s*FeatureId\("([^"]+)"\)\s*,\s*'
        r'FeatureKind\.[A-Z]+\s*,\s*"([^"]+)"',
        flags=re.S,
    )
    for src in source_files:
        source = src.read_text(encoding="utf-8")
        for regex in (named_descriptor, positional_descriptor):
            for match in regex.finditer(source):
                explicit_source_titles[match.group(1)] = match.group(2)
    def resource_key(value: str) -> str:
        return re.sub(r"_+$", "", re.sub(r"[^a-z0-9]+", "_", value.lower()))
    unlocalized_literal_feature_titles = []
    for feature_id, raw_title in sorted(explicit_source_titles.items()):
        suffix = resource_key(feature_id)
        if (("macro_feature_" + suffix + "_title" in zh)
                or ("shortx_feature_" + suffix + "_title" in zh)
                or ("feature_" + suffix + "_title" in zh)
                or ("feature_phrase_" + resource_key(raw_title) in zh)):
            continue
        unlocalized_literal_feature_titles.append({"id": feature_id, "raw_title": raw_title})
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
    definitions = sorted(set(m.group(1) for p in source_files for m in ID.finditer(p.read_text(encoding="utf-8"))))
    # Validate the complete semantic category vocabulary against picker resources.
    category_source = (ROOT / "core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeaturePickerCategory.kt").read_text(encoding="utf-8")
    picker_source = (ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeaturePickerCatalogModel.kt").read_text(encoding="utf-8")
    enum_match = re.search(r"enum class FeaturePickerCategory\s*\{([^}]+)\}", category_source)
    if enum_match is None:
        raise RuntimeError("Cannot find FeaturePickerCategory enum")
    category_ids = {part.strip() for part in enum_match.group(1).split(",") if part.strip()}
    category_mappings = set(re.findall(r"FeaturePickerCategory\.([A-Z_]+)\s*->", picker_source))
    category_keys = set(re.findall(r"TextR\.string\.(macro_category_[a-z_]+)", picker_source))
    missing_category_mappings = sorted(category_ids - category_mappings)
    missing_category_en = sorted(category_keys - set(en))
    missing_category_zh = sorted(category_keys - set(zh))
    macro_keys_en = {k for k in en if k.startswith("macro_feature_") and k.endswith("_title")}
    macro_keys_zh = {k for k in zh if k.startswith("macro_feature_") and k.endswith("_title")}
    missing_macro_en = sorted(macro_keys_zh - macro_keys_en)
    missing_macro_zh = sorted(macro_keys_en - macro_keys_zh)
    # Stable, reviewed semantic mapping: no automatically guessed feature labels.
    reviewed_path = ROOT / "tools/macrodroid_reviewed_names.csv"
    with reviewed_path.open(encoding="utf-8", newline="") as mapping_file:
        reviewed = list(__import__("csv").DictReader(mapping_file))
    reviewed_keys = [row["yauto_resource_key"] for row in reviewed]
    duplicate_reviewed_keys = sorted(key for key, cnt in __import__("collections").Counter(reviewed_keys).items() if cnt > 1)
    missing_reviewed_overrides = sorted("macro_" + key for key in set(reviewed_keys) if "macro_" + key not in macro_keys_en or "macro_" + key not in macro_keys_zh)
    unmapped_macro_overrides = sorted((macro_keys_en | macro_keys_zh) - {"macro_" + key for key in reviewed_keys})
    mismatched_reviewed_kinds = []
    for row in reviewed:
        name = row["yauto_resource_key"]
        # Only a real feature kind segment counts: calendar_event_add is an
        # ACTION and condition_calendar_event is a CONSTRAINT, despite "event".
        actual_kind = ("constraint" if re.match(r"^feature_[a-z0-9]+_(?:condition|state)_", name) else
                       "trigger" if re.match(r"^feature_[a-z0-9]+_event_", name) else
                       "action")
        if row["kind"] != actual_kind or not row["macrodroid_resource_key"].startswith(actual_kind + "_"):
            mismatched_reviewed_kinds.append(name)
    # Verify actual localized titles against the curated APK terminology reference.
    with (ROOT / "tools/macrodroid_verified_titles.csv").open(encoding="utf-8", newline="") as source_file:
        verified_titles = list(__import__("csv").DictReader(source_file))
    verified_pairs = {(r["yauto_resource_key"], r["macrodroid_resource_key"]) for r in verified_titles}
    expected_pairs = {(r["yauto_resource_key"], r["macrodroid_resource_key"]) for r in reviewed}
    missing_verified_pairs = sorted(expected_pairs - verified_pairs)
    unreviewed_reference_pairs = sorted(verified_pairs - expected_pairs)
    mismatched_verified_labels = []
    def normalized_android_label(value: str) -> str:
        return value.replace("\\'", "'")

    for row in verified_titles:
        key = "macro_" + row["yauto_resource_key"]
        actual_en = normalized_android_label(en.get(key, ("", ""))[0])
        actual_zh = normalized_android_label(zh.get(key, ("", ""))[0])
        if actual_en != row["english"] or actual_zh != row["chinese"]:
            mismatched_verified_labels.append(row["yauto_resource_key"])
    # YAuto title policy: MacroDroid > ShortX > YAuto native/source fallback.
    # Only behavior-verified ShortX equivalents belong in this table.
    with (ROOT / "tools/shortx_verified_titles.csv").open(encoding="utf-8", newline="") as sx_file:
        shortx_reviewed = list(__import__("csv").DictReader(sx_file))
    shortx_expected = {"shortx_" + item["yauto_resource_key"] for item in shortx_reviewed}
    shortx_xml_en = {k for k in en if k.startswith("shortx_feature_") and k.endswith("_title")}
    shortx_xml_zh = {k for k in zh if k.startswith("shortx_feature_") and k.endswith("_title")}
    shortx_issues = []
    if len(shortx_reviewed) != len(shortx_expected):
        shortx_issues.append("duplicate ShortX feature IDs")
    if shortx_expected != shortx_xml_en or shortx_expected != shortx_xml_zh:
        shortx_issues.append("missing or unmatched ShortX localized resources")
    if {"macro_" + item["yauto_resource_key"] for item in shortx_reviewed} & macro_keys_en:
        shortx_issues.append("ShortX override overlaps a higher priority MacroDroid override")
    for item in shortx_reviewed:
        key = "shortx_" + item["yauto_resource_key"]
        source_id = item["yauto_resource_key"]
        # A state is a condition-like picker feature; it must never map to an action or trigger.
        actual_kind = ("constraint" if re.match(r"^feature_[a-z0-9]+_(?:condition|state)_", source_id) else
                       "trigger" if re.match(r"^feature_[a-z0-9]+_event_", source_id) else
                       "action")
        if item["kind"] != actual_kind:
            shortx_issues.append(source_id + ": kind mismatch")
        if en.get(key, ("", ""))[0].replace("\\'", "'") != item["english"]:
            shortx_issues.append(source_id + ": English label differs from ShortX APK")
        if zh.get(key, ("", ""))[0].replace("\\'", "'") != item["chinese"]:
            shortx_issues.append(source_id + ": Chinese label differs from ShortX APK")
    resolver_path = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeatureTextResources.kt"
    resolver_code = resolver_path.read_text(encoding="utf-8")
    macro_title_line = 'resource("macro_feature_${resourceKey(descriptor.id.value)}_title")'
    shortx_title_line = 'resource("shortx_feature_${resourceKey(descriptor.id.value)}_title")'
    native_title_line = 'resource("feature_${resourceKey(descriptor.id.value)}_title")'
    source_alias_line = 'sourceAlignedFeatureTitle(descriptor.id.value)'
    if (macro_title_line not in resolver_code
            or shortx_title_line not in resolver_code
            or native_title_line not in resolver_code
            or source_alias_line not in resolver_code
            or not (resolver_code.index(macro_title_line)
                    < resolver_code.index(shortx_title_line)
                    < resolver_code.index(native_title_line)
                    < resolver_code.index(source_alias_line))):
        shortx_issues.append("wrong MacroDroid > ShortX > native title > legacy alias order")
    # Explicitly block misleading machine-suggested matches that change behavior.
    with (ROOT / "tools/macrodroid_false_matches.csv").open(encoding="utf-8", newline="") as rejected_file:
        false_friends = list(__import__("csv").DictReader(rejected_file))
    reviewed_pairs = {(row["yauto_resource_key"], row["macrodroid_resource_key"]) for row in reviewed}
    forbidden_matches = [
        row["yauto_resource_key"] for row in false_friends
        if (row["yauto_resource_key"], row["rejected_macro_resource_key"]) in reviewed_pairs
    ]
    # Shared MacroDroid labels can hide distinct YAuto implementations; expose each group
    # for manual review rather than incorrectly failing intentional state/constraint pairs.
    reviewed_groups = defaultdict(list)
    for row in reviewed:
        reviewed_groups[row["macrodroid_resource_key"]].append(row["yauto_resource_key"])
    multiple_features_per_macro_name = {
        macro: sorted(keys) for macro, keys in sorted(reviewed_groups.items()) if len(keys) > 1
    }
    report = {
        "macrodroid_aligned_feature_titles": len(macro_keys_en),
        "reviewed_mapping_count": len(reviewed),
        "shortx_reviewed_mapping_count": len(shortx_reviewed),
        "shortx_validation_issues": shortx_issues,
        "apk_reference_titles_checked": len(verified_titles),
        "missing_apk_reference_pairs": missing_verified_pairs,
        "unreviewed_apk_reference_pairs": unreviewed_reference_pairs,
        "titles_different_from_apk_reference": mismatched_verified_labels,
        "multiple_features_per_macro_name": multiple_features_per_macro_name,
        "pending_semantic_review_keys": sorted(set(feature_keys) - set(reviewed_keys)),
        "rejected_unsafe_mapping_count": len(false_friends),
        "forbidden_matches": forbidden_matches,
        "unreviewed_feature_title_count": len(set(feature_keys) - set(reviewed_keys)),
        "duplicate_reviewed_keys": duplicate_reviewed_keys,
        "missing_reviewed_overrides": missing_reviewed_overrides,
        "unmapped_macro_overrides": unmapped_macro_overrides,
        "mismatched_reviewed_kinds": mismatched_reviewed_kinds,
        "missing_macro_english": missing_macro_en,
        "missing_macro_chinese": missing_macro_zh,
        "semantic_picker_categories": len(category_ids),
        "unmapped_picker_categories": missing_category_mappings,
        "missing_picker_category_english": missing_category_en,
        "missing_picker_category_chinese": missing_category_zh,
        "chinese_xml_files_scanned": len(zh_files),
        "english_xml_files_scanned": len(en_files),
        "unapproved_english_expansion_phrases": unapproved_english_expansion,
        "obsolete_untranslated_technical_exceptions": obsolete_neutral_exceptions,
        "unchanged_language_neutral_terms": len(copied_expansion_keys),
        "feature_titles_identical_to_english": english_copy_titles,
        "chinese_title_keys_checked": len(chinese_title_keys),
        "english_only_chinese_titles": english_only_chinese_titles,
        "explicit_source_titles_scanned": len(explicit_source_titles),
        "possible_raw_english_title_fallbacks": unlocalized_literal_feature_titles,
        "phrase_labels_identical_to_english": english_copy_phrases,
        "english_copy_by_zh_resource_file": dict(sorted(english_copy_by_file.items(), key=lambda item: (-item[1], item[0]))),
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
            "An English string copied into values-zh-rCN is counted as untranslated even if its key exists.",
        ],
    }
    target = ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Scanned {len(zh_files)} zh-CN XML and {len(en_files)} default XML files; "
          f"{len(feature_keys)} feature title resource keys")
    print(f"Missing: zh={len(missing_zh)}, en={len(missing_en)}; "
          f"shared labels={len(duplicated_labels)}; event/state collisions={len(event_names)}")
    print(f"Chinese strings still identical to English: feature titles={len(english_copy_titles)}, "
          f"phrase/field labels={len(english_copy_phrases)}")
    print("Top files still requiring translation:",
          sorted(english_copy_by_file.items(), key=lambda item: -item[1])[:8])
    print(f"Detailed report: {target}")
    if english_only_chinese_titles:
        print("ERROR: English-only feature/source titles in Chinese resources:", english_only_chinese_titles, file=sys.stderr)
        return 1
    if missing_verified_pairs or unreviewed_reference_pairs or mismatched_verified_labels:
        print("ERROR: MacroDroid localized titles differ from the approved APK reference", file=sys.stderr)
        return 1
    if unapproved_english_expansion or obsolete_neutral_exceptions:
        print("ERROR: Chinese expansion phrase vocabulary is missing translations or has stale exceptions", file=sys.stderr)
        return 1
    if shortx_issues:
        print("ERROR: ShortX title validation failed: " + "; ".join(shortx_issues), file=sys.stderr)
        return 1
    if forbidden_matches:
        print("ERROR: non-equivalent MacroDroid names must not replace YAuto feature names", file=sys.stderr)
        return 1
    if duplicate_reviewed_keys or missing_reviewed_overrides or unmapped_macro_overrides or mismatched_reviewed_kinds:
        print("ERROR: reviewed MacroDroid mapping is inconsistent with localization overrides", file=sys.stderr)
        return 1
    if missing_macro_en or missing_macro_zh:
        print("ERROR: MacroDroid feature title overrides missing locale pair", file=sys.stderr)
        return 1
    if missing_category_mappings or missing_category_en or missing_category_zh:
        print("ERROR: picker categories or translations are missing", file=sys.stderr)
        return 1
    if args.fail_on_missing and (missing_zh or missing_en or missing_names):
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
