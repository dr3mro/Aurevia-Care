"""Coverage tests for the swarm integrator's publish, notes and dispatch helpers.

Every pull request, issue, note and git call here is a synthetic fixture. The fake
``gh`` records calls and answers canned queries; no test uses the network, a token
or a real repository. The tests drive the public functions in ``scripts/swarm.py``
that the integrator uses to publish a worker, reconcile daily notes and report a
failed integration, including their failure paths.
"""
import contextlib
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")


class FakeGh:
    """Record gh calls and answer the listing and view queries from canned data.

    ``pr_view`` maps a ``--jq`` expression to the value returned for it. A fix
    worker's state probe uses ``--jq .state``, so ``".state"`` is its key; the
    number lookup of a created pull request uses ``".number"``.
    """

    def __init__(self, pr_view=None, create_result=""):
        self.calls = []
        self.pr_view = pr_view or {}
        self.create_result = create_result

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("pr", "view"):
            if "--jq" in args:
                key = args[args.index("--jq") + 1]
                return str(self.pr_view.get(key, "OPEN" if key == ".state" else "7"))
            return str(self.pr_view.get(".state", "OPEN"))
        if args[:2] == ("pr", "create"):
            return self.create_result
        return ""

    def calls_for(self, *prefix):
        """The recorded calls whose leading arguments equal ``prefix``."""
        return [c for c in self.calls if c[:len(prefix)] == prefix]


def scratch():
    """A fresh temporary directory cleaned up when the test ends."""
    root = Path(tempfile.mkdtemp())
    return root


def worker_bundle(root, branch, result="ok", checks="passed", merge="skipped"):
    """A synthetic worker artifact directory with a bundle and its status files."""
    work = root / "work"
    work.mkdir(parents=True, exist_ok=True)
    (work / "result").write_text(result)
    (work / "commits").write_text("1")
    (work / "result.bundle").write_bytes(b"synthetic bundle")
    (work / "branch").write_text(branch)
    (work / "checks").write_text(checks)
    (work / "merge").write_text(merge)
    (work / "publish-notes.md").write_text("Synthetic worker notes.")
    return work


def publish_git(changed="f.txt"):
    """A git stand-in: ``diff --name-only`` reports ``changed`` and all else succeeds."""
    calls = []

    def git(*args, check=True):
        calls.append(args)
        if args[:2] == ("diff", "--name-only"):
            return changed
        return ""

    return git, calls


