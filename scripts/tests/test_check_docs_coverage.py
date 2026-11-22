"""Coverage tests for the check-docs command-line entry point.

The repository, identification and structural rules of ``scripts/check-docs.py``
are already exercised by ``test_check_docs.py``. These tests reach the two
remaining public surfaces: the ``main`` reporting function and the module's
``__main__`` guard. Both are driven with synthetic error lists or the real
repository check result, in-process, so no network or external process is used.
"""
import io
import runpy
import unittest
from contextlib import redirect_stderr
from unittest.mock import patch

from helpers import ROOT, load

docs = load("check-docs")
SCRIPT = ROOT / "scripts" / "check-docs.py"


class MainTests(unittest.TestCase):
    """The CLI reporting contract: print each error and map errors to status."""

    def test_clean_check_returns_zero_and_prints_nothing(self):
        with patch.object(docs, "check", return_value=[]) as check:
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                status = docs.main()
        self.assertEqual(status, 0)
        self.assertEqual(stderr.getvalue(), "")
        check.assert_called_once_with()

    def test_each_error_is_printed_and_status_is_one(self):
        errors = ["A.md: broken link missing.md", "B.md: unknown requirement ID ZZ-99"]
        with patch.object(docs, "check", return_value=errors):
            stderr = io.StringIO()
            with redirect_stderr(stderr):
                status = docs.main()
        self.assertEqual(status, 1)
        self.assertEqual(
            stderr.getvalue(),
            "check-docs: A.md: broken link missing.md\n"
            "check-docs: B.md: unknown requirement ID ZZ-99\n",
        )

    def test_module_guard_exits_with_status_matching_reported_errors(self):
        # Running the file as __main__ covers the command-line entry point.
        # The status is asserted against what was actually reported so the
        # result stays correct whether or not the working tree has doc errors.
        stderr = io.StringIO()
        with redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as raised:
                runpy.run_path(str(SCRIPT), run_name="__main__")
        reported = stderr.getvalue()
        self.assertIn(raised.exception.code, (0, 1))
        self.assertEqual(raised.exception.code, 1 if reported else 0)


if __name__ == "__main__":
    unittest.main()
