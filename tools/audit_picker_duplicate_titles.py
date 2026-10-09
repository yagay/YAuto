#!/usr/bin/env python3
"""List same-kind duplicate feature names after YAuto's resource override priority.

Report-only: sharing a translated title is not proof of equivalent semantics.
At runtime FeatureKind and category are also checked before merging.
"""
from __future__ import annotations
import argparse
import json
import xml.etree.ElementTree as ET
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "ui/design/src/main/res"
PREFIX = ("feature_display_", "macro_feature_", "shortx_feature_", "feature_")
FEATURE_START = ("android_", "file_", "core_", "surface_", "script_",
                 "tasker_", "accessibility_", "data_", "ai_", "variable_")

def read_titles(folder: Path) -> dict[str, str]:
    result = {}
    for path in sorted(folder.glob("*.xml")):
        for node in ET.parse(path).getroot().findall("string"):
            key = node.get("name")
            if key and key.endswith("_title"):
                if key in result:
                    raise ValueError(f"Duplicate resource {key} in {path}")
                result[key] = "".join(node.itertext()).strip()
    return result

def effective_titles(resources: dict[str, str]) -> dict[str, str]:
    candidates = {}
    for key, title in resources.items():
        for priority, prefix in enumerate(PREFIX):
            if not key.startswith(prefix): continue
            feature = key[len(prefix):-len("_title")]
            if not feature.startswith(FEATURE_START): break
            if feature not in candidates or priority < candidates[feature][0]:
                candidates[feature] = (priority, title)
            break
    return {key: data[1] for key, data in candidates.items()}

def kind(feature: str) -> str:
    if feature.startswith("android_event_") or "_event_" in feature: return "trigger"
    if feature.startswith("android_condition_") or "_condition_" in feature or feature.endswith("_condition"): return "constraint"
    if feature.startswith("android_state_") or "_state_" in feature or feature.endswith("_state"): return "state"
    return "action"

def approved_members() -> list[set[str]]:
    from audit_verified_picker_merges import APPROVED, csv_rows
    return [set(part.replace(".", "_") for part in r["member_ids"].split("|"))
            for r in csv_rows(APPROVED)]

# These are registered compatibility aliases, not separate picker descriptors.
# Validate against FeatureDescriptor before excluding them from the title audit.
ALIAS_PACK = ROOT / "platform/android/src/main/kotlin/com/yagay/yauto/platform/android/AndroidPowerUserFeaturePack.kt"
ALIAS_IDS = {
    "android_vibrate_cancel": ("android.vibration.cancel", "android.vibrate.cancel"),
    "android_state_data_saver_status": ("android.state.data_saver", "android.state.data_saver_status"),
    "android_condition_data_saver_status": ("android.condition.data_saver", "android.condition.data_saver_status"),
}

def registered_alias_ids() -> set[str]:
    source = ALIAS_PACK.read_text(encoding="utf-8")
    matches = set()
    for normalized, (canonical, alias) in ALIAS_IDS.items():
        start = source.find(f'FeatureId("{canonical}")')
        if start < 0:
            raise ValueError(f"Missing canonical feature descriptor {canonical}")
        segment = source[start:start+950]
        if f'aliases = setOf("{alias}")' not in segment:
            raise ValueError(f"Unverified compatibility alias {canonical} -> {alias}")
        matches.add(normalized)
    return matches

def inspect(titles: dict[str,str]) -> dict:
    resolved = effective_titles(titles)
    aliases = registered_alias_ids()
    same = defaultdict(list)
    for fid, title in resolved.items():
        if fid in aliases:
            continue  # backward-compatibility ID is not a separate user-facing operation
        same[(kind(fid),title)].append(fid)
    candidates = [
        {"kind":role,"title":title,"feature_ids":sorted(ids)}
        for (role,title),ids in same.items() if len(ids)>1
    ]
    candidates.sort(key=lambda x:(-len(x["feature_ids"]), x["kind"],x["title"]))
    approvals = approved_members()
    for group in candidates:
        ids = set(group["feature_ids"])
        group["approved_as_one_parameterized_entry"] = any(ids <= approved for approved in approvals)
    unresolved = [row for row in candidates if not row["approved_as_one_parameterized_entry"]]
    return {
        "resource_feature_titles":len(resolved),
        "compatibility_alias_resource_titles": sorted(aliases & set(resolved)),
        "possible_same_kind_duplicate_groups":len(candidates),
        "unresolved_candidate_groups":len(unresolved),
        "groups":candidates,
    }

def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument("--output",default="build/reports/picker_duplicate_title_inventory.json")
    ap.add_argument("--fail-on-unresolved-zh",action="store_true")
    args=ap.parse_args()
    en=inspect(read_titles(RES/"values"))
    zh=inspect(read_titles(RES/"values-zh-rCN"))
    result={
        "English":en,"Simplified_Chinese":zh,
        "notice":"Candidate scan only. Same titles may have different categories; never auto-merge by name.",
        "policy":"Keep one semantic title plus parameter choices only for matching MacroDroid/ShortX feature evidence.",
    }
    output=ROOT/args.output
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f"Picker title candidates: en {en['possible_same_kind_duplicate_groups']}, "
          f"zh-CN {zh['possible_same_kind_duplicate_groups']}; "
          f"unresolved zh-CN {zh['unresolved_candidate_groups']}")
    for language, data in (("zh-CN", zh), ("en", en)):
        for row in (r for r in data["groups"] if not r["approved_as_one_parameterized_entry"]):
            print(f"  {language} {row['kind']} / {row['title']}: {', '.join(row['feature_ids'])}")
    print(f"Review report: {output}")
    if args.fail_on_unresolved_zh and zh["unresolved_candidate_groups"]:
        raise SystemExit("Unresolved same-kind Chinese picker title collisions remain.")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
