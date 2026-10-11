#!/usr/bin/env python3
"""Inventory every FeaturePack and enforce the one-schema/one-editor architecture.

Does not assume a file per feature: several legitimate packs contain helpers or
delegate to self-contained definitions. Reports coverage without deleting functionality.
"""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = (
    ROOT / "platform/android/src/main",
    ROOT / "platform/accessibility/src/main",
    ROOT / "feature/standard/src/main",
)
EDITOR = ROOT / "ui/editor/src/main/kotlin/com/yagay/yauto/ui/editor"
REGISTRY = ROOT / "core/registry/src/main/kotlin/com/yagay/yauto/core/registry"

def inventory() -> dict:
    packs = []
    violations = []
    for folder in SOURCES:
        for path in sorted(folder.rglob("*.kt")):
            source = path.read_text(encoding="utf-8")
            rel = path.relative_to(ROOT).as_posix()
            direct = len(re.findall(r"registry\.register(?:Action|Condition|Event|State)\s*\(", source))
            descriptors = len(re.findall(r"FeatureDescriptor\s*\(", source))
            schema = len(re.findall(r"FieldSchema\.", source))
            compose = bool(re.search(r"@Composable\b|import\s+androidx\.compose\.", source))
            is_pack = path.name.endswith("FeaturePack.kt")
            if not is_pack and direct == 0 and descriptors == 0:
                continue
            if compose:
                violations.append(f"{rel}: feature packs cannot implement Compose UI; use the shared editor")
            packs.append({
                "path": rel,
                "direct_registration_calls": direct,
                "descriptor_constructors": descriptors,
                "schema_field_references": schema,
                "delegated_definitions": direct == 0,
                "compose_ui": compose,
                "is_feature_pack": is_pack,
            })

    editor_file = EDITOR / "GenericFeatureConfigEditor.kt"
    serialization = EDITOR / "FeatureConfigSerialization.kt"
    contract = REGISTRY / "FeatureEditorFields.kt"
    ui_code = editor_file.read_text(encoding="utf-8")
    serializer_code = serialization.read_text(encoding="utf-8")
    contract_code = contract.read_text(encoding="utf-8")
    rules = {
        "generic_editor_uses_shared_visibility": "visibleEditorFields(" in ui_code,
        "generic_editor_uses_shared_enabled_rules": "editableEditorFields(" in ui_code,
        "serialization_uses_shared_enabled_rules": "editableEditorFields(" in serializer_code,
        "registry_owns_enabled_rules": "fun FeatureDescriptor.isEditorFieldActive(" in contract_code,
        "unified_editor_uses_generic_editor": "GenericFeatureConfigEditor(" in (EDITOR / "UnifiedFeatureConfigEditor.kt").read_text(encoding="utf-8"),
    }
    violations.extend(name for name, passed in rules.items() if not passed)
    return {
        "pack_count": sum(item["is_feature_pack"] for item in packs),
        "registration_source_count": len(packs),
        "total_descriptor_constructors": sum(item["descriptor_constructors"] for item in packs),
        "direct_registration_calls": sum(item["direct_registration_calls"] for item in packs),
        "editor_contracts": rules,
        "packs": packs,
        "violations": violations,
    }

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--fail-on-unified-violations", action="store_true")
    args = parser.parse_args()
    report = inventory()
    output = ROOT / "build/reports/featurepack_schema_inventory.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Audited {report['pack_count']} FeaturePack files across {report['registration_source_count']} registration sources; {report['total_descriptor_constructors']} literal descriptors")
    print(f"Direct registration calls: {report['direct_registration_calls']}")
    for item in report["violations"]:
        print("VIOLATION:", item)
    if args.fail_on_unified_violations and report["violations"]:
        raise SystemExit(1)

if __name__ == "__main__":
    main()
