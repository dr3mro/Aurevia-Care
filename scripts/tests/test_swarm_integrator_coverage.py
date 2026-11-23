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
        self.assertIn("fails the docs check", err)
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


class RepairTaskTest(unittest.TestCase):
    """A run that is red only because of its workers needs no automatic repair."""

    def test_a_worker_only_failure_returns_no_repair_task(self):
        """A routine worker failure must not queue a repair that blocks the whole swarm."""
        run = {"name": "Agent swarm", "headBranch": "main", "conclusion": "failure",
               "headSha": "abc123456789", "url": "https://example.invalid/runs/9",
               "jobs": [{"name": "Swarm worker (story, CW-001)", "conclusion": "failure"}]}
        with mock.patch.object(swarm, "gh_json", return_value=run), \
                mock.patch.object(swarm, "gh") as gh:
            self.assertIsNone(swarm.repair_task("9"))
        gh.assert_not_called()


class IntegrateBatchGuardTest(unittest.TestCase):
    """A batch stops a pull request at the guard or at its own merge gate."""

    def test_a_blocked_pr_is_skipped_before_any_merge(self):
        """A guard finding skips the PR while the rest of the batch is still processed."""
        with mock.patch.object(swarm, "migrate_tasks") as migrate, \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=["scripts/swarm.py"]) as guard, \
                mock.patch.object(swarm, "gh_json") as query, \
                mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "merge_local") as merge, \
                mock.patch.object(swarm, "emit") as emitted:
            count = swarm.integrate_batch([5], check_delivery=False)
        self.assertEqual(count, 0)
        guard.assert_called_once_with(5, False)
        merge.assert_not_called()
        query.assert_not_called()
        gh.assert_not_called()
        migrate.assert_called_once_with()
        emitted.assert_any_call("merged", 0)

    def test_a_failing_merge_gate_fails_the_pr_and_resets_the_merge(self):
        """A red gate sends the PR back to the workers and rolls back its merge."""
        with mock.patch.object(swarm, "migrate_tasks"), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "gh_json", return_value={"headRefOid": "abc"}), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "gate_for", return_value=("gate.sh", {})), \
                mock.patch.object(swarm, "run_gate", return_value=(False, "gate failed")), \
                mock.patch.object(swarm, "git") as git, \
                mock.patch.object(swarm, "fail") as failed, \
                mock.patch.object(swarm, "emit"):
            count = swarm.integrate_batch([5], check_delivery=False)
        self.assertEqual(count, 0)
        failed.assert_called_once()
        self.assertEqual(failed.call_args.args[0], 5)
        self.assertIn("gate.sh", failed.call_args.args[1])
        self.assertIn("gate failed", failed.call_args.args[2])
        self.assertTrue(any(c.args[:3] == ("reset", "--hard", "HEAD~1")
                            for c in git.call_args_list))

    def test_a_failed_integrate_commit_is_reported_with_git_state(self):
        """A commit that loses a race raises with status and staged output attached."""
        error = subprocess.CalledProcessError(1, "git commit", "out", "err")

        def git(*args, **kwargs):
            if "commit" in args:
                raise error
            if args[:2] == ("status", "--short"):
                return "?? untracked\n"
            if args[:2] == ("diff", "--cached"):
                return "A file\n"
            return ""

        with mock.patch.object(swarm, "migrate_tasks"), \
                mock.patch.object(swarm, "is_repair", return_value=False), \
                mock.patch.object(swarm, "guard", return_value=[]), \
                mock.patch.object(swarm, "gh_json", return_value={"headRefOid": "abc"}), \
                mock.patch.object(swarm, "merge_local", return_value=("abc", "CW-001", [])), \
                mock.patch.object(swarm, "gate_for", return_value=("gate.sh", {})), \
                mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
                mock.patch.object(swarm, "record_merges",
                                  return_value=([(5, "CW-001", "abc")], ["comment"], [])), \
                mock.patch.object(swarm, "todo_paths", return_value=[]), \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "emit"):
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                swarm.integrate_batch([5], check_delivery=False)
        self.assertIn("--- status ---", raised.exception.output)
        self.assertIn("?? untracked", raised.exception.output)
        self.assertIn("--- staged ---", raised.exception.output)


class ResolvePublishFailureTest(unittest.TestCase):
    """A resolution whose artifact or push fails goes back to the workers."""

    def setUp(self):
        self.root = scratch()
        self.addCleanup(shutil.rmtree, self.root)
        (self.root / "5").write_text("merged")
        (self.root / "5.bundle").write_bytes(b"synthetic bundle")
        self.info = {"headRefName": "swarm/CW-001-1", "isCrossRepository": False}

    def test_an_unreadable_bundle_is_reported_and_not_pushed(self):
        """A bundle that cannot be fetched never becomes a pushed resolution."""
        def git(*args, **kwargs):
            if args[:1] == ("fetch",):
                raise subprocess.CalledProcessError(1, "git fetch")
            return ""

        with mock.patch.object(swarm, "gh_json", return_value=self.info), \
                mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "git", side_effect=git), \
                mock.patch.object(swarm, "authed_git") as push, \
                mock.patch.object(swarm, "fail") as failed, \
                mock.patch.object(swarm, "emit") as emitted:
            pushed = swarm.resolve_publish(self.root)
        self.assertEqual(pushed, [])
        push.assert_not_called()
        failed.assert_called_once()
        self.assertEqual(failed.call_args.args[0], 5)
        self.assertIn("could not be read", failed.call_args.args[1])
        emitted.assert_any_call("pushed", "[]")
        emitted.assert_any_call("failed", '["5"]')
        self.assertFalse(any(c.args[:2] == ("workflow", "run") for c in gh.call_args_list))

    def test_a_branch_that_moved_during_resolution_is_reported(self):
        """A stale resolution branch is failed back instead of being force-pushed."""
        error = subprocess.CalledProcessError(1, "git push")
        with mock.patch.object(swarm, "gh_json", return_value=self.info), \
                mock.patch.object(swarm, "gh") as gh, \
                mock.patch.object(swarm, "git", return_value=""), \
                mock.patch.object(swarm, "authed_git", side_effect=error), \
                mock.patch.object(swarm, "fail") as failed, \
                mock.patch.object(swarm, "emit") as emitted:
            pushed = swarm.resolve_publish(self.root)
        self.assertEqual(pushed, [])
        failed.assert_called_once()
        self.assertIn("the branch moved while the conflict was being resolved",
                      failed.call_args.args[1])
        emitted.assert_any_call("failed", '["5"]')
        self.assertFalse(any(c.args[:2] == ("workflow", "run") for c in gh.call_args_list))


if __name__ == "__main__":
    unittest.main()
