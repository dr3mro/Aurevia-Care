"""Executable tests for the recovery-drill build-failing coverage gate.

These pin the marker parser, cover-file selection, production-module discovery
and the exact ``trace --count --missing`` command path so the gate cannot pass
vacuously. All inputs are synthetic; no live system or real data is used.
"""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parent / "coverage_gate.py"
SPEC = importlib.util.spec_from_file_location("coverage_gate", MODULE_PATH)
coverage_gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(coverage_gate)

cg = coverage_gate


class FindUncoveredLinesTest(unittest.TestCase):
    def test_executed_lines_are_not_uncovered(self):
        text = ("    1: \"\"\"doc\"\"\"\n"
                "    2: def f():\n"
                "    3:     return 1\n")
        self.assertEqual(cg.find_uncovered_lines(text), [])

    def test_trace_marker_is_detected(self):
        text = ("    1: def f():\n"
                ">>>>>> 2:     return 1\n"
                "    3: pass\n")
        self.assertEqual(cg.find_uncovered_lines(text), [2])

    def test_marker_must_start_the_line(self):
        text = "  1: value = '>>>>>> not a marker'\n"
        self.assertEqual(cg.find_uncovered_lines(text), [])

    def test_empty_cover_text_has_no_uncovered_lines(self):
        self.assertEqual(cg.find_uncovered_lines(""), [])


class ProductionModulesTest(unittest.TestCase):
    def test_drill_is_a_measured_production_module(self):
        names = {path.name for path in cg.production_modules()}
        self.assertIn("restore_drill.py", names)

    def test_test_utilities_are_not_production_modules(self):
        names = {path.name for path in cg.production_modules()}
        self.assertNotIn("test_restore_drill.py", names)
        self.assertNotIn("coverage_gate.py", names)


class FindCoverFileTest(unittest.TestCase):
    def test_selects_drill_cover_not_test_module_cover(self):
        with tempfile.TemporaryDirectory(prefix="restore_drill_files_") as directory:
            root = Path(directory)
            (root / "test_restore_drill.cover").write_text("test", encoding="utf-8")
            drill_cover = root / "restore_drill.cover"
            drill_cover.write_text("drill", encoding="utf-8")
            self.assertEqual(cg.find_cover_file(root, "restore_drill"), drill_cover)

    def test_raises_when_no_drill_cover_exists(self):
        with tempfile.TemporaryDirectory(prefix="restore_drill_files_") as directory:
            with self.assertRaises(RuntimeError):
                cg.find_cover_file(directory, "restore_drill")

    def test_raises_on_ambiguous_cover_files(self):
        with tempfile.TemporaryDirectory(prefix="restore_drill_files_") as directory:
            root = Path(directory)
            (root / "a.restore_drill.cover").write_text("a", encoding="utf-8")
            (root / "b.restore_drill.cover").write_text("b", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                cg.find_cover_file(root, "restore_drill")


class CoverageGateDecisionTest(unittest.TestCase):
    def test_main_passes_when_every_line_is_covered(self):
        with mock.patch.object(cg, "measure_coverage",
                               return_value={"restore_drill": (10, [])}), \
                mock.patch("sys.stdout"):
            self.assertEqual(cg.main(), 0)

    def test_main_fails_when_any_line_is_uncovered(self):
        with mock.patch.object(cg, "measure_coverage",
                               return_value={"restore_drill": (10, [3, 7])}), \
                mock.patch("sys.stderr"):
            self.assertEqual(cg.main(), 1)

    def test_main_errors_when_coverage_cannot_be_measured(self):
        with mock.patch.object(cg, "measure_coverage",
                               side_effect=RuntimeError("no cover file")), \
                mock.patch("sys.stderr"):
            self.assertEqual(cg.main(), 2)

    def test_main_errors_on_subprocess_failure(self):
        with mock.patch.object(cg, "measure_coverage",
                               side_effect=subprocess.TimeoutExpired("trace", 1)), \
                mock.patch("sys.stderr"):
            self.assertEqual(cg.main(), 2)


class MeasureCoverageTest(unittest.TestCase):
    def test_raises_when_traced_suite_fails(self):
        failed = mock.Mock(returncode=1, stderr="suite failed", stdout="")
        with mock.patch.object(cg, "run_traced_suite", return_value=failed):
            with self.assertRaises(RuntimeError):
                cg.measure_coverage()


class TraceIntegrationTest(unittest.TestCase):
    def _trace_probe(self, directory):
        root = Path(directory)
        probe = root / "restore_drill.py"
        probe.write_text(
            "value = 1\n"
            "if value > 100:\n"
            "    reported = value * 2\n"
            "    ignored = value * 3  #pragma NO COVER\n"
            "print(value)\n",
            encoding="utf-8",
        )
        runner = root / "run_probe.py"
        runner.write_text(
            "import importlib.util\n"
            f"spec = importlib.util.spec_from_file_location('probe', {str(probe)!r})\n"
            "module = importlib.util.module_from_spec(spec)\n"
            "spec.loader.exec_module(module)\n",
            encoding="utf-8",
        )
        cover_dir = root / "cov"
        result = cg.run_traced_suite(cover_dir, target=runner)
        self.assertEqual(result.returncode, 0, result.stderr)
        cover = cg.find_cover_file(cover_dir, "restore_drill")
        return cg.find_uncovered_lines(cover.read_text(encoding="utf-8"))

    def test_production_trace_detects_uncovered_line(self):
        with tempfile.TemporaryDirectory(prefix="restore_drill_probe_") as directory:
            self.assertEqual(self._trace_probe(directory), [3])

    def test_pragma_mechanism_is_honoured_on_this_interpreter(self):
        with tempfile.TemporaryDirectory(prefix="restore_drill_probe_") as directory:
            self.assertNotIn(4, self._trace_probe(directory))


if __name__ == "__main__":
    unittest.main()
