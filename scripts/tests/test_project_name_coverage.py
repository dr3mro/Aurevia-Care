"""Coverage tests for scripts/project-name.py through its command-line entry point.

The script is a top-level CLI: it parses argv, reads project.json from the
repository root and prints the requested field. Each case below executes the
real script in-process with synthetic argv and a synthetic project.json, so
coverage is measured on scripts/project-name.py itself and no network or real
project data is used.
"""
import io
import json
import runpy
import sys
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path
from unittest.mock import patch

from helpers import ROOT

SCRIPT = ROOT / "scripts" / "project-name.py"


def run_script(field, project):
    """Run the real script with synthetic argv and project data; return (code, stdout, stderr)."""
    payload = json.dumps(project)
    with patch.object(sys, "argv", [str(SCRIPT), field]), \
            patch.object(Path, "read_text", return_value=payload), \
            redirect_stdout(io.StringIO()) as out, \
            redirect_stderr(io.StringIO()) as err:
        try:
            runpy.run_path(str(SCRIPT), run_name="__main__")
            code = 0
        except SystemExit as exc:
            code = exc.code
    return code, out.getvalue(), err.getvalue()


def synthetic_project(**overrides):
    """Build a valid synthetic project identity, optionally replacing fields."""
    project = {"displayName": "Synthetic Clinic", "repositorySlug": "synthetic-clinic"}
    project.update(overrides)
    return project


class ProjectNameSuccessTests(unittest.TestCase):
    def test_display_name_is_printed(self):
        code, out, err = run_script("displayName", synthetic_project())
        self.assertEqual(code, 0)
        self.assertEqual(out, "Synthetic Clinic\n")
        self.assertEqual(err, "")

    def test_repository_slug_is_printed(self):
        code, out, err = run_script("repositorySlug", synthetic_project())
        self.assertEqual(code, 0)
        self.assertEqual(out, "synthetic-clinic\n")
        self.assertEqual(err, "")

    def test_valid_printable_name_with_punctuation(self):
        project = synthetic_project(displayName="Synthetic Clinic — Unit 2")
        code, out, err = run_script("displayName", project)
        self.assertEqual(code, 0)
        self.assertEqual(out, "Synthetic Clinic — Unit 2\n")
        self.assertEqual(err, "")


class ProjectNameDisplayValidationTests(unittest.TestCase):
    def assert_display_rejected(self, project):
        code, out, err = run_script("displayName", project)
        self.assertEqual(code, 2)
        self.assertEqual(out, "")
        self.assertIn("displayName must be a nonempty, single-line printable string", err)

    def test_blank_display_name_is_rejected(self):
        self.assert_display_rejected(synthetic_project(displayName="   "))

    def test_non_string_display_name_is_rejected(self):
        self.assert_display_rejected(synthetic_project(displayName=17))

    def test_missing_display_name_is_rejected(self):
        self.assert_display_rejected({"repositorySlug": "synthetic-clinic"})

    def test_non_printable_display_name_is_rejected(self):
        self.assert_display_rejected(synthetic_project(displayName="Synthetic\nClinic"))


class ProjectNameSlugValidationTests(unittest.TestCase):
    def assert_slug_rejected(self, project):
        code, out, err = run_script("repositorySlug", project)
        self.assertEqual(code, 2)
        self.assertEqual(out, "")
        self.assertIn("repositorySlug must be lowercase kebab-case", err)

    def test_uppercase_slug_is_rejected(self):
        self.assert_slug_rejected(synthetic_project(repositorySlug="Synthetic-Clinic"))

    def test_underscore_slug_is_rejected(self):
        self.assert_slug_rejected(synthetic_project(repositorySlug="synthetic_clinic"))

    def test_non_string_slug_is_rejected(self):
        self.assert_slug_rejected(synthetic_project(repositorySlug=None))

    def test_missing_slug_is_rejected(self):
        self.assert_slug_rejected({"displayName": "Synthetic Clinic"})


class ProjectNameRobustnessTests(unittest.TestCase):
    def test_malformed_project_json_propagates_decode_error(self):
        with patch.object(sys, "argv", [str(SCRIPT), "displayName"]), \
                patch.object(Path, "read_text", return_value="{not json"), \
                redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
            with self.assertRaises(json.JSONDecodeError):
                runpy.run_path(str(SCRIPT), run_name="__main__")

    def test_additional_invalid_slugs_are_rejected(self):
        for slug in ("", "-leading-hyphen", "trailing-hyphen-", "double--hyphen", "café"):
            with self.subTest(slug=slug):
                code, out, err = run_script("repositorySlug", synthetic_project(repositorySlug=slug))
                self.assertEqual(code, 2)
                self.assertEqual(out, "")
                self.assertIn("repositorySlug must be lowercase kebab-case", err)


class ProjectNameArgumentTests(unittest.TestCase):
    def test_unknown_field_is_rejected_before_reading_project(self):
        with patch.object(Path, "read_text", side_effect=AssertionError("must not read")):
            with patch.object(sys, "argv", [str(SCRIPT), "repositorySlugX"]), \
                    redirect_stderr(io.StringIO()) as err:
                with self.assertRaises(SystemExit) as caught:
                    runpy.run_path(str(SCRIPT), run_name="__main__")
        self.assertEqual(caught.exception.code, 2)
        self.assertIn("invalid choice", err.getvalue())


if __name__ == "__main__":
    unittest.main()
