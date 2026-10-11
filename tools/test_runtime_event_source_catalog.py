import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class RuntimeEventCatalogTest(unittest.TestCase):
    def test_service_delegates_registration_to_single_catalog(self):
        service = (ROOT / "app/src/main/kotlin/com/yagay/yauto/AutomationRuntimeService.kt").read_text()
        catalog = (ROOT / "app/src/main/kotlin/com/yagay/yauto/RuntimeEventSourceCatalog.kt").read_text()
        self.assertIn("registerRuntimeEventSources(this, appGraph, ::registerSource)", service)
        self.assertNotIn('registerSource("system-broadcast")', service)
        self.assertGreaterEqual(len(re.findall(r'registerSource\("', catalog)), 35)
        for key in ('system-broadcast', 'configured-sensor', 'configured-interval', 'shortx-time', 'spotify', 'usage-foreground'):
            self.assertIn('registerSource("' + key + '"', catalog)


if __name__ == "__main__":
    unittest.main()
