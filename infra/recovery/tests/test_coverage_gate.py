"""Executable acceptance tests for the S-G5-06 build-failing coverage gate.

The quality bar requires 100% line coverage with a build-failing gate. These
tests pin the marker parser, cover-file selection, production-module discovery
and the exact ``trace --count --missing`` command path. All inputs are
synthetic; no live system or real data is used.
"""
import importlib.util
import subprocess
import sys
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
        text = (
            "    1: \"\"\"module docstring\"\"\"\n"
            "    2: def f():\n"
            "    3:     return 1\n"
        )
        self.assertEqual(cg.find_uncovered_lines(text), [])

    def test_trace_marker_is_detected(self):
        text = (
            "    1: def f():\n"
            ">>>>>> 2:     return 1\n"
            "    3: pass\n"
        )
        self.assertEqual(cg.find_uncovered_lines(text), [2])

    def test_multiple_markers_are_all_detected(self):
        text = (
            ">>>>>> 1: a = 1\n"
            "    2: b = 2\n"
            ">>>>>> 3: c = 3\n"
        )
        self.assertEqual(cg.find_uncovered_lines(text), [1, 3])

    def test_marker_must_start_the_line(self):
        # A count column that merely contains the marker characters is not a
        # never-executed mark; trace always prefixes the line with ">>>>>> ".
        text = "  1: value = '>>>>>> not a marker'\n"
        self.assertEqual(cg.find_uncovered_lines(text), [])

    def test_empty_cover_text_has_no_uncovered_lines(self):
        self.assertEqual(cg.find_uncovered_lines(""), [])


class ProductionModulesTest(unittest.TestCase):
    def test_evaluator_is_a_measured_production_module(self):
        names = {path.name for path in cg.production_modules()}
        self.assertIn("recovery_evidence.py", names)

    def test_test_utilities_are_not_production_modules(self):
        names = {path.name for path in cg.production_modules()}
        self.assertNotIn("test_coverage_gate.py", names)
        self.assertNotIn("coverage_gate.py", names)


class FindCoverFileTest(unittest.TestCase):
    def test_selects_evaluator_cover_not_test_module_cover(self):
        with tempfile.TemporaryDirectory(prefix="recovery_files_") as directory:
            root = Path(directory)
            (root / "test_recovery_evidence.cover").write_text("test", encoding="utf-8")
            evaluator = root / "__w.infra.recovery.recovery_evidence.cover"
            evaluator.write_text("evaluator", encoding="utf-8")
            self.assertEqual(
                cg.find_cover_file(root, "recovery_evidence"), evaluator
            )

    def test_accepts_a_bare_cover_filename(self):
        with tempfile.TemporaryDirectory(prefix="recovery_files_") as directory:
            root = Path(directory)
            evaluator = root / "recovery_evidence.cover"
            evaluator.write_text("evaluator", encoding="utf-8")
            self.assertEqual(
                cg.find_cover_file(root, "recovery_evidence"), evaluator
            )

    def test_raises_when_no_evaluator_cover_exists(self):
        with tempfile.TemporaryDirectory(prefix="recovery_files_") as directory:
            root = Path(directory)
            (root / "test_recovery_evidence.cover").write_text("test", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                cg.find_cover_file(root, "recovery_evidence")

    def test_raises_on_ambiguous_cover_files(self):
        with tempfile.TemporaryDirectory(prefix="recovery_files_") as directory:
            root = Path(directory)
            (root / "a.recovery_evidence.cover").write_text("a", encoding="utf-8")
            (root / "b.recovery_evidence.cover").write_text("b", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                cg.find_cover_file(root, "recovery_evidence")


class CoverageGateDecisionTest(unittest.TestCase):
    def test_main_passes_when_every_line_is_covered(self):
        report = {"recovery_evidence": (10, [])}
        with mock.patch.object(cg, "measure_coverage", return_value=report), \
                mock.patch("sys.stdout"):
            self.assertEqual(cg.main(), 0)

    def test_main_fails_when_any_line_is_uncovered(self):
        report = {"recovery_evidence": (10, [3, 7])}
        with mock.patch.object(cg, "measure_coverage", return_value=report), \
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


class TraceIntegrationTest(unittest.TestCase):
    def _trace_probe(self, directory):
        """Trace a synthetic probe through the production command path."""
        root = Path(directory)
        probe = root / "recovery_evidence.py"
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
        # Drive the exact production command: this pins --missing. Removing
        # the flag from run_traced_suite makes this test fail.
        result = cg.run_traced_suite(cover_dir, target=runner)
        self.assertEqual(result.returncode, 0, result.stderr)
        cover = cg.find_cover_file(cover_dir, "recovery_evidence")
        return cg.find_uncovered_lines(cover.read_text(encoding="utf-8"))

    def test_production_trace_detects_uncovered_line(self):
        with tempfile.TemporaryDirectory(prefix="recovery_probe_") as directory:
            # Line 3 is unexecuted and unannotated -> reported.
            # Line 4 is unexecuted but carries trace's pragma -> ignored.
            self.assertEqual(self._trace_probe(directory), [3])

    def test_pragma_mechanism_is_honoured_on_this_interpreter(self):
        with tempfile.TemporaryDirectory(prefix="recovery_probe_") as directory:
            uncovered = self._trace_probe(directory)
            self.assertNotIn(4, uncovered)


if __name__ == "__main__":
    unittest.main()
