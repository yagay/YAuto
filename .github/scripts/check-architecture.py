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
    "fun buildEditedFeatureConfig(": "FeatureConfigSerialization.kt",
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

# Native Android overlays and Xposed package targeting each have one authoritative policy.
android_surface_dir = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android"
overlay_controller = android_surface_dir / "OverlaySurfaceController.kt"
panel_factory = android_surface_dir / "OverlayPanelFactory.kt"
if not panel_factory.exists() or "fun createOverlayBasePanel(" not in panel_factory.read_text(encoding="utf-8"):
    errors.append("OverlayPanelFactory must own the shared overlay panel layout")
if overlay_controller.exists():
    controller_text = overlay_controller.read_text(encoding="utf-8")
    if "fun basePanel(" in controller_text:
        errors.append("OverlaySurfaceController must use the shared panel factory instead of a private panel implementation")
    if "createOverlayBasePanel(context," not in controller_text:
        errors.append("OverlaySurfaceController must delegate panel appearance to OverlayPanelFactory")

xposed_dir = ROOT / "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed"
xposed_router = xposed_dir / "XposedPackageHookRouter.kt"
xposed_entry = xposed_dir / "YAutoXposedModule.kt"
if not xposed_router.exists() or "fun routeXposedPackageHooks(" not in xposed_router.read_text(encoding="utf-8"):
    errors.append("XposedPackageHookRouter must own system package-to-Hook routing")
xposed_installers = xposed_dir / "XposedHookInstallers.kt"
if not (xposed_dir / "XposedSystemEventInstallers.kt").exists():
    errors.append("XposedSystemEventInstallers must own SystemServer and input event hook installation")
if not (xposed_dir / "XposedSystemUiInstallers.kt").exists():
    errors.append("XposedSystemUiInstallers must own SystemUI hook families")
if not (xposed_dir / "XposedProviderInstallers.kt").exists():
    errors.append("XposedProviderInstallers must own provider and input hooks")
if not xposed_installers.exists():
    errors.append("XposedHookInstallers must own package installation logic")
if xposed_installers.exists() and "routeXposedPackageHooks(" not in xposed_installers.read_text(encoding="utf-8"):
    errors.append("XposedHookInstallers must delegate package routing to the shared router")

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


# The runtime must have exactly one lifecycle owner for active jobs.
runtime_dir = ROOT / "core/runtime/src/main/kotlin/com/yagay/yauto/core/runtime"
runtime_entry = runtime_dir / "AutomationRuntime.kt"
job_registry = runtime_dir / "ExecutionJobRegistry.kt"
if not job_registry.exists():
    errors.append("ExecutionJobRegistry is required for shared execution lifecycle ownership")
if runtime_entry.exists():
    runtime_text = runtime_entry.read_text(encoding="utf-8")
    if "ExecutionJobRegistry()" not in runtime_text:
        errors.append("AutomationRuntime must delegate job lifecycle to ExecutionJobRegistry")
    if "runningExecutions = " in runtime_text or "private fun trackJob(" in runtime_text:
        errors.append("AutomationRuntime must not reintroduce inline active-job tracking")
    if "RuntimeEventWaitRegistry()" not in runtime_text or "eventWaitRequests = " in runtime_text:
        errors.append("AutomationRuntime must delegate event waiter lifetime to RuntimeEventWaitRegistry")
if not (runtime_dir / "RuntimeEventWaitRegistry.kt").exists():
    errors.append("RuntimeEventWaitRegistry must own event waiting and cleanup")


# Canonical runtime mutation and selection policies must remain shared after refactoring.
if runtime_entry.exists():
    runtime_text = runtime_entry.read_text(encoding="utf-8")
    for owner in ("RuntimeLastRunStore", "RuntimeVariableStore", "RuntimeSelectionPolicy"):
        if owner not in runtime_text:
            errors.append(f"AutomationRuntime must use shared {owner}")
    if "unexecutionJobs" in runtime_text:
        errors.append("AutomationRuntime contains a broken execution-jobs reference")
for owner in ("RuntimeLastRunStore.kt", "RuntimeVariableStore.kt", "RuntimeSelectionPolicy.kt"):
    if not (runtime_dir / owner).exists():
        errors.append(f"Missing canonical runtime component {owner}")

# Privileged capability support metadata is defined once and consumed by both backends.
privileged_contract = ROOT / "core/capability/src/main/kotlin/com/yagay/yauto/core/capability/PrivilegedOperationContract.kt"
if not privileged_contract.exists():
    errors.append("PrivilegedOperationContract is required")
for rel in (
    "platform/root/src/main/kotlin/com/yagay/yauto/platform/root/RootBackend.kt",
    "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed/XposedBackend.kt",
):
    path = ROOT / rel
    if path.exists() and "PrivilegedOperationContract" not in path.read_text(encoding="utf-8"):
        errors.append(f"{rel} must use the shared privileged operation contract")

xposed_init_policy = xposed_dir / "XposedPackageInitPolicy.kt"
if not xposed_init_policy.exists():
    errors.append("XposedPackageInitPolicy is required")
if xposed_entry.exists() and "XposedPackageInitPolicy.shouldInitialize(" not in xposed_entry.read_text(encoding="utf-8"):
    errors.append("YAutoXposedModule must delegate package initialization policy")

# Dispatcher, runtime and importer data contracts must have one canonical source.
if runtime_entry.exists() and "RuntimeEventDispatchPolicy.eligible(" not in runtime_entry.read_text(encoding="utf-8"):
    errors.append("AutomationRuntime must share event eligibility rules")
if not (runtime_dir / "RuntimeEventDispatchPolicy.kt").exists():
    errors.append("RuntimeEventDispatchPolicy.kt is required")
capability_file = ROOT / "core/capability/src/main/kotlin/com/yagay/yauto/core/capability/Capability.kt"
if capability_file.exists() and "backendSnapshot" not in capability_file.read_text(encoding="utf-8"):
    errors.append("CapabilityBroker must use snapshot backend registration")

if errors:
    print("Architecture guard failed:")
    for error in errors:
        print(f" - {error}")
    sys.exit(1)

print("Architecture guard passed: UI/app-shell boundaries, file budgets, native startup path, and definition-based feature packs are enforced.")
