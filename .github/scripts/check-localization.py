#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(".")
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
DIRECT_TEXT = re.compile(r'\bText\s*\(\s*(?:text\s*=\s*)?"[^"\n]*"')
CONTENT_DESCRIPTION = re.compile(r'\bcontentDescription\s*=\s*"[^"\n]+"')
LITERAL_NAMED_COPY = re.compile(r'\b(?:title|subtitle|label|supportingText|placeholder)\s*=\s*"[^"\n]+"')
DIRECT_HELPERS = re.compile(r'\b(?:EmptyHint|FlowEmpty|SettingsRow|EngineCard|BackendCard|PermissionCard|CategoryRow)\s*\(\s*"[^"\n]*"')
ANDROID_VISIBLE_LITERAL = re.compile(r'\b(?:setContentTitle|setContentText|setTicker)\s*\(\s*"[^"\n]+"')
TOAST_LITERAL = re.compile(r'\bToast\.makeText\([^\n]{0,300}?,\s*"[^"\n]+"')
BANNED_ICON_CHARS = {"‹", "›", "＋", "⋮", "↑", "↓", "←", "→", "▶", "◀", "✓", "✕", "×", "⌂", "≡", "↳", "⚙", "⌃", "⌄", "★", "☆"}
RESOURCE_ICON_CHARS = {"＋", "⋮", "▶", "◀", "✓", "✕", "×", "⌂", "≡", "↳", "⚙", "⌃", "⌄", "★", "☆"}
HARDCODED_DISPLAY_SEPARATOR = re.compile(r'(?:joinToString|append)\(\s*"\s*(?:·|\+|/)\s*"\s*\)|\+\s*"\s*(?:·|\*|/|\+)\s*"')
RAW_ENUM_TEXT = re.compile(r'\bText\s*\(\s*(?:type|kind|category|policy|phase|state)\.name\b|\bstringResource\([^\n,]+,\s*(?:type|kind|category|policy|phase|state)\.name\b')
RAW_CAPABILITY_BADGE = re.compile(r'\bCapabilityBadge\s*\(\s*(?:it|capability|id)\.value(?:\.substringAfterLast\([^)]*\))?\s*\)')

# Dedicated localization catalogs are the only Kotlin files allowed to contain translated CJK.
CJK_KOTLIN_ALLOW: set[Path] = set()


def main_kotlin_files():
    for path in ROOT.glob("**/src/main/kotlin/**/*.kt"):
        if any(part in {"build", ".gradle"} for part in path.parts):
            continue
        yield path


def resource_keys(folder: Path, failures: list[str]) -> set[str]:
    out: set[str] = set()
    if not folder.exists():
        return out
    for path in folder.glob("*.xml"):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as exc:
            raise RuntimeError(f"Invalid resource XML {path}: {exc}")
        for node in root.findall("string"):
            name = node.attrib.get("name")
            if name:
                if name in out:
                    raise RuntimeError(f"Duplicate string resource {name} in {path}")
                out.add(name)
            value = "".join(node.itertext()).strip()
            banned = sorted({ch for ch in value if ch in RESOURCE_ICON_CHARS})
            if banned:
                failures.append(
                    f"{path}: icon-like character(s) {''.join(banned)!r} must use vector/image icons or normal localized wording"
                )
            if folder.name == "values" and CJK.search(value):
                failures.append(f"{path}: default resource contains CJK translated copy: {name}")
    return out


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def call_windows(text: str, name: str):
    """Yield balanced Kotlin call expressions for a named call.

    This deliberately skips parentheses inside quoted strings/chars and avoids leaking into a
    following constructor, which keeps the localization guard precise without a Kotlin parser.
    """
    token = name + "("
    start = 0
    while True:
        idx = text.find(token, start)
        if idx < 0:
            return
        i = idx + len(token)
        depth = 1
        quote: str | None = None
        escaped = False
        while i < len(text) and depth > 0:
            ch = text[i]
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
        yield idx, text[idx:i]
        start = max(i, idx + len(token))


