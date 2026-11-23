"""Full line and branch coverage of the worker hand-off gate.

Every fixture here is synthetic: temporary directories, fabricated surefire XML,
mocked subprocess/Git calls and made-up environment dictionaries. No real notes,
commits, tokens, network calls or patient data are involved. The suite loads the
script under test directly from its file so it measures that exact source.
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
SPEC = importlib.util.spec_from_file_location("worker_gate_under_test", SCRIPTS / "worker_gate.py")
worker_gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(worker_gate)

NOTES = ".agent/pr-notes/WORKER-COVERAGE-20261122-001724.md"
SCOPE_TEXT = ("Scope (stay inside these paths): scripts/worker_gate.py, "
              "scripts/tests/test_worker_gate_coverage.py")
CHANGED = ["scripts/worker_gate.py", "scripts/tests/test_worker_gate_coverage.py", NOTES]


def notes_text(finished=True, missing=(), empty=(), placeholder=()):
    """Build synthetic hand-off notes with the named sections broken on request."""
    sections = {
        "Test evidence": "python3 -I -m unittest discover -s scripts/tests -q -> OK",
        "Coverage": "python3 -m coverage report -> 100%",
        "Findings": "No defects found.",
    }
    parts = ["# Worker notes", ""]
    for heading, body in sections.items():
        if heading in missing:
            continue
        parts += ["## " + heading,
                  "" if heading in empty else
                  ("see <task-id> for details" if heading in placeholder else body), ""]
    if not finished and "Why this is not finished" not in missing:
        parts += ["## Why this is not finished",
                  "" if "Why this is not finished" in empty else
                  ("<reason>" if "Why this is not finished" in placeholder else
                   "The coverage tool is unavailable in this image."), ""]
    return "\n".join(parts)


def write_report(directory, filename, xml):
    """Write a synthetic surefire XML file into a temporary reports directory."""
    (Path(directory) / filename).write_text(xml)
    return directory


def suite_xml(name=None, failures="0", errors="0"):
    """A minimal surefire <testsuite> element with selectable attributes."""
    attrs = ""
    if name is not None:
        attrs += ' name="%s"' % name
    attrs += ' failures="%s" errors="%s"' % (failures, errors)
    return "<testsuite%s></testsuite>" % attrs


class FailingClassesTest(unittest.TestCase):
    """failing_classes: parse success/failure, empty/many reports and each counter."""

    def test_empty_directory_returns_empty_set(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(worker_gate.failing_classes(tmp), set())

    def test_parse_error_uses_the_report_stem(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, "TEST-broken.FooTest.xml", "<not xml")
            self.assertEqual(worker_gate.failing_classes(tmp), {"FooTest"})

    def test_failures_counter_marks_a_class(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, "TEST-foo.xml", suite_xml("pkg.FooTest", "1", "0"))
            self.assertEqual(worker_gate.failing_classes(tmp), {"FooTest"})

    def test_errors_counter_marks_a_class_when_failures_is_zero(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, "TEST-foo.xml", suite_xml("pkg.BarIT", "0", "2"))
            self.assertEqual(worker_gate.failing_classes(tmp), {"BarIT"})

    def test_no_counter_and_no_name_defaults(self):
        # With a failure recorded but no name attribute, the report stem is used.
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, "TEST-pkg.BazTest.xml", suite_xml(failures="1"))
            self.assertEqual(worker_gate.failing_classes(tmp), {"BazTest"})

    def test_many_reports_accumulate_all_failing_classes(self):
        with tempfile.TemporaryDirectory() as tmp:
            write_report(tmp, "TEST-a.xml", suite_xml("pkg.ATest", "1", "0"))
            write_report(tmp, "TEST-b.xml", suite_xml("pkg.BTest", "0", "1"))
            write_report(tmp, "TEST-c.xml", suite_xml("pkg.CTest", "0", "0"))
            self.assertEqual(worker_gate.failing_classes(tmp), {"ATest", "BTest"})


class StemAndRelatedTest(unittest.TestCase):
    """_stem and related: substring matching, short-circuits and empty sets."""

    def test_stem_strips_test_suffixes(self):
        for name, expected in (("FooTest", "Foo"), ("FooIT", "Foo"),
                               ("FooIntegrationTest", "Foo"), ("FooIsolationTest", "Foo"),
                               ("FooFlowTest", "Foo"), ("Foo", "Foo")):
            with self.subTest(name=name):
                self.assertEqual(worker_gate._stem(name), expected)

    def test_empty_failing_set_has_no_related_classes(self):
        self.assertEqual(worker_gate.related(set(), ["Foo.java"]), set())

    def test_changed_class_name_matches_directly(self):
        self.assertEqual(worker_gate.related({"FooTest"}, ["FooTest.java"]), {"FooTest"})

    def test_full_stem_substring_matches_changed_source(self):
        self.assertEqual(worker_gate.related({"ChartRecordIntegrationTest"},
                                             ["ChartRecord.java"]),
                         {"ChartRecordIntegrationTest"})

    def test_changed_stem_inside_class_core_matches(self):
        self.assertEqual(worker_gate.related({"ChartRecordIntegrationTest"}, ["Chart.java"]),
                         {"ChartRecordIntegrationTest"})

    def test_empty_core_does_not_match(self):
        # _stem("Test") is empty, so the guarded `core and ...` generator is falsy.
        self.assertEqual(worker_gate.related({"Test"}, ["Foo.java"]), set())

    def test_no_source_paths_gives_empty_stems(self):
        self.assertEqual(worker_gate.related({"FooTest"}, ["notes.txt"]), set())

    def test_non_matching_core_is_excluded(self):
        self.assertEqual(worker_gate.related({"FooTest"}, ["Bar.java"]), set())

    def test_only_java_and_sql_paths_contribute_stems(self):
        result = worker_gate.related({"FooTest"}, ["Foo.sql", "unrelated.md"])
        self.assertEqual(result, {"FooTest"})

    def test_many_failing_classes_keep_only_the_related_ones(self):
        result = worker_gate.related({"FooTest", "BarTest"}, ["Foo.java"])
        self.assertEqual(result, {"FooTest"})


class GitPlumbingTest(unittest.TestCase):
    """changed_paths, head_commit, find_notes_file and read_notes Git/filesystem edges."""

    def test_head_commit_returns_stripped_sha(self):
        result = mock.Mock(returncode=0, stdout="abc123\n")
        with mock.patch.object(worker_gate.subprocess, "run", return_value=result):
            self.assertEqual(worker_gate.head_commit(), "abc123")

    def test_head_commit_returns_empty_on_nonzero_exit(self):
        result = mock.Mock(returncode=128, stdout="")
        with mock.patch.object(worker_gate.subprocess, "run", return_value=result):
            self.assertEqual(worker_gate.head_commit(), "")

    def test_changed_paths_combines_diff_and_long_dirty_lines(self):
        results = [mock.Mock(stdout="a.py\nb.py\n"),
                   mock.Mock(stdout=" M c.py\n?? d.md\n")]
        with mock.patch.object(worker_gate.subprocess, "run", side_effect=results) as run:
            paths = worker_gate.changed_paths()
        self.assertEqual(paths, ["a.py", "b.py", "c.py", "d.md"])
        self.assertEqual(run.call_count, 2)
        self.assertEqual(run.call_args_list[0].args[0],
                         ["git", "diff", "--name-only", "origin/main...HEAD"])

    def test_changed_paths_honours_a_custom_base(self):
        results = [mock.Mock(stdout=""), mock.Mock(stdout="")]
        with mock.patch.object(worker_gate.subprocess, "run", side_effect=results) as run:
            self.assertEqual(worker_gate.changed_paths("main"), [])
        self.assertEqual(run.call_args_list[0].args[0][-1], "main...HEAD")

    def test_changed_paths_drops_short_dirty_lines(self):
        results = [mock.Mock(stdout=""), mock.Mock(stdout="??\n M x.py\n")]
        with mock.patch.object(worker_gate.subprocess, "run", side_effect=results):
            self.assertEqual(worker_gate.changed_paths(), ["x.py"])

    def test_find_notes_file_returns_empty_for_empty_story_id(self):
        self.assertEqual(worker_gate.find_notes_file("", "."), "")

    def test_find_notes_file_returns_empty_when_no_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertEqual(worker_gate.find_notes_file("MISSING", tmp), "")

    def test_find_notes_file_returns_newest_match(self):
        with tempfile.TemporaryDirectory() as tmp:
            folder = Path(tmp) / ".agent" / "pr-notes"
            folder.mkdir(parents=True)
            (folder / "W-20260101-000000.md").write_text("old")
            (folder / "W-20261231-235959.md").write_text("new")
            self.assertEqual(worker_gate.find_notes_file("W", tmp),
                             str(Path(".agent/pr-notes/W-20261231-235959.md")))

    def test_read_notes_returns_none_for_empty_path(self):
        self.assertIsNone(worker_gate.read_notes("", "."))

    def test_read_notes_returns_none_for_missing_file(self):
        self.assertIsNone(worker_gate.read_notes(".agent/pr-notes/missing.md", "."))

    def test_read_notes_returns_file_contents(self):
        with tempfile.TemporaryDirectory() as tmp:
            (Path(tmp) / "notes.md").write_text("hello notes")
            self.assertEqual(worker_gate.read_notes("notes.md", tmp), "hello notes")


class DecideTest(unittest.TestCase):
    """decide: no failures, strict repair tasks and ordinary related/unrelated paths."""

    def test_no_failures_passes(self):
        self.assertEqual(worker_gate.decide(set(), [], "W"),
                         (True, "integration tests passed"))

    def test_strict_task_without_named_classes_is_strict(self):
        ok, message = worker_gate.decide({"FooTest"}, [], "FIX-01", "")
        self.assertFalse(ok)
        self.assertIn("repair task: failing classes", message)
        self.assertIn("FooTest", message)

    def test_strict_task_with_a_still_failing_named_class_fails(self):
        ok, message = worker_gate.decide({"FooTest"}, [], "DLV-02", "see `FooTest`")
        self.assertFalse(ok)
        self.assertIn("still failing", message)

    def test_strict_task_ignores_failure_in_another_named_class(self):
        ok, message = worker_gate.decide({"OtherTest"}, [], "FIX-01", "see `FooTest`")
        self.assertTrue(ok)
        self.assertIn("other classes still red", message)

    def test_ordinary_task_fails_on_related_failure(self):
        ok, message = worker_gate.decide({"FooTest"}, ["Foo.java"], "W")
        self.assertFalse(ok)
        self.assertIn("related to this change", message)

    def test_ordinary_task_warns_on_unrelated_failure(self):
        ok, message = worker_gate.decide({"FooTest"}, ["Bar.java"], "W")
        self.assertTrue(ok)
        self.assertIn("WARNING", message)

    def test_named_classes_extracts_backticked_classes(self):
        self.assertEqual(worker_gate.named_classes("run `FooTest` and `BarIT`"),
                         {"FooTest", "BarIT"})

    def test_named_classes_handles_none(self):
        self.assertEqual(worker_gate.named_classes(None), set())


class ScopeAndNotesParsingTest(unittest.TestCase):
    """parse_scope, is_notes_file, under_scope, section_body and has_placeholder."""

    def test_parse_scope_splits_and_trims(self):
        self.assertEqual(worker_gate.parse_scope(SCOPE_TEXT),
                         ["scripts/worker_gate.py",
                          "scripts/tests/test_worker_gate_coverage.py"])

    def test_parse_scope_discards_empty_parts(self):
        text = "Scope (stay inside these paths): a, , b,\n"
        self.assertEqual(worker_gate.parse_scope(text), ["a", "b"])

    def test_parse_scope_without_match_returns_empty(self):
        self.assertEqual(worker_gate.parse_scope("no scope here"), [])

    def test_parse_scope_with_none_returns_empty(self):
        self.assertEqual(worker_gate.parse_scope(None), [])

    def test_is_notes_file_recognises_repository_relative_notes(self):
        self.assertTrue(worker_gate.is_notes_file(NOTES))

    def test_is_notes_file_handles_none(self):
        self.assertFalse(worker_gate.is_notes_file(None))

    def test_is_notes_file_rejects_other_paths(self):
        for path in (".agent/pr-notes.md", "scripts/worker_gate.py", "notes.md"):
            with self.subTest(path=path):
                self.assertFalse(worker_gate.is_notes_file(path))

    def test_under_scope_handles_an_empty_scope(self):
        self.assertFalse(worker_gate.under_scope("anything", []))

    def test_under_scope_matches_exact_file_and_directory_children(self):
        scope = ["scripts/worker_gate.py", "apps/api/src/main/"]
        self.assertTrue(worker_gate.under_scope("scripts/worker_gate.py", scope))
        self.assertTrue(worker_gate.under_scope("apps/api/src/main/Foo.java", scope))

    def test_under_scope_rejects_sibling_and_prefix_lookalikes(self):
        scope = ["apps/api/src/main"]
        self.assertFalse(worker_gate.under_scope("apps/api/src/mainx/Foo.java", scope))
        self.assertFalse(worker_gate.under_scope("scripts/other.py", scope))

    def test_section_body_stops_at_the_next_heading(self):
        result = worker_gate.section_body(notes_text(), "Test evidence")
        self.assertIn("unittest discover", result)
        self.assertNotIn("## Coverage", result)

    def test_section_body_is_case_insensitive(self):
        self.assertIn("unittest discover", worker_gate.section_body(notes_text(), "test evidence"))

    def test_section_body_returns_none_when_absent(self):
        self.assertIsNone(worker_gate.section_body(notes_text(), "Why this is not finished"))

    def test_section_body_returns_empty_string_for_empty_section(self):
        self.assertEqual(worker_gate.section_body(notes_text(empty=["Coverage"]), "Coverage"), "")

    def test_section_body_handles_none_and_empty_notes(self):
        self.assertIsNone(worker_gate.section_body(None, "Coverage"))
        self.assertIsNone(worker_gate.section_body("", "Coverage"))

    def test_section_body_ignores_non_heading_lines_and_other_headings(self):
        notes = "# Title\nplain line\n## Other\nvalue\n## Wanted\nanswer\n"
        self.assertEqual(worker_gate.section_body(notes, "Wanted"), "answer")

    def test_section_body_heading_at_end_has_empty_body(self):
        self.assertEqual(worker_gate.section_body("## Coverage\n", "Coverage"), "")

    def test_has_placeholder_detects_angle_bracket_placeholders(self):
        self.assertTrue(worker_gate.has_placeholder("see <task-id>"))
        self.assertTrue(worker_gate.has_placeholder("<...>"))

    def test_has_placeholder_ignores_comparisons_and_plain_text(self):
        self.assertFalse(worker_gate.has_placeholder("a < b and c > d"))
        self.assertFalse(worker_gate.has_placeholder("all done"))

    def test_has_placeholder_handles_none(self):
        self.assertFalse(worker_gate.has_placeholder(None))


class ValidateCandidateTest(unittest.TestCase):
    """validate_candidate: every rule and short-circuit."""

    def validate(self, changed=CHANGED, scope=None, notes=None, notes_path=NOTES,
                 finished=True, candidate_sha="abc123", head_sha="abc123"):
        if scope is None:
            scope = worker_gate.parse_scope(SCOPE_TEXT)
        if notes is None:
            notes = notes_text(finished)
        return worker_gate.validate_candidate(changed, scope, notes, notes_path, finished,
                                              candidate_sha, head_sha)

    def test_empty_changed_list_still_checks_notes(self):
        self.assertEqual(self.validate(changed=[]), [])

    def test_clean_candidate_passes(self):
        self.assertEqual(self.validate(), [])

    def test_clean_unfinished_candidate_passes(self):
        self.assertEqual(self.validate(notes=notes_text(False), finished=False), [])

    def test_file_outside_scope_is_reported(self):
        out = self.validate(changed=CHANGED + ["scripts/other.py"])
        self.assertIn({"file": "scripts/other.py", "rule": "scope"}, out)

    def test_notes_file_outside_scope_is_allowed(self):
        self.assertEqual(self.validate(), [])

    def test_every_tracker_file_is_reported(self):
        for name in worker_gate.TRACKER_FILES:
            with self.subTest(name=name):
                out = self.validate(changed=[name])
                self.assertEqual(out, [{"file": name, "rule": "tracker"}])

    def test_tracker_rule_short_circuits_the_scope_rule(self):
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

    def test_finished_candidate_does_not_need_a_reason(self):
        self.assertEqual(self.validate(finished=True), [])

    def test_missing_notes_file_reports_each_missing_heading(self):
        out = worker_gate.validate_candidate(CHANGED, worker_gate.parse_scope(SCOPE_TEXT),
                                             None, NOTES, True, "abc123", "abc123")
        self.assertIn({"file": NOTES, "rule": "notes-heading"}, out)

    def test_candidate_sha_mismatch_is_reported(self):
        out = self.validate(candidate_sha="abc123", head_sha="def456")
        self.assertEqual(out, [{"file": "", "rule": "candidate-changed"}])

    def test_none_candidate_sha_skips_the_check(self):
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


class CandidateFromEnvTest(unittest.TestCase):
    """candidate_from_env: default environment, scope pin and fallback lookups."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.notes = self.root / NOTES
        self.notes.parent.mkdir(parents=True)
        self.notes.write_text(notes_text())

    def tearDown(self):
        self.tmp.cleanup()

    def env(self, **extra):
        base = {"STORY_ID": "WORKER-COVERAGE", "STORY_BODY": SCOPE_TEXT,
                "NOTES_FILE": str(self.notes), "WORKER_FINISHED": "1"}
        base.update(extra)
        return base

    def run_with_head(self, environ=None, changed=CHANGED, head="abc123"):
        with mock.patch.object(worker_gate, "head_commit", return_value=head):
            return worker_gate.candidate_from_env(environ, str(self.root), changed)

    def test_returns_none_without_a_declared_scope(self):
        self.assertIsNone(self.run_with_head(
            {"STORY_ID": "W", "NOTES_FILE": str(self.notes)}))

    def test_validates_against_head_commit_twice(self):
        with mock.patch.object(worker_gate, "head_commit", side_effect=["abc123", "def456"]) as head:
            out = worker_gate.candidate_from_env(self.env(), str(self.root), CHANGED)
        self.assertEqual(head.call_count, 2)
        self.assertEqual(out, [{"file": "", "rule": "candidate-changed"}])

    def test_uses_the_named_notes_file(self):
        self.assertEqual(self.run_with_head(self.env()), [])

    def test_uses_os_environment_when_none_is_passed(self):
        with mock.patch.dict(os.environ, self.env(), clear=True), \
             mock.patch.object(worker_gate, "head_commit", return_value="abc123"):
            self.assertEqual(worker_gate.candidate_from_env(None, str(self.root), CHANGED), [])

    def test_unfinished_worker_needs_a_reason(self):
        self.notes.write_text(notes_text(True))
        self.assertIn({"file": str(self.notes), "rule": "notes-unfinished"},
                      self.run_with_head(self.env(WORKER_FINISHED="0")))

    def test_whitespace_finished_flag_is_trimmed(self):
        self.assertEqual(self.run_with_head(self.env(WORKER_FINISHED=" 1 ")), [])

    def test_empty_story_id_has_no_notes_file(self):
        self.assertEqual(worker_gate.find_notes_file("", str(self.root)), "")

    def test_finds_notes_file_by_story_id_when_env_missing(self):
        env = self.env()
        env.pop("NOTES_FILE")
        self.notes.rename(self.root / ".agent/pr-notes/WORKER-COVERAGE-20991231-235959.md")
        self.assertEqual(self.run_with_head(env), [])

    def test_no_notes_file_reports_missing_headings(self):
        env = self.env()
        env.pop("NOTES_FILE")
        self.notes.unlink()
        out = self.run_with_head(env)
        self.assertTrue(any(v["rule"] == "notes-heading" for v in out))

    def test_defaults_changed_paths_when_not_supplied(self):
        with mock.patch.object(worker_gate, "head_commit", return_value="abc123"), \
             mock.patch.object(worker_gate, "changed_paths", return_value=CHANGED) as paths:
            out = worker_gate.candidate_from_env(self.env(), str(self.root))
        paths.assert_called_once_with()
        self.assertEqual(out, [])


