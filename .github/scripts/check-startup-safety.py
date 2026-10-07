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


PAIR_HELPERS = (
    'booleanPair',
    'choicePair',
    'numberPair',
    'textPair',
    'hardwarePair',
    'rangePair',
    'numericPercentPair',
    'pair',
)


def literal_strings(text: str) -> list[str]:
    return re.findall(r'"([^"]+)"', text)


def add_feature_id(
    feature_ids: dict[str, tuple[Path, int]],
    failures: list[str],
    feature_id: str,
    path: Path,
    line: int,
) -> bool:
    previous = feature_ids.get(feature_id)
    if previous is not None:
        failures.append(
            f'Duplicate Feature ID {feature_id!r}: '
            f'{previous[0]}:{previous[1]} and {path}:{line}'
        )
        return False
    feature_ids[feature_id] = (path, line)
    return True


def main() -> int:
    failures: list[str] = []
    feature_ids: dict[str, tuple[Path, int]] = {}
    feature_aliases: dict[str, list[tuple[Path, int]]] = {}
    descriptor_count = 0
    dynamic_descriptor_count = 0

    for path in sorted(ROOT.glob('**/src/main/kotlin/**/*.kt')):
        if not path.parts or path.parts[0] not in {'feature', 'platform'}:
            continue
        source = path.read_text(encoding='utf-8')

        for offset, snippet in call_windows(source, 'FeatureDescriptor'):
            match = re.search(r'FeatureId\("([^"]+)"\)', snippet)
            if not match:
                continue
            feature_id = match.group(1)
            if '
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
        f'Startup safety guard passed: {descriptor_count} literal and '
        f'{dynamic_descriptor_count} expanded FeatureDescriptor IDs are unique; '
        f'{len(feature_aliases)} compatibility aliases do not collide with canonical IDs; '
        'FeaturePack/EventSource isolation is enforced and compatibility importers are off the V2 startup path.'
    )
    return 0


if __name__ == '__main__':
    sys.exit(main())
 not in feature_id:
                descriptor_count += 1
                add_feature_id(feature_ids, failures, feature_id, path, line_number(source, offset))

            for alias_match in re.finditer(r'aliases\s*=\s*setOf\((.*?)\)', snippet, re.S):
                for alias in literal_strings(alias_match.group(1)):
                    feature_aliases.setdefault(alias, []).append((path, line_number(source, offset)))

        # Most Android state packs build IDs through a shared "...$key" pair helper. Expand
        # literal helper invocations so collisions between independently-owned packs are visible
        # to CI instead of surfacing only while AppGraph installs the registry at runtime.
        if 'FeatureId("android.state.$key")' in source and 'FeatureId("android.condition.$key")' in source:
            helper_names = '|'.join(map(re.escape, PAIR_HELPERS))
            pattern = re.compile(
                rf'\b(?:{helper_names})\(\s*registry\s*,\s*"([A-Za-z0-9_.-]+)"'
            )
            for match in pattern.finditer(source):
                key = match.group(1)
                for prefix in ('android.state.', 'android.condition.'):
                    if add_feature_id(
                        feature_ids,
                        failures,
                        prefix + key,
                        path,
                        line_number(source, match.start()),
                    ):
                        dynamic_descriptor_count += 1

        # Audio state helpers intentionally namespace their canonical IDs but accept historical
        # un-namespaced IDs through legacyKey.
        if 'FeatureId(stateId)' in source and '"android.state.audio.$key"' in source:
            for match in re.finditer(
                r'\bstateAndCondition\(\s*registry\s*,\s*"([A-Za-z0-9_.-]+)"',
                source,
            ):
                key = match.group(1)
                for prefix in ('android.state.audio.', 'android.condition.audio.'):
                    if add_feature_id(
                        feature_ids,
                        failures,
                        prefix + key,
                        path,
                        line_number(source, match.start()),
                    ):
                        dynamic_descriptor_count += 1

        # Resolve the two common compatibility forms used by pair helpers.
        for match in re.finditer(r'legacyKey\s*=\s*"([A-Za-z0-9_.-]+)"', source):
            key = match.group(1)
            where = (path, line_number(source, match.start()))
            feature_aliases.setdefault('android.state.' + key, []).append(where)
            feature_aliases.setdefault('android.condition.' + key, []).append(where)

        for match in re.finditer(r'legacyKeys\s*=\s*setOf\((.*?)\)', source, re.S):
            where = (path, line_number(source, match.start()))
            for key in literal_strings(match.group(1)):
                feature_aliases.setdefault('android.state.' + key, []).append(where)
                feature_aliases.setdefault('android.condition.' + key, []).append(where)

    if descriptor_count == 0:
        failures.append('No literal FeatureDescriptor IDs found; startup-safety parser is stale')

    for alias, locations in sorted(feature_aliases.items()):
        canonical = feature_ids.get(alias)
        if canonical is not None:
            for path, line in locations:
                failures.append(
                    f'Feature alias {alias!r} collides with canonical ID at '
                    f'{canonical[0]}:{canonical[1]} (alias at {path}:{line})'
                )
        unique_locations = sorted(set(locations), key=lambda item: (str(item[0]), item[1]))
        if len(unique_locations) > 1:
            rendered = ', '.join(f'{path}:{line}' for path, line in unique_locations)
            failures.append(f'Duplicate Feature alias {alias!r}: {rendered}')

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
