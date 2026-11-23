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
        good.write_text("see pr-notes/KEEP.md")
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
            "diff --git a/AGENTS.md b/AGENTS.md\n--- a/AGENTS.md\n+++ b/AGENTS.md\n"
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

    def test_closing_an_integrated_pr_marks_both_open_task_forms_done(self):
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
        self.assertEqual(rewritten, ["- [x] CW-001\n", "- [x] CW-001\n", "nothing to change"])

    def test_closing_an_integrated_pr_without_a_task_still_comments(self):
        fake = FakeGh()
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "update_todo") as update:
            swarm.close_integrated_pr(7, "")
        self.assertTrue(fake.calls_for("pr", "close"))
        update.assert_not_called()

    def test_a_tracker_write_failure_does_not_stop_the_close(self):
        fake = FakeGh()
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "update_todo", side_effect=OSError("locked")):
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
                mock.patch.object(swarm, "migrate_tasks"), \
                mock.patch.object(swarm, "gh_json", return_value=self.view()), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=["scripts/swarm.py"]), \
                mock.patch.object(swarm, "merge_local", merged), \
                mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False), 0)
        merged.assert_not_called()

    def test_an_already_integrated_pr_is_closed_and_marked_done(self):
        self.repo()
        out = io.StringIO()
        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "migrate_tasks"), \
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
                mock.patch.object(swarm, "migrate_tasks"), \
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
                mock.patch.object(swarm, "migrate_tasks"), \
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
                mock.patch.object(swarm, "authed_git", side_effect=error) as push, \
                mock.patch.object(swarm, "emit") as emit:
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False), 0)
        push.assert_called_once()
        emit.assert_any_call("merged", 0)
        self.assertTrue(any(c.args[:2] == ("pr", "comment") for c in gh.call_args_list))

    def test_a_reconciled_regate_uses_the_repair_gate_when_no_full_gate_was_used(self):
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
            return "main-sha\n"

        with mock.patch.object(swarm, "gh"), \
                mock.patch.object(swarm, "migrate_tasks"), \
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
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False), 1)
        self.assertEqual(len(pushes), 2)
        gate.assert_any_call(swarm.REPAIR_GATE, {})


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


if __name__ == "__main__":
    unittest.main()
