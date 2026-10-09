#!/usr/bin/env python3
"""Unit tests: AOSP ID matching, arrays, and protected automation feature titles."""
import tempfile
import unittest
from pathlib import Path

from audit_aosp_terms import Term, audit, import_official_terms, load_terms, resource_items, verify_official


class AospAuditTests(unittest.TestCase):
    def test_source_key_must_match_in_both_languages(self):
        with tempfile.TemporaryDirectory() as d:
            en = Path(d) / "en.xml"
            zh = Path(d) / "zh.xml"
            en.write_text('<resources><string name="screen">Screen</string></resources>', encoding="utf-8")
            zh.write_text('<resources><string name="different">屏幕</string></resources>', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "disagrees"):
                verify_official([Term("settings", "screen", "Screen", "屏幕")],
                                {"settings": (en, zh)})

    def test_string_arrays_match_by_slot(self):
        with tempfile.TemporaryDirectory() as d:
            en = Path(d) / "en.xml"
            zh = Path(d) / "zh.xml"
            en.write_text('<resources><string-array name="state"><item>Off</item><item>On</item></string-array></resources>', encoding="utf-8")
            zh.write_text('<resources><string-array name="state"><item>已关闭</item><item>已开启</item></string-array></resources>', encoding="utf-8")
            self.assertEqual(resource_items(en)["state[0]"], "Off")
            verify_official([Term("systemui", "state[0]", "Off", "已关闭")],
                            {"systemui": (en, zh)})

    def test_macro_names_are_protected_from_auto_replacement(self):
        terms = [Term("settings", "airplane_mode", "Airplane mode", "飞行模式")]
        english = {"ui::macro_feature_foo_title": {"name": "macro_feature_foo_title", "value": "Airplane mode"}}
        chinese = {"ui::macro_feature_foo_title": {"value": "飞行模式开关", "file": "cn.xml"}}
        report = audit(terms, english, chinese)
        self.assertEqual(report["automatic_replacements"], 0)
        self.assertTrue(report["aosp_review_candidates"][0]["protected_feature_title"])

    def test_detect_english_copies_but_not_machine_terms(self):
        english = {
            "ui::a": {"name": "a", "value": "Airplane mode"},
            "ui::b": {"name": "b", "value": "NFC"},
        }
        chinese = {key: {"value": value["value"], "file": "cn.xml"} for key, value in english.items()}
        report = audit([], english, chinese)
        self.assertEqual([r["resource"] for r in report["untranslated_english"]], ["ui::a"])

    def test_full_official_source_import_is_advisory_and_keyed(self):
        with tempfile.TemporaryDirectory() as d:
            en, zh = Path(d) / "en.xml", Path(d) / "zh.xml"
            en.write_text('<resources><string name="new">Quick settings</string><string name="old">Bluetooth</string></resources>', encoding="utf-8")
            zh.write_text('<resources><string name="new">快捷设置</string><string name="old">蓝牙</string></resources>', encoding="utf-8")
            reviewed = [Term("settings", "old", "Bluetooth", "蓝牙")]
            result = import_official_terms(reviewed, {"settings": (en, zh)})
            self.assertEqual(result, [Term("settings", "new", "Quick settings", "快捷设置")])

    def test_glossary_present_and_valid(self):
        self.assertGreaterEqual(len(load_terms()), 30)


if __name__ == "__main__":
    unittest.main()
