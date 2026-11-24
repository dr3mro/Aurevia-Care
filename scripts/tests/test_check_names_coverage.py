"""Coverage-focused tests for ``scripts/check-names.py``.

The behavioural tests in ``test_check_names.py`` exercise the normal validation
path. This module covers the remaining failure and entry-point paths: the
``tracked_files`` fallback when git is unavailable, an unreadable Java source,
the ``main`` reporting loop, and the command-line ``__main__`` guard.

All fixtures are synthetic temporary directories. ``subprocess`` is mocked and
the network is never used.
"""
import contextlib
import importlib.util
import io
import os
import runpy
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).parents[1]
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))
SCRIPT = SCRIPTS / "check-names.py"
spec = importlib.util.spec_from_file_location("check_names_cov", SCRIPT)
check_names = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_names)


class TrackedFilesFallbackTest(unittest.TestCase):
    """The os.walk fallback runs when git cannot list tracked files."""

    def test_git_oserror_falls_back_to_walking_the_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "apps" / "patient").mkdir(parents=True)
            (root / "apps" / "patient" / "Api.java").write_text("class Api {}", encoding="utf-8")
            (root / "node_modules").mkdir()
            (root / "node_modules" / "ignored.txt").write_text("x", encoding="utf-8")
            with mock.patch.object(check_names.subprocess, "run", side_effect=OSError("git missing")):
                found = check_names.tracked_files(tmp)
        self.assertIn("apps/patient/Api.java", found)
        self.assertNotIn("node_modules/ignored.txt", found)

    def test_git_called_process_error_also_uses_fallback(self):
        error = subprocess.CalledProcessError(128, ["git", "ls-files"])
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "README.md").write_text("x", encoding="utf-8")
            with mock.patch.object(check_names.subprocess, "run", side_effect=error):
                found = check_names.tracked_files(tmp)
        self.assertEqual(found, ["README.md"])

    def test_violations_uses_fallback_when_git_is_unavailable(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "x_04").mkdir()
            Path(tmp, "x_04", "A.java").write_text("x", encoding="utf-8")
            with mock.patch.object(check_names.subprocess, "run", side_effect=OSError("no git")):
                found = check_names.violations(tmp)
        self.assertEqual(found, ["x_04"])


class JavaReadFailureTest(unittest.TestCase):
    """A Java source that cannot be read is skipped, not a hard failure."""

    def test_unreadable_java_file_is_skipped(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "src").mkdir()
            Path(tmp, "src", "Foo.java").write_text("package x_09;", encoding="utf-8")
            with mock.patch.object(check_names.Path, "read_text", side_effect=OSError("unreadable")):
                found = check_names.violations(tmp, ["src/Foo.java"], set())
        self.assertEqual(found, [])


class MainTest(unittest.TestCase):
    """``main`` reports each violation and returns a shell exit status."""

    def test_main_reports_violations_and_returns_one(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "x_04").mkdir()
            (root / "x_04" / "A.java").write_text("x", encoding="utf-8")
            (root / "dlv_02").mkdir()
            (root / "dlv_02" / "B.java").write_text("x", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = check_names.main(tmp)
        self.assertEqual(code, 1)
        self.assertIn("x_04", stderr.getvalue())
        self.assertIn("dlv_02", stderr.getvalue())

    def test_main_returns_zero_for_clean_tree(self):
        with tempfile.TemporaryDirectory() as tmp:
            Path(tmp, "README.md").write_text("x", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = check_names.main(tmp)
        self.assertEqual(code, 0)
        self.assertEqual(stderr.getvalue(), "")


class CommandLineTest(unittest.TestCase):
    """The ``__main__`` guard maps the root argument to an exit status."""

    def _run_script(self, argv):
        stderr = io.StringIO()
        with mock.patch.object(sys, "argv", argv), contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as raised:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        return raised.exception.code, stderr.getvalue()

    def test_cli_with_argument_exits_with_violation_status(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "cw_004").mkdir()
            Path(tmp, "cw_004", "A.java").write_text("x", encoding="utf-8")
            code, stderr = self._run_script(["check-names.py", tmp])
        self.assertEqual(code, 1)
        self.assertIn("cw_004", stderr)

    def test_cli_without_argument_defaults_to_current_directory(self):
        repo_root = str(Path(__file__).parents[2])
        original = os.getcwd()
        try:
            os.chdir(repo_root)
            code, stderr = self._run_script(["check-names.py"])
        finally:
            os.chdir(original)
        self.assertEqual(code, 0)
        self.assertEqual(stderr, "")


if __name__ == "__main__":
    unittest.main()