class ReleaseSummaryTest(unittest.TestCase):
    """The worker summary is appended when a step summary file is configured."""

    def test_release_appends_a_row_to_the_step_summary(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        summary = root / "summary.md"
        summary.write_text("head\n")
        task = {"slot": 1, "id": "CW-001", "kind": "story"}
        with mock.patch.dict(os.environ, {"GITHUB_STEP_SUMMARY": str(summary)}):
            swarm.release(task, "ok", 2, "42")
        self.assertIn("| 1 | CW-001 | story | ok | 2 commit(s) | #42 |", summary.read_text())


class PublishGuardTest(unittest.TestCase):
    """Publishing a repair and blocking a fix that changes workflow files."""

    def test_repair_pull_request_explains_the_automatic_merge(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        work = worker_bundle(root, "swarm/FIX-100-1")
        git, _ = publish_git()
        fake = FakeGh()
        task = {"kind": "story", "id": "FIX-100", "number": 0, "slot": 3,
                "branch": "", "title": "Repair main"}
        with mock.patch.object(swarm, "git", git), \
                mock.patch.object(swarm, "authed_git", mock.Mock(return_value="")), \
                mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "task_field", return_value=""), \
                mock.patch.object(swarm, "note_worker_outcome"):
            swarm.publish(task, work)
        create = fake.calls_for("pr", "create")[0]
        self.assertIn("Automatic repair of a failed run on main",
                      create[create.index("--body") + 1])
        self.assertIn("autofix", create)

    def test_a_fix_with_workflow_changes_does_not_release_a_claim(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        work = worker_bundle(root, "swarm/CW-001-1")
        git, _ = publish_git(".github/workflows/x.yml")
        fake = FakeGh(pr_view={".state": "OPEN"})
        task = {"kind": "fix", "id": "CW-001", "number": 193, "slot": 2,
                "branch": "swarm/CW-001-1", "title": "Fix"}
        authed = mock.Mock(return_value="")
        err = io.StringIO()
        with mock.patch.object(swarm, "git", git), \
                mock.patch.object(swarm, "authed_git", authed), \
                mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "task_field", return_value=""), \
                mock.patch.object(swarm, "note_worker_outcome"), \
                mock.patch.object(swarm, "release_claim") as released, \
                mock.patch.dict(os.environ, {"SWARM_WORKFLOW_PUSH": ""}), \
                contextlib.redirect_stderr(err):
            swarm.publish(task, work)
        released.assert_not_called()
        # The fix branch is fetched first; the push itself must never happen.
        self.assertFalse(any(c.args[:1] == ("push",) for c in authed.call_args_list))
        self.assertIn("workflow files", err.getvalue())


class NotesClockTest(unittest.TestCase):
    """Clock helpers use the New York calendar day for reconciliation."""

    def test_new_york_now_is_timezone_aware(self):
        moment = swarm.new_york_now()
        self.assertEqual(str(moment.tzinfo), "America/New_York")

    def test_an_unreadable_tracked_note_is_skipped(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        good = root / "good.md"
        good.write_text("see docs/pr/notes/KEEP.md")
        listed = f"{good}\n{root / 'missing.md'}\n"
        # Absolute fixture paths keep this test independent of the process cwd.
        with mock.patch.object(swarm, "git", return_value=listed):
            self.assertEqual(swarm.referenced_notes(), {"KEEP.md"})

    def test_a_day_with_no_files_left_ends_collection(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        notes = root / "pr-notes"
        notes.mkdir()
        (notes / "A-20261119-150000.md").write_text("synthetic note")
        with mock.patch.object(swarm, "MAX_NOTE_FILES", 0):
            days = swarm.notes_collect(root / "in", str(notes), today="2026-11-20", keep=set())
        self.assertEqual(days, [])
        self.assertFalse((root / "in" / "2026-11-19.md").exists())


class PushMainTest(unittest.TestCase):
    """The trusted notes push handles an unchanged edit, a race and a final failure."""

    def setUp(self):
        self.calls = []

    def git(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("diff", "--cached"):
            return "notes/2026-11-19.md\n"
        return ""

    def test_an_edit_with_no_changes_pushes_nothing(self):
        with mock.patch.object(swarm, "git", self.git), \
                mock.patch.object(swarm, "authed_git", mock.Mock()) as authed:
            self.assertFalse(swarm.push_main("m", lambda: False))
        authed.assert_not_called()
        self.assertEqual(self.calls, [])

    def test_zero_attempts_reports_failure_without_touching_git(self):
        with mock.patch.object(swarm, "git", self.git), \
                mock.patch.object(swarm, "authed_git", mock.Mock()) as authed:
            self.assertFalse(swarm.push_main("m", lambda: True, attempts=0))
        authed.assert_not_called()
        self.assertEqual(self.calls, [])

    def test_a_clean_change_is_committed_and_pushed(self):
        authed = mock.Mock(return_value="")
        with mock.patch.object(swarm, "git", self.git), \
                mock.patch.object(swarm, "authed_git", authed):
            self.assertTrue(swarm.push_main("m", lambda: True))
        authed.assert_called_once_with("push", "origin", "HEAD:main")
        self.assertTrue(any("commit" in c for c in self.calls))

    def test_a_push_race_is_retried_on_a_fresh_main(self):
        error = subprocess.CalledProcessError(1, "push")
        pushes, fetches = [], []

        def authed(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                pushes.append(args)
                if len(pushes) == 1:
                    raise error
            elif args[:2] == ("fetch", "origin"):
                fetches.append(args)
            return ""

        with mock.patch.object(swarm, "git", self.git), \
                mock.patch.object(swarm, "authed_git", authed), \
                mock.patch.object(swarm.time, "sleep") as slept, \
                mock.patch.object(swarm.random, "uniform", return_value=0):
            self.assertTrue(swarm.push_main("m", lambda: True, attempts=2))
        self.assertEqual(len(pushes), 2)
        slept.assert_called_once()
        self.assertEqual(fetches, [("fetch", "origin", "main")])
        self.assertIn(("reset", "-q", "--hard", "origin/main"), self.calls)

    def test_a_push_failure_on_the_last_attempt_is_raised(self):
        error = subprocess.CalledProcessError(1, "push")
        with mock.patch.object(swarm, "git", self.git), \
                mock.patch.object(swarm, "authed_git", mock.Mock(side_effect=error)), \
                mock.patch.object(swarm.time, "sleep"), \
                mock.patch.object(swarm.random, "uniform", return_value=0):
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.push_main("m", lambda: True, attempts=1)


class ArchiveDayTest(unittest.TestCase):
    """Archiving handles an empty list and a name already present in a day's zip."""

    def setUp(self):
        self.root = scratch()
        self.addCleanup(shutil.rmtree, self.root)
        self.notes = self.root / "notes"
        self.notes.mkdir()
        self.archive = self.root / "archive"

    def test_no_names_leaves_no_archive(self):
        self.assertFalse(swarm.archive_day("2026-11-19", [], str(self.notes), str(self.archive)))
        self.assertFalse(self.archive.exists())

    def test_a_name_already_in_the_zip_is_not_written_twice(self):
        import zipfile
        removed = []

        def git(*args, check=True):
            removed.append(Path(args[-1]).name)
            (self.notes / Path(args[-1]).name).unlink()

        (self.notes / "A.md").write_text("a")
        with mock.patch.object(swarm, "git", git):
            self.assertTrue(swarm.archive_day("2026-11-19", ["A.md"], str(self.notes), str(self.archive)))
        (self.notes / "A.md").write_text("a again")
        (self.notes / "B.md").write_text("b")
        with mock.patch.object(swarm, "git", git):
            self.assertTrue(swarm.archive_day("2026-11-19", ["A.md", "B.md"],
                                              str(self.notes), str(self.archive)))
        self.assertEqual(sorted(zipfile.ZipFile(self.archive / "2026-11-19.zip").namelist()),
                         ["A.md", "B.md"])
        self.assertEqual(removed, ["A.md", "A.md", "B.md"])


class NotesIssueEntriesTest(unittest.TestCase):
    """A reconciler proposal that is not a JSON list yields no issues."""

    def test_a_json_object_is_not_a_list_of_proposals(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        path = root / "issues.json"
        path.write_text(json.dumps({"title": "A real problem in the notes", "body": "evidence"}))
        self.assertEqual(swarm.notes_issue_entries(path), [])


class RulesPullRequestTest(unittest.TestCase):
    """The rules PR drops unusable patches and failed docs checks before it publishes."""

    def setUp(self):
        self.root = scratch()
        self.addCleanup(shutil.rmtree, self.root)
        self.patch = self.root / "rules.patch"
        self.patch.write_text(
            "diff --git a/.opencode/setup/AGENTS.md b/.opencode/setup/AGENTS.md\n--- a/.opencode/setup/AGENTS.md\n+++ b/.opencode/setup/AGENTS.md\n"
            "+A synthetic instruction.\n")

    def open(self, returncode=0, gh_result="/pull/42", patch_text=None, exists=True):
        if not exists:
            self.patch.unlink()
        elif patch_text is not None:
            self.patch.write_text(patch_text)
        with mock.patch.object(swarm, "authed_git", mock.Mock(return_value="")) as authed, \
                mock.patch.object(swarm, "git", mock.Mock(return_value="")) as git, \
                mock.patch.object(swarm.subprocess, "run",
                                  return_value=mock.Mock(returncode=returncode, stderr="docs failed")), \
                mock.patch.object(swarm, "gh", return_value=gh_result) as gh, \
                mock.patch.object(swarm, "add_labels") as labeled:
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                result = swarm.open_rules_pr("2026-11-19", str(self.patch))
        return result, authed, git, gh, labeled, err.getvalue()

    def test_a_missing_or_empty_patch_is_dropped(self):
        self.assertEqual(self.open(exists=False)[0], "")
        self.assertEqual(self.open(patch_text="   \n")[0], "")

    def test_a_patch_outside_the_allowed_documents_is_dropped(self):
        bad = ("diff --git a/scripts/swarm.py b/scripts/swarm.py\n"
               "--- a/scripts/swarm.py\n+++ b/scripts/swarm.py\n+pass\n")
        result, authed, _, _, labeled, err = self.open(patch_text=bad)
        self.assertEqual(result, "")
        authed.assert_not_called()
        labeled.assert_not_called()
        self.assertIn("outside the allowed set", err)

    def test_a_patch_that_fails_the_docs_check_is_dropped(self):
        result, _, git, _, labeled, err = self.open(returncode=1)
        self.assertEqual(result, "")
        labeled.assert_not_called()
        self.assertIn("fails check-docs.py", err)
        self.assertTrue(any("reset" in c.args for c in git.call_args_list))

    def test_a_clean_patch_opens_a_draft_and_asks_for_review(self):
        result, authed, git, _, labeled, err = self.open()
        self.assertEqual(result, "42")
        labeled.assert_called_once_with("42", "needs-review")
        authed.assert_any_call("fetch", "origin", "main")
        self.assertTrue(any("apply" in c[0] for c in git.call_args_list))
        self.assertEqual(err, "")

    def test_a_patch_that_opens_no_pull_request_returns_no_number(self):
        result, _, _, _, labeled, _ = self.open(gh_result="")
        self.assertEqual(result, "")
        labeled.assert_not_called()


class NotesPublishFailureTest(unittest.TestCase):
    """Publishing notes keeps going when an issue is not opened or a rules PR fails."""

    def setUp(self):
        self.root = scratch()
        self.addCleanup(shutil.rmtree, self.root)
        self.notes = self.root / "notes"
        self.notes.mkdir()
        self.collected = self.root / "in"
        self.collected.mkdir()
        self.reconciled = self.root / "out"
        self.reconciled.mkdir()
        (self.collected / "2026-11-19.files").write_text("A.md\n")
        (self.reconciled / "2026-11-19.md").write_text(
            "# Notes 2026-11-19\n" + "Synthetic summary line.\n" * 30)
        good = {"title": "A real problem in the notes", "body": "evidence", "scope": ["apps/api"]}
        (self.reconciled / "issues.json").write_text(json.dumps([good, good]))

    def publish(self, record_issue, rules_error=None):
        with mock.patch.object(swarm, "push_main", lambda *a: True), \
                mock.patch.object(swarm, "retire_dailies", lambda *a: None), \
                mock.patch.object(swarm, "archive_day", lambda *a: True), \
                mock.patch.object(swarm, "store_daily", lambda *a: None), \
                mock.patch.object(swarm, "record_issue", side_effect=record_issue) as recorded, \
                mock.patch.object(swarm, "open_rules_pr") as rules:
            if rules_error is None:
                rules.return_value = ""
            else:
                rules.side_effect = rules_error
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                result = swarm.notes_publish(self.collected, self.reconciled, str(self.notes))
        return result, recorded, rules, err.getvalue()

    def test_an_issue_that_cannot_be_opened_does_not_stop_the_next_one(self):
        result, recorded, _, err = self.publish(["", "9"])
        self.assertEqual(result, {"days": ["2026-11-19"], "issues": ["9"]})
        self.assertEqual(recorded.call_count, 2)
        self.assertEqual(err, "")

    def test_a_failed_rules_pull_request_does_not_fail_the_reconciliation(self):
        failure = subprocess.CalledProcessError(1, "git push")
        result, _, rules, err = self.publish(["9", "10"], rules_error=failure)
        self.assertEqual(result["days"], ["2026-11-19"])
        rules.assert_called_once()
        self.assertIn("could not open the rules pull request", err)


class IntegratorMergeDecisionTest(unittest.TestCase):
    """Already-integrated pull requests are recognised without a second gate run."""

    def test_an_ancestor_head_is_already_in_main(self):
        calls = []

        def run(args, **kwargs):
            calls.append(args)
            return mock.Mock(returncode=0, stdout="")

        with mock.patch.object(swarm.subprocess, "run", side_effect=run):
            self.assertTrue(swarm.pr_already_in_main(7, "abc123", "main"))
        self.assertEqual(calls, [["git", "merge-base", "--is-ancestor", "abc123", "main"]])

    def test_an_empty_merge_tree_is_treated_as_not_integrated(self):
        def run(args, **kwargs):
            if args[1] == "merge-base":
                return mock.Mock(returncode=1, stdout="")
            return mock.Mock(returncode=0, stdout="")

        with mock.patch.object(swarm.subprocess, "run", side_effect=run):
            self.assertFalse(swarm.pr_already_in_main(7, "abc123", "main"))

    def test_a_git_io_error_is_treated_as_not_integrated(self):
        def run(args, **kwargs):
            if args[1] == "merge-base":
                return mock.Mock(returncode=1, stdout="")
            raise OSError("git unavailable")

        with mock.patch.object(swarm.subprocess, "run", side_effect=run):
            self.assertFalse(swarm.pr_already_in_main(7, "abc123", "main"))

    def test_closing_an_integrated_pr_leaves_both_open_task_forms_recoverable(self):
        fake = FakeGh()
        edits = []
        rewritten = []

        def update(edit, message):
            edits.append((edit, message))
            # The real update_todo invokes the callback; do the same so the
            # no-match return and both replacement forms actually execute.
            rewritten.append(edit("- [-] CW-001\n"))
            rewritten.append(edit("- [ ] CW-001\n"))
            rewritten.append(edit("nothing to change"))
            return "updated"

        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "update_todo", side_effect=update):
            swarm.close_integrated_pr(7, "CW-001")
        self.assertTrue(fake.calls_for("pr", "close"))
        self.assertEqual(len(edits), 1)
        self.assertIn("CW-001", edits[0][1])
        self.assertEqual(rewritten, ["- [ ] CW-001\n", "- [ ] CW-001\n", "nothing to change"])

    def test_closing_an_integrated_pr_without_a_task_still_comments(self):
        fake = FakeGh()
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "update_todo") as update:
            swarm.close_integrated_pr(7, "")
        self.assertTrue(fake.calls_for("pr", "close"))
        update.assert_not_called()

    def test_close_failure_prevents_tracker_update(self):
        error = subprocess.CalledProcessError(1, ("pr", "close"))
        with mock.patch.object(swarm, "gh", side_effect=error), \
                mock.patch.object(swarm, "update_todo") as update:
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.close_integrated_pr(7, "CW-001")
        update.assert_not_called()

    def test_tracker_restore_failure_propagates_after_close(self):
        fake = FakeGh()
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "update_todo", side_effect=OSError("locked")):
            with self.assertRaises(OSError):
                swarm.close_integrated_pr(7, "CW-001")
        self.assertTrue(fake.calls_for("pr", "close"))


class IntegratorBatchTest(unittest.TestCase):
    """Batch decisions for blocked, already-present and reconciled pull requests."""

    def repo(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        (root / "TODO.md").write_text("# TODO\n")
        previous = Path.cwd()
        os.chdir(root)
        self.addCleanup(os.chdir, previous)
        return root

    def view(self):
        return {"headRefOid": "abc", "headRefName": "swarm/CW-001-1"}

    def test_a_protected_pr_is_skipped_before_any_merge(self):
        self.repo()
        merged = mock.Mock()
        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=["scripts/swarm.py"]), \
                mock.patch.object(swarm, "merge_local", merged), \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False), 0)
        merged.assert_not_called()

    def test_an_already_integrated_pr_is_closed_and_task_reopened(self):
        self.repo()
        out = io.StringIO()
        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "git", return_value="main-sha"), \
                mock.patch.object(swarm, "pr_already_in_main", return_value=True), \
                mock.patch.object(swarm, "close_integrated_pr") as closed, \
                mock.patch.object(swarm, "merge_local") as merged, \
                mock.patch.object(swarm, "emit"), \
                contextlib.redirect_stdout(out):
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False), 0)
        closed.assert_called_once_with(7, "CW-001")
        merged.assert_not_called()
        self.assertIn("already in main", out.getvalue())

    def test_close_integrated_pr_reopens_task_instead_of_marking_done(self):
        self.repo()
        text = "- [-] CW-001 · Staff\n  - agent: swarm-1\n"
        seen = []
        def update(edit, message, path="", attempts=8):
            seen.append(edit(text))
            return True
        with mock.patch.object(swarm, "gh"), mock.patch.object(swarm, "update_todo", side_effect=update):
            swarm.close_integrated_pr(7, "CW-001")
        self.assertIn("- [ ] CW-001", seen[0])
        self.assertNotIn("- [x] CW-001", seen[0])

    def test_a_failed_integrator_commit_reports_status_and_staged_diff(self):
        self.repo()
        error = subprocess.CalledProcessError(1, "git commit")

        def git(*args, **kwargs):
            if "commit" in args:
                raise error
            if args[:1] == ("status",):
                return "?? leftover\n"
            if "--cached" in args:
                return "TODO.md | 1 +\n"
            return "main-sha\n"

        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "pr_already_in_main", return_value=False), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
                mock.patch.object(swarm, "record_merges",
                                  return_value=([(7, "CW-001", "abc")], ["comment"], [])), \
                mock.patch.object(swarm, "emit"):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                swarm.integrate_batch([7], check_delivery=False)
        self.assertIn("--- status ---", str(raised.exception.output))
        self.assertIn("--- staged ---", str(raised.exception.output))

    def test_a_protected_main_rejection_stops_the_batch_without_retry(self):
        self.repo()
        error = subprocess.CalledProcessError(
            1, ("push",), stderr="error: GH013: Repository rule violations found for refs/heads/main."
        )
        with mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "git", return_value="main-sha"), \
                mock.patch.object(swarm, "pr_already_in_main", return_value=False), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
                mock.patch.object(swarm, "record_merges",
                                  return_value=([(7, "CW-001", "abc")], ["comment"], [])), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "authed_git", side_effect=error) as push, \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch, \
                mock.patch.object(swarm, "emit") as emit:
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False), swarm.INTEGRATION_FAILED)
        push.assert_called_once()
        emit.assert_any_call("merged", 0)
        write.assert_called_once_with("", "before\n")
        dispatch.assert_not_called()
        self.assertTrue(any("needs-human" in str(arg) for c in gh.call_args_list for arg in c.args))

    def test_a_label_failure_still_fails_the_protected_main_rejection(self):
        self.repo()
        push_error = subprocess.CalledProcessError(
            1, ("push",), stderr="error: GH013: Repository rule violations found for refs/heads/main."
        )
        label_error = subprocess.CalledProcessError(1, ("api",), stderr="label API failed")
        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "git", return_value="main-sha"), \
                mock.patch.object(swarm, "pr_already_in_main", return_value=False), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
                mock.patch.object(swarm, "record_merges",
                                  return_value=([(7, "CW-001", "abc")], ["comment"], [])), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "authed_git", side_effect=push_error) as push, \
                mock.patch.object(swarm, "add_labels", side_effect=label_error) as labels, \
                mock.patch.object(swarm, "remove_label") as remove, \
                mock.patch.object(swarm, "comment_best_effort") as comment, \
                mock.patch.object(swarm, "report_pr_stuck") as stuck, \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch, \
                mock.patch.object(swarm, "emit") as emit:
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                result = swarm.integrate_batch([7], check_delivery=False)
        self.assertEqual(result, swarm.INTEGRATION_FAILED)
        push.assert_called_once()
        labels.assert_called_once_with(7, "needs-human")
        remove.assert_called_once_with(7, "needs-fix")
        comment.assert_called_once()
        stuck.assert_called_once()
        write.assert_called_once_with("", "before\n")
        emit.assert_any_call("merged", 0)
        dispatch.assert_not_called()
        self.assertIn("could not label", stderr.getvalue())

    def test_integrate_publish_rejection_restores_tasks_and_returns_nonzero(self):
        root = self.repo()
        manifest = root / "manifest.json"
        bundle = root / "main.bundle"
        manifest.write_text(json.dumps({"tip": "tip", "accepted": [
            {"pr": 7, "task_id": "CW-001", "head_sha": "abc", "merge_sha": "merge"}],
            "gates": {"7": ["gate.sh", {}]}, "rejected": [], "already": [],
            "conflict_details": []}))
        bundle.write_text("synthetic")
        error = subprocess.CalledProcessError(1, ("push",), stderr="GH013 protected branch")
        # A rejected publish still labels, comments and opens an issue for a person.
        # Keep those trusted GitHub writes inside the fixture so the test stays hermetic.
        with mock.patch.object(swarm, "git", return_value="tip"), \
             mock.patch.object(swarm, "gh") as gh, \
             mock.patch.object(swarm, "gh_json", return_value={"headRefOid": "abc"}), \
             mock.patch.object(swarm, "report_validation"), \
             mock.patch.object(swarm, "record_merges",
                               return_value=([(7, "CW-001", "merge")], ["comment"], [])), \
             mock.patch.object(swarm, "todo_read", return_value="before\n"), \
             mock.patch.object(swarm, "todo_write") as write, \
             mock.patch.object(swarm, "authed_git", side_effect=error), \
             mock.patch.object(swarm, "dispatch_delivery") as dispatch, \
             mock.patch.object(swarm, "emit") as emit:
            self.assertEqual(swarm.integrate_publish(str(manifest), str(bundle)), swarm.INTEGRATION_FAILED)
        write.assert_called_once_with("", "before\n")
        dispatch.assert_not_called()
        emit.assert_any_call("merged", 0)
        self.assertTrue(any("needs-human" in str(arg) for c in gh.call_args_list for arg in c.args))

    def test_integrate_publish_with_no_accepted_prs_succeeds(self):
        """A conflict/report-only wave still publishes a manifest with an empty accepted list."""
        root = self.repo()
        manifest = root / "manifest.json"
        bundle = root / "main.bundle"
        manifest.write_text(json.dumps({"tip": "tip", "accepted": [], "gates": {},
                                        "rejected": [], "conflict_details": []}))
        bundle.write_text("synthetic")
        with mock.patch.object(swarm, "git", return_value="tip"), \
             mock.patch.object(swarm, "report_validation") as report, \
             mock.patch.object(swarm, "emit") as emit, \
             mock.patch.object(swarm, "integrate_finalize") as finalize:
            self.assertEqual(swarm.integrate_publish(str(manifest), str(bundle)), 0)
        report.assert_called_once()
        emit.assert_called_once_with("merged", 0)
        finalize.assert_not_called()

    def test_rejection_restores_existing_note_and_removes_new_note(self):
        root = self.repo()
        notes = root / "docs" / "swarm"
        notes.mkdir(parents=True)
        (notes / "CW-001.md").write_text("original note\n")
        merged = [(7, "CW-001", "abc"), (8, "CW-002", "def")]

        def record(items, gates):
            (notes / "CW-001.md").write_text("changed note\n")
            (notes / "CW-002.md").write_text("new note\n")
            swarm.todo_write("", "after\n")
            return items, ["one", "two"], []

        error = subprocess.CalledProcessError(1, ("push",), stderr="GH013 protected branch")
        with mock.patch.object(swarm, "record_merges", side_effect=record), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "git", return_value="main-sha"), \
                mock.patch.object(swarm, "authed_git", side_effect=error), \
                mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "report_pr_stuck"), \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch, \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_finalize(
                merged, {7: ("gate.sh", {}), 8: ("gate.sh", {})}, "gate.sh"),
                swarm.INTEGRATION_FAILED)
        self.assertEqual((notes / "CW-001.md").read_text(), "original note\n")
        self.assertFalse((notes / "CW-002.md").exists())
        write.assert_any_call("", "before\n")
        dispatch.assert_not_called()

    def test_retry_preserves_original_untracked_note(self):
        root = self.repo()
        notes = root / "docs" / "swarm"
        notes.mkdir(parents=True)
        original = notes / "CW-001.md"
        original.write_text("original untracked note\n")
        merged = [(7, "CW-001", "abc")]

        def record(items, gates):
            original.write_text("recorded note\n")
            swarm.todo_write("", "after\n")
            return items, ["comment"], []

        def git(*args, **kwargs):
            if args[:1] == ("merge-base",):
                return "base\n"
            if args[:1] == ("diff",):
                return ""
            if args[:2] == ("rev-parse", "origin/main"):
                return "a" * 40 + "\n"
            return "main-sha\n"

        pushes = iter([
            subprocess.CalledProcessError(1, ("push",), stderr="fetch first"),
            subprocess.CalledProcessError(1, ("push",), stderr="GH013 protected branch"),
        ])

        def authed(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                raise next(pushes)
            return ""

        with mock.patch.object(swarm, "record_merges", side_effect=record), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write"), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "restore_trackers", side_effect=lambda: original.unlink()), \
                mock.patch.object(swarm, "authed_git", side_effect=authed), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
                mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "report_pr_stuck"), \
                mock.patch.object(swarm, "dispatch_delivery"), \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(
                swarm.integrate_finalize(
                    merged, {7: ("gate.sh", {})}, "gate.sh"),
                swarm.INTEGRATION_RETRY)
        self.assertEqual(original.read_text(), "original untracked note\n")

    def test_reconciled_commit_failure_restores_records_and_stops(self):
        root = self.repo()
        notes = root / "docs" / "swarm"
        notes.mkdir(parents=True)
        original = notes / "CW-001.md"
        original.write_text("original note\n")
        merged = [(7, "CW-001", "abc")]
        commits = []

        def record(items, gates):
            original.write_text("recorded note\n")
            swarm.todo_write("", "after\n")
            return items, ["comment"], []

        def git(*args, **kwargs):
            commits.append((args, kwargs))
            if "Integrate swarm PRs (reconciled)" in args:
                raise subprocess.CalledProcessError(1, args, stderr="commit failed")
            if args[:1] == ("merge-base",):
                return "base\n"
            if args[:1] == ("diff",):
                return ""
            if args[:2] == ("rev-parse", "origin/main"):
                return "a" * 40 + "\n"
            return "main-sha\n"

        pushes = iter([subprocess.CalledProcessError(1, ("push",), stderr="fetch first")])

        def authed(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                raise next(pushes)
            return ""

        with mock.patch.object(swarm, "record_merges", side_effect=record), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "restore_trackers"), \
                mock.patch.object(swarm, "authed_git", side_effect=authed), \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch:
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.integrate_finalize(merged, {7: ("gate.sh", {})}, "gate.sh")
        reconciled = next(kwargs for args, kwargs in commits
                          if "Integrate swarm PRs (reconciled)" in args)
        self.assertIsNot(reconciled.get("check"), False)
        write.assert_any_call("", "before\n")
        self.assertEqual(original.read_text(), "original note\n")
        dispatch.assert_not_called()

    def test_pre_record_failure_restores_records(self):
        root = self.repo()
        notes = root / "docs" / "swarm"
        notes.mkdir(parents=True)
        original = notes / "CW-001.md"
        original.write_text("original note\n")

        def record(items, gates):
            original.write_text("partial note\n")
            swarm.todo_write("", "partial tasks\n")
            raise swarm.ReconciliationError("bad identity")

        with mock.patch.object(swarm, "record_merges", side_effect=record), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "authed_git") as push:
            with self.assertRaises(swarm.ReconciliationError):
                swarm.integrate_finalize([(7, "CW-001", "abc")], {7: ("gate.sh", {})}, "gate.sh")
        write.assert_any_call("", "before\n")
        self.assertEqual(original.read_text(), "original note\n")
        push.assert_not_called()

    def test_reconciled_base_change_retries_without_running_candidate_gate(self):
        root = self.repo()
        merged = [(7, "CW-001", "abc")]

        def git(*args, **kwargs):
            if args[:1] == ("merge-base",):
                return "base\n"
            if args[:1] == ("diff",):
                return ""
            if args[:2] == ("rev-parse", "origin/main"):
                return "a" * 40 + "\n"
            return "main-sha\n"

        pushes = iter([subprocess.CalledProcessError(1, ("push",), stderr="fetch first")])

        def authed(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                raise next(pushes)
            return ""

        with mock.patch.object(swarm, "record_merges",
                               return_value=(merged, ["comment"], [])), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write"), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "restore_trackers"), \
                mock.patch.object(swarm, "authed_git", side_effect=authed), \
                mock.patch.object(swarm, "run_gate") as gate, \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch:
            self.assertEqual(
                swarm.integrate_finalize(merged, {7: ("gate.sh", {})}, "gate.sh"),
                swarm.INTEGRATION_RETRY)
        gate.assert_not_called()
        dispatch.assert_not_called()

    def test_initial_add_failure_restores_records(self):
        self.repo()
        notes = Path("docs/swarm")
        notes.mkdir(parents=True)
        note = notes / "CW-001.md"
        note.write_text("original\n")
        error = subprocess.CalledProcessError(1, ("git", "add"))

        def record(items, gates):
            note.write_text("changed\n")
            swarm.todo_write("", "after\n")
            return items, ["comment"], []

        def git(*args, **kwargs):
            if args[:1] == ("add",):
                raise error
            return "main-sha\n"

        with mock.patch.object(swarm, "record_merges", side_effect=record), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "authed_git") as push:
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.integrate_finalize([(7, "CW-001", "abc")], {7: ("gate.sh", {})}, "gate.sh")
        write.assert_any_call("", "before\n")
        self.assertEqual(note.read_text(), "original\n")
        push.assert_not_called()

    def test_base_advance_restores_records_and_emits_the_retry_signal(self):
        self.repo()
        merged = [(7, "CW-001", "abc")]
        error = subprocess.CalledProcessError(1, ("push",), stderr="fetch first")

        def push(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                raise error
            return ""

        def record(items, gates):
            swarm.todo_write("", "after\n")
            return items, ["comment"], []

        def git(*args, **kwargs):
            if args[:2] == ("rev-parse", "origin/main"):
                return "a" * 40 + "\n"
            return "main-sha\n"

        with mock.patch.object(swarm, "record_merges", side_effect=record), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "authed_git", side_effect=push) as pushed, \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch, \
                mock.patch.object(swarm, "emit") as emitted:
            self.assertEqual(
                swarm.integrate_finalize(merged, {7: ("gate.sh", {})}, "gate.sh"),
                swarm.INTEGRATION_RETRY)
        self.assertTrue(any(c.args[0] == "integration_retry" for c in emitted.call_args_list))
        pushes = [c for c in pushed.call_args_list if c.args[:2] == ("push", "origin")]
        self.assertEqual(len(pushes), 1)
        write.assert_any_call("", "before\n")
        dispatch.assert_not_called()

    def test_retry_uses_latest_tasks_and_full_gate_environment(self):
        self.repo()
        reads = iter(["before\n", "fresh-main\n"])
        writes, records = [], []

        def record(items, gates):
            records.append(len(records) + 1)
            swarm.todo_write("", f"after-{len(records)}\n")
            return items, ["comment"], []

        def git(*args, **kwargs):
            if args[:1] == ("merge-base",):
                return "base\n"
            if args[:1] == ("diff",):
                return ""
            if args[:2] == ("rev-parse", "origin/main"):
                return "a" * 40 + "\n"
            return "main-sha\n"

        pushes = iter([subprocess.CalledProcessError(1, ("push",), stderr="fetch first")])

        def authed(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                raise next(pushes)
            return ""

        with mock.patch.object(swarm, "record_merges", side_effect=record), \
                mock.patch.object(swarm, "todo_read", side_effect=lambda: next(reads)), \
                mock.patch.object(swarm, "todo_write", side_effect=lambda path, text: writes.append(text)), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "restore_trackers"), \
                mock.patch.object(swarm, "authed_git", side_effect=authed), \
                mock.patch.object(swarm, "run_gate", return_value=(False, "failed")) as gate, \
                mock.patch.object(swarm, "fail"), \
                mock.patch.object(swarm, "dispatch_delivery"), \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(
                swarm.integrate_finalize(
                    [(7, "CW-001", "abc")], {7: ("scripts/quick-check.sh", {"STORY_ID": "CW-001"})},
                    "scripts/quick-check.sh"),
                swarm.INTEGRATION_RETRY)
        self.assertEqual(records, [1, 2])
        self.assertIn("fresh-main\n", writes)
        gate.assert_not_called()

    def test_rejection_removes_needs_fix_and_never_reports_merge(self):
        self.repo()
        error = subprocess.CalledProcessError(
            1, ("push",), stderr="error: GH013: Repository rule violations found for refs/heads/main."
        )
        with mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "git", return_value="main-sha"), \
                mock.patch.object(swarm, "pr_already_in_main", return_value=False), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
                mock.patch.object(swarm, "record_merges",
                                  return_value=([(7, "CW-001", "abc")], ["merged comment"], [])), \
                mock.patch.object(swarm, "close_merged_issues") as closed, \
                mock.patch.object(swarm, "authed_git", side_effect=error), \
                mock.patch.object(swarm, "dispatch_delivery") as dispatch, \
                mock.patch.object(swarm, "emit") as emit:
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False),
                             swarm.INTEGRATION_FAILED)
        emit.assert_any_call("merged", 0)
        closed.assert_not_called()
        dispatch.assert_not_called()
        self.assertTrue(any("labels%5B%5D=needs-human" in str(a) or "needs-human" in str(a)
                            for c in gh.call_args_list for a in c.args))
        self.assertFalse(any("merged comment" in str(a)
                             for c in gh.call_args_list for a in c.args))

    def test_reconciled_base_change_retries_instead_of_regating_candidate_code(self):
        self.repo()
        pushes = []

        def authed(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                pushes.append(args)
                if len(pushes) == 1:
                    raise subprocess.CalledProcessError(1, args, "", "fetch first")
            return ""

        def git(*args, **kwargs):
            if args[:1] == ("merge-base",):
                return "oldbase\n"
            if args[:1] == ("diff",) and "oldbase" in args:
                return "tasks/0001-A.md\0"
            if args[:2] == ("rev-parse", "origin/main"):
                return "a" * 40 + "\n"
            return "main-sha\n"

        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "authed_git", side_effect=authed), \
                mock.patch.object(swarm, "pr_already_in_main", return_value=False), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
                mock.patch.object(swarm, "gate_for", return_value=("custom-gate", {})), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")) as gate, \
                mock.patch.object(swarm, "record_merges",
                                  return_value=([(7, "CW-001", "abc")], ["comment"], [])), \
                mock.patch.object(swarm, "restore_trackers"), \
                mock.patch.object(swarm, "close_merged_issues"), \
                mock.patch.object(swarm, "dispatch_delivery"), \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(
                swarm.integrate_batch([7], check_delivery=False),
                swarm.INTEGRATION_RETRY)
        self.assertEqual(len(pushes), 1)
        gate.assert_called_once_with("custom-gate", {})


