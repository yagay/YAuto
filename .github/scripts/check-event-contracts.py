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
EVENT_LITERAL = re.compile(r'"(android\.event\.[^"]+)"')

# Internal signals intentionally dispatched only to refresh/evaluate state and therefore do not
# represent user-selectable Trigger/Event features.
INTERNAL_RUNTIME_EVENTS = {
    'android.event.runtime_started',
    'android.event.workspace_changed',
}


def source_files() -> list[Path]:
    files: set[Path] = set()
    for pattern in SOURCE_GLOBS:
        files.update(ROOT.glob(pattern))
    return sorted(files)


def literals(source: str) -> set[str]:
    return {match.group(1) for match in EVENT_LITERAL.finditer(source)}


def registered_events(source: str) -> set[str]:
    # Many YAuto packs intentionally use helpers such as registerBroadcastEvent(typeId), where the
    # FeatureDescriptor contains FeatureId(typeId) rather than a literal. If a source file actually
    # registers events, every android.event.* literal in that registration file is part of its event
    # contract and is therefore considered a registered Trigger/Event ID.
    # Domain registrars can be extracted into extension modules. In that case the
    # FeaturePack's install() delegates via registerChangedEvent(registry, id, ...)
    # while the actual registry.registerEvent call lives in a sibling file.
    helper_ids = set(re.findall(
        r'registerChangedEvent\(registry,\s*"(android\.event\.[^"]+)"',
        source,
    )) if 'FeaturePack' in source else set()
    if 'registerEvent(' not in source and 'FeatureKind.EVENT' not in source:
        return helper_ids
    return literals(source) | helper_ids


def emitted_events(source: str) -> set[str]:
    # Event sources often map a broadcast/action to a local `typeId` variable and later call
    # RuntimeEvent(typeId, ...). Collecting all event literals from EventSource/RuntimeEvent code
    # therefore catches both direct and variable-based emission paths.
    is_emitter = (
        'RuntimeEvent(' in source
        or ': AndroidEventSource' in source
        or 'RuntimeEventEmitter' in source
    )
    return literals(source) if is_emitter else set()


def main() -> int:
    files = source_files()
    if not files:
        print('Event contract guard failed: no Kotlin source files found')
        return 1

    registered: dict[str, list[Path]] = {}
    emitted: dict[str, list[Path]] = {}
    for path in files:
        source = path.read_text(encoding='utf-8')
        for event_id in registered_events(source):
            registered.setdefault(event_id, []).append(path)
        for event_id in emitted_events(source):
            emitted.setdefault(event_id, []).append(path)

    failures: list[str] = []
    for event_id, paths in sorted(emitted.items()):
        if event_id in INTERNAL_RUNTIME_EVENTS:
            continue
        if event_id not in registered:
            locations = ', '.join(str(path) for path in paths)
            failures.append(
                f'Runtime event {event_id!r} is emitted/referenced by {locations} but no native '
                'FeaturePack registers the same event ID'
            )

    if failures:
        print('Event contract guard failed:\n' + '\n'.join(failures))
        return 1

    checked = sum(1 for event_id in emitted if event_id not in INTERNAL_RUNTIME_EVENTS)
    print(
        f'Event contract guard passed: {checked} runtime event contract IDs have matching native '
        f'Event registrations; {len(INTERNAL_RUNTIME_EVENTS)} internal event ID(s) excluded.'
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
