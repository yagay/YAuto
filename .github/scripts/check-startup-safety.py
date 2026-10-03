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
        direct = re.finditer(r'sources\s*\+=\s*[A-Z]\w*EventSource\s*\(', source)
        for match in direct:
            failures.append(
                f'{service}:{line_number(source, match.start())}: '
                'EventSource construction must go through addSource() startup isolation'
            )
        if 'private fun addSource(' not in source:
            failures.append(f'{service}: missing addSource() startup isolation helper')

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
                'Compatibility importers must use registerLazy() and stay off the cold-start path'
            )
        if 'importers.registerLazy(' not in source:
            failures.append(f'{app_graph}: compatibility importers must be registered lazily')

    if failures:
        print('Startup safety guard failed:\n' + '\n'.join(failures))
        return 1

    print(
        f'Startup safety guard passed: {descriptor_count} literal FeatureDescriptor IDs are unique; '
        'FeaturePack/EventSource isolation and lazy compatibility importers are enforced.'
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
