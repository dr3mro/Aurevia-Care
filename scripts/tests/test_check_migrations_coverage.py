"""Coverage tests for the OPS-06 migration change-safety gate.

These tests exercise the scanner's escape and end-of-input paths and the
command-line entry point, which the main test module does not reach. Fixtures
are synthetic strings and the checked-in migration set; no network, credentials
or patient data are involved.
"""
import runpy
import sys
import unittest
from unittest.mock import patch

from helpers import ROOT, load

check_migrations = load("check-migrations")


class NoiseScannerEscapeTest(unittest.TestCase):
    """Escape sequences inside quoted regions must not break the scanner."""

    def test_doubled_single_quote_inside_literal_is_blanked(self):
        # SQL escapes a quote inside a literal by doubling it. The scanner must
        # treat both quotes as content, otherwise the literal ends early and a
        # later destructive statement could be scored incorrectly.
        text = "SELECT 'a''b';"
        self.assertEqual(check_migrations.strip_noise(text), "SELECT       ;")

    def test_doubled_double_quote_inside_identifier_is_blanked(self):
        text = 'SELECT "a""b" FROM t;'
        self.assertEqual(check_migrations.strip_noise(text), "SELECT        FROM t;")

    def test_lone_dollar_sign_is_not_a_quote_delimiter(self):
        # DOLLAR_TAG needs an opening and a closing tag. A lone '$' is ordinary
        # text (e.g. a positional parameter) and must pass through unchanged.
        text = "SELECT $1 FROM t;"
        self.assertEqual(check_migrations.strip_noise(text), text)


class NoiseScannerEofTest(unittest.TestCase):
    """An unterminated quoted region is blanked through end of input."""

    def test_unterminated_single_quoted_string_is_blanked_to_eof(self):
        text = "SELECT 'unterminated"
        prefix = "SELECT "
        self.assertEqual(check_migrations.strip_noise(text),
                         prefix + " " * (len(text) - len(prefix)))

    def test_unterminated_quoted_identifier_is_blanked_to_eof(self):
        text = 'SELECT "unterminated'
        prefix = "SELECT "
        self.assertEqual(check_migrations.strip_noise(text),
                         prefix + " " * (len(text) - len(prefix)))

    def test_unterminated_dollar_body_is_blanked_to_eof(self):
        text = "DO $body$\nDELETE FROM t;\nEND;"
        cleaned = check_migrations.strip_noise(text)
        self.assertEqual(cleaned.count("\n"), 2)
        self.assertNotIn("DELETE", cleaned)
        # The destructive token lives inside an unterminated dollar body, so it
        # is invisible to the gate.
        self.assertEqual(check_migrations.destructive_statements(text), [])


class CommandLineEntryTest(unittest.TestCase):
    def test_module_main_exits_with_check_result(self):
        script = ROOT / "scripts" / "check-migrations.py"
        with patch.object(sys, "argv", [str(script)]):
            with self.assertRaises(SystemExit) as caught:
                runpy.run_path(str(script), run_name="__main__")
        self.assertEqual(caught.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
