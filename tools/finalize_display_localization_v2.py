#!/usr/bin/env python3
from __future__ import annotations

import finalize_display_localization as base


def replace_guard(old_variants: list[str], new: str) -> None:
    path = '.github/scripts/check-localization.py'
    text = base.read(path)
    if new in text:
        return
    for old in old_variants:
        if old in text:
            base.write(path, text.replace(old, new, 1))
            return
    raise RuntimeError(f'{path}: expected guard fragment not found')


def harden_guard() -> None:
    baseline_constants = 'BANNED_ICON_LITERALS = {"‹", "›", "＋", "⋮", "↑", "↓", "←", "→", "▶", "◀", "✓", "✕", "×"}'
    broad_constants = 'BANNED_ICON_CHARS = {"‹", "›", "＋", "⋮", "↑", "↓", "←", "→", "▶", "◀", "✓", "✕", "×", "⌂", "≡", "↳", "⚙", "⌃", "⌄", "★", "☆"}\nHARDCODED_DISPLAY_SEPARATOR = re.compile(r\'(?:joinToString|append)\\(\\s*"\\s*(?:·|\\+|/)\\s*"\\s*\\)|\\+\\s*"\\s*(?:·|\\*|/|\\+)\\s*"\')\nRAW_ENUM_TEXT = re.compile(r\'\\bText\\s*\\([^\\n)]*\\.name\\b\')\nRAW_CAPABILITY_BADGE = re.compile(r\'\\bCapabilityBadge\\s*\\([^\\n]*(?:substringAfterLast|\\.value\\b)\')'
    narrow_constants = 'BANNED_ICON_CHARS = {"‹", "›", "＋", "⋮", "↑", "↓", "←", "→", "▶", "◀", "✓", "✕", "×", "⌂", "≡", "↳", "⚙", "⌃", "⌄", "★", "☆"}\nRESOURCE_ICON_CHARS = {"＋", "⋮", "▶", "◀", "✓", "✕", "×", "⌂", "≡", "↳", "⚙", "⌃", "⌄", "★", "☆"}\nHARDCODED_DISPLAY_SEPARATOR = re.compile(r\'(?:joinToString|append)\\(\\s*"\\s*(?:·|\\+|/)\\s*"\\s*\\)|\\+\\s*"\\s*(?:·|\\*|/|\\+)\\s*"\')\nRAW_ENUM_TEXT = re.compile(r\'\\bText\\s*\\(\\s*(?:type|kind|category|policy|phase|state)\\.name\\b|\\bstringResource\\([^\\n,]+,\\s*(?:type|kind|category|policy|phase|state)\\.name\\b\')\nRAW_CAPABILITY_BADGE = re.compile(r\'\\bCapabilityBadge\\s*\\(\\s*(?:it|capability|id)\\.value(?:\\.substringAfterLast\\([^)]*\\))?\\s*\\)\')'
    replace_guard([baseline_constants, broad_constants], narrow_constants)

    baseline_resource = '''            value = "".join(node.itertext()).strip()
            if value in BANNED_ICON_LITERALS:
                failures.append(f"{path}: icon-like character {value!r} must be a vector/image icon, not a string resource")'''
    broad_resource = '''            value = "".join(node.itertext()).strip()
            banned = sorted({ch for ch in value if ch in BANNED_ICON_CHARS})
            if banned:
                failures.append(
                    f"{path}: icon-like character(s) {''.join(banned)!r} must use vector/image icons or normal localized wording"
                )
            if folder.name == "values" and CJK.search(value):
                failures.append(f"{path}: default resource contains CJK translated copy: {name}")'''
    narrow_resource = '''            value = "".join(node.itertext()).strip()
            banned = sorted({ch for ch in value if ch in RESOURCE_ICON_CHARS})
            if banned:
                failures.append(
                    f"{path}: icon-like character(s) {''.join(banned)!r} must use vector/image icons or normal localized wording"
                )
            if folder.name == "values" and CJK.search(value):
                failures.append(f"{path}: default resource contains CJK translated copy: {name}")'''
    replace_guard([baseline_resource, broad_resource], narrow_resource)

    baseline_loop = '''            if ANDROID_VISIBLE_LITERAL.search(line) or TOAST_LITERAL.search(line):
                failures.append(f"{path}:{line_no}: hardcoded Android-visible text must use localized resources/userText(): {line.strip()}")'''
    broad_loop = '''            if ANDROID_VISIBLE_LITERAL.search(line) or TOAST_LITERAL.search(line):
                failures.append(f"{path}:{line_no}: hardcoded Android-visible text must use localized resources/userText(): {line.strip()}")
            if path.parts[0] in {"app", "ui"} and any(ch in line for ch in BANNED_ICON_CHARS):
                failures.append(f"{path}:{line_no}: character glyph used as UI/icon state; use a vector icon or localized wording: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and HARDCODED_DISPLAY_SEPARATOR.search(line):
                failures.append(f"{path}:{line_no}: hardcoded display separator/required marker; use localized formatting: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and RAW_ENUM_TEXT.search(line):
                failures.append(f"{path}:{line_no}: raw enum .name is visible; map it to a localized label: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and RAW_CAPABILITY_BADGE.search(line):
                failures.append(f"{path}:{line_no}: raw capability ID is visible; map it to a localized label: {line.strip()}")'''
    narrow_loop = broad_loop
    replace_guard([baseline_loop, broad_loop], narrow_loop)

    baseline_parity = '''    # Resource parity: every default UI string has a zh-CN counterpart and vice versa.
    for module in (Path("ui/design/src/main/res"), Path("platform/accessibility/src/main/res")):
        en = resource_keys(module / "values", failures)
        zh = resource_keys(module / "values-zh-rCN", failures)
        for missing in sorted(en - zh):
            failures.append(f"{module}: missing zh-CN string resource: {missing}")
        for missing in sorted(zh - en):
            failures.append(f"{module}: missing default English string resource: {missing}")'''
    advanced_parity = '''    # Resource parity is automatic for every string-bearing Android module and every locale
    # directory. Adding a module or language therefore cannot silently bypass localization checks.
    format_token = re.compile(r"(?<!%)%(?!%)(?:\\d+\\$)?[a-zA-Z]")

    def values_map(folder: Path) -> dict[str, str]:
        out: dict[str, str] = {}
        if not folder.exists():
            return out
        for xml in folder.glob("*.xml"):
            root = ET.parse(xml).getroot()
            for node in root.findall("string"):
                name = node.attrib.get("name")
                if name:
                    out[name] = "".join(node.itertext()).strip()
        return out

    def locale_name(folder: Path) -> str | None:
        qualifier = folder.name.removeprefix("values-")
        if re.fullmatch(r"[a-z]{2,3}(?:-r[A-Z]{2})?", qualifier):
            parts = qualifier.split("-r", 1)
            return parts[0] if len(parts) == 1 else f"{parts[0]}-{parts[1]}"
        if qualifier.startswith("b+"):
            return qualifier[2:].replace("+", "-")
        return None

    discovered_locales: set[str] = set()
    for default in sorted(ROOT.glob("**/src/main/res/values")):
        base = values_map(default)
        if not base:
            continue
        module = default.parent
        zh_dir = module / "values-zh-rCN"
        if not zh_dir.exists():
            failures.append(f"{module}: string-bearing module is missing values-zh-rCN")
        locale_dirs = [p for p in module.glob("values-*") if p.is_dir() and locale_name(p)]
        for locale_dir in sorted(locale_dirs):
            locale = locale_name(locale_dir)
            assert locale is not None
            discovered_locales.add(locale)
            translated = values_map(locale_dir)
            for missing in sorted(base.keys() - translated.keys()):
                failures.append(f"{module}: missing {locale} string resource: {missing}")
            for extra in sorted(translated.keys() - base.keys()):
                failures.append(f"{module}: {locale} resource has no default counterpart: {extra}")
            for name in sorted(base.keys() & translated.keys()):
                if sorted(format_token.findall(base[name])) != sorted(format_token.findall(translated[name])):
                    failures.append(
                        f"{locale_dir}: format placeholders differ for {name}: "
                        f"default={format_token.findall(base[name])}, locale={format_token.findall(translated[name])}"
                    )

    locale_config = Path("app/src/main/res/xml/locales_config.xml")
    if locale_config.exists():
        declared = set(re.findall(r'android:name="([^"]+)"', locale_config.read_text(encoding="utf-8")))
        required = {"en"} | discovered_locales
        for locale in sorted(required - declared):
            failures.append(f"{locale_config}: locale {locale!r} has resources but is not declared")'''
    replace_guard([baseline_parity], advanced_parity)

    old_print = '    print("Localization guard passed: UI/icon/accessibility copy is resource-backed and runtime/import/diagnostic wrapper copy is localized.")'
    new_print = '    print("Localization guard passed: visible copy, icon semantics, locale parity, format placeholders, machine-label boundaries and runtime/import/diagnostic messages are localization-safe.")'
    replace_guard([old_print], new_print)


def main() -> None:
    base.create_shared_formatting()
    base.create_navigation_icons()
    base.patch_home()
    base.patch_automation_editor()
    base.patch_flow_editor()
    base.patch_feature_picker()
    base.patch_action_tree()
    base.patch_macro_style()
    base.patch_quick_settings()
    base.patch_resources()
    harden_guard()
    print('Display localization and future-locale guard finalized (v2).')


if __name__ == '__main__':
    main()