class IntegratorConflictPublishTest(unittest.TestCase):
    """A conflict resolution that cannot be applied is reported, never pushed."""

    def outcomes(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        (root / "5").write_text("merged")
        (root / "5.bundle").write_text("synthetic bundle")
        return root

    def info(self):
        return {"headRefName": "swarm/CW-001-1", "isCrossRepository": False}

    def test_an_unreadable_bundle_is_reported_and_not_pushed(self):
        root = self.outcomes()

        def git(*args, **kwargs):
            if args[:1] == ("fetch",):
                raise subprocess.CalledProcessError(1, args, "", "bad bundle")
            return ""

        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "gh_json", return_value=self.info()), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "authed_git") as pushed, \
                mock.patch.object(swarm, "fail") as failed, \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.resolve_publish(str(root)), [])
        pushed.assert_not_called()
        self.assertIn("could not be read", failed.call_args.args[1])

    def test_a_resolved_branch_can_be_pushed_without_a_second_dispatch(self):
        root = self.outcomes()
        (root / "5").unlink()
        (root / "5.bundle").unlink()
        (root / "7").write_text("merged")
        (root / "7.bundle").write_text("synthetic bundle")
        with mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "gh_json", return_value=self.info()), \
                mock.patch.object(swarm, "git", return_value=""), \
                mock.patch.object(swarm, "authed_git") as pushed, \
                mock.patch.object(swarm, "fail"), \
                mock.patch.object(swarm, "emit") as emitted:
            self.assertEqual(swarm.resolve_publish(str(root), dispatch=False), ['7'])
        pushed.assert_called_once()
        emit = [c for c in emitted.call_args_list if c.args[0] == "pushed"]
        self.assertEqual(json.loads(emit[0].args[1]), ['7'])
        self.assertFalse(any(c.args[:2] == ("workflow", "run")
                             for c in gh.call_args_list))

    def test_a_branch_that_moved_during_resolution_is_sent_back(self):
        root = self.outcomes()

        def authed(*args, **kwargs):
            if args[:1] == ("push",):
                raise subprocess.CalledProcessError(1, args, "", "non fast forward")
            return ""

        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "gh_json", return_value=self.info()), \
                mock.patch.object(swarm, "git", return_value=""), \
                mock.patch.object(swarm, "authed_git", side_effect=authed), \
                mock.patch.object(swarm, "fail") as failed, \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.resolve_publish(str(root)), [])
        self.assertIn("branch moved", failed.call_args.args[1])