class MainTest(unittest.TestCase):
    """main: Maven status parsing, reports presence, structured output and exit codes."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.notes = self.root / NOTES
        self.notes.parent.mkdir(parents=True)
        self.notes.write_text(notes_text())

    def tearDown(self):
        self.tmp.cleanup()

    def patch_common(self, changed=(), failing=None, reports=False, sha="abc123"):
        return (
            mock.patch.object(worker_gate, "changed_paths", return_value=list(changed)),
            mock.patch.object(worker_gate, "failing_classes",
                              return_value=set() if failing is None else failing),
            mock.patch.object(worker_gate.Path, "is_dir", return_value=reports),
            mock.patch.object(worker_gate, "head_commit", return_value=sha),
        )

    def run_main(self, env=None, changed=(), argv=None, failing=None, reports=False,
                 sys_argv=None):
        notes_env = {"STORY_BODY": "", "NOTES_FILE": str(self.notes), "WORKER_FINISHED": "1"}
        notes_env.update(env or {})
        patches = self.patch_common(changed, failing, reports)
        with patches[0], patches[1], patches[2], patches[3], \
             mock.patch.object(sys, "argv", sys_argv or ["worker_gate.py"]), \
             mock.patch.dict(os.environ, notes_env, clear=True):
            return worker_gate.main(argv if argv is not None else ["0"])

    def test_without_a_pinned_scope_behaves_as_before(self):
        self.assertEqual(self.run_main(), 0)

    def test_clean_pinned_candidate_passes(self):
        self.assertEqual(self.run_main(env={"STORY_BODY": SCOPE_TEXT}, changed=CHANGED), 0)

    def test_pinned_candidate_violation_fails_the_gate(self):
        self.assertEqual(self.run_main(env={"STORY_BODY": SCOPE_TEXT},
                                       changed=CHANGED + ["TODO.md"]), 1)

    def test_candidate_change_fails_with_structured_output(self):
        output = io.StringIO()
        notes_env = {"STORY_BODY": SCOPE_TEXT, "NOTES_FILE": str(self.notes)}
        with mock.patch.object(worker_gate, "changed_paths", return_value=list(CHANGED)), \
             mock.patch.object(worker_gate, "failing_classes", return_value=set()), \
             mock.patch.object(worker_gate.Path, "is_dir", return_value=False), \
             mock.patch.object(worker_gate, "head_commit", side_effect=["abc123", "def456"]), \
             mock.patch.object(sys, "argv", ["worker_gate.py"]), \
             mock.patch.dict(os.environ, notes_env, clear=True), redirect_stdout(output):
            code = worker_gate.main(["0"])
        self.assertEqual(code, 1)
        printed = output.getvalue()
        self.assertIn('"rule": "candidate-changed"', printed)
        self.assertIn('"file": ""', printed)
        self.assertIn("integration tests", printed)

    def test_maven_failure_without_reports_is_a_hard_failure(self):
        self.assertEqual(self.run_main(argv=["1"], reports=False), 1)

    def test_maven_failure_with_failing_reports_still_fails(self):
        # A strict repair task that names no class stays red on any failing class.
        # Use a real private report directory with a parseable XML file, not the
        # shared Surefire path, and do not mask is_dir: the real directory must
        # satisfy worker_gate itself for the strict verdict to be exercised.
        with tempfile.TemporaryDirectory() as reports:
            write_report(reports, "TEST-FooTest.xml", suite_xml("pkg.FooTest", "0", "1"))
            with mock.patch.object(worker_gate, "changed_paths", return_value=[]), \
                 mock.patch.object(worker_gate, "failing_classes", return_value={"FooTest"}), \
                 mock.patch.dict(os.environ, {"STORY_ID": "FIX-01", "STORY_BODY": ""}, clear=True):
                self.assertEqual(worker_gate.main(["1", reports]), 1)

    def test_maven_success_with_failing_reports_uses_the_decision(self):
        ok, message = worker_gate.decide({"FooTest"}, [], "FIX-01", "")
        self.assertFalse(ok)
        self.assertIn("repair task", message)

    def test_empty_argv_falls_back_to_sys_argv(self):
        with mock.patch.object(sys, "argv", ["worker_gate.py", "1"]):
            self.assertEqual(self.run_main(argv=[], reports=False,
                                           sys_argv=["worker_gate.py", "1"]), 1)

    def test_empty_argv_and_empty_sys_argv_default_to_success(self):
        self.assertEqual(self.run_main(argv=[], sys_argv=["worker_gate.py"]), 0)

    def test_default_argument_reads_sys_argv(self):
        with mock.patch.object(sys, "argv", ["worker_gate.py", "1"]):
            patches = self.patch_common([], None, False)
            with patches[0], patches[1], patches[2], patches[3], \
                 mock.patch.dict(os.environ, {"STORY_BODY": "", "NOTES_FILE": str(self.notes)},
                                 clear=True):
                self.assertEqual(worker_gate.main(), 1)

    def test_module_main_guard_exits_with_the_gate_result(self):
        # runpy re-executes the source, so the module guard is exercised here.
        with mock.patch.dict(os.environ, {}, clear=True), \
             mock.patch.object(sys, "argv", ["worker_gate.py", "0"]), \
             mock.patch.object(worker_gate.Path, "is_dir", return_value=False):
            with self.assertRaises(SystemExit) as ctx:
                runpy.run_path(str(SCRIPTS / "worker_gate.py"), run_name="__main__")
        self.assertEqual(ctx.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
