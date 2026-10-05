#!/usr/bin/env python3
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path('.')


def call_windows(text: str, name: str):
    token = name + '('
    start = 0
    while True:
        idx = text.find(token, start)
        if idx < 0:
            return
        i = idx + len(token)
        depth = 1
        quote = None
        escaped = False
        while i < len(text) and depth > 0:
            ch = text[i]
            if quote is not None:
                if escaped:
                    escaped = False
                elif ch == '\\':
                    escaped = True
                elif ch == quote:
                    quote = None
            else:
                if ch in {'"', "'"}:
                    quote = ch
                elif ch == '(':
                    depth += 1
                elif ch == ')':
                    depth -= 1
            i += 1
        yield idx, text[idx:i]
        start = max(i, idx + len(token))


def line_number(text: str, offset: int) -> int:
    return text.count('\n', 0, offset) + 1


def main() -> int:
    failures: list[str] = []
    feature_ids: dict[str, tuple[Path, int]] = {}
    descriptor_count = 0

    for path in sorted(ROOT.glob('**/src/main/kotlin/**/*.kt')):
        if not path.parts or path.parts[0] not in {'feature', 'platform'}:
            continue
        source = path.read_text(encoding='utf-8')
        for offset, snippet in call_windows(source, 'FeatureDescriptor'):
            match = re.search(r'FeatureId\("([^"]+)"\)', snippet)
            if not match:
                continue
            feature_id = match.group(1)
            if '$' in feature_id:
                continue
            descriptor_count += 1
            here = (path, line_number(source, offset))
            previous = feature_ids.get(feature_id)
            if previous is not None:
                failures.append(
                    f'Duplicate Feature ID {feature_id!r}: '
                    f'{previous[0]}:{previous[1]} and {here[0]}:{here[1]}'
                )
            else:
                feature_ids[feature_id] = here

    if descriptor_count == 0:
        failures.append('No literal FeatureDescriptor IDs found; startup-safety parser is stale')

    service = ROOT / 'app/src/main/kotlin/com/yagay/yauto/AutomationRuntimeService.kt'
    if not service.exists():
        failures.append(f'Missing runtime service: {service}')
    else:
        source = service.read_text(encoding='utf-8')
        direct = re.finditer(r'(?:sources|eventSources)\s*\+=\s*[A-Z]\w*EventSource\s*\(', source)
        for match in direct:
            failures.append(
                f'{service}:{line_number(source, match.start())}: '
                'EventSource construction must go through AndroidEventSourceManager'
            )
        if 'AndroidEventSourceManager()' not in source:
            failures.append(f'{service}: missing central AndroidEventSourceManager')
        if 'private fun registerSource(' not in source:
            failures.append(f'{service}: missing registerSource() isolation helper')
        if 'eventSources.startAll(' not in source or 'eventSources.stopAll()' not in source:
            failures.append(f'{service}: EventSource lifecycle must be delegated to AndroidEventSourceManager')

    manager = ROOT / 'platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidEventSourceManager.kt'
    if not manager.exists():
        failures.append(f'Missing central event-source manager: {manager}')
    else:
        source = manager.read_text(encoding='utf-8')
        if 'Duplicate AndroidEventSource id' not in source:
            failures.append(f'{manager}: manager must reject duplicate source IDs')
        if 'asReversed()' not in source:
            failures.append(f'{manager}: event sources must stop in reverse registration order')

    app_graph = ROOT / 'app/src/main/kotlin/com/yagay/yauto/AppGraph.kt'
    if not app_graph.exists():
        failures.append(f'Missing AppGraph: {app_graph}')
    else:
        source = app_graph.read_text(encoding='utf-8')
        if 'private fun installPack(' not in source or 'features.uninstallPack' not in source:
            failures.append(f'{app_graph}: FeaturePack installation must be isolated and rolled back on failure')
        for match in re.finditer(r'importers\.register\s*\(', source):
            failures.append(
                f'{app_graph}:{line_number(source, match.start())}: '
                'Importers must use registerLazy() and stay off the cold-start path'
            )
        # Compatibility parser implementations stay outside AppGraph. AppGraph may invoke the
        # generic catalog boundary, but concrete parsers must remain lazy.
        forbidden = (
            'importer.macrodroid', 'importer.shortx', 'importer.tasker',
            'MacroDroidImporter', 'EnhancedShortXImporter', 'EnhancedTaskerImporter',
        )
        for token in forbidden:
            if token in source:
                failures.append(f'{app_graph}: compatibility importer implementation must stay behind the lazy catalog: {token}')

    compat_catalog = ROOT / 'app/src/main/kotlin/com/yagay/yauto/CompatibilityImporterCatalog.kt'
    if not compat_catalog.exists():
        failures.append(f'Missing lazy compatibility importer catalog: {compat_catalog}')
    else:
        source = compat_catalog.read_text(encoding='utf-8')
        if source.count('registerLazy(') < 3:
            failures.append(f'{compat_catalog}: MacroDroid, ShortX and Tasker must all use registerLazy()')
        for match in re.finditer(r'\.register\s*\(', source):
            failures.append(
                f'{compat_catalog}:{line_number(source, match.start())}: '
                'Compatibility importer construction must stay lazy'
            )

    if failures:
        print('Startup safety guard failed:\n' + '\n'.join(failures))
        return 1

    print(
        f'Startup safety guard passed: {descriptor_count} literal FeatureDescriptor IDs are unique; '
        'FeaturePack/EventSource isolation is enforced and compatibility importers are off the V2 startup path.'
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