class IntegratorIssueCloseTest(unittest.TestCase):
    """The issue hand-off kill switch stops issue closure on merge."""

    def test_no_issue_is_closed_when_the_issue_handoff_is_off(self):
        fake = FakeGh()
        with mock.patch.dict(os.environ, {"SWARM_ISSUES": "off"}), \
                mock.patch.object(swarm, "gh", fake):
            swarm.close_merged_issues([(7, "ISS-5")])
        self.assertEqual(fake.calls, [])


class IntegratorTreeBindingTest(unittest.TestCase):
    """A candidate gate cannot substitute a different tree after validation."""

    def prepare(self, tree_after):
        self.repo = tempfile.TemporaryDirectory()
        self.addCleanup(self.repo.cleanup)
        old = os.getcwd()
        os.chdir(self.repo.name)
        self.addCleanup(os.chdir, old)
        Path("tasks").mkdir()
        Path("tasks/CW-001.md").write_text("- [ ] CW-001 · synthetic\n")
        tree_calls = iter([("tree\n"), (tree_after)])

        def git(*args, **kwargs):
            if args[:2] == ("rev-parse", "HEAD^{tree}"):
                return next(tree_calls)
            if args[:2] == ("rev-parse", "HEAD"):
                return "merge\n"
            return ""

        with mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "gh_json", return_value={
                    "headRefOid": "abc", "headRefName": "swarm/CW-001-1"}), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "pr_already_in_main", return_value=False), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "_checker", return_value=object()), \
                mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
                mock.patch.object(swarm, "gate_for", return_value=("gate.sh", {})), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")):
            return swarm.integrate_prepare([7], check_delivery=False)

    def test_accepted_head_is_bound_to_the_gated_tree(self):
        result = self.prepare("tree\n")
        self.assertEqual(result["accepted"][0]["tree_sha"], "tree")

    def test_gate_tree_mutation_rejects_the_head(self):
        result = self.prepare("changed\n")
        self.assertEqual(result["accepted"], [])
        self.assertEqual(result["rejected"][0]["kind"], "mutated")


