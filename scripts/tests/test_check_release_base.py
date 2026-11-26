"""Regression tests for the release precondition's exact-SHA green-gate check.

A newer push cancels a Delivery run at run level while its required gate job
stays green. The release check must accept that job-level evidence, and must
never accept a pull request, a wrong commit, a wrong branch or a failed gate.
"""
import contextlib
import io
import json
import runpy
import sys
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

checker = load("check-release-base")

SHA = "a" * 40
OTHER_SHA = "b" * 40
REPO = "owner/repo"
# Fields observed on Delivery dispatch run 45784861813: GitHub reports the
# default branch as its head branch. A bare SHA ref cannot be dispatched.
OBSERVED_DISPATCH_SHA = "f5aa25e5ae81cd19c13d0daec03567a83960a1a9"


def record(**overrides):
    """A green push run on the exact commit unless a field is overridden."""
    base = {
        "id": 1,
        "head_sha": SHA,
        "head_branch": "main",
        "event": "push",
        "status": "completed",
        "conclusion": "success",
        "head_repository": {"full_name": REPO},
    }
    base.update(overrides)
    return base


def gate(run_id, conclusion="success", name="Delivery gate"):
    """A synthetic required-check job for one run."""
    return {"name": name, "conclusion": conclusion}


def check(runs, sha=SHA, branch="main", repo=REPO, jobs=None):
    """Call main with an envelope and capture both output streams.

    By default every run whose run-level conclusion is ``success`` carries a
    green gate, so the identity tests stay focused. The bug cases pass jobs
    explicitly to control the gate verdict independently of the run verdict.
    """
    if jobs is None:
        jobs = {}
        for item in runs.get("workflow_runs", []):
            if isinstance(item, dict) and item.get("conclusion") == "success":
                jobs[str(item.get("id"))] = [gate(item.get("id"))]
    envelope = {"runs": runs, "jobs": jobs}
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = checker.main(["--sha", sha, "--branch", branch, "--repo", repo],
                            stream=io.StringIO(json.dumps(envelope)))
    return code, out.getvalue(), err.getvalue()


