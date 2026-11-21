"""Tests for the build-failing 100% line-coverage gate itself.

These prove the gate cannot pass vacuously: without ``trace --missing`` an
uncovered line is not annotated, and the integration test below exercises that
exact difference on a deliberately under-covered probe.
"""
import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

GATE_PATH = Path(__file__).resolve().parent / "coverage_gate.py"
SPEC = importlib.util.spec_from_file_location("coverage_gate", GATE_PATH)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)

PROBE = '''\
def covered(value):
    return value + 1


def uncovered(value):
    return value - 1


assert covered(1) == 2
'''


class CoverageGateTest(unittest.TestCase):
    def test_find_uncovered_lines_only_uses_leading_marker(self):
        text = "    1: x = 1\n>>>>>>     2: y = 2\n    3: s = '>>>>>> no'\n"
        self.assertEqual(gate.find_uncovered_lines(text), [2])

    def test_production_modules_excludes_test_utilities(self):
        modules = [p.name for p in gate.production_modules()]
        self.assertEqual(modules, ["terraform_policy_check.py"])

    def test_find_cover_file_requires_exactly_one_match(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(RuntimeError):
                gate.find_cover_file(root, "probe")
            (root / "a.probe.cover").write_text("source", encoding="utf-8")
            self.assertEqual(gate.find_cover_file(root, "probe").name,
                             "a.probe.cover")
            (root / "b.probe.cover").write_text("source", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                gate.find_cover_file(root, "probe")

    def test_missing_flag_is_what_reveals_uncovered_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "probe.py").write_text(PROBE, encoding="utf-8")
            marked = root / "marked"
            plain = root / "plain"
            marked.mkdir()
            plain.mkdir()
            base = [sys.executable, "-I", "-m", "trace", "--count"]
            probe = str(root / "probe.py")
            subprocess.run(base + ["--coverdir", str(marked), "--missing", probe],
                           cwd=directory, capture_output=True, text=True,
                           check=True)
            subprocess.run(base + ["--coverdir", str(plain), probe],
                           cwd=directory, capture_output=True, text=True,
                           check=True)
            with_marked = (marked / "probe.cover").read_text(encoding="utf-8")
            without_marked = (plain / "probe.cover").read_text(encoding="utf-8")
            self.assertTrue(gate.find_uncovered_lines(with_marked))
            self.assertEqual(gate.find_uncovered_lines(without_marked), [])

    def test_run_traced_suite_reports_a_missing_target(self):
        with tempfile.TemporaryDirectory() as directory:
            result = gate.run_traced_suite(directory,
                                           target="/no/such/runner.py")
            self.assertNotEqual(result.returncode, 0)

    def test_main_returns_zero_when_everything_is_covered(self):
        report = {"module": (10, [])}
        stdout = io.StringIO()
        with mock.patch.object(gate, "measure_coverage", return_value=report):
            with contextlib.redirect_stdout(stdout):
                code = gate.main()
        self.assertEqual(code, 0)
        self.assertIn("100% line coverage", stdout.getvalue())

    def test_main_returns_one_when_a_line_is_uncovered(self):
        report = {"module": (10, [4])}
        stderr = io.StringIO()
        with mock.patch.object(gate, "measure_coverage", return_value=report):
            with contextlib.redirect_stderr(stderr):
                code = gate.main()
        self.assertEqual(code, 1)
        self.assertIn("FAIL", stderr.getvalue())

    def test_main_returns_two_when_measurement_errors(self):
        stderr = io.StringIO()
        with mock.patch.object(gate, "measure_coverage",
                               side_effect=OSError("no trace")):
            with contextlib.redirect_stderr(stderr):
                code = gate.main()
        self.assertEqual(code, 2)
        self.assertIn("ERROR", stderr.getvalue())


if __name__ == "__main__":
    unittest.main()
