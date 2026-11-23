"""Scope and structured hand-off validation for the pinned worker candidate.

All fixtures are synthetic: no real notes, commits or GitHub state are used.
"""
import importlib.util
import io
import os
import runpy
import sys
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest import mock

SCRIPTS = Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location("worker_gate", SCRIPTS / "worker_gate.py")
worker_gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker_gate)

NOTES = ".agent/pr-notes/WQUALITY-03-20261121-153620.md"
SCOPE_TEXT = ("Scope (stay inside these paths): scripts/worker_gate.py, "
              "scripts/tests/test_worker_gate.py")
CHANGED = ["scripts/worker_gate.py", "scripts/tests/test_worker_gate.py", NOTES]


def notes_text(finished=True, missing=(), empty=(), placeholder=()):
    """Synthetic hand-off notes; the named sections are broken on request."""
    sections = {
        "Test evidence": "python3 -I -m unittest discover -s scripts/tests -q -> OK (12 tests)",
        "Coverage": "python3 -m trace --count worker_gate.py -> 100% lines",
        "Findings": "No defects found.",
    }
    parts = ["# Worker notes", ""]
    for heading, body in sections.items():
        if heading in missing:
            continue
        parts += [f"## {heading}", "" if heading in empty else
                  ("see <task-id> for details" if heading in placeholder else body), ""]
    if not finished and "Why this is not finished" not in missing:
        parts += ["## Why this is not finished",
                  "" if "Why this is not finished" in empty else
                  ("<reason>" if "Why this is not finished" in placeholder else
                   "The coverage tool is unavailable in this image."), ""]
    return "\n".join(parts)


class ParseScopeTest(unittest.TestCase):
    def test_parses_comma_separated_scopes(self):
        self.assertEqual(worker_gate.parse_scope(SCOPE_TEXT),
                         ["scripts/worker_gate.py", "scripts/tests/test_worker_gate.py"])

    def test_returns_empty_when_no_scope_line(self):
        self.assertEqual(worker_gate.parse_scope("Task: something\nScope is not declared here"), [])

    def test_returns_empty_for_none(self):
        self.assertEqual(worker_gate.parse_scope(None), [])


class PathRulesTest(unittest.TestCase):
    def test_worker_notes_are_recognised(self):
        for path in (NOTES, ".agent/pr-notes/FIX-123-20261121-010101.md"):
            with self.subTest(path=path):
                self.assertTrue(worker_gate.is_notes_file(path))

    def test_non_notes_paths_are_rejected(self):
        for path in ("scripts/worker_gate.py", ".agent/pr-notes.md",
                     ".agent/pr-notes/notes.txt", "docs/swarm/WQUALITY-03.md"):
            with self.subTest(path=path):
                self.assertFalse(worker_gate.is_notes_file(path))

    def test_under_scope_matches_files_and_directories(self):
        scope = ["scripts/worker_gate.py", "apps/api/src/main"]
        self.assertTrue(worker_gate.under_scope("scripts/worker_gate.py", scope))
        self.assertTrue(worker_gate.under_scope("apps/api/src/main/a/Foo.java", scope))
        self.assertFalse(worker_gate.under_scope("scripts/other.py", scope))
        self.assertFalse(worker_gate.under_scope("apps/api/src/test/a/FooTest.java", scope))
        self.assertFalse(worker_gate.under_scope("anything", []))


class NotesParsingTest(unittest.TestCase):
    def test_section_body_returns_content_until_next_heading(self):
        result = worker_gate.section_body(notes_text(), "Test evidence")
        self.assertIn("unittest discover", result)
        self.assertNotIn("## Coverage", result)

    def test_section_body_is_case_insensitive(self):
        self.assertIn("unittest discover", worker_gate.section_body(notes_text(), "test evidence"))

    def test_section_body_returns_none_when_missing(self):
        self.assertIsNone(worker_gate.section_body(notes_text(), "Why this is not finished"))

    def test_section_body_returns_empty_for_empty_section(self):
        self.assertEqual(worker_gate.section_body(notes_text(empty=["Coverage"]), "Coverage"), "")

    def test_section_body_handles_absent_notes(self):
        self.assertIsNone(worker_gate.section_body(None, "Coverage"))
        self.assertIsNone(worker_gate.section_body("", "Coverage"))

    def test_placeholder_detection(self):
        self.assertTrue(worker_gate.has_placeholder("see <task-id>"))
        self.assertTrue(worker_gate.has_placeholder("<...>"))
        self.assertFalse(worker_gate.has_placeholder("a < b and c > d"))
        self.assertFalse(worker_gate.has_placeholder("all done"))


