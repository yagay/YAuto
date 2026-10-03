#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path('.')
SOURCE_GLOBS = (
    'app/src/main/kotlin/**/*.kt',
    'platform/*/src/main/kotlin/**/*.kt',
    'feature/*/src/main/kotlin/**/*.kt',
)

# Internal signals intentionally dispatched only to refresh/evaluate state and therefore do not
# represent user-selectable Trigger/Event features.
INTERNAL_RUNTIME_EVENTS = {
    'android.event.runtime_started',
}


def source_files() -> list[Path]:
    files: set[Path] = set()
    for pattern in SOURCE_GLOBS:
        files.update(ROOT.glob(pattern))
    return sorted(files)


def event_descriptors(source: str) -> set[str]:
    result: set[str] = set()
    # FeatureDescriptor arguments are mostly compact in YAuto. Limit the window to avoid pairing a
    # FeatureId with FeatureKind.EVENT from a distant descriptor in the same file.
    for match in re.finditer(r'FeatureId\(\s*"(android\.event\.[^"]+)"\s*\)', source):
        window = source[match.start(): min(len(source), match.start() + 700)]
        if re.search(r'FeatureKind\.EVENT\b', window):
            result.add(match.group(1))
    return result


def emitted_runtime_events(source: str) -> set[str]:
    result: set[str] = set()
    # Supports RuntimeEvent("id", ...), RuntimeEvent(\n "id", ...), and
    # RuntimeEvent(typeId = "id", ...). Dynamic type IDs are intentionally outside this static
    # guard and are covered by their source-specific tests.
    pattern = re.compile(
        r'RuntimeEvent\s*\(\s*(?:typeId\s*=\s*)?"(android\.event\.[^"]+)"',
        re.MULTILINE,
    )
    result.update(match.group(1) for match in pattern.finditer(source))
    return result


def main() -> int:
    files = source_files()
    if not files:
        print('Event contract guard failed: no Kotlin source files found')
        return 1

    descriptors: dict[str, list[Path]] = {}
    emitted: dict[str, list[Path]] = {}
    for path in files:
        source = path.read_text(encoding='utf-8')
        for event_id in event_descriptors(source):
            descriptors.setdefault(event_id, []).append(path)
        for event_id in emitted_runtime_events(source):
            emitted.setdefault(event_id, []).append(path)

    failures: list[str] = []
    for event_id, paths in sorted(emitted.items()):
        if event_id in INTERNAL_RUNTIME_EVENTS:
            continue
        if event_id not in descriptors:
            locations = ', '.join(str(path) for path in paths)
            failures.append(
                f'Runtime event {event_id!r} is emitted from {locations} but no literal '
                'FeatureKind.EVENT descriptor registers it'
            )

    if failures:
        print('Event contract guard failed:\n' + '\n'.join(failures))
        return 1

    checked = sum(1 for event_id in emitted if event_id not in INTERNAL_RUNTIME_EVENTS)
    print(
        f'Event contract guard passed: {checked} literal runtime event IDs have matching '
        f'FeatureKind.EVENT descriptors; {len(INTERNAL_RUNTIME_EVENTS)} internal event ID(s) excluded.'
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
