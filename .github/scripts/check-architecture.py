#!/usr/bin/env python3
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[2]
errors: list[str] = []

# Legacy inspiration names must not become file-level architecture again.
for path in ROOT.glob("ui/**/*.kt"):
    if path.name.startswith("Macro"):
        errors.append(f"legacy Macro-prefixed UI file is not allowed: {path.relative_to(ROOT)}")

# MainActivity is only the Android lifecycle host. App workflows belong in YAutoAppScreen/helpers.
main_activity = ROOT / "app/src/main/kotlin/com/yagay/yauto/MainActivity.kt"
if main_activity.exists():
    lines = main_activity.read_text(encoding="utf-8").splitlines()
    if len(lines) > 60:
        errors.append(f"MainActivity.kt is {len(lines)} lines; keep it <= 60 and move app-shell logic out")
    text = "\n".join(lines)
    if "YAutoAppScreen(" not in text:
        errors.append("MainActivity.kt must delegate UI/workflow state to YAutoAppScreen")

app_shell = ROOT / "app/src/main/kotlin/com/yagay/yauto/YAutoAppScreen.kt"
if not app_shell.exists():
    errors.append("YAutoAppScreen.kt is required as the typed app-shell boundary")
else:
    shell_text = app_shell.read_text(encoding="utf-8")
    if "enum class AppPage" not in shell_text:
        errors.append("YAutoAppScreen must use typed AppPage navigation instead of string page IDs")

# Keep large, domain-owned editors from silently turning into new monoliths.
size_budgets = {
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/ActionTreeEditor.kt": 36_000,
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt": 28_000,
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/AutomationEditorScreen.kt": 33_000,
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FlowEditorScreen.kt": 22_000,
    "ui/home/src/main/kotlin/com/yagay/yauto/ui/home/HomeScreen.kt": 22_000,
}
for rel, maximum in size_budgets.items():
    path = ROOT / rel
    if path.exists() and path.stat().st_size > maximum:
        errors.append(f"{rel} is {path.stat().st_size} bytes; budget is {maximum}. Split responsibilities before adding more.")

# Third-party compatibility importers must not return to the product startup graph.
app_graph = ROOT / "app/src/main/kotlin/com/yagay/yauto/AppGraph.kt"
if app_graph.exists():
    graph_text = app_graph.read_text(encoding="utf-8")
    forbidden = ("MacroDroidImporter", "TaskerImporter", "ShortXImporter", "CompatibilityFeaturePack")
    for name in forbidden:
        if name in graph_text:
            errors.append(f"AppGraph must not register legacy compatibility component: {name}")

# Standard packs should remain thin aggregators; direct registration belongs in definitions.
standard_dir = ROOT / "feature/standard/src/main/kotlin/com/yagay/yauto/feature/standard"
if standard_dir.exists():
    for path in standard_dir.glob("*FeaturePack.kt"):
        text = path.read_text(encoding="utf-8")
        if "registry.registerAction(" in text or "registry.registerCondition(" in text or "registry.registerEvent(" in text:
            errors.append(
                f"{path.relative_to(ROOT)} directly registers features; move descriptors/executors into self-contained definitions"
            )

if errors:
    print("Architecture guard failed:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Architecture guard passed: UI/app-shell boundaries, file budgets, native startup path, and definition-based feature packs are enforced.")
