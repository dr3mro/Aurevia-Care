import importlib.util
import io
import json
import runpy
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).resolve().parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location(
    "python_coverage", SCRIPTS / "python_coverage.py")
pc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(pc)

DIFF = """diff --git a/scripts/a.py b/scripts/a.py
--- a/scripts/a.py
+++ b/scripts/a.py
@@ -3,0 +4,2 @@ def f():
+x = 1
+y = 2
@@ -10 +12 @@ def g():
-old
+new
diff --git a/scripts/tests/test_a.py b/scripts/tests/test_a.py
--- a/scripts/tests/test_a.py
+++ b/scripts/tests/test_a.py
@@ -1,0 +2 @@
+ignored
diff --git a/scripts/gone.py b/scripts/gone.py
--- a/scripts/gone.py
+++ /dev/null
@@ -1 +0,0 @@
-deleted
"""
REPORT = {"files": {
    "scripts/a.py": {"missing_lines": [5, 40], "missing_branches": [[12, 13], [50, 51]]},
    "scripts/b.py": {"missing_lines": [], "missing_branches": []}}}


class CoverageGateTests(unittest.TestCase):
    def test_added_lines_reads_only_script_hunks(self):
        self.assertEqual(pc.added_lines(DIFF), {"scripts/a.py": {4, 5, 12}})

    def test_uncovered_reports_added_lines_and_branches_only(self):
        found = pc.uncovered(REPORT, {"scripts/a.py": {4, 5, 12}})
        self.assertEqual(found, [("scripts/a.py", 5, "line not run"), ("scripts/a.py", 12, "branch not taken")])

    def test_uncovered_reports_a_file_that_was_not_measured(self):
        self.assertEqual(pc.uncovered(REPORT, {"scripts/new.py": {1}}),
                         [("scripts/new.py", 0, "file not measured")])

    def test_full_gaps_lists_everything(self):
        self.assertEqual(len(pc.full_gaps(REPORT)), 4)
        self.assertEqual(pc.full_gaps({}), [])

    def test_diff_against_a_missing_base_is_none(self):
        self.assertIsNone(pc.diff_against("refs/does/not/exist"))

    def test_diff_against_a_real_ref_returns_text(self):
        self.assertIsInstance(pc.diff_against("HEAD"), str)

    def run_main(self, report, *args, diff="__unset__"):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "r.json"
            path.write_text(json.dumps(report))
            out, err = io.StringIO(), io.StringIO()
            patch = mock.patch.object(pc, "diff_against", return_value=DIFF if diff == "__unset__" else diff)
            with patch, redirect_stdout(out), redirect_stderr(err):
                code = pc.main([str(path), *args])
        return code, out.getvalue(), err.getvalue()

    def test_main_fails_on_an_uncovered_added_line(self):
        code, _, err = self.run_main(REPORT)
        self.assertEqual(code, 1)
        self.assertIn("scripts/a.py:5: line not run", err)

    def test_main_passes_when_added_lines_are_covered(self):
        clean = {"files": {"scripts/a.py": {"missing_lines": [40], "missing_branches": [[50, 51]]}}}
        code, out, _ = self.run_main(clean)
        self.assertEqual((code, "changed script line" in out), (0, True))

    def test_main_skips_when_the_base_is_missing(self):
        code, _, err = self.run_main(REPORT, diff=None)
        self.assertEqual(code, 0)
        self.assertIn("skipped", err)

    def test_main_full_requires_everything(self):
        code, _, _ = self.run_main(REPORT, "--full")
        self.assertEqual(code, 1)
        code, out, _ = self.run_main({"files": {}}, "--full")
        self.assertEqual((code, "script line" in out), (0, True))

    def test_running_the_file_as_a_script_exits_with_the_result(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "r.json"
            path.write_text(json.dumps({"files": {}}))
            argv = ["python_coverage.py", str(path), "--full"]
            with mock.patch.object(sys, "argv", argv), redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_path(str(Path(pc.__file__)), run_name="__main__")
        self.assertEqual(raised.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
