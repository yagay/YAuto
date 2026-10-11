import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class FeatureRegistryInventoryTest(unittest.TestCase):
    def test_scan_covers_delegated_registration_sources(self):
        audit = (ROOT / "tools/audit_featurepack_schema.py").read_text(encoding="utf-8")
        self.assertIn('rglob("*.kt")', audit)
        self.assertIn('"registration_source_count": len(packs)', audit)
        self.assertIn('if not is_pack and direct == 0 and descriptors == 0:', audit)

    def test_registration_files_not_silently_skipped(self):
        from importlib.machinery import SourceFileLoader
        report = SourceFileLoader("schema_audit", str(ROOT / "tools/audit_featurepack_schema.py")).load_module().inventory()
        self.assertGreater(report["registration_source_count"], report["pack_count"])
        self.assertFalse(report["violations"], "\\n".join(report["violations"]))


if __name__ == "__main__":
    unittest.main()