class IntegrateSealTest(unittest.TestCase):
    """A fresh trusted job rebuilds only independently eligible exact PR heads."""

    BASE = "a" * 40
    HEAD = "b" * 40
    TIP = "c" * 40
    TREE = "d" * 40

    def payload(self, accepted=None, **extra):
        return {"accepted": accepted or [], "rejected": [], "already": [],
                "conflicted": [], "conflict_details": [], **extra}

    def item(self, pr=7, head=None, tree=None):
        return {"pr": pr, "head_sha": head or self.HEAD,
                "tree_sha": tree or self.TREE, "task_id": "forged"}

    def run_seal(self, payload, base=None, raw=None, **overrides):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "payload.json"
            source.write_text(raw if raw is not None else json.dumps(payload), encoding="utf-8")
            out = Path(directory) / "validated"
            self.git_calls = []

            def git(*args, **kwargs):
                self.git_calls.append(args)
                if args[:2] == ("rev-parse", "HEAD"):
                    return self.TIP + "\n"
                if args[:2] == ("rev-parse", "HEAD^{tree}"):
                    return self.TREE + "\n"
                if args[:2] == ("bundle", "create"):
                    Path(args[2]).write_bytes(b"synthetic bundle")
                return ""

            patches = {
                "git": mock.patch.object(swarm, "git", side_effect=git),
                "merge_local": mock.patch.object(
                    swarm, "merge_local", return_value=(self.HEAD, "CW-001", [])
                ),
                "guard": mock.patch.object(swarm, "guard", return_value=("", [], 0)),
                "is_repair": mock.patch.object(swarm, "is_repair", return_value=False),
                "delivery_gate": mock.patch.object(
                    swarm, "delivery_gate", return_value="merge"
                ),
                "checker": mock.patch.object(swarm, "_checker", return_value=object()),
                "wiring": mock.patch.object(
                    swarm, "refresh_wiring_baseline", return_value=""
                ),
                "gate_for": mock.patch.object(
                    swarm, "gate_for",
                    return_value=("scripts/integrator-gate.sh", {"STORY_ID": "CW-001"}),
                ),
            }
            for name, patch in patches.items():
                if name in overrides:
                    patches[name] = overrides[name]
            try:
                with patches["git"], patches["merge_local"], patches["guard"], \
                        patches["is_repair"], patches["delivery_gate"], patches["checker"], \
                        patches["wiring"], patches["gate_for"]:
                    manifest = swarm.integrate_seal(self.BASE if base is None else base,
                                                    str(source), str(out))
            except Exception:
                self.failure_artifacts = (
                    (out / "manifest.json").exists(), (out / "main.bundle").exists()
                )
                raise
            return manifest, json.loads((out / "manifest.json").read_text()), \
                (out / "main.bundle").exists()

    def test_seal_rebuilds_exact_heads_and_bundles_the_trusted_tip(self):
        guard = mock.Mock(return_value=("", [], 0))
        delivery = mock.Mock(return_value="merge")
        merge = mock.Mock(return_value=(self.HEAD, "CW-001", []))
        manifest, written, bundled = self.run_seal(
            self.payload([self.item()]),
            guard=mock.patch.object(swarm, "guard", guard),
            delivery_gate=mock.patch.object(swarm, "delivery_gate", delivery),
            merge_local=mock.patch.object(swarm, "merge_local", merge),
        )
        self.assertEqual(self.git_calls[0], ("checkout", "-q", "--detach", self.BASE))
        guard.assert_called_once_with(7, False, report=False)
        delivery.assert_called_once_with(7, self.HEAD, report=False)
        merge.assert_called_once_with(7)
        self.assertEqual(manifest["base"], self.BASE)
        self.assertEqual(manifest["tip"], self.TIP)
        self.assertEqual(manifest["accepted"], [
            {"pr": 7, "task_id": "CW-001", "head_sha": self.HEAD, "merge_sha": self.HEAD}
        ])
        self.assertEqual(manifest["gates"]["7"], [
            "scripts/integrator-gate.sh", {"STORY_ID": "CW-001"}
        ])
        self.assertEqual(written, manifest)
        self.assertTrue(bundled)

    def test_seal_accepts_an_empty_batch(self):
        manifest, _, bundled = self.run_seal(self.payload())
        self.assertEqual(manifest["accepted"], [])
        self.assertEqual(manifest["tip"], self.TIP)
        self.assertTrue(bundled)

    def test_seal_rejects_invalid_base_and_payload_shapes(self):
        for base in ("", "ABC", "a" * 39):
            with self.subTest(base=base), self.assertRaisesRegex(SystemExit, "base must"):
                self.run_seal(self.payload(), base=base)
        for payload in ([], {}, {"accepted": {}}, {"accepted": ["bad"]}):
            with self.subTest(payload=payload), self.assertRaises(SystemExit):
                self.run_seal(payload)

    def test_seal_rejects_invalid_and_duplicate_pr_numbers(self):
        invalid = (True, 0, -1, "7")
        for pr in invalid:
            with self.subTest(pr=pr), self.assertRaisesRegex(SystemExit, "PR number"):
                self.run_seal(self.payload([self.item(pr=pr)]))
        with self.assertRaisesRegex(SystemExit, "duplicate PR"):
            self.run_seal(self.payload([self.item(), self.item()]))

    def test_seal_rejects_invalid_head_shas(self):
        for head in (None, 7, "", "b" * 39, "B" * 40):
            with self.subTest(head=head), self.assertRaisesRegex(SystemExit, "head SHA"):
                self.run_seal(self.payload([{"pr": 7, "head_sha": head}]))

    def test_seal_rechecks_policy_override_and_delivery_evidence(self):
        for blocked in (None, "override", ("", [], 0)):
            with self.subTest(blocked=blocked):
                self.run_seal(
                    self.payload([self.item()]),
                    guard=mock.patch.object(swarm, "guard", return_value=blocked),
                )
        for blocked in ("protected", ("protected", ["x"], 1)):
            with self.subTest(blocked=blocked), \
                    self.assertRaisesRegex(SystemExit, "no longer eligible"):
                self.run_seal(
                    self.payload([self.item()]),
                    guard=mock.patch.object(swarm, "guard", return_value=blocked),
                )
        with self.assertRaisesRegex(SystemExit, "no longer has merge evidence"):
            self.run_seal(
                self.payload([self.item()]),
                delivery_gate=mock.patch.object(swarm, "delivery_gate", return_value="wait"),
            )

    def test_seal_rejects_invalid_or_changed_tree_shas(self):
        for tree in (None, 7, "", "d" * 39, "D" * 40):
            item = self.item()
            item["tree_sha"] = tree
            with self.subTest(tree=tree), self.assertRaisesRegex(SystemExit, "tree SHA"):
                self.run_seal(self.payload([item]))
        with self.assertRaisesRegex(SystemExit, "gated tree changed"):
            self.run_seal(self.payload([self.item(tree="e" * 40)]))

    def test_seal_drops_candidate_reporting_decisions(self):
        payload = self.payload(
            [self.item()],
            rejected=[{"pr": 99, "kind": "forged"}],
            already=[{"pr": 98, "task_id": "forged"}],
            conflicted=[97],
            conflict_details=[{"pr": 96, "reason": "forged", "tries": 0}],
        )
        manifest, _, _ = self.run_seal(payload)
        for key in ("rejected", "already", "conflicted", "conflict_details"):
            self.assertEqual(manifest[key], [])

    def test_seal_rejects_malformed_json_without_artifacts(self):
        with self.assertRaises(json.JSONDecodeError):
            self.run_seal(None, raw="{bad")
        self.assertEqual(self.failure_artifacts, (False, False))

    def test_seal_handles_multiple_exact_heads_in_order(self):
        second = "e" * 40
        merge = mock.Mock(side_effect=[(self.HEAD, "CW-001", []), (second, "CW-002", [])])
        manifest, _, bundled = self.run_seal(
            self.payload([self.item(), self.item(pr=8, head=second)]),
            merge_local=mock.patch.object(swarm, "merge_local", merge),
        )
        self.assertTrue(bundled)
        self.assertEqual([item["pr"] for item in manifest["accepted"]], [7, 8])
        self.assertEqual([item["head_sha"] for item in manifest["accepted"]],
                         [self.HEAD, second])
        self.assertEqual(merge.call_args_list, [mock.call(7), mock.call(8)])

    def test_seal_rejects_conflicts_moved_heads_and_wiring_failures(self):
        self.failure_artifacts = (False, False)
        cases = (
            ("conflicts in x", {"merge_local": (None, "CW-001", ["x"])}),
            ("conflicts in unknown files", {"merge_local": (None, "CW-001", [])}),
            ("head moved", {"merge_local": ("f" * 40, "CW-001", [])}),
            ("already integrated", {"wiring": None}),
            ("wiring check failed", {"wiring": "new finding"}),
        )
        for message, values in cases:
            overrides = {}
            if "merge_local" in values:
                overrides["merge_local"] = mock.patch.object(
                    swarm, "merge_local", return_value=values["merge_local"]
                )
            if "wiring" in values:
                overrides["wiring"] = mock.patch.object(
                    swarm, "refresh_wiring_baseline", return_value=values["wiring"]
                )
            with self.subTest(message=message), \
                    self.assertRaisesRegex(SystemExit, message):
                self.run_seal(self.payload([self.item()]), **overrides)
            self.assertEqual(self.failure_artifacts, (False, False))


