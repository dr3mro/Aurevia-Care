import contextlib
import io
import runpy
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

verdicts = load("check-verdicts")

GOOD = """# Notes
## Summary
x
## Review verdicts
- smartclinic-code-reviewer: GO
- smartclinic-coverage-auditor: GO
- `smartclinic-devsecops` — GO
Optional: rename a helper
## Test evidence
done
"""


class ParsingTests(unittest.TestCase):
    def test_the_section_ends_at_the_next_heading_or_the_end(self):
        self.assertIn("smartclinic-devsecops", verdicts.review_section(GOOD))
        self.assertNotIn("Test evidence", verdicts.review_section(GOOD))
        self.assertEqual(verdicts.review_section("## review verdicts\n- a: GO\n"), "\n- a: GO\n")
        self.assertIsNone(verdicts.review_section("## Summary\nx\n"))

    def test_verdicts_and_unresolved_findings_are_read(self):
        section = ("- smartclinic-code-reviewer: go\n* smartclinic-x - NO-GO\n"
                   "Critical: injection in Foo\nRequired: add a test (resolved in abc)\n"
                   "Optional: style\nplain line\n")
        found, findings = verdicts.verdicts_and_findings(section)
        self.assertEqual(found, {"smartclinic-code-reviewer": "GO", "smartclinic-x": "NO-GO"})
        self.assertEqual(findings, ["Critical: injection in Foo"])

    def test_the_word_unresolved_does_not_count_as_resolved(self):
        section = ("Required: the false side of the CAS is unresolved\n"
                   "Required: add a missing negative case (Resolved in abc123)\n")
        _, findings = verdicts.verdicts_and_findings(section)
        self.assertEqual(findings, ["Required: the false side of the CAS is unresolved"])


class ProblemTests(unittest.TestCase):
    def test_a_clean_set_of_verdicts_passes(self):
        self.assertEqual(verdicts.problems(GOOD, verdicts.REQUIRED), [])

    def test_a_missing_section_fails_closed(self):
        self.assertEqual(verdicts.problems("nothing", verdicts.REQUIRED),
                         ['the notes have no "## Review verdicts" section'])

    def test_a_missing_reviewer_a_no_go_and_an_open_finding_each_fail(self):
        text = ("## Review verdicts\n- smartclinic-code-reviewer: NO-GO\n"
                "- smartclinic-coverage-auditor: GO\nRequired: the false side has no test\n")
        found = verdicts.problems(text, verdicts.REQUIRED)
        self.assertEqual(len(found), 3)
        self.assertIn("smartclinic-code-reviewer answered NO-GO", found[0])
        self.assertIn("no verdict from smartclinic-devsecops", found[1])
        self.assertIn("unresolved finding: Required: the false side", found[2])


class MainTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: shutil.rmtree(self.root))
        self.git("init", "-q", "-b", "main")
        (self.root / "a.py").write_text("x = 1\n")
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "base")

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True)

    def run_main(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = verdicts.main(list(args), root=self.root)
        return status, out.getvalue(), err.getvalue()

    def notes(self, text):
        path = self.root / "notes.txt"
        path.write_text(text)
        return str(path)

    def test_clean_notes_pass_and_bad_notes_fail(self):
        self.assertEqual(self.run_main(self.notes(GOOD))[0], 0)
        status, _, err = self.run_main(self.notes("## Review verdicts\n"))
        self.assertEqual(status, 1)
        self.assertIn("verdicts: no verdict from smartclinic-code-reviewer", err)

    def test_an_unreadable_notes_file_fails(self):
        status, _, err = self.run_main(str(self.root / "missing.md"))
        self.assertEqual(status, 1)
        self.assertIn("cannot read the notes file", err)

    def test_required_reviewers_can_be_chosen(self):
        notes = self.notes("## Review verdicts\n- smartclinic-qa-reviewer: GO\n")
        self.assertEqual(self.run_main(notes, "--require", "smartclinic-qa-reviewer, ,")[0], 0)

    def test_a_documentation_only_change_needs_no_review(self):
        (self.root / "docs").mkdir()
        (self.root / "docs/x.txt").write_text("y\n")
        (self.root / "README.md").write_text("z\n")
        status, out, _ = self.run_main(str(self.root / "missing.md"), "--base", "HEAD")
        self.assertEqual((status, "only documentation changed" in out), (0, True))

    def test_a_code_change_is_reviewed(self):
        (self.root / "a.py").write_text("x = 2\n")
        status, _, err = self.run_main(self.notes("no section"), "--base", "HEAD")
        self.assertEqual(status, 1)
        self.assertIn("no \"## Review verdicts\" section", err)

    def test_an_unknown_base_falls_back_to_checking_the_notes(self):
        self.assertEqual(self.run_main(self.notes(GOOD), "--base", "nowhere")[0], 0)
        self.assertEqual(self.run_main(self.notes("x"), "--base", "nowhere")[0], 1)


class CommandLineTests(unittest.TestCase):
    def test_the_script_exits_with_the_status_of_main(self):
        script = Path(__file__).resolve().parents[1] / "check-verdicts.py"
        err = io.StringIO()
        with mock.patch.object(sys, "argv", [str(script), "/no/such/notes.md"]), \
                contextlib.redirect_stderr(err), self.assertRaises(SystemExit) as raised:
            runpy.run_path(str(script), run_name="__main__")
        self.assertEqual(raised.exception.code, 1)


if __name__ == "__main__":
    unittest.main()
