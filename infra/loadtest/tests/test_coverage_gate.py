"""Prove the build-failing coverage gate can detect an uncovered line.

These tests drive the gate's real ``trace --count --missing`` command against a
deliberately under-covered probe module, so removing ``--missing`` or weakening
the command fails a test. The probe uses only stdlib and a temporary directory.
"""
import tempfile
import unittest
from pathlib import Path

import coverage_gate as cg


class CoverageGateTest(unittest.TestCase):
    def test_trace_marker_is_detected_only_at_line_start(self):
        text = "    x = 1\n>>>>>>     y = 2\n    print('>>>>>> ')\n"
        self.assertEqual(cg.find_uncovered_lines(text), [2])

    def test_missing_flag_and_command_are_exercised_on_a_probe(self):
        with tempfile.TemporaryDirectory() as directory:
            probe_dir = Path(directory) / "probe"
            probe_dir.mkdir()
            (probe_dir / "probe_module.py").write_text(
                "def covered():\n    return 1\n\n\ndef uncovered():\n    return 2\n",
                encoding="utf-8")
            runner = probe_dir / "run_probe.py"
            runner.write_text(
                f"import sys\nsys.path.insert(0, {str(probe_dir)!r})\n"
                "import probe_module\nprobe_module.covered()\n", encoding="utf-8")
            with tempfile.TemporaryDirectory(prefix="probe_cov_") as cover_dir:
                result = cg.run_traced_suite(cover_dir, target=runner)
                self.assertEqual(result.returncode, 0, result.stderr)
                cover = cg.find_cover_file(cover_dir, "probe_module")
                marked = cg.find_uncovered_lines(cover.read_text(encoding="utf-8"))
        self.assertTrue(marked)

    def test_find_cover_file_rejects_ambiguous_or_missing_matches(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(RuntimeError):
                cg.find_cover_file(directory, "absent")
            Path(directory, "a.load_generator.cover").write_text("x", encoding="utf-8")
            Path(directory, "b.load_generator.cover").write_text("x", encoding="utf-8")
            with self.assertRaises(RuntimeError):
                cg.find_cover_file(directory, "load_generator")


if __name__ == "__main__":
    unittest.main()
