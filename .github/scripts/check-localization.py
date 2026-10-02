#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(".")
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
DIRECT_TEXT = re.compile(r'\bText\s*\(\s*"[^"\n]*[A-Za-z]{2,}[^"\n]*"')
LITERAL_NAMED_COPY = re.compile(r'\b(?:title|subtitle|label|supportingText)\s*=\s*"[^"\n]*[A-Za-z]{2,}[^"\n]*"')
DIRECT_HELPERS = re.compile(r'\b(?:EmptyHint|FlowEmpty|SettingsRow|EngineCard|BackendCard|PermissionCard|CategoryRow)\s*\(\s*"[^"\n]*[A-Za-z]{2,}[^"\n]*"')

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


def main() -> int:
    failures: list[str] = []

    # UI call sites: visible prose must use resources/resolvers.
    for path in sorted(main_kotlin_files()):
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), 1):
            if path not in CJK_KOTLIN_ALLOW and CJK.search(line):
                # Search aliases in feature descriptors are legacy source metadata and are stripped
                # by FeatureRegistry; they are not display copy.
                if "keywords" not in line and "setOf(" not in line:
                    failures.append(f"{path}:{line_no}: translated CJK belongs in localization resources/catalogs: {line.strip()}")
            if path.parts[0] in {"app", "ui"}:
                if 'Text("i"' in line:
                    continue
                if DIRECT_TEXT.search(line) or LITERAL_NAMED_COPY.search(line) or DIRECT_HELPERS.search(line):
                    failures.append(f"{path}:{line_no}: hardcoded visible text must use a localized resource/resolver: {line.strip()}")

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

    # Source paths that feed diagnostics/import results must use userText() for wrapper prose.
    sensitive_patterns = [
        re.compile(r'DiagnosticRecord\([^\n]*title\s*=\s*"[A-Za-z]'),
        re.compile(r'CollectorStatus\([^\n]*"[A-Za-z][^"\n]+"'),
        re.compile(r'CompatibilityIssue\([^\n]*"[A-Za-z][^"\n]+"'),
        re.compile(r'Signal\.Failure\(\s*"[A-Za-z]'),
    ]
    for path in sorted(main_kotlin_files()):
        if path.parts[0] not in {"core", "platform", "importer", "feature", "app"}:
            continue
        for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if any(p.search(line) for p in sensitive_patterns) and "userText(" not in line:
                failures.append(f"{path}:{line_no}: user-facing runtime/import/diagnostic prose must use userText(): {line.strip()}")

    if failures:
        print("Localization guard failed:\n" + "\n".join(failures))
        return 1
    print("Localization guard passed: UI resources are paired and runtime/import/diagnostic wrapper copy is localized.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
