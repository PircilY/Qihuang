"""UI evidence packaging checks; no corpus or frozen test data is read."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("package_delivery", Path(__file__).with_name("package_delivery.py"))
package = importlib.util.module_from_spec(spec)
spec.loader.exec_module(package)


class UiDeliveryTests(unittest.TestCase):
    def test_screenshot_whitelist(self):
        for name in ("docs/ui-redesign/before/workbench-320.jpg",
                     "docs/ui-redesign/after/model-1280-dark.jpg",
                     "docs/ui-redesign/after/audit-report.json",
                     "docs/ui-redesign/test-results.json"):
            self.assertTrue(package.allowed(name), name)

    def test_runtime_and_unlisted_evidence_are_excluded(self):
        for name in (".tmp/ui/answer.png", "backend/target/classes/app.js",
                     "docs/ui-redesign/after/raw.png", "docs/ui-redesign/after/archive.zip",
                     "docs/ui-redesign/before/model-1280-dark.jpg",
                     "docs/ui-redesign/after/model-1280.jpg"):
            self.assertFalse(package.allowed(name), name)

    def test_large_screenshot_rejected_before_packaging(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            name = "docs/ui-redesign/after/workbench-1440-light.jpg"
            image = root / name
            image.parent.mkdir(parents=True)
            image.write_bytes(b"x" * 300_001)
            with patch.object(package.subprocess, "check_output", return_value=(name + "\0").encode()):
                with self.assertRaisesRegex(ValueError, "300 KB"):
                    package.build(root, root / "out.zip")

    def test_stage_screenshot_budgets(self):
        views = ("overview", "knowledge", "workbench", "evaluation", "pipeline", "comparison", "model")
        widths = (1440, 1280, 390, 320)
        for stage, count in (("before", 11), ("after", 21)):
            with self.subTest(stage=stage), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                names = [f"docs/ui-redesign/{stage}/{view}-{width}"
                         + ("-light" if stage == "after" else "") + ".jpg"
                         for view in views for width in widths][:count]
                for name in names:
                    image = root / name
                    image.parent.mkdir(parents=True, exist_ok=True)
                    image.write_bytes(b"x" * 300_000)
                with patch.object(package.subprocess, "check_output",
                                  return_value=("\0".join(names) + "\0").encode()):
                    with self.assertRaisesRegex(ValueError, "stage budget"):
                        package.build(root, root / "out.zip")


if __name__ == "__main__":
    unittest.main()
