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
        if node.attrib.get("name") and node.attrib.get("translatable", "true").lower() != "false"
    }


def all_strings(folder: Path) -> dict[str, str]:
    result: dict[str, str] = {}
    if not folder.exists():
        return result
    for path in folder.glob("*.xml"):
        root = ET.parse(path).getroot()
        for node in root.findall("string"):
            name = node.attrib.get("name")
            if not name or node.attrib.get("translatable", "true").lower() == "false":
                continue
            if name in result:
                raise RuntimeError(f"Duplicate string resource {name} in {folder}")
            result[name] = text(node)
    return result


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


def resource_key(value: str) -> str:
    lowered = value.lower()
    normalized = "".join(ch if ch.isalnum() else "_" for ch in lowered)
    normalized = re.sub(r"_+", "_", normalized).strip("_")
    if normalized:
        return normalized
    return "symbol_" + "_".join(format(ord(ch), "x") for ch in value)


def call_windows(text_value: str, name: str):
    token = name + "("
    start = 0
    while True:
        idx = text_value.find(token, start)
        if idx < 0:
            return
        i = idx + len(token)
        depth = 1
        quote: str | None = None
        escaped = False
        while i < len(text_value) and depth > 0:
            ch = text_value[i]
            if quote is not None:
                if escaped:
                    escaped = False
                elif ch == "\\":
                    escaped = True
                elif ch == quote:
                    quote = None
            else:
                if ch in {'"', "'"}:
                    quote = ch
                elif ch == "(":
                    depth += 1
                elif ch == ")":
                    depth -= 1
            i += 1
        yield idx, text_value[idx:i]
        start = max(i, idx + len(token))


def line_number(text_value: str, offset: int) -> int:
    return text_value.count("\n", 0, offset) + 1


def localizable(
    exact_name: str,
    phrase_value: str,
    resources_en: dict[str, str],
    resources_zh: dict[str, str],
) -> bool:
    if exact_name in resources_en and exact_name in resources_zh:
        return True
    phrase_name = f"feature_phrase_{resource_key(phrase_value)}"
    return phrase_name in resources_en and phrase_name in resources_zh


def check_title_description(
    failures: list[str],
    path: Path,
    offset: int,
    source: str,
    feature_id: str,
    title: str,
    description: str,
    resources_en: dict[str, str],
    resources_zh: dict[str, str],
) -> None:
    base = f"feature_{resource_key(feature_id)}"
    if not localizable(f"{base}_title", title, resources_en, resources_zh):
        failures.append(
            f"{path}:{line_number(source, offset)}: feature {feature_id} title is not localized: {title!r}"
        )
    if not localizable(f"{base}_description", description, resources_en, resources_zh):
        failures.append(
            f"{path}:{line_number(source, offset)}: feature {feature_id} description is not localized: {description!r}"
        )


