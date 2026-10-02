#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(".")
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
# Any directly rendered literal is forbidden in app/ui. This intentionally includes symbol-only
# copy such as +, ×, ✓, → and icon-like letters such as "i". Visible symbols must be real icons
# or localized string resources; accessibility labels must also be localized.
DIRECT_TEXT = re.compile(r'\bText\s*\(\s*(?:text\s*=\s*)?"[^"\n]*"')
CONTENT_DESCRIPTION = re.compile(r'\bcontentDescription\s*=\s*"[^"\n]+"')
LITERAL_NAMED_COPY = re.compile(r'\b(?:title|subtitle|label|supportingText|placeholder)\s*=\s*"[^"\n]+"')
DIRECT_HELPERS = re.compile(r'\b(?:EmptyHint|FlowEmpty|SettingsRow|EngineCard|BackendCard|PermissionCard|CategoryRow)\s*\(\s*"[^"\n]*"')

# Dedicated localization catalogs are the only Kotlin files allowed to contain translated CJK.
CJK_KOTLIN_ALLOW = {
    Path("core/model/src/main/kotlin/com/yagay/yauto/core/model/UserText.kt"),
}


def main_kotlin_files():
    for path in ROOT.glob("**/src/main/kotlin/**/*.kt"):
        if any(part in {"build", ".gradle"} for part in path.parts):
            continue
        yield path


def resource_keys(folder: Path) -> set[str]:
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
    return out


def line_number(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def constructor_windows(text: str, name: str, max_chars: int = 900):
    """Yield bounded call windows without leaking into the next same constructor.

    This is intentionally lightweight (not a Kotlin parser) but avoids the prior false positive
    where one ActionExecutionResult without a message consumed the next ActionExecutionResult.
    """
    token = name + "("
    start = 0
    while True:
        idx = text.find(token, start)
        if idx < 0:
            return
        next_idx = text.find(token, idx + len(token))
        end = min(len(text), idx + max_chars)
        if next_idx >= 0:
            end = min(end, next_idx)
        yield idx, text[idx:end]
        start = idx + len(token)


def main() -> int:
    failures: list[str] = []

    # UI call sites: ALL directly visible literals must use resources/resolvers, including symbols.
    kotlin = sorted(main_kotlin_files())
    for path in kotlin:
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), 1):
            if path not in CJK_KOTLIN_ALLOW and CJK.search(line):
                # Search aliases in feature descriptors are legacy source metadata and are stripped
                # by FeatureRegistry; they are not display copy.
                if "keywords" not in line and "setOf(" not in line:
                    failures.append(f"{path}:{line_no}: translated CJK belongs in localization resources/catalogs: {line.strip()}")
            if path.parts[0] in {"app", "ui"}:
                if DIRECT_TEXT.search(line) or CONTENT_DESCRIPTION.search(line) or LITERAL_NAMED_COPY.search(line) or DIRECT_HELPERS.search(line):
                    failures.append(f"{path}:{line_no}: hardcoded visible/icon/accessibility text must use a localized resource or real icon: {line.strip()}")

    # Android manifests must not hardcode human-readable labels/descriptions.
    for path in ROOT.glob("**/src/main/AndroidManifest.xml"):
        text = path.read_text(encoding="utf-8")
        for attr in ("label", "description"):
            for m in re.finditer(rf'android:{attr}="([^"]+)"', text):
                value = m.group(1)
                if value and not value.startswith("@") and not value.startswith("${"):
                    failures.append(f"{path}: hardcoded android:{attr}={value!r}; use @string/ resource")

    # Resource parity: every default UI string has a zh-CN counterpart and vice versa.
    for module in (Path("ui/design/src/main/res"), Path("platform/accessibility/src/main/res")):
        en = resource_keys(module / "values")
        zh = resource_keys(module / "values-zh-rCN")
        for missing in sorted(en - zh):
            failures.append(f"{module}: missing zh-CN string resource: {missing}")
        for missing in sorted(zh - en):
            failures.append(f"{module}: missing default English string resource: {missing}")

    # Source paths that feed diagnostics/import/results must localize wrapper prose. Raw exception,
    # shell stderr and logcat text are allowed to remain original for diagnostics.
    for path in kotlin:
        if path.parts[0] not in {"core", "platform", "importer", "feature", "app"}:
            continue
        text = path.read_text(encoding="utf-8")

        for ctor in ("ActionExecutionResult", "CapabilityResult", "DiagnosticRecord", "CollectorStatus", "CompatibilityIssue"):
            for offset, snippet in constructor_windows(text, ctor):
                literal_message = re.search(r'\b(?:message|title)\s*=\s*"[A-Za-z][^"\n]*"', snippet)
                if literal_message and "userText(" not in snippet[: literal_message.end()]:
                    failures.append(
                        f"{path}:{line_number(text, offset)}: {ctor} contains hardcoded user-facing prose; use userText(): "
                        f"{snippet.splitlines()[0].strip()}"
                    )

        for match in re.finditer(r'Signal\.Failure\(\s*"[A-Za-z][^"\n]*"', text):
            failures.append(f"{path}:{line_number(text, match.start())}: Signal.Failure contains hardcoded user-facing prose; use userText()")

        # Engine trace text is displayed in execution logs. Dynamic technical IDs may remain raw,
        # but human wrapper words such as Start/Call/failed must be localized.
        for match in re.finditer(r'\btrace\([^\n]{0,700}?,\s*"([A-Za-z][^"\n]*)"', text):
            snippet = match.group(0)
            if "userText(" not in snippet:
                failures.append(
                    f"{path}:{line_number(text, match.start())}: trace message contains hardcoded user-facing prose; use userText(): {snippet}"
                )

    if failures:
        print("Localization guard failed:\n" + "\n".join(failures))
        return 1
    print("Localization guard passed: UI/icon/accessibility copy is resource-backed and runtime/import/diagnostic wrapper copy is localized.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
