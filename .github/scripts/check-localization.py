#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOTS = [Path("app/src/main/kotlin"), Path("ui")]

# UI-facing Kotlin should not carry Chinese copy at all. Chinese belongs in values-zh-rCN.
CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")

# Compose/custom UI call sites that should use stringResource() rather than literal prose.
DIRECT_TEXT = re.compile(r'''\bText\s*\(\s*"[^"\n]*[A-Za-z]{2,}[^"\n]*"''')
LITERAL_NAMED_COPY = re.compile(r'''\b(?:title|subtitle|label|supportingText)\s*=\s*"[^"\n]*[A-Za-z]{2,}[^"\n]*"''')
DIRECT_HELPERS = re.compile(
    r'''\b(?:EmptyHint|FlowEmpty|SettingsRow|EngineCard|BackendCard|PermissionCard|CategoryRow)\s*\(\s*"[^"\n]*[A-Za-z]{2,}[^"\n]*"'''
)

# Stable technical identifiers are intentionally not localized. The patterns above only target
# visible UI call sites, while this list handles a few deliberate one/two-letter visual glyphs.
ALLOW_LINE_FRAGMENTS = (
    'Text("i"',
)


def kotlin_files():
    for root in ROOTS:
        if not root.exists():
            continue
        if root == Path("ui"):
            yield from root.glob("**/src/main/kotlin/**/*.kt")
        else:
            yield from root.glob("**/*.kt")


def main() -> int:
    failures: list[str] = []
    for path in sorted(set(kotlin_files())):
        text = path.read_text(encoding="utf-8")
        for line_no, line in enumerate(text.splitlines(), 1):
            if CJK.search(line):
                failures.append(f"{path}:{line_no}: CJK UI text must be in Android string resources: {line.strip()}")
                continue
            if any(fragment in line for fragment in ALLOW_LINE_FRAGMENTS):
                continue
            if DIRECT_TEXT.search(line) or LITERAL_NAMED_COPY.search(line) or DIRECT_HELPERS.search(line):
                failures.append(f"{path}:{line_no}: hardcoded visible text must use stringResource(): {line.strip()}")

    if failures:
        print("Localization guard failed. Move visible text to ui:design Android resources.")
        print("\n".join(failures))
        return 1

    print("Localization guard passed: no hardcoded CJK or direct visible Compose prose found in app/ui Kotlin.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