def main() -> int:
    failures: list[str] = []

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

    resources_en = all_strings(ROOT / "ui/design/src/main/res/values")
    resources_zh = all_strings(ROOT / "ui/design/src/main/res/values-zh-rCN")
    descriptor_count = 0
    for path in sorted(ROOT.glob("**/src/main/kotlin/**/*.kt")):
        if path.parts[0] not in {"feature", "platform"}:
            continue
        source = path.read_text(encoding="utf-8")
        for offset, snippet in call_windows(source, "FeatureDescriptor"):
            id_match = re.search(r'FeatureId\("([^"]+)"\)', snippet)
            if not id_match:
                continue
            feature_id = id_match.group(1)
            if feature_id.startswith("compat."):
                continue
            # Helper-generated descriptors use an interpolated ID and are audited below
            # from their literal helper call sites, where title/description are visible.
            if "$" in feature_id:
                continue
            header = re.search(
                r'FeatureId\("[^"]+"\)\s*,\s*FeatureKind\.\w+\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"',
                snippet,
                re.S,
            )
            title_match = re.search(r'\btitle\s*=\s*"([^"]+)"', snippet)
            description_match = re.search(r'\bdescription\s*=\s*"([^"]+)"', snippet)
            title = title_match.group(1) if title_match else (header.group(1) if header else None)
            description = description_match.group(1) if description_match else (header.group(2) if header else None)
            if title is None or description is None:
                failures.append(
                    f"{path}:{line_number(source, offset)}: built-in FeatureDescriptor {feature_id} must expose literal title/description for localization coverage"
                )
                continue
            descriptor_count += 1
            check_title_description(
                failures, path, offset, source, feature_id, title, description, resources_en, resources_zh
            )
            base = f"feature_{resource_key(feature_id)}"

            field_pattern = re.compile(
                r'FieldSchema\.\w+\(\s*"([^"]+)"\s*,\s*"([^"]+)"',
                re.S,
            )
            for field_key, field_label in field_pattern.findall(snippet):
                exact = f"{base}_field_{resource_key(field_key)}"
                if not localizable(exact, field_label, resources_en, resources_zh):
                    failures.append(
                        f"{path}:{line_number(source, offset)}: feature {feature_id} field {field_key!r} is not localized: {field_label!r}"
                    )

            for _, choice in call_windows(snippet, "FieldSchema.Choice"):
                choice_head = re.search(r'FieldSchema\.Choice\(\s*"([^"]+)"\s*,\s*"([^"]+)"', choice, re.S)
                if not choice_head:
                    continue
                field_key = choice_head.group(1)
                options = re.search(r'listOf\(([^)]*)\)', choice, re.S)
                if not options:
                    continue
                for option in re.findall(r'"([^"]+)"', options.group(1)):
                    exact = f"{base}_field_{resource_key(field_key)}_option_{resource_key(option)}"
                    if not localizable(exact, option, resources_en, resources_zh):
                        failures.append(
                            f"{path}:{line_number(source, offset)}: feature {feature_id} choice {field_key}={option!r} is not localized"
                        )

    helper_feature_files = {
        ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidInteractionCoverageFeaturePack.kt": (
            "pair", "booleanPair", "textPair",
        ),
        ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidSystemPowerCoverageFeaturePack.kt": (
            "pair", "booleanPair", "choicePair", "rangePair",
        ),
    }
    for path, helper_names in helper_feature_files.items():
        if not path.exists():
            continue
        source = path.read_text(encoding="utf-8")
        seen: set[str] = set()
        for helper_name in helper_names:
            for offset, snippet in call_windows(source, helper_name):
                match = re.search(
                    rf'{re.escape(helper_name)}\(\s*registry\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"\s*,\s*"([^"]+)"',
                    snippet,
                    re.S,
                )
                if not match:
                    continue
                key, title, description = match.groups()
                for feature_id in (f"android.state.{key}", f"android.condition.{key}"):
                    if feature_id in seen:
                        continue
                    seen.add(feature_id)
                    descriptor_count += 1
                    check_title_description(
                        failures,
                        path,
                        offset,
                        source,
                        feature_id,
                        title,
                        description,
                        resources_en,
                        resources_zh,
                    )

    if descriptor_count == 0:
        failures.append("No literal built-in FeatureDescriptor definitions were checked; localization coverage parser is stale")

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

    for path in sorted(ROOT.glob("ui/**/src/main/kotlin/**/*.kt")):
        source = path.read_text(encoding="utf-8")
        for match in re.finditer(r'\.sortedBy\s*\{[^\n}]*\.lowercase\(\)', source):
            failures.append(f"{path}:{line_number(source, match.start())}: visible sorting must use locale Collator")
        for legacy in ("private fun ConfigValue.asText", "private fun flowValueText"):
            pos = source.find(legacy)
            if pos >= 0:
                failures.append(f"{path}:{line_number(source, pos)}: raw ConfigValue display helper must use localizedConfigValue")

    config_editor = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt"
    if not config_editor.exists():
        failures.append(f"{config_editor}: missing schema-driven feature configuration editor")
    else:
        source = config_editor.read_text(encoding="utf-8")
        if "parseLocalizedDouble(" not in source:
            failures.append(f"{config_editor}: numeric editor input must use locale-aware parsing")

    picker_shell = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFeaturePicker.kt"
    if picker_shell.exists() and "FieldSchema." in picker_shell.read_text(encoding="utf-8"):
        failures.append(f"{picker_shell}: picker shell must not own field-specific editor logic")

    for relative in (
        "ui/design/src/main/res/drawable/ic_back.xml",
        "ui/design/src/main/res/drawable/ic_chevron_right.xml",
    ):
        path = ROOT / relative
        source = path.read_text(encoding="utf-8") if path.exists() else ""
        if 'android:autoMirrored="true"' not in source:
            failures.append(f"{path}: directional icon must declare android:autoMirrored=\"true\"")

    manifest_strings = ROOT / "ui/design/src/main/res/values/manifest_strings.xml"
    if manifest_strings.exists():
        root = ET.parse(manifest_strings).getroot()
        node = next((n for n in root.findall("string") if n.attrib.get("name") == "fgs_special_use_subtype"), None)
        if node is None or node.attrib.get("translatable") != "false":
            failures.append(f"{manifest_strings}: fgs_special_use_subtype must be translatable=\"false\"")
    else:
        failures.append(f"{manifest_strings}: missing machine-stable FGS subtype resource")

    for path in sorted(ROOT.glob("importer/**/src/main/kotlin/**/*.kt")):
        source = path.read_text(encoding="utf-8")
        for offset, snippet in call_windows(source, "ImportTrace"):
            if '"IMPORTED"' in snippet and "userText(" not in snippet:
                failures.append(
                    f"{path}:{line_number(source, offset)}: IMPORTED trace summary must use userText(); keep only the status code machine-stable"
                )

    if failures:
        print("Localization quality guard failed:\n" + "\n".join(failures))
        return 1

    print(
        f"Localization quality guard passed: {descriptor_count} built-in FeatureDescriptors have semantic localization coverage; "
        "feature phrases, plurals, locale-sensitive formatting, RTL icons and importer trace summaries are guarded."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
