#!/usr/bin/env python3
from pathlib import Path
import re

ROOT = Path('.')


def edit(path: str, transform):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    new = transform(text)
    if new != text:
        p.write_text(new, encoding='utf-8')


def remove_line(text: str, needle: str) -> str:
    return ''.join(line for line in text.splitlines(True) if needle not in line)

# Remove the mistakenly added machine-level requirement; Shizuku is the intended backend.
edit(
    'core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeatureRegistry.kt',
    lambda t: remove_line(t, 'SHAMIKO("shamiko")'),
)

# Picker mapping must match the AccessRequirement enum exactly.
edit(
    'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFeaturePicker.kt',
    lambda t: remove_line(t, 'AccessRequirement.SHAMIKO ->'),
)

# Remove the environment-only card created for the mistaken requirement.
def cleanup_runtime_settings(text: str) -> str:
    block = '''                item {\n                    EngineCard(\n                        stringResource(TextR.string.backend_environment_title),\n                        stringResource(TextR.string.backend_environment_detail),\n                    )\n                }\n'''
    return text.replace(block, '')

edit('app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsScreen.kt', cleanup_runtime_settings)

# Remove English and Chinese resource entries that existed only for the mistaken requirement.
for path in (
    'ui/design/src/main/res/values/strings.xml',
    'ui/design/src/main/res/values-zh-rCN/strings.xml',
):
    def cleanup_strings(text: str) -> str:
        drop_names = {
            'backend_environment_title',
            'backend_environment_detail',
            'backend_environment_status',
            'access_shamiko',
        }
        return ''.join(
            line for line in text.splitlines(True)
            if not any(f'name="{name}"' in line for name in drop_names)
        )
    edit(path, cleanup_strings)

# Remove the test block that encoded the mistaken requirement. Existing tests already verify
# Root/Shizuku/LSPosed implementation metadata and selection.
def cleanup_test(text: str) -> str:
    start_marker = '    @Test fun `Shamiko and Zygisk remain environment requirements not fake backends`() {'
    end_marker = '    @Test fun `selected backend is forwarded by privileged feature`() = runBlocking {'
    start = text.find(start_marker)
    if start < 0:
        return text
    end = text.find(end_marker, start)
    if end < 0:
        raise RuntimeError('Could not find end of mistaken access-metadata test block')
    return text[:start] + text[end:]

edit(
    'feature/standard/src/test/kotlin/com/yagay/yauto/feature/standard/PrivilegedAndroidFeaturePackTest.kt',
    cleanup_test,
)

# Self-check production source/resources/tests/docs. Temporary migration files are excluded and
# deleted after verification.
leftovers = []
for p in ROOT.rglob('*'):
    if not p.is_file():
        continue
    rel = p.as_posix()
    if rel in {
        'tools/remove_shamiko.py',
        '.github/workflows/localization-finalize.yml',
    } or '/build/' in rel or rel.startswith('.git/'):
        continue
    if p.suffix.lower() not in {'.kt', '.kts', '.xml', '.md', '.txt', '.yml', '.yaml', '.json', '.properties'}:
        continue
    try:
        text = p.read_text(encoding='utf-8')
    except UnicodeDecodeError:
        continue
    if re.search(r'shamiko', text, re.I):
        leftovers.append(rel)

if leftovers:
    raise SystemExit('Mistaken access references remain in: ' + ', '.join(sorted(leftovers)))

print('Mistaken access metadata removed; Shizuku backend remains unchanged.')