def main() -> int:
    failures: list[str] = []
    kotlin = sorted(main_kotlin_files())

    # No translated language copy belongs in business/UI Kotlin. UI literals, icon characters and
    # accessibility descriptions must use resources/resolvers or actual vector/image icons.
    for path in kotlin:
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), 1):
            if path not in CJK_KOTLIN_ALLOW and CJK.search(line):
                failures.append(f"{path}:{line_no}: translated CJK belongs in localization resources/catalogs: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and (
                DIRECT_TEXT.search(line)
                or CONTENT_DESCRIPTION.search(line)
                or LITERAL_NAMED_COPY.search(line)
                or DIRECT_HELPERS.search(line)
            ):
                failures.append(f"{path}:{line_no}: hardcoded visible/icon/accessibility text must use a localized resource or real icon: {line.strip()}")
            if ANDROID_VISIBLE_LITERAL.search(line) or TOAST_LITERAL.search(line):
                failures.append(f"{path}:{line_no}: hardcoded Android-visible text must use localized resources/userText(): {line.strip()}")
            if path.parts[0] in {"app", "ui"} and any(ch in line for ch in BANNED_ICON_CHARS):
                failures.append(f"{path}:{line_no}: character glyph used as UI/icon state; use a vector icon or localized wording: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and HARDCODED_DISPLAY_SEPARATOR.search(line):
                failures.append(f"{path}:{line_no}: hardcoded display separator/required marker; use localized formatting: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and RAW_ENUM_TEXT.search(line):
                failures.append(f"{path}:{line_no}: raw enum .name is visible; map it to a localized label: {line.strip()}")
            if path.parts[0] in {"app", "ui"} and RAW_CAPABILITY_BADGE.search(line):
                failures.append(f"{path}:{line_no}: raw capability ID is visible; map it to a localized label: {line.strip()}")

    # userText call sites must carry only a stable key and formatting args. A literal second
    # argument is a language-specific fallback and defeats the Android resource architecture.
    for path in kotlin:
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r'userText\(\s*"[^"]+"\s*,\s*"', text):
            failures.append(f"{path}:{line_number(text, match.start())}: remove language-specific userText fallback; keep only key + args")

    # Feature UI must never expose raw descriptor prose when a resource is missing.
    feature_resolver = Path("ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FeatureTextResources.kt")
    if feature_resolver.exists():
        resolver_text = feature_resolver.read_text(encoding="utf-8")
        for pattern in (r'else\s+descriptor\.title', r'else\s+descriptor\.description', r'else\s+field\.label', r'else\s+option'):
            match = re.search(pattern, resolver_text)
            if match:
                failures.append(f"{feature_resolver}:{line_number(resolver_text, match.start())}: raw FeatureDescriptor display fallback is forbidden")

    # Android manifests must not hardcode human-readable labels/descriptions.
    for path in ROOT.glob("**/src/main/AndroidManifest.xml"):
        text = path.read_text(encoding="utf-8")
        for attr in ("label", "description"):
            for m in re.finditer(rf'android:{attr}="([^"]+)"', text):
                value = m.group(1)
                if value and not value.startswith("@") and not value.startswith("${"):
                    failures.append(f"{path}: hardcoded android:{attr}={value!r}; use @string/ resource")

    # Resource parity is automatic for every string-bearing Android module and every locale
    # directory. Adding a module or language therefore cannot silently bypass localization checks.
    format_token = re.compile(r"(?<!%)%(?!%)(?:\d+\$)?[a-zA-Z]")

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
            failures.append(f"{locale_config}: locale {locale!r} has resources but is not declared")

    # Every userText() key must have default-English and zh-CN Android resources. This keeps
    # runtime/diagnostic/import messages on the same standard Android localization path as UI copy.
    runtime_en = resource_keys(Path("ui/design/src/main/res/values"), failures)
    runtime_zh = resource_keys(Path("ui/design/src/main/res/values-zh-rCN"), failures)
    for path in kotlin:
        text = path.read_text(encoding="utf-8")
        for match in re.finditer(r'userText\(\s*"([^"]+)"', text):
            name = "runtime_" + re.sub(r'[^a-z0-9]+', '_', match.group(1).lower()).strip('_')
            if name not in runtime_en:
                failures.append(f"{path}:{line_number(text, match.start())}: missing default runtime string resource: {name}")
            if name not in runtime_zh:
                failures.append(f"{path}:{line_number(text, match.start())}: missing zh-CN runtime string resource: {name}")

    # Runtime/import/diagnostic wrapper prose must be localized. Raw stack traces, logcat, shell
    # stderr and exception details may remain original technical data, but not as a bare UI message.
    for path in kotlin:
        if path.parts[0] not in {"core", "platform", "importer", "feature", "app"}:
            continue
        text = path.read_text(encoding="utf-8")

        for ctor in ("ActionExecutionResult", "CapabilityResult", "DiagnosticRecord", "CollectorStatus", "CompatibilityIssue"):
            for offset, snippet in call_windows(text, ctor):
                for field in ("message", "title"):
                    m = re.search(rf'\b{field}\s*=\s*([^,\n)]+)', snippet)
                    if not m:
                        continue
                    expr = m.group(1).strip()
                    if expr.startswith('"') or re.match(r'(?:it|error|t)\.message\b', expr):
                        if "userText(" not in expr:
                            failures.append(
                                f"{path}:{line_number(text, offset)}: {ctor} {field} contains hardcoded/raw user-facing prose; use userText(): {snippet.splitlines()[0].strip()}"
                            )

        for match in re.finditer(r'Signal\.Failure\(\s*"[A-Za-z][^"\n]*"', text):
            failures.append(f"{path}:{line_number(text, match.start())}: Signal.Failure contains hardcoded user-facing prose; use userText()")

        # Engine trace text is displayed in execution-log diagnostics.
        for match in re.finditer(r'\btrace\([^\n]{0,700}?,\s*"([A-Za-z][^"\n]*)"', text):
            snippet = match.group(0)
            if "userText(" not in snippet:
                failures.append(
                    f"{path}:{line_number(text, match.start())}: trace message contains hardcoded user-facing prose; use userText(): {snippet}"
                )

    if failures:
        print("Localization guard failed:\n" + "\n".join(failures))
        return 1
    print("Localization guard passed: visible copy, icon semantics, locale parity, format placeholders, machine-label boundaries and runtime/import/diagnostic messages are localization-safe.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
