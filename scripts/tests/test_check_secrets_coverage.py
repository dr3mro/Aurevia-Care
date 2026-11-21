"""Line and branch coverage for the CLI entry points of scripts/check-secrets.py.

The repository-wide suite reaches the scanner functions but never invokes
``main()`` or runs the module as a script, leaving lines 64-67 and 71 and the
arcs out of the ``for`` loop and the ``__main__`` guard uncovered. These tests
drive those entry points with synthetic findings and a mocked ``subprocess``
so no network or real credential is ever touched.

Public entry points exercised:
  * ``check_secrets.main`` - return code and stderr for empty and non-empty
    findings (both outcomes of the ``1 if findings else 0`` expression and
    both arcs of the reporting loop).
  * the ``if __name__ == "__main__"`` guard - executed through ``runpy`` with
    ``run_name="__main__"`` so ``sys.exit(main())`` actually runs.
"""
import contextlib
import io
import runpy
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT, load

secrets = load("check-secrets")


class MainCoverageTests(unittest.TestCase):
    """Cover ``main`` and the module ``__main__`` guard (script lines 63-71)."""

    def run_main(self, findings):
        """Call ``main`` with a controlled ``scan`` result; return (rc, stderr)."""
        stderr = io.StringIO()
        with mock.patch.object(secrets, "scan", return_value=findings):
            with contextlib.redirect_stderr(stderr):
                return_code = secrets.main()
        return return_code, stderr.getvalue()

    def test_main_with_no_findings_reports_nothing_and_returns_zero(self):
        # Empty loop: arc 65 -> 67 (skip the loop body) and the "else 0" arm.
        return_code, stderr = self.run_main([])
        self.assertEqual(return_code, 0)
        self.assertEqual(stderr, "")

    def test_main_with_many_findings_prints_each_and_returns_one(self):
        # Populated loop: arc 65 -> 66 and the "1" arm; several iterations.
        findings = [
            (Path("fixture/keys.txt"), 3, "private key"),
            (Path("fixture/config.txt"), 41, "password assignment"),
        ]
        return_code, stderr = self.run_main(findings)
        self.assertEqual(return_code, 1)
        self.assertIn("check-secrets: fixture/keys.txt:3: possible private key", stderr)
        self.assertIn(
            "check-secrets: fixture/config.txt:41: possible password assignment",
            stderr,
        )
        self.assertEqual(stderr.count("check-secrets:"), 2)

    def test_main_with_single_finding_prints_one_line(self):
        # The reporting loop body runs for a one-element iterable as well.
        return_code, stderr = self.run_main([(Path("one.txt"), 7, "GitHub token")])
        self.assertEqual(return_code, 1)
        self.assertEqual(
            stderr, "check-secrets: one.txt:7: possible GitHub token\n")

    def test_module_main_guard_exits_through_sys_exit(self):
        """Cover line 70's true arc and line 71 by executing the script body."""
        script = ROOT / "scripts" / "check-secrets.py"
        # A clean, empty file list makes scan() return [] without touching the
        # real checkout or the network. candidate_files uses subprocess.run.
        empty_listing = mock.Mock(stdout=b"")
        with mock.patch("subprocess.run", return_value=empty_listing) as run:
            with contextlib.redirect_stderr(io.StringIO()):
                with self.assertRaises(SystemExit) as raised:
                    runpy.run_path(str(script), run_name="__main__")
        self.assertEqual(raised.exception.code, 0)
        run.assert_called_once()


if __name__ == "__main__":
    unittest.main()
