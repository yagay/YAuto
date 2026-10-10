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
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/ActionTreeEditor.kt": 14_000,
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/GenericFeatureConfigEditor.kt": 23_000,
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/AutomationEditorScreen.kt": 27_000,
    "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/FlowEditorScreen.kt": 22_000,
    "app/src/main/kotlin/com/yagay/yauto/YAutoAppScreen.kt": 24_000,
    "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/ShortXMappings.kt": 36_000,
    "importer/shortx/src/main/kotlin/com/yagay/yauto/importer/shortx/ShortXVerifiedBatchMappings.kt": 31_000,
    "ui/home/src/main/kotlin/com/yagay/yauto/ui/home/HomeScreen.kt": 22_000,
    # Domain registrars should stay separate from public feature-pack facades.
    "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidPersonalDataFeaturePack.kt": 8_000,
    "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidRemainingParityFeaturePack.kt": 20_000,
    "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidFinalParityFeaturePack.kt": 20_000,
    "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidSurfaceFeaturePack.kt": 8_000,
    "platform/accessibility/src/main/kotlin/com/yagay/yauto/platform/accessibility/AccessibilityFeaturePack.kt": 28_000,
    "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/OverlaySurfaceController.kt": 44_000,
}
for rel, maximum in size_budgets.items():
    path = ROOT / rel
    if path.exists() and path.stat().st_size > maximum:
        errors.append(f"{rel} is {path.stat().st_size} bytes; budget is {maximum}. Split responsibilities before adding more.")

# Shared feature configuration and access labels have a single canonical source.
editor_dir = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor"
shared_contracts = {
    "buildEditedFeatureConfig(": "FeatureConfigSerialization.kt",
    "fun fieldValid(": "FeatureConfigSerialization.kt",
    "fun accessRequirementResource(": "FeatureAccessLabels.kt",
}
for token, owner in shared_contracts.items():
    matches = [
        path.name for path in editor_dir.glob("*.kt")
        if token in path.read_text(encoding="utf-8")
    ] if editor_dir.exists() else []
    if matches != [owner]:
        errors.append(f"{token} must have exactly one implementation in {owner}; found {matches}")

# All hardware-key capture sources must merge in one runtime-owned coordinator.
capture_screen = ROOT / "app/src/main/kotlin/com/yagay/yauto/YAutoAppScreen.kt"
capture_coordinator = ROOT / "app/src/main/kotlin/com/yagay/yauto/HardwareKeyCaptureCoordinator.kt"
if capture_screen.exists() and "suspend fun captureHardwareKey(" in capture_screen.read_text(encoding="utf-8"):
    errors.append("YAutoAppScreen must not duplicate hardware-key capture orchestration")
if not capture_coordinator.exists():
    errors.append("HardwareKeyCaptureCoordinator.kt is required for unified key event capture")

# Third-party compatibility parsers must stay behind the lazy compatibility catalog rather than
# becoming direct AppGraph dependencies.
app_graph = ROOT / "app/src/main/kotlin/com/yagay/yauto/AppGraph.kt"
if app_graph.exists():
    graph_text = app_graph.read_text(encoding="utf-8")
    forbidden = ("MacroDroidImporter", "TaskerImporter", "ShortXImporter", "CompatibilityFeaturePack")
    for name in forbidden:
        if name in graph_text:
            errors.append(f"AppGraph must not directly register compatibility component: {name}")

compat_catalog = ROOT / "app/src/main/kotlin/com/yagay/yauto/CompatibilityImporterCatalog.kt"
if not compat_catalog.exists():
    errors.append("CompatibilityImporterCatalog.kt is required for lazy third-party importer registration")
else:
    catalog_text = compat_catalog.read_text(encoding="utf-8")
    if catalog_text.count("registerLazy(") < 3:
        errors.append("CompatibilityImporterCatalog must lazily register MacroDroid, ShortX and Tasker")
    if "registry.register(" in catalog_text:
        errors.append("CompatibilityImporterCatalog must not eagerly construct importer instances")

# Standard packs should remain thin aggregators; direct registration belongs in definitions.
standard_dir = ROOT / "feature/standard/src/main/kotlin/com/yagay/yauto/feature/standard"
if standard_dir.exists():
    for path in standard_dir.glob("*FeaturePack.kt"):
        text = path.read_text(encoding="utf-8")
        if "registry.registerAction(" in text or "registry.registerCondition(" in text or "registry.registerEvent(" in text:
            errors.append(
                f"{path.relative_to(ROOT)} directly registers features; move descriptors/executors into self-contained definitions"
            )

# Debug execution must share the production engine factory and the editor must
# not reintroduce the former isolated step-runner with different semantics.
tester = ROOT / "app/src/main/kotlin/com/yagay/yauto/RuntimeFeatureTester.kt"
if tester.exists():
    tester_text = tester.read_text(encoding="utf-8")
    if "graph.runtime.createDebugEngine()" not in tester_text:
        errors.append("RuntimeFeatureTester must construct its debugger through the shared AutomationRuntime factory")
    if "AutomationEngine(" in tester_text:
        errors.append("RuntimeFeatureTester cannot build a separate AutomationEngine configuration")

old_debug = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor/StepDebugDialog.kt"
if old_debug.exists():
    errors.append("obsolete isolated StepDebugDialog must not coexist with EngineDebugDialog")

home_screen = ROOT / "ui/home/src/main/kotlin/com/yagay/yauto/ui/home/HomeScreen.kt"
if home_screen.exists():
    home_text = home_screen.read_text(encoding="utf-8")
    if "onManual = onRunManual" in home_text:
        errors.append("settings must open the shared test centre, not bypass it with direct manual execution")

if errors:
    print("Architecture guard failed:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Architecture guard passed: UI/app-shell boundaries, file budgets, native startup path, and definition-based feature packs are enforced.")