class CandidateValidationTest(unittest.TestCase):
    def validate(self, changed=CHANGED, scope=None, notes=None, notes_path=NOTES,
                 finished=True, candidate_sha="abc123", head_sha="abc123"):
        if scope is None:
            scope = worker_gate.parse_scope(SCOPE_TEXT)
        if notes is None:
            notes = notes_text(finished)
        return worker_gate.validate_candidate(changed, scope, notes, notes_path, finished,
                                              candidate_sha, head_sha)

    def test_clean_candidate_passes(self):
        self.assertEqual(self.validate(), [])

    def test_clean_unfinished_candidate_passes(self):
        self.assertEqual(self.validate(notes=notes_text(False), finished=False), [])

    def test_file_outside_scope_is_reported(self):
        out = self.validate(changed=CHANGED + ["scripts/other.py"])
        self.assertIn({"file": "scripts/other.py", "rule": "scope"}, out)

    def test_worker_notes_are_allowed_outside_scope(self):
        # NOTES is not under either scope prefix, yet a clean candidate passes.
        self.assertEqual(self.validate(), [])

    def test_every_tracker_file_is_reported(self):
        for name in worker_gate.TRACKER_FILES:
            with self.subTest(name=name):
                out = self.validate(changed=[name])
                self.assertEqual(out, [{"file": name, "rule": "tracker"}])

    def test_tracker_rule_wins_over_scope(self):
        self.assertEqual(self.validate(changed=["TODO.md"]),
                         [{"file": "TODO.md", "rule": "tracker"}])

    def test_missing_heading_is_reported(self):
        out = self.validate(notes=notes_text(missing=["Coverage"]))
        self.assertIn({"file": NOTES, "rule": "notes-heading"}, out)

    def test_empty_section_is_reported(self):
        out = self.validate(notes=notes_text(empty=["Test evidence"]))
        self.assertIn({"file": NOTES, "rule": "notes-empty"}, out)

    def test_placeholder_is_reported(self):
        out = self.validate(notes=notes_text(placeholder=["Coverage"]))
        self.assertIn({"file": NOTES, "rule": "notes-placeholder"}, out)

    def test_unfinished_without_reason_is_reported(self):
        notes = "\n".join(line for line in notes_text(True).splitlines()
                          if not line.startswith("## Findings") and line != "No defects found.")
        out = self.validate(notes=notes, finished=False)
        self.assertIn({"file": NOTES, "rule": "notes-unfinished"}, out)

    def test_finished_candidate_does_not_need_reason(self):
        self.assertEqual(self.validate(notes=notes_text(True), finished=True), [])

    def test_missing_notes_file_reports_missing_headings(self):
        out = worker_gate.validate_candidate(CHANGED, worker_gate.parse_scope(SCOPE_TEXT),
                                             None, NOTES, True, "abc123", "abc123")
        self.assertIn({"file": NOTES, "rule": "notes-heading"}, out)

    def test_candidate_sha_mismatch_is_reported(self):
        out = self.validate(candidate_sha="abc123", head_sha="def456")
        self.assertEqual(out, [{"file": "", "rule": "candidate-changed"}])

    def test_candidate_sha_absent_skips_the_check(self):
        self.assertEqual(self.validate(candidate_sha=None, head_sha="def456"), [])

    def test_empty_head_sha_is_reported(self):
        out = self.validate(candidate_sha="abc123", head_sha="")
        self.assertEqual(out, [{"file": "", "rule": "candidate-changed"}])

    def test_empty_candidate_sha_is_reported(self):
        out = self.validate(candidate_sha="", head_sha="abc123")
        self.assertEqual(out, [{"file": "", "rule": "candidate-changed"}])

    def test_all_violations_are_returned_together(self):
        out = self.validate(changed=["TODO.md", "scripts/other.py"],
                            notes=notes_text(missing=["Coverage"]))
        self.assertEqual(out, [
            {"file": "TODO.md", "rule": "tracker"},
            {"file": "scripts/other.py", "rule": "scope"},
            {"file": NOTES, "rule": "notes-heading"},
        ])


class EnvironmentWiringTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.notes = self.root / NOTES
        self.notes.parent.mkdir(parents=True)
        self.notes.write_text(notes_text())

    def tearDown(self):
        self.tmp.cleanup()

    def env(self, **extra):
        base = {"STORY_ID": "WQUALITY-03", "STORY_BODY": SCOPE_TEXT,
                "NOTES_FILE": str(self.notes), "WORKER_FINISHED": "1"}
        base.update(extra)
        return base

    def test_returns_none_without_a_declared_scope(self):
        with mock.patch.object(worker_gate, "head_commit", return_value="abc123"):
            self.assertIsNone(worker_gate.candidate_from_env(
                {"STORY_ID": "W", "NOTES_FILE": str(self.notes)}, str(self.root), ["scripts/worker_gate.py"]))

    def test_validates_against_head_commit_twice(self):
        with mock.patch.object(worker_gate, "head_commit", side_effect=["abc123", "def456"]) as head:
            out = worker_gate.candidate_from_env(self.env(), str(self.root), CHANGED)
        self.assertEqual(head.call_count, 2)
        self.assertEqual(out, [{"file": "", "rule": "candidate-changed"}])

    def test_reads_notes_from_the_named_file(self):
        with mock.patch.object(worker_gate, "head_commit", return_value="abc123"):
            out = worker_gate.candidate_from_env(self.env(), str(self.root), CHANGED)
        self.assertEqual(out, [])

    def test_unfinished_worker_needs_a_reason(self):
        self.notes.write_text(notes_text(True))
        with mock.patch.object(worker_gate, "head_commit", return_value="abc123"):
            out = worker_gate.candidate_from_env(self.env(WORKER_FINISHED="0"), str(self.root), CHANGED)
        self.assertIn({"file": str(self.notes), "rule": "notes-unfinished"}, out)

    def test_empty_story_id_has_no_notes_file(self):
        self.assertEqual(worker_gate.find_notes_file("", str(self.root)), "")

    def test_finds_notes_file_by_story_id_when_env_missing(self):
        env = self.env()
        env.pop("NOTES_FILE")
        self.notes.rename(self.root / ".agent/pr-notes/WQUALITY-03-20991231-235959.md")
        with mock.patch.object(worker_gate, "head_commit", return_value="abc123"):
            out = worker_gate.candidate_from_env(env, str(self.root), CHANGED)
        self.assertEqual(out, [])

    def test_no_notes_file_reports_headings(self):
        env = self.env()
        env.pop("NOTES_FILE")
        self.notes.unlink()
        with mock.patch.object(worker_gate, "head_commit", return_value="abc123"):
            out = worker_gate.candidate_from_env(env, str(self.root), CHANGED)
        self.assertTrue(any(v["rule"] == "notes-heading" for v in out))

    def test_defaults_changed_paths_when_not_supplied(self):
        with mock.patch.object(worker_gate, "head_commit", return_value="abc123"), \
             mock.patch.object(worker_gate, "changed_paths", return_value=CHANGED) as paths:
            out = worker_gate.candidate_from_env(self.env(), str(self.root))
        paths.assert_called_once_with()
        self.assertEqual(out, [])


class GitPlumbingTest(unittest.TestCase):
    def test_head_commit_returns_the_sha(self):
        result = mock.Mock(returncode=0, stdout="abc123\n")
        with mock.patch.object(worker_gate.subprocess, "run", return_value=result):
            self.assertEqual(worker_gate.head_commit(), "abc123")

    def test_head_commit_returns_empty_on_failure(self):
        result = mock.Mock(returncode=128, stdout="")
        with mock.patch.object(worker_gate.subprocess, "run", return_value=result):
            self.assertEqual(worker_gate.head_commit(), "")

    def test_changed_paths_combines_diff_and_dirty_files(self):
        results = [mock.Mock(stdout="scripts/worker_gate.py\nscripts/tests/test_worker_gate.py\n"),
                   mock.Mock(stdout=" M scripts/worker_gate.py\n?? .agent/pr-notes/W.md\n")]
        with mock.patch.object(worker_gate.subprocess, "run", side_effect=results) as run:
            paths = worker_gate.changed_paths()
        self.assertEqual(paths, ["scripts/worker_gate.py", "scripts/tests/test_worker_gate.py",
                                 "scripts/worker_gate.py", ".agent/pr-notes/W.md"])
        self.assertEqual(run.call_count, 2)

    def test_failing_classes_marks_unparseable_reports(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "TEST-broken.FooTest.xml").write_text("<not xml")
            self.assertEqual(worker_gate.failing_classes(tmp), {"FooTest"})


class MainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.notes = self.root / NOTES
        self.notes.parent.mkdir(parents=True)
        self.notes.write_text(notes_text())

    def tearDown(self):
        self.tmp.cleanup()

    def run_main(self, env=None, changed=(), sha="abc123", argv=None, reports=False):
        notes_env = {"STORY_BODY": "", "NOTES_FILE": str(self.notes), "WORKER_FINISHED": "1"}
        notes_env.update(env or {})
        with mock.patch.object(worker_gate, "changed_paths", return_value=list(changed)), \
             mock.patch.object(worker_gate, "head_commit", return_value=sha), \
             mock.patch.object(worker_gate.Path, "is_dir", return_value=reports), \
             mock.patch.object(sys, "argv", ["worker_gate.py"]), \
             mock.patch.dict(os.environ, notes_env, clear=True):
            return worker_gate.main(argv if argv is not None else ["0"])

    def test_without_a_pinned_scope_behaves_as_before(self):
        self.assertEqual(self.run_main(), 0)

    def test_clean_pinned_candidate_passes(self):
        self.assertEqual(self.run_main(env={"STORY_BODY": SCOPE_TEXT}, changed=CHANGED), 0)

    def test_pinned_candidate_violation_fails_the_gate(self):
        self.assertEqual(self.run_main(env={"STORY_BODY": SCOPE_TEXT},
                                       changed=CHANGED + ["TODO.md"]), 1)

    def test_candidate_change_fails_the_gate_with_structured_output(self):
        output = io.StringIO()
        with mock.patch.object(worker_gate, "changed_paths", return_value=list(CHANGED)), \
             mock.patch.object(worker_gate, "head_commit", side_effect=["abc123", "def456"]), \
             mock.patch.object(worker_gate.Path, "is_dir", return_value=False), \
             mock.patch.dict(os.environ, {"STORY_BODY": SCOPE_TEXT, "NOTES_FILE": str(self.notes)},
                             clear=True), redirect_stdout(output):
            code = worker_gate.main(["0"])
        self.assertEqual(code, 1)
        printed = output.getvalue()
        self.assertIn('"rule": "candidate-changed"', printed)
        self.assertIn('"file": ""', printed)

    def test_integration_failure_still_fails_the_gate(self):
        # A repair task is strict: its failing class fails the gate even with no
        # hand-off scope declared. Own a real, parseable report directory so the
        # gate reads usable results without depending on Maven creating the
        # shared Surefire directory first.
        with tempfile.TemporaryDirectory() as reports:
            (Path(reports) / "TEST-RxTransmissionFlowTest.xml").write_text(
                '<testsuite name="RxTransmissionFlowTest" tests="1" errors="1" failures="0"/>')
            with mock.patch.object(worker_gate, "failing_classes",
                                   return_value={"RxTransmissionFlowTest"}), \
                 mock.patch.object(worker_gate, "changed_paths", return_value=[]), \
                 mock.patch.dict(os.environ, {"STORY_ID": "FIX-01"}, clear=True):
                self.assertEqual(worker_gate.main(["1", reports]), 1)

    def test_missing_maven_status_defaults_to_zero(self):
        self.assertEqual(self.run_main(argv=[]), 0)

    def test_maven_failure_without_reports_is_a_hard_failure(self):
        self.assertEqual(self.run_main(argv=["1"], reports=False), 1)

    def test_module_main_guard_is_executable(self):
        # runpy re-executes the source, so drive it through real inputs instead of
        # patching the imported module's functions.
        with mock.patch.dict(os.environ, {}, clear=True), \
             mock.patch.object(sys, "argv", ["worker_gate.py", "0"]), \
             mock.patch.object(worker_gate.Path, "is_dir", return_value=False):
            with self.assertRaises(SystemExit) as ctx:
                runpy.run_path(str(SCRIPTS / "worker_gate.py"), run_name="__main__")
        self.assertEqual(ctx.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
