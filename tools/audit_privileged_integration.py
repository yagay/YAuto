#!/usr/bin/env python3
"""Inventory Root/LSPosed entry points, runtime bridges and ShortX hook subscriptions.

This is a wiring audit, not a claim that ROM-specific hooks work on a device.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
XPOSED = ROOT / "platform/xposed/src/main/kotlin/com/yagay/yauto/platform/xposed"
ANDROID = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android"
ROOT_BACKEND = ROOT / "platform/root/src/main/kotlin/com/yagay/yauto/platform/root"
PRIVILEGED = ROOT / "feature/standard/src/main/kotlin/com/yagay/yauto/feature/standard/privileged"
EVENT = re.compile(r'"(android\.event\.[a-z0-9_]+)"')
DESCRIPTOR = re.compile(r'FeatureId\(\s*"([^"]+)"')
CATALOG_EVENT = re.compile(r'eventType\s*=\s*"(android\.event\.[a-z0-9_]+)"')
BRIDGE_OPERATIONS = (
    "PING", "APP_PROCESS_START", "STATUS_ICON_SET", "STATUS_ICON_REMOVE",
    "SHORTX_BEHAVIOR_SET", "SYSTEM_EVENT_SUBSCRIPTIONS_SET",
    "HARDWARE_KEY_CAPTURE_START", "HOOK_INSTALL_SESSION", "HOOK_DISABLE_SESSION",
    "HOOK_CRASH_GUARD_STATUS", "HOOK_CRASH_GUARD_RESET",
    "SHORTX_PACKAGE_BEHAVIOR_SET", "STATUS_CHIP_SHOW", "STATUS_CHIP_HIDE",
)

def sources(folder: Path):
    for path in sorted(folder.rglob("*.kt")):
        yield path.relative_to(ROOT).as_posix(), path.read_text(encoding="utf-8")


def collect_inventory() -> dict:
    android_sources = list(sources(ANDROID))
    privileged_sources = list(sources(PRIVILEGED))
    root_sources = list(sources(ROOT_BACKEND))
    xposed_sources = list(sources(XPOSED))
    catalog = (XPOSED / "ShortXCompatHookCatalog.kt").read_text(encoding="utf-8")
    backend = (XPOSED / "XposedBackend.kt").read_text(encoding="utf-8")
    module = (XPOSED / "YAutoXposedModule.kt").read_text(encoding="utf-8")
    module += (XPOSED / "XposedSystemBridgeRegistration.kt").read_text(encoding="utf-8")
    protocol = (XPOSED / "SystemBridgeProtocol.kt").read_text(encoding="utf-8")
    android_events = set().union(*(set(EVENT.findall(src)) for _, src in android_sources))
    subscribed_events = set(CATALOG_EVENT.findall(catalog))
    android_privileged = [
        {"path": name, "literal_feature_ids": sorted(set(DESCRIPTOR.findall(src)))}
        for name, src in android_sources
        if ("CapabilityIds.PRIVILEGED_SHELL" in src or
            "CapabilityIds.LSPOSED" in src or
            "AccessRequirement.LSPOSED" in src)
    ]
    standard_privileged = [
        {"path": name, "literal_feature_ids": sorted(set(DESCRIPTOR.findall(src)))}
        for name, src in privileged_sources
    ]
    operations = {
        op: {
            "declared": bool(re.search(r"const val " + op + r"\s*=", protocol)),
            # Chip requests use a separate, authenticated SystemUI receiver.
            # Their operation names are checked by ShortXStatusChipController,
            # not listed as case labels in YAutoXposedModule.
            "handled": (
                ("SystemBridgeProtocol." + op) in module or
                (op in {"STATUS_CHIP_SHOW", "STATUS_CHIP_HIDE"} and
                 "SystemBridgeProtocol.CHIP_ACTION" in module and
                 ("SystemBridgeProtocol." + op) in
                 (XPOSED / "ShortXStatusChipController.kt").read_text(encoding="utf-8"))
            ),
            "backend": ("SystemBridgeProtocol." + op) in backend,
        }
        for op in BRIDGE_OPERATIONS
    }
    # Some actions (e.g. installed-only subscriptions) are handled directly by
    # XposedBackend and need not have a branch in the module's when statement.
    required_roundtrips = (
        "APP_PROCESS_START", "STATUS_ICON_SET", "STATUS_ICON_REMOVE",
        "SHORTX_BEHAVIOR_SET", "HOOK_INSTALL_SESSION",
        "HOOK_DISABLE_SESSION", "HOOK_CRASH_GUARD_STATUS", "HOOK_CRASH_GUARD_RESET",
        "SHORTX_PACKAGE_BEHAVIOR_SET",
        "STATUS_CHIP_SHOW", "STATUS_CHIP_HIDE",
    )
    missing_roundtrips = [
        op for op in required_roundtrips
        if not all(operations[op].values())
    ]
    missing_observer_events = sorted(subscribed_events - android_events)
    root_backend_ready = any("class RootBackend" in src and
                              "CapabilityIds.PRIVILEGED_SHELL" in src
                              for _, src in root_sources)
    root_shell_bounded = any("BoundedProcessRunner.run" in src
                             for _, src in root_sources)
    return {
        "root": {
            "backend_declared": root_backend_ready,
            "bounded_root_shell": root_shell_bounded,
            "privileged_source_files": standard_privileged,
        },
        "lsposed": {
            "protocol_operations": operations,
            "missing_roundtrips": missing_roundtrips,
            "system_server_catalog_observers": len(subscribed_events),
            "system_server_observer_events": sorted(subscribed_events),
            "missing_registered_observer_events": missing_observer_events,
            "android_privileged_source_files": android_privileged,
        },
        "limitations": [
            "Static wiring coverage does not prove OEM compatibility or Hook installation",
            "Observer Hook registration is not equivalent to behavior-changing parity",
            "Root shell requires live su authorization; LSPosed package scopes require a target restart",
            "Undocumented ShortX plugin/Any variants are not automatically executable",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", default="build/reports/root_lsposed_integration.json")
    parser.add_argument("--fail-on-unwired", action="store_true")
    args = parser.parse_args()
    report = collect_inventory()
    target = ROOT / args.output
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("Root backend:", report["root"]["backend_declared"],
          "bounded runner:", report["root"]["bounded_root_shell"])
    print("ShortX system observers:", report["lsposed"]["system_server_catalog_observers"],
          "missing picker events:", len(report["lsposed"]["missing_registered_observer_events"]))
    print("Bridge roundtrips missing:", report["lsposed"]["missing_roundtrips"])
    print("Report:", target)
    if args.fail_on_unwired and (
        not report["root"]["backend_declared"] or
        not report["root"]["bounded_root_shell"] or
        report["lsposed"]["missing_registered_observer_events"] or
        report["lsposed"]["missing_roundtrips"]
    ):
        raise SystemExit("Root/LSPosed capability wiring audit failed")


if __name__ == "__main__":
    main()
