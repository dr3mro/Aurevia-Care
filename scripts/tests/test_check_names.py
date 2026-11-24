import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("check_names", Path(__file__).parents[1] / "check-names.py")
check_names = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_names)


class CheckNamesTest(unittest.TestCase):
    def found(self, files, legacy=()):
        with tempfile.TemporaryDirectory() as tmp:
            for f in files:
                p = Path(tmp, f); p.parent.mkdir(parents=True, exist_ok=True); p.write_text("x")
            return check_names.violations(tmp, files, set(legacy))

    def test_story_task_and_gate_ids_are_rejected(self):
        for name in ("s_g1_08", "S-G3-02", "x_04", "dlv_02", "DLV-03", "cw_004", "aud_20261120", "fix_9"):
            with self.subTest(name=name):
                self.assertEqual(self.found([f"apps/api/{name}/A.java"]), [f"apps/api/{name}"])

    def test_business_names_pass(self):
        self.assertEqual(self.found(["apps/api/patient/identity/A.java", "infra/capacity/check.sh",
                                     "docs/operations/recovery/README.md", "contracts/openapi/a.yaml"]), [])

    def test_legacy_folders_are_tolerated_but_siblings_are_not(self):
        files = ["contracts/x_01/a.yaml", "contracts/x_09/a.yaml"]
        self.assertEqual(self.found(files, {"contracts/x_01"}), ["contracts/x_09"])

    def test_java_package_segment_is_checked(self):
        with tempfile.TemporaryDirectory() as tmp:
            p = Path(tmp, "src/Foo.java"); p.parent.mkdir()
            p.write_text("package com.aurevia.api.x_07;\nclass Foo {}\n")
            out = check_names.violations(tmp, ["src/Foo.java"], set())
        self.assertEqual(len(out), 1)
        self.assertIn("x_07", out[0])

    def test_history_folders_are_ignored(self):
        self.assertEqual(self.found([".agent/pr-notes/S-G5-09.md", "docs/swarm/S-G3-02.md",
                                     "docs/archive/s_g1_01/a.md"]), [])

    def test_the_repository_itself_is_clean(self):
        self.assertEqual(check_names.violations(str(Path(__file__).parents[2])), [])


if __name__ == "__main__":
    unittest.main()