def state(runs, sha=SHA, branch="main", repo=REPO):
    """Ask the checker whether a trusted Delivery run for the commit is still going."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = checker.main(
            ["--state", "--sha", sha, "--branch", branch, "--repo", repo],
            stream=io.StringIO(json.dumps({"runs": runs, "jobs": {}})),
        )
    return code, out.getvalue().strip(), err.getvalue()


class ReleaseWaitStateTests(unittest.TestCase):
    """The workflow must not decide red or green while Delivery is still running."""

    def test_an_in_progress_run_on_the_exact_commit_is_pending(self):
        for status in ("in_progress", "queued", "waiting"):
            with self.subTest(status=status):
                code, out, err = state({"workflow_runs": [record(status=status)]})
                self.assertEqual((code, out, err), (0, "pending", ""))

    def test_a_completed_run_is_ready_even_when_its_gate_failed(self):
        runs = {"workflow_runs": [record(status="completed", conclusion="failure")]}
        self.assertEqual(state(runs)[1], "ready")

    def test_a_non_qualifying_run_never_holds_the_release(self):
        for run in (record(head_sha=OTHER_SHA, status="in_progress"),
                    record(head_branch="feature", status="in_progress"),
                    record(event="pull_request", status="in_progress"),
                    record(head_repository={"full_name": "other/repo"}, status="in_progress"),
                    record(head_repository="owner/repo", status="in_progress"),
                    "not a run", None):
            with self.subTest(run=run):
                self.assertEqual(state({"workflow_runs": [run]})[1], "ready")

    def test_an_empty_run_list_is_ready_so_the_gate_check_fails_immediately(self):
        self.assertEqual(state({"workflow_runs": []})[1], "ready")

    def test_one_pending_run_wins_over_completed_runs(self):
        runs = {"workflow_runs": [record(id=1), record(id=2, status="in_progress")]}
        self.assertEqual(state(runs)[1], "pending")


class ReleaseBaseTests(unittest.TestCase):
    def test_push_success_on_the_exact_default_branch_head_is_accepted(self):
        code, out, err = check({"workflow_runs": [record()]})
        self.assertEqual(code, 0, err)
        self.assertIn(SHA, out)
        self.assertEqual(err, "")

    def test_dispatch_success_on_the_exact_default_branch_head_is_accepted(self):
        code, _, err = check({"workflow_runs": [record(event="workflow_dispatch")]})
        self.assertEqual(code, 0, err)

    def test_an_observed_exact_head_dispatch_payload_is_accepted(self):
        # Every field except the repository name is from real Delivery dispatch
        # run 45784861813; that run was dispatched on main, not on a SHA ref.
        observed = record(id=7, event="workflow_dispatch",
                          head_sha=OBSERVED_DISPATCH_SHA)
        code, out, err = check({"workflow_runs": [observed]}, sha=OBSERVED_DISPATCH_SHA)
        self.assertEqual(code, 0, err)
        self.assertIn(OBSERVED_DISPATCH_SHA, out)

    def test_a_cancelled_push_with_a_green_gate_job_is_accepted(self):
        # The observed bug: run 45799087845 ended `cancelled` at run level while
        # every job, including Delivery gate, reported success.
        runs = {"workflow_runs": [record(id=1, conclusion="cancelled")]}
        jobs = {"1": [gate(1)]}
        code, out, err = check(runs, jobs=jobs)
        self.assertEqual(code, 0, err)
        self.assertIn("1 green gate(s)", out)

    def test_a_cancelled_push_without_a_green_gate_is_rejected(self):
        runs = {"workflow_runs": [record(conclusion="cancelled")]}
        code, _, err = check(runs, jobs={"1": [gate(1, conclusion="cancelled")]})
        self.assertEqual(code, 1)
        self.assertIn("no green gate", err)

    def test_a_successful_run_with_a_failed_gate_job_is_rejected(self):
        runs = {"workflow_runs": [record()]}
        code, _, err = check(runs, jobs={"1": [gate(1, conclusion="failure")]})
        self.assertEqual(code, 1)
        self.assertIn("no green gate", err)

    def test_a_dispatch_with_a_green_gate_at_either_end_of_a_list_is_found(self):
        for qualifier_first in (True, False):
            with self.subTest(qualifier_first=qualifier_first):
                bad = record(id=1, event="pull_request")
                good = record(id=2, event="workflow_dispatch")
                runs = {"workflow_runs": [good, bad] if qualifier_first else [bad, good]}
                self.assertEqual(check(runs)[0], 0)

    def test_a_pull_request_run_even_when_green_is_rejected(self):
        code, _, err = check({"workflow_runs": [record(event="pull_request")]})
        self.assertEqual(code, 1)
        self.assertIn("pull_request", err)

    def test_merge_group_and_other_events_are_rejected(self):
        for event in ("merge_group", "schedule", "workflow_call"):
            with self.subTest(event=event):
                code, _, err = check({"workflow_runs": [record(event=event)]})
                self.assertEqual(code, 1)
                self.assertIn(event, err)

    def test_a_stale_sha_or_another_branch_is_rejected(self):
        for run in (record(head_sha=OTHER_SHA), record(head_branch="feature"),
                    record(head_branch="v0.0.4")):
            with self.subTest(run=run):
                code, _, err = check({"workflow_runs": [run]})
                self.assertEqual(code, 1)
                self.assertIn("no green gate for " + SHA, err)

    def test_failure_queued_and_skipped_runs_are_rejected(self):
        for conclusion in ("failure", "queued", "skipped"):
            with self.subTest(conclusion=conclusion):
                code, _, err = check({"workflow_runs": [record(conclusion=conclusion)]})
                self.assertEqual(code, 1)
                self.assertIn(conclusion, err)

    def test_a_non_completed_run_is_rejected(self):
        code, _, err = check({"workflow_runs": [record(status="in_progress")]})
        self.assertEqual(code, 1)
        self.assertIn("success", err)

    def test_a_run_from_another_repository_is_rejected(self):
        code, _, err = check({"workflow_runs": [record(
            head_repository={"full_name": "other/repo"})]})
        self.assertEqual(code, 1)
        self.assertIn("no green gate", err)

    def test_a_malformed_head_repository_is_rejected(self):
        for source in (None, "owner/repo", 42):
            with self.subTest(source=source):
                self.assertEqual(check({"workflow_runs": [record(
                    head_repository=source)]})[0], 1)

    def test_gate_jobs_must_belong_to_the_matching_run_id(self):
        runs = {"workflow_runs": [record(id=1)]}
        self.assertEqual(check(runs, jobs={"2": [gate(2)]})[0], 1)

    def test_malformed_gate_job_entries_are_rejected(self):
        runs = {"workflow_runs": [record()]}
        for entries in ([], "not a list", [None], [gate(1, name="Other")]):
            with self.subTest(entries=entries):
                self.assertEqual(check(runs, jobs={"1": entries})[0], 1)

    def test_malformed_records_are_ignored_not_crashed_on(self):
        runs = {"workflow_runs": ["not a run", None, 42, record(event="workflow_dispatch")]}
        code, out, err = check(runs)
        self.assertEqual(code, 0, err)
        self.assertIn("1 green gate(s)", out)

    def test_malformed_records_are_ignored_in_the_failure_diagnostic(self):
        # No green gate: both diagnostic comprehensions must skip bad entries.
        runs = {"workflow_runs": [record(event="pull_request"), "not a run", None, 42]}
        code, _, err = check(runs)
        self.assertEqual(code, 1)
        self.assertIn("events: pull_request", err)
        self.assertNotIn("None", err)

    def test_missing_event_or_conclusion_is_rejected(self):
        for field in ("event", "conclusion"):
            with self.subTest(field=field):
                run = record()
                del run[field]
                self.assertEqual(check({"workflow_runs": [run]})[0], 1)

    def test_an_empty_run_list_fails_closed_with_a_diagnostic(self):
        code, _, err = check({"workflow_runs": []})
        self.assertEqual(code, 1)
        self.assertIn("events: none", err)
        self.assertIn("conclusions: none", err)

    def test_a_bad_total_count_does_not_override_the_run_array(self):
        code, _, err = check({"total_count": 99, "workflow_runs": [record()]})
        self.assertEqual(code, 0, err)

    def test_invalid_json_and_wrong_envelopes_exit_two_with_distinct_messages(self):
        argv = ["--sha", SHA, "--branch", "main", "--repo", REPO]
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = checker.main(argv, stream=io.StringIO("{broken"))
        self.assertEqual(code, 2)
        self.assertIn("invalid runs JSON", err.getvalue())

        for payload, expected in (
                ([], "expected runs and jobs objects"),
                (None, "expected runs and jobs objects"),
                ({"runs": [], "jobs": {}}, "expected runs and jobs objects"),
                ({"jobs": {}}, "expected runs and jobs objects"),
                ({"runs": {"workflow_runs": []}, "jobs": []},
                 "expected runs and jobs objects"),
                ({"runs": {"workflow_runs": "not a list"}, "jobs": {}},
                 "expected a workflow_runs list")):
            with self.subTest(payload=payload):
                out, err = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    code = checker.main(argv, stream=io.StringIO(json.dumps(payload)))
                self.assertEqual(code, 2)
                self.assertIn(expected, err.getvalue())


class CommandLineTests(unittest.TestCase):
    """Exercise the executable entry point so its branch is covered."""

    def run_script(self, envelope):
        """Run check-release-base.py as __main__ and return its exit code."""
        script = Path(__file__).resolve().parents[1] / "check-release-base.py"
        argv = [str(script), "--sha", SHA, "--branch", "main", "--repo", REPO]
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(sys, "stdin", io.StringIO(json.dumps(envelope))), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as raised:
            runpy.run_path(str(script), run_name="__main__")
        return raised.exception.code

    def test_the_entry_point_accepts_a_green_gate(self):
        envelope = {"runs": {"workflow_runs": [record()]},
                    "jobs": {"1": [gate(1)]}}
        self.assertEqual(self.run_script(envelope), 0)

    def test_the_entry_point_rejects_a_pull_request(self):
        envelope = {"runs": {"workflow_runs": [record(event="pull_request")]},
                    "jobs": {"1": [gate(1)]}}
        self.assertEqual(self.run_script(envelope), 1)


if __name__ == "__main__":
    unittest.main()