if __name__ == "__main__":
    unittest.main()


class ValidatorReportTest(unittest.TestCase):
    """Validation emits every decision that the trusted publisher must replay."""

    def test_integrate_validate_emits_decisions_needing_publication(self):
        result = {"accepted": [], "rejected": [{"pr": 7, "kind": "moved"}],
                  "already": [{"pr": 8, "task_id": "CW-001"}], "conflicted": [],
                  "conflict_details": [{"pr": 9, "reason": "conflict", "tries": 5}],
                  "gates": {}}
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.object(swarm, "integrate_prepare", return_value=result), \
             mock.patch.object(swarm, "git", return_value="tip"), \
             mock.patch.object(swarm, "emit") as emitted:
            swarm.integrate_validate([7, 8, 9], "gate.sh", str(Path(directory) / "validated"))
        reports = [json.loads(call.args[1]) for call in emitted.call_args_list
                   if call.args[0] == "report"]
        self.assertEqual(len(reports), 1)
        self.assertEqual(len(reports[0]), 3)
        self.assertEqual({item["pr"] for item in reports[0]}, {7, 8, 9})
        seals = [json.loads(call.args[1]) for call in emitted.call_args_list
                 if call.args[0] == "seal"]
        self.assertEqual(seals, [{"accepted": []}])

    def test_integrate_validate_emits_exact_heads_and_trees_to_the_seal(self):
        """The trusted seal payload carries exact PR heads and gated trees."""
        head_a, tree_a, head_b, tree_b = ("a" * 40, "b" * 40, "c" * 40, "d" * 40)
        result = {
            "accepted": [
                {"pr": 1768, "task_id": "AUD-19", "head_sha": head_a,
                 "merge_sha": head_a, "tree_sha": tree_a},
                {"pr": 1769, "task_id": "AUD-14", "head_sha": head_b,
                 "merge_sha": head_b, "tree_sha": tree_b},
            ],
            "rejected": [], "already": [], "conflicted": [], "conflict_details": [],
            "gates": {},
        }
        with tempfile.TemporaryDirectory() as directory, \
             mock.patch.object(swarm, "integrate_prepare", return_value=result), \
             mock.patch.object(swarm, "git", return_value="tip"), \
             mock.patch.object(swarm, "emit") as emitted:
            swarm.integrate_validate([1768, 1769], "gate.sh", str(Path(directory) / "validated"))
        seals = [json.loads(call.args[1]) for call in emitted.call_args_list
                 if call.args[0] == "seal"]
        self.assertEqual(seals, [{"accepted": [
            {"pr": 1768, "head_sha": head_a, "tree_sha": tree_a},
            {"pr": 1769, "head_sha": head_b, "tree_sha": tree_b},
        ]}])
        accepted = [json.loads(call.args[1]) for call in emitted.call_args_list
                    if call.args[0] == "accepted"]
        self.assertEqual(accepted, [[1768, 1769]])


