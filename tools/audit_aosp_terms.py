#!/usr/bin/env python3
"""Audit YAuto localization using verified Android Open Source Project terminology.

The AOSP glossary is advisory: a matching English word alone never authorizes
replacement of MacroDroid, ShortX, or YAuto feature names and semantics.
"""
from __future__ import annotations

import argparse
import csv
import json
import re
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
GLOSSARY = ROOT / "tools/aosp_verified_terms.csv"
SOURCES = {
    "settings": {
        "repo": "aosp-mirror/platform_packages_apps_settings",
        "en": "res/values/strings.xml",
        "zh_cn": "res/values-zh-rCN/strings.xml",
    },
    "systemui": {
        "repo": "aosp-mirror/platform_frameworks_base",
        "en": "packages/SystemUI/res/values/tiles_states_strings.xml",
        "zh_cn": "packages/SystemUI/res/values-zh-rCN/tiles_states_strings.xml",
    },
}
CJK = re.compile(r"[\u3400-\u9fff]")
LATIN = re.compile(r"[A-Za-z]{3}")
TECHNICAL = re.compile(
    r"(?:Wi-?Fi|WLAN|NFC|ADB|API|HTTP|HTTPS|URI|URL|SSID|SIM|IMEI|"
    r"Root|LSPosed|Shizuku|Bluetooth|GPS|UUID|ID|PIN|JSON|XML|Java|Kotlin|"
    r"Tasker|MacroDroid|ShortX|YAuto|LTE|5G|4G|3G|USB|DNS|TCP|UDP)", re.I
)
PROTECTED = ("macro_feature_", "shortx_feature_", "source_feature_name_")


@dataclass(frozen=True)
class Term:
    source: str
    source_key: str
    en: str
    zh_cn: str


def xml_text(node: ET.Element) -> str:
    return "".join(node.itertext()).strip().strip('"')


def resource_items(path: Path) -> dict[str, str]:
    """Index both languages by resource key; arrays are keyed by ordered slot."""
    out: dict[str, str] = {}
    for node in ET.parse(path).getroot():
        key = node.get("name")
        if not key:
            continue
        if node.tag == "string":
            if key in out:
                raise ValueError(f"Duplicate XML key {key} in {path}")
            out[key] = xml_text(node)
        if node.tag == "string-array":
            for index, item in enumerate(node.findall("item")):
                slot = f"{key}[{index}]"
                if slot in out:
                    raise ValueError(f"Duplicate array slot {slot} in {path}")
                out[slot] = xml_text(item)
    return out


def load_terms(path: Path = GLOSSARY) -> list[Term]:
    with path.open(encoding="utf-8", newline="") as f:
        csv_file = csv.DictReader(f)
        if csv_file.fieldnames != ["source", "source_key", "en", "zh_cn"]:
            raise ValueError("Invalid AOSP glossary CSV columns")
        terms = [Term(**row) for row in csv_file]
    used: set[tuple[str, str]] = set()
    for term in terms:
        identity = (term.source, term.source_key)
        if term.source not in SOURCES or not all(asdict(term).values()) or identity in used:
            raise ValueError(f"Invalid or duplicate AOSP source: {identity}")
        used.add(identity)
    return terms


def load_yauto_resources(root: Path) -> tuple[dict, dict]:
    result: dict[str, dict] = {"en": {}, "zh_cn": {}}
    for en_folder in sorted(root.glob("**/src/main/res/values")):
        for locale, folder in (("en", en_folder), ("zh_cn", en_folder.parent / "values-zh-rCN")):
            if not folder.is_dir():
                continue
            for path in sorted(folder.glob("*.xml")):
                for key, value in resource_items(path).items():
                    qualified = str(en_folder.parent.relative_to(root)) + "::" + key
                    if qualified in result[locale]:
                        raise ValueError(f"Duplicate YAuto string key: {qualified}")
                    result[locale][qualified] = {
                        "name": key, "value": value, "file": str(path.relative_to(root))
                    }
    return result["en"], result["zh_cn"]


def protected_title(key: str) -> bool:
    return key.startswith(PROTECTED) or (key.startswith("feature_") and key.endswith("_title"))


def audit(terms: list[Term], english: dict, chinese: dict) -> dict:
    options: dict[str, set[str]] = defaultdict(set)
    origins: dict[str, list[str]] = defaultdict(list)
    for term in terms:
        options[term.en].add(term.zh_cn)
        origins[term.en].append(f"{term.source}:{term.source_key}")
    reviews, untranslated = [], []
    for key, source in sorted(english.items()):
        target = chinese.get(key)
        if target is None:
            continue  # check-localization.py already validates missing keys.
        en, zh = source["value"], target["value"]
        if en == zh and LATIN.search(zh) and not CJK.search(zh) and not TECHNICAL.fullmatch(zh):
            untranslated.append({"resource": key, "english": en, "file": target["file"]})
        # Do not guess fuzzy or ambiguous meanings.
        variants = options.get(en, set())
        if len(variants) != 1:
            continue
        suggestion = next(iter(variants))
        if zh == suggestion:
            continue
        reviews.append({
            "resource": key, "english": en, "current_zh": zh,
            "suggested_zh": suggestion, "sources": sorted(origins[en]),
            "protected_feature_title": protected_title(source["name"]),
        })
    return {
        "glossary_terms": len(terms),
        "yauto_english_resources": len(english),
        "yauto_chinese_resources": len(chinese),
        "untranslated_english": untranslated,
        "aosp_review_candidates": reviews,
        "review_count": len(reviews),
        "untranslated_count": len(untranslated),
        "automatic_replacements": 0,
    }


def verify_official(terms: list[Term], pairs: dict[str, tuple[Path, Path]]) -> None:
    """Verify that EN and zh-CN are from the same upstream source key."""
    for source, (en_file, zh_file) in pairs.items():
        original_en = resource_items(en_file)
        original_zh = resource_items(zh_file)
        for row in terms:
            if row.source == source and (
                original_en.get(row.source_key) != row.en
                or original_zh.get(row.source_key) != row.zh_cn
            ):
                raise ValueError(f"AOSP original pair changed or disagrees: {source}:{row.source_key}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", default="build/reports/aosp_terminology_audit.json")
    parser.add_argument("--aosp-settings-en", type=Path)
    parser.add_argument("--aosp-settings-zh", type=Path)
    parser.add_argument("--aosp-systemui-en", type=Path)
    parser.add_argument("--aosp-systemui-zh", type=Path)
    args = parser.parse_args()
    official = {}
    for source in SOURCES:
        en_file = getattr(args, f"aosp_{source}_en")
        zh_file = getattr(args, f"aosp_{source}_zh")
        if bool(en_file) != bool(zh_file):
            parser.error(f"Both upstream XML files must be provided for {source}")
        if en_file:
            official[source] = (en_file, zh_file)
    terms = load_terms()
    if official:
        verify_official(terms, official)
    en, zh = load_yauto_resources(ROOT)
    report = audit(terms, en, zh)
    report["sources"] = SOURCES
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"AOSP audit: {report['glossary_terms']} reviewed terms; "
        f"{report['review_count']} semantic review candidates; "
        f"{report['untranslated_count']} English-copy candidates; "
        f"report: {output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
