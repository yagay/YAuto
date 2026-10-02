#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(".")
FORMAT_TOKEN = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[a-zA-Z]")
PLACEHOLDER_TRANSLATIONS = {
    "自动化功能",
    "待翻译",
    "未翻译",
    "TODO",
    "TBD",
}
PRESERVED_FEATURE_TOKENS = {
    "feature_phrase_get": "GET",
    "feature_phrase_post": "POST",
    "feature_phrase_put": "PUT",
    "feature_phrase_delete": "DELETE",
    "feature_phrase_head": "HEAD",
    "feature_phrase_md5": "MD5",
    "feature_phrase_sha_1": "SHA-1",
    "feature_phrase_sha_256": "SHA-256",
    "feature_phrase_gps": "GPS",
    "feature_phrase_vpn": "VPN",
    "feature_phrase_uri": "URI",
    "feature_phrase_url": "URL",
}


def text(node: ET.Element) -> str:
    return "".join(node.itertext()).strip()


def strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    return {
        node.attrib["name"]: text(node)
        for node in root.findall("string")
        if node.attrib.get("name")
    }


def plurals(folder: Path) -> dict[str, dict[str, str]]:
    result: dict[str, dict[str, str]] = {}
    if not folder.exists():
        return result
    for path in folder.glob("*.xml"):
        root = ET.parse(path).getroot()
        for node in root.findall("plurals"):
            name = node.attrib.get("name")
            if not name:
                continue
            if name in result:
                raise RuntimeError(f"Duplicate plurals resource {name} in {folder}")
            result[name] = {
                item.attrib.get("quantity", ""): text(item)
                for item in node.findall("item")
            }
    return result


def locale_name(folder: Path) -> str | None:
    qualifier = folder.name.removeprefix("values-")
    if re.fullmatch(r"[a-z]{2,3}(?:-r[A-Z]{2})?", qualifier):
        parts = qualifier.split("-r", 1)
        return parts[0] if len(parts) == 1 else f"{parts[0]}-{parts[1]}"
    if qualifier.startswith("b+"):
        return qualifier[2:].replace("+", "-")
    return None


def main() -> int:
    failures: list[str] = []

    # Feature phrases are a semantic fallback catalog. A generic placeholder may satisfy key
    # parity while still presenting completely wrong UI copy, so reject known placeholder values.
    feature_en_path = ROOT / "ui/design/src/main/res/values/feature_phrases.xml"
    feature_zh_path = ROOT / "ui/design/src/main/res/values-zh-rCN/feature_phrases.xml"
    feature_en = strings(feature_en_path)
    feature_zh = strings(feature_zh_path)

    if feature_en.keys() != feature_zh.keys():
        for name in sorted(feature_en.keys() - feature_zh.keys()):
            failures.append(f"Missing zh-CN feature phrase: {name}")
        for name in sorted(feature_zh.keys() - feature_en.keys()):
            failures.append(f"Unexpected zh-CN feature phrase: {name}")

    for name, value in sorted(feature_zh.items()):
        if not value:
            failures.append(f"{feature_zh_path}: empty feature translation: {name}")
        if value in PLACEHOLDER_TRANSLATIONS:
            failures.append(
                f"{feature_zh_path}: placeholder translation {value!r} is forbidden for {name}"
            )

    for name, required in PRESERVED_FEATURE_TOKENS.items():
        actual = feature_zh.get(name)
        if actual != required:
            failures.append(
                f"{feature_zh_path}: protocol/technical token {name} must remain {required!r}, got {actual!r}"
            )

    # Plural resources have language-specific quantity categories, so locale files do not need the
    # same categories. They do need the same plural keys, an 'other' case, and compatible format
    # arguments. This closes the gap left by the string-only parity guard.
    for default in sorted(ROOT.glob("**/src/main/res/values")):
        base = plurals(default)
        if not base:
            continue
        module_res = default.parent
        for name, quantities in sorted(base.items()):
            if "other" not in quantities:
                failures.append(f"{default}: plurals {name} is missing quantity='other'")

        locale_dirs = [p for p in module_res.glob("values-*") if p.is_dir() and locale_name(p)]
        for locale_dir in sorted(locale_dirs):
            locale = locale_name(locale_dir)
            translated = plurals(locale_dir)
            for name in sorted(base.keys() - translated.keys()):
                failures.append(f"{module_res}: missing {locale} plurals resource: {name}")
            for name in sorted(translated.keys() - base.keys()):
                failures.append(f"{module_res}: {locale} plurals has no default counterpart: {name}")
            for name in sorted(base.keys() & translated.keys()):
                translated_quantities = translated[name]
                if "other" not in translated_quantities:
                    failures.append(f"{locale_dir}: plurals {name} is missing quantity='other'")
                    continue
                expected_tokens = sorted(FORMAT_TOKEN.findall(base[name]["other"]))
                for quantity, value in sorted(translated_quantities.items()):
                    actual_tokens = sorted(FORMAT_TOKEN.findall(value))
                    if actual_tokens != expected_tokens:
                        failures.append(
                            f"{locale_dir}: plural placeholders differ for {name}/{quantity}: "
                            f"default={expected_tokens}, locale={actual_tokens}"
                        )

    if failures:
        print("Localization quality guard failed:\n" + "\n".join(failures))
        return 1

    print(
        "Localization quality guard passed: feature translations contain no known placeholders, "
        "technical tokens are preserved, and plural resources are locale-safe."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