class IntegratorBaseAdvanceRecoveryTest(unittest.TestCase):
    """A publish race with a newer main is a bounded recovery signal, not a crash."""

    NEW_BASE = "a" * 40

    def repo(self):
        root = scratch()
        self.addCleanup(shutil.rmtree, root)
        (root / "TODO.md").write_text("# TODO\n")
        previous = Path.cwd()
        os.chdir(root)
        self.addCleanup(os.chdir, previous)
        return root

    def git_for_advance(self, *args, **kwargs):
        if args[:1] == ("merge-base",):
            return "old\n"
        if args[:1] == ("diff",):
            return "scripts/swarm.py\0"
        if args[:2] == ("rev-parse", "origin/main"):
            return self.NEW_BASE + "\n"
        return "tip\n"

    def run_publish(self, stderr, attempt=0, base=None):
        self.repo()
        merged = [(7, "CW-001", "abc")]
        push_error = subprocess.CalledProcessError(1, ("push",), stderr=stderr)

        def authed(*args, **kwargs):
            if args[:2] == ("push", "origin"):
                raise push_error
            return ""

        git_for_publish = self.git_for_advance
        if base is not None:
            def git_for_publish(*args, **kwargs):
                if args[:2] == ("rev-parse", "origin/main"):
                    return base + "\n"
                return self.git_for_advance(*args, **kwargs)

        with mock.patch.object(swarm, "record_merges",
                               return_value=(merged, ["comment"], [])), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "git", side_effect=git_for_publish), \
                mock.patch.object(swarm, "authed_git", side_effect=authed), \
                mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "report_pr_stuck"), \
                mock.patch.object(swarm, "emit") as emit, \
                contextlib.redirect_stderr(io.StringIO()):
            result = swarm.integrate_finalize(
                merged, {7: ("gate.sh", {})}, "gate.sh", attempt=attempt)
        return result, emit, write, gh

    def test_non_tracker_base_advance_emits_only_a_bounded_retry_signal(self):
        result, emit, write, gh = self.run_publish(
            "! [rejected] HEAD -> main (non-fast-forward)")
        self.assertEqual(result, swarm.INTEGRATION_RETRY)
        retry = [c for c in emit.call_args_list if c.args[0] == "integration_retry"]
        self.assertEqual(len(retry), 1)
        payload = json.loads(retry[0].args[1])
        self.assertEqual(payload, {"id": "base_advanced", "new_base": self.NEW_BASE,
                                   "prs": [7], "attempt": 0})
        emit.assert_any_call("merged", 0)
        write.assert_called_once_with("", "before\n")
        self.assertFalse(any("needs-human" in str(arg)
                            for call in gh.call_args_list for arg in call.args))

    def test_repository_rule_rejection_stays_terminal_even_with_retry_enabled(self):
        result, emit, _write, gh = self.run_publish(
            "error: GH013: Repository rule violations found for refs/heads/main.")
        self.assertEqual(result, swarm.INTEGRATION_FAILED)
        self.assertFalse(any(c.args[0] == "integration_retry" for c in emit.call_args_list))
        self.assertTrue(any("needs-human" in str(arg)
                            for call in gh.call_args_list for arg in call.args))

    def test_second_base_advance_emits_the_next_recovery_cycle(self):
        result, emit, write, gh = self.run_publish(
            "! [rejected] HEAD -> main (non-fast-forward)", attempt=1)
        self.assertEqual(result, swarm.INTEGRATION_RETRY)
        retry = [c for c in emit.call_args_list if c.args[0] == "integration_retry"]
        self.assertEqual(len(retry), 1)
        payload = json.loads(retry[0].args[1])
        self.assertEqual(payload, {"id": "base_advanced", "new_base": self.NEW_BASE,
                                   "prs": [7], "attempt": 1})
        self.assertFalse(any("needs-human" in str(arg)
                             for call in gh.call_args_list for arg in call.args))
        write.assert_called_once_with("", "before\n")

    def test_third_base_advance_at_the_cap_is_terminal(self):
        result, emit, write, gh = self.run_publish(
            "! [rejected] HEAD -> main (non-fast-forward)", attempt=2)
        self.assertEqual(result, swarm.INTEGRATION_FAILED)
        self.assertFalse(any(c.args[0] == "integration_retry" for c in emit.call_args_list))
        self.assertTrue(any("needs-human" in str(arg)
                            for call in gh.call_args_list for arg in call.args))
        write.assert_called_once_with("", "before\n")

    def test_retry_payload_rejects_malformed_or_secret_bearing_values(self):
        for attempt in (0, 1):
            with self.subTest(attempt=attempt):
                self.assertEqual(
                    swarm.integration_retry_payload("base_advanced", self.NEW_BASE, [7], attempt),
                    json.dumps({"id": "base_advanced", "new_base": self.NEW_BASE,
                                "prs": [7], "attempt": attempt}, separators=(",", ":")))
        self.assertEqual(swarm.integration_retry_payload("other", self.NEW_BASE, [7], 0), "")
        self.assertEqual(swarm.integration_retry_payload("base_advanced", "short", [7], 0), "")
        self.assertEqual(swarm.integration_retry_payload("base_advanced", self.NEW_BASE, ["7"], 0), "")
        self.assertEqual(swarm.integration_retry_payload("base_advanced", self.NEW_BASE, [7], 2), "")
        self.assertEqual(swarm.integration_retry_payload("base_advanced", self.NEW_BASE, [7], -1), "")
        self.assertEqual(swarm.integration_retry_payload("base_advanced", self.NEW_BASE, [], 0), "")

    def test_malformed_new_base_fails_closed_without_a_retry_signal(self):
        result, emit, write, _gh = self.run_publish(
            "! [rejected] HEAD -> main (non-fast-forward)", base="not-a-sha")
        self.assertEqual(result, swarm.INTEGRATION_FAILED)
        self.assertFalse(any(c.args[0] == "integration_retry" for c in emit.call_args_list))
        write.assert_called_once_with("", "before\n")

    def test_successful_push_breaks_the_reconciliation_loop(self):
        self.repo()
        merged = [(7, "CW-001", "abc")]
        with mock.patch.object(swarm, "record_merges",
                               return_value=(merged, ["comment"], [])), \
                mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write"), \
                mock.patch.object(swarm, "git", return_value="tip\n"), \
                mock.patch.object(swarm, "authed_git") as push, \
                mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "emit") as emit:
            result = swarm.integrate_finalize(
                merged, {7: ("gate.sh", {})}, "gate.sh")
        self.assertEqual(result, 1)
        push.assert_called_once_with("push", "origin", "HEAD:main")
        emit.assert_any_call("merged", 1)

