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
    for tag, role in (("android_event_", "trigger"), ("android_state_", "state"),
                      ("android_condition_", "constraint")):
        if feature.startswith(tag):
            return role
    return "action"

def inspect(titles: dict[str,str]) -> dict:
    resolved = effective_titles(titles)
    same = defaultdict(list)
    for fid, title in resolved.items():
        same[(kind(fid),title)].append(fid)
    candidates = [
        {"kind":role,"title":title,"feature_ids":sorted(ids)}
        for (role,title),ids in same.items() if len(ids)>1
    ]
    candidates.sort(key=lambda x:(-len(x["feature_ids"]), x["kind"],x["title"]))
    return {
        "resource_feature_titles":len(resolved),
        "possible_same_kind_duplicate_groups":len(candidates),
        "groups":candidates,
    }

def main() -> int:
    ap=argparse.ArgumentParser()
    ap.add_argument("--output",default="build/reports/picker_duplicate_title_inventory.json")
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
          f"zh-CN {zh['possible_same_kind_duplicate_groups']}")
    for row in zh["groups"][:12]:
        print(f"  {row['kind']} / {row['title']}: {', '.join(row['feature_ids'])}")
    print(f"Review report: {output}")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
