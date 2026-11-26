import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT, load

docs = load("check-docs")
PREFIXES = {"MSG", "IAM", "ARC"}


class IdTests(unittest.TestCase):
    def test_ranges_and_slashes_expand(self):
        self.assertEqual(
            docs.mentioned_ids("MSG-01–03 and IAM-01/02", PREFIXES),
            {"MSG-01", "MSG-02", "MSG-03", "IAM-01", "IAM-02"},
        )

    def test_hyphen_range_and_longer_numbers(self):
        self.assertEqual(
            docs.mentioned_ids("MSG-01-02", PREFIXES), {"MSG-01", "MSG-02"}
        )
        self.assertEqual(docs.mentioned_ids("ARC-123", PREFIXES), set())

    def test_spec_ids_ignore_appendix(self):
        text = (
            "| IAM-01 | x |\n**ARC-02 · Isolation.**\n## Appendix A\n| IAM-99 | y |\n"
        )
        self.assertEqual(docs.spec_ids(text), {"IAM-01", "ARC-02"})


class CompletionMatrixTests(unittest.TestCase):
    """The completion matrix must state explicit, evidence-bearing statuses."""

    HEADER = ("| Feature / spec IDs | Requirements | DB | Backend | API | Authorization | "
              "Tenant isolation | Staff web | Patient mobile | Wiring | Unit | Integration | "
              "Clinical safety | Docs | Independent review | Delivery |")
    SEP = "|" + "---|" * 16
    MATRIX = "docs/orca-refactor/04-completion-and-safety.md"

    def repo(self, rows=()):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        if rows is not None:
            path = tmp / self.MATRIX
            path.parent.mkdir(parents=True)
            lines = [self.HEADER, self.SEP]
            lines.extend(rows)
            path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        return tmp

    def row(self, *statuses):
        cells = ["Feature"] + list(statuses)
        cells += ["verified"] * (16 - len(cells))
        return "| " + " | ".join(cells) + " |"

    def test_a_clean_matrix_has_no_errors(self):
        self.assertEqual(docs.completion_matrix_problems(self.repo()), [])

    def test_a_missing_matrix_is_an_error(self):
        tmp = self.repo(rows=None)
        (tmp / "docs" / "orca-refactor").mkdir(parents=True, exist_ok=True)
        problems = docs.completion_matrix_problems(tmp)
        self.assertTrue(any(self.MATRIX in item and "missing" in item for item in problems))

    def test_a_missing_header_is_an_error(self):
        tmp = self.repo()
        (tmp / self.MATRIX).write_text("| Other | header |\n|---|---|\n", encoding="utf-8")
        self.assertTrue(any("header" in item for item in docs.completion_matrix_problems(tmp)))

    def test_bare_and_cited_verified_are_accepted(self):
        tmp = self.repo([self.row("verified", "verified: `test_a`"),
                         self.row("verified: V1__x.sql", "pending", "failed")])
        self.assertEqual(docs.completion_matrix_problems(tmp), [])

    def test_verified_without_a_citation_is_an_error(self):
        tmp = self.repo([self.row("verified:   ")])
        self.assertTrue(any("verified" in item for item in docs.completion_matrix_problems(tmp)))

    def test_pending_needs_a_named_gap(self):
        tmp = self.repo([self.row("pending: G1 human gate open")])
        self.assertEqual(docs.completion_matrix_problems(tmp), [])
        (tmp / self.MATRIX).write_text(
            "\n".join([self.HEADER, self.SEP, self.row("pending:")]) + "\n", encoding="utf-8")
        self.assertTrue(any("pending" in item for item in docs.completion_matrix_problems(tmp)))

    def test_failed_accepts_a_bug_id_and_a_gate_ref(self):
        tmp = self.repo([self.row("failed: `QA-SOURCE-05`", "failed: `g0:41,52-69`")])
        self.assertEqual(docs.completion_matrix_problems(tmp), [])

    def test_failed_without_a_reference_is_an_error(self):
        tmp = self.repo([self.row("failed:")])
        self.assertTrue(any("failed" in item for item in docs.completion_matrix_problems(tmp)))

    def test_not_applicable_needs_a_justification(self):
        tmp = self.repo([self.row("not applicable (PRD-06)")])
        self.assertEqual(docs.completion_matrix_problems(tmp), [])
        (tmp / self.MATRIX).write_text(
            "\n".join([self.HEADER, self.SEP, self.row("not applicable")]) + "\n",
            encoding="utf-8")
        self.assertTrue(any("not applicable" in item
                            for item in docs.completion_matrix_problems(tmp)))

    def test_empty_and_unknown_cells_are_errors(self):
        for status in ("", "green"):
            with self.subTest(status=status):
                tmp = self.repo([self.row(status)])
                self.assertTrue(docs.completion_matrix_problems(tmp))

    def test_errors_are_ordered_row_major_then_left_to_right(self):
        tmp = self.repo([self.row("pending:", "verified:"), self.row("green")])
        problems = docs.completion_matrix_problems(tmp)
        self.assertEqual(len(problems), 3)
        self.assertTrue(problems[0].endswith("3: pending has no gap"))
        self.assertTrue(problems[1].endswith("3: verified has no citation"))
        self.assertTrue(problems[2].endswith("4: unrecognised status green"))

    def test_check_reports_a_bad_matrix_cell(self):
        tmp = self.repo([self.row("verified:   ")])
        spec = tmp / docs.SPEC
        spec.parent.mkdir(parents=True, exist_ok=True)
        spec.write_text("| IAM-01 | x |\n", encoding="utf-8")
        (tmp / "NOTE.md").write_text("IAM-01 clean\n", encoding="utf-8")
        errors = docs.check(tmp)
        self.assertTrue(any("04-completion-and-safety.md:3: verified has no citation"
                            in error for error in errors), errors)

    def test_repository_without_the_docs_tree_ignores_the_matrix(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        self.assertEqual(docs.completion_matrix_problems(tmp), [])

    def test_not_applicable_with_empty_justification_is_an_error(self):
        tmp = self.repo([self.row("not applicable ()")])
        self.assertTrue(any("not applicable has no justification" in item
                            for item in docs.completion_matrix_problems(tmp)))

    def test_a_ragged_row_is_left_to_the_structural_pass(self):
        tmp = self.repo(["| Feature | pending: |"])
        self.assertEqual(docs.completion_matrix_problems(tmp), [])

    def test_specification_ids_are_not_checked_as_a_consumer(self):
        tmp = self.repo()
        spec = tmp / docs.SPEC
        spec.parent.mkdir(parents=True, exist_ok=True)
        spec.write_text("| IAM-01 | x |\n| IAM-99 | y |\n", encoding="utf-8")
        self.assertFalse(any("unknown requirement ID" in item for item in docs.check(tmp)))

    def test_non_matrix_documents_are_ignored(self):
        tmp = self.repo()
        (tmp / "README.md").write_text("| Feature | x |\n", encoding="utf-8")
        self.assertEqual(docs.completion_matrix_problems(tmp), [])


class GitIgnoreTests(unittest.TestCase):
    """Git-ignored scratch documents are skipped without weakening tracked records."""

    def repo(self, ignore):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        subprocess.run(["git", "init", "-q", str(tmp)], check=True)
        (tmp / ".gitignore").write_text(ignore, encoding="utf-8")
        spec = tmp / docs.SPEC
        spec.parent.mkdir(parents=True, exist_ok=True)
        spec.write_text("| IAM-01 | x |\n", encoding="utf-8")
        return tmp

    def test_ignored_and_untracked_documents_are_skipped(self):
        tmp = self.repo("/scratch/\nignored.md\n")
        for rel in ("scratch/N.md", "ignored.md", "KEPT.md"):
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("IAM-01\n", encoding="utf-8")
        names = {p.relative_to(tmp).as_posix() for p in docs.markdown_files(tmp)}
        self.assertIn(docs.SPEC, names)
        self.assertIn("KEPT.md", names)
        self.assertNotIn("scratch/N.md", names)
        self.assertNotIn("ignored.md", names)

    def test_tracked_document_is_not_skipped_by_an_ignore_pattern(self):
        tmp = self.repo("tracked.md\n")
        path = tmp / "tracked.md"
        path.write_text("IAM-01\n", encoding="utf-8")
        subprocess.run(["git", "-C", str(tmp), "add", "-f", "tracked.md"], check=True)
        names = {p.relative_to(tmp).as_posix() for p in docs.markdown_files(tmp)}
        self.assertIn("tracked.md", names)

    def test_negation_unignores_a_document(self):
        tmp = self.repo("*.md\n!visible.md\n")
        for rel in ("hidden.md", "visible.md"):
            (tmp / rel).write_text("IAM-01\n", encoding="utf-8")
        names = {p.relative_to(tmp).as_posix() for p in docs.markdown_files(tmp)}
        self.assertNotIn("hidden.md", names)
        self.assertIn("visible.md", names)

    def test_no_paths_needs_no_git_call(self):
        with mock.patch.object(docs.subprocess, "run") as run:
            self.assertEqual(docs.gitignored_paths(Path("."), []), set())
        run.assert_not_called()

    def test_git_ignore_child_uses_sanitized_environment(self):
        tmp = self.make_tmp()
        path = tmp / "plain.md"
        path.write_text("IAM-01\n", encoding="utf-8")
        with mock.patch.dict("os.environ", {"GIT_DIR": "/synthetic/extra"}), \
                mock.patch.object(docs.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 1, "", "")
            self.assertEqual(docs.gitignored_paths(tmp, [path]), set())
        env = run.call_args.kwargs["env"]
        self.assertNotIn("GIT_DIR", env)
        self.assertEqual("/dev/null", env["GIT_CONFIG_GLOBAL"])

    def test_unavailable_git_adds_no_skips(self):
        tmp = self.make_tmp()
        path = tmp / "plain.md"
        path.write_text("IAM-01\n", encoding="utf-8")
        with mock.patch.object(docs.subprocess, "run", side_effect=OSError("git missing")):
            self.assertEqual(docs.gitignored_paths(tmp, [path]), set())

    def make_tmp(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        return tmp


class RepositoryTests(unittest.TestCase):
    def test_repository_docs_are_consistent(self):
        self.assertEqual(docs.check(ROOT), [])

    def test_agent_notes_and_reports_are_not_enumerated_as_docs(self):
        tmp = self.make_repo("IAM-01 clean\n")
        for rel in (".agents/notes/N.md", ".agents/reports/2026-11-24.md",
                    ".agents/notes/archive/x.md", ".workspaces/pr-1/notes/N.md"):
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("bad | ragged | table\n")
        names = [p.relative_to(tmp).as_posix() for p in docs.markdown_files(tmp)]
        self.assertNotIn(".agents/notes/N.md", names)
        self.assertNotIn(".agents/reports/2026-11-24.md", names)
        self.assertNotIn(".agents/notes/archive/x.md", names)
        self.assertNotIn(".workspaces/pr-1/notes/N.md", names)

    def make_repo(self, note):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        spec = tmp / docs.SPEC
        spec.parent.mkdir(parents=True, exist_ok=True)
        spec.write_text("| IAM-01 | x |\n")
        (tmp / "NOTE.md").write_text(note)
        return tmp

    def test_unknown_id_and_broken_link_are_reported(self):
        errors = docs.check(
            self.make_repo("See IAM-09 and [x](missing.md) and [y](https://a.b)")
        )
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("IAM-09" in e for e in errors))
        self.assertTrue(any("missing.md" in e for e in errors))

    def test_valid_links_pass(self):
        errors = docs.check(self.make_repo("IAM-01 [self](NOTE.md#top) [a](#x)"))
        self.assertEqual(errors, [])

    def test_broken_link_to_a_moved_config_doc_is_reported(self):
        """Active links to a moved tracker must fail instead of being ignored."""
        errors = docs.check(
            self.make_repo("IAM-01 [setup](README-SETUP.md) [memory](MEMORY.md)")
        )
        self.assertEqual(len(errors), 2)
        self.assertTrue(any("README-SETUP.md" in e for e in errors), errors)
        self.assertTrue(any("MEMORY.md" in e for e in errors), errors)

    def test_broken_link_to_a_generated_view_is_reported(self):
        """TODO.md is generated and untracked, so a link to it breaks a clean checkout."""
        errors = docs.check(
            self.make_repo("IAM-01 [tasks](TODO.md) [up](../../TODO.md#x)")
        )
        self.assertEqual(len(errors), 2)

    def test_broken_link_to_a_missing_task_file_is_reported(self):
        """Task files are tracked; a link to a nonexistent one is a real broken reference."""
        errors = docs.check(
            self.make_repo("IAM-01 [t](../../tasks/00299000-GATE-X.md)")
        )
        self.assertEqual(len(errors), 1)

    def test_historical_report_links_are_not_checked(self):
        """Records under docs/pr are history, not specification pages."""
        tmp = self.make_repo("IAM-01 [ok](NOTE.md)")
        report = tmp / "docs" / "pr" / "reports" / "OLD.md"
        report.parent.mkdir(parents=True, exist_ok=True)
        report.write_text("[x](missing.md) and IAM-09")
        self.assertEqual(docs.check(tmp), [])


class StructureTests(unittest.TestCase):
    """One passing and one failing sample per generated-Markdown rule."""

    def repo(self, note, name="NOTE.md", extra=None):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        spec = tmp / docs.SPEC
        spec.parent.mkdir(parents=True, exist_ok=True)
        spec.write_text("| IAM-01 | x |\n")
        (tmp / name).write_text(note)
        for rel, text in (extra or {}).items():
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text)
        return tmp

    def errors(self, note, **kwargs):
        return docs.check(self.repo(note, **kwargs))

    def assert_one(self, errors, line, keyword):
        self.assertEqual(len(errors), 1, errors)
        self.assertIn(f"NOTE.md:{line}", errors[0])
        self.assertIn(keyword, errors[0])

    # Code fences -----------------------------------------------------------
    def test_balanced_fence_passes(self):
        self.assertEqual(self.errors("text\n```python\nprint(1)\n```\nafter\n"), [])

    def test_unbalanced_fence_reports_line(self):
        self.assert_one(self.errors("text\n```python\nprint(1)\nafter\n"), 2, "fence")

    # Headings --------------------------------------------------------------
    def test_heading_levels_pass(self):
        self.assertEqual(self.errors("# A\n## B\n### C\n## D\n"), [])

    def test_heading_jump_reports_line(self):
        self.assert_one(self.errors("# A\n### C\n"), 2, "heading")

    def test_first_heading_may_be_deep(self):
        # No previous heading yet, so a deep first heading is not a jump.
        self.assertEqual(self.errors("### Deep\n#### Deeper\n"), [])

    def test_same_level_and_in_code_are_ignored(self):
        note = "# A\n## B\n```\n###### shell comment\n```\n## C\n"
        self.assertEqual(self.errors(note), [])

    # Tables ----------------------------------------------------------------
    def test_consistent_table_passes(self):
        self.assertEqual(self.errors("| A | B |\n|---|---|\n| 1 | 2 |\n"), [])

    def test_ragged_table_reports_line(self):
        table = "| A | B |\n|---|---|\n| 1 | 2 |\n| 3 | 4 | 5 |\n"
        self.assert_one(self.errors(table), 4, "table")

    def test_escaped_pipes_are_not_cells(self):
        self.assertEqual(self.errors("| A | B |\n|---|---|\n| 1 \\| 2 | 3 |\n"), [])

    def test_table_inside_code_is_ignored(self):
        note = "```\n| A | B |\n|---|---|\n| 1 | 2 | 3 |\n```\n"
        self.assertEqual(self.errors(note), [])

    def test_code_line_ends_table_scan(self):
        note = "| A | B |\n|---|---|\n```text\n| 1 | 2 | 3 |\n```\n"
        self.assertEqual(self.errors(note), [])

    def test_blank_line_ends_table(self):
        self.assertEqual(self.errors("| A | B |\n|---|---|\n| 1 | 2 |\n\nprose\n"), [])

    # Control characters ----------------------------------------------------
    def test_no_control_characters_passes(self):
        self.assertEqual(self.errors("plain text\n"), [])

    def test_control_character_reports_line(self):
        self.assert_one(self.errors("ok\nraw\x07bell\n"), 2, "control")

    # Tokens ----------------------------------------------------------------
    def test_ordinary_words_are_not_tokens(self):
        self.assertEqual(self.errors("risk-transitions-available-including.md\n"), [])

    def test_unmasked_token_reports_line(self):
        self.assert_one(self.errors("ok\ntoken ghp_" + "a" * 24 + "\n"), 2, "token")

    def test_other_token_shapes_are_reported(self):
        for token in ("github_pat_" + "a" * 24, "sk-" + "b" * 24):
            with self.subTest(prefix=token[:6]):
                self.assert_one(self.errors("x " + token + "\n"), 1, "token")

    # HTML comments and markers ---------------------------------------------
    def test_closed_marker_passes(self):
        self.assertEqual(self.errors("<!-- swarm-task:X -->\nbody\n"), [])

    def test_unclosed_marker_reports_line(self):
        self.assert_one(self.errors("ok\n<!-- open marker\nbody\n"), 2, "comment")

    def test_multiple_opens_report_first_line(self):
        self.assert_one(self.errors("<!-- one\n<!-- two\n<!-- three\n"), 1, "comment")

    # File selection and combined document ----------------------------------
    def test_generated_files_exclude_records_and_notes(self):
        root = self.repo(
            "IAM-01 clean\n",
            extra={
                "docs/swarm/REC.md": "# Record\n",
                ".agents/notes/N.md": "# Note\n",
                ".agents/reports/OLD.md": "# Old\n",
                "docs/GOOD.md": "# Good\n",
            },
        )
        names = sorted(
            p.relative_to(root).as_posix() for p in docs.generated_markdown_files(root)
        )
        self.assertEqual(
            names, [".opencode/config/PROJECT_V1_SPEC.md", "NOTE.md", "docs/GOOD.md"]
        )

    def test_clean_document_passes_all_rules(self):
        doc = (
            "# Title\n\n## Section\n\ntext\n\n```python\nx = 1\n```\n\n"
            "| A | B |\n|---|---|\n| 1 | 2 |\n\n<!-- swarm-task:X -->\n"
        )
        self.assertEqual(self.errors(doc), [])

    def test_cells_ignores_escaped_pipes(self):
        self.assertEqual(docs._cells("| a \\| b | c |"), 3)
        self.assertEqual(docs._cells("| a | b |"), 3)


if __name__ == "__main__":
    unittest.main()
