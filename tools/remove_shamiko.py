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

# Machine-level metadata: Shamiko was added by mistake; Shizuku is the intended backend.
edit(
    'core/registry/src/main/kotlin/com/yagay/yauto/core/registry/FeatureRegistry.kt',
    lambda t: remove_line(t, 'SHAMIKO("shamiko")'),
)

# Picker mapping must match the AccessRequirement enum exactly.
edit(
    'ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/MacroFeaturePicker.kt',
    lambda t: remove_line(t, 'AccessRequirement.SHAMIKO ->'),
)

# Remove the environment-only card that was created for the mistaken Shamiko concept.
def cleanup_runtime_settings(text: str) -> str:
    block = '''                item {\n                    EngineCard(\n                        stringResource(TextR.string.backend_environment_title),\n                        stringResource(TextR.string.backend_environment_detail),\n                    )\n                }\n'''
    return text.replace(block, '')

edit('app/src/main/kotlin/com/yagay/yauto/RuntimeSettingsScreen.kt', cleanup_runtime_settings)

# Remove English and Chinese resource entries that exist only for Shamiko.
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
        out = []
        for line in text.splitlines(True):
            if any(f'name="{name}"' in line for name in drop_names):
                continue
            out.append(line)
        return ''.join(out)
    edit(path, cleanup_strings)

# Remove the test that enshrined Shamiko as an access requirement. Existing tests already verify
# Root/Shizuku/LSPosed backend exposure and selection.
def cleanup_test(text: str) -> str:
    pattern = re.compile(
        r'\n\s*@Test fun `Shamiko and Zygisk remain environment labels not fake backends`\(\) \{.*?\n\s*\}\n',
        re.S,
    )
    return pattern.sub('\n', text, count=1)

edit(
    'feature/standard/src/test/kotlin/com/yagay/yauto/feature/standard/PrivilegedAndroidFeaturePackTest.kt',
    cleanup_test,
)

# Make the cleanup self-checking. The one-time script itself is excluded and will be deleted after
# verification; no production source/resource/test/docs may retain Shamiko references.
leftovers = []
for p in ROOT.rglob('*'):
    if not p.is_file():
        continue
    rel = p.as_posix()
    if rel in {'tools/remove_shamiko.py'} or '/build/' in rel or rel.startswith('.git/'):
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
    raise SystemExit('Shamiko references remain in: ' + ', '.join(sorted(leftovers)))

print('Shamiko cleanup complete; Shizuku backend remains unchanged.')
