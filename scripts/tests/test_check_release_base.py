"""Regression tests for the release precondition's exact-SHA green-run check.

The release job must accept a green default-branch run from a trusted event even
when a newer push cancelled an older run at run level. A pull request, a wrong
commit, a wrong branch or a cancelled run must never satisfy it.
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
# Fields observed on Delivery dispatch run 45784861813, which was dispatched
# on an exact commit SHA and reported the default branch as its head branch.
OBSERVED_DISPATCH_SHA = "f5aa25e5ae81cd19c13d0daec03567a83960a1a9"


def record(**overrides):
    """A green push run on the exact commit unless a field is overridden."""
    base = {
        "head_sha": SHA,
        "head_branch": "main",
        "event": "push",
        "status": "completed",
        "conclusion": "success",
        "head_repository": {"full_name": REPO},
    }
    base.update(overrides)
    return base


def check(runs, sha=SHA, branch="main", repo=REPO):
    """Call main with a runs payload and capture both output streams."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = checker.main(["--sha", sha, "--branch", branch, "--repo", repo],
                            stream=io.StringIO(json.dumps(runs)))
    return code, out.getvalue(), err.getvalue()


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
        # run 45784861813: GitHub reports head_branch=main for an exact-SHA ref.
        observed = {
            "event": "workflow_dispatch",
            "head_branch": "main",
            "head_sha": OBSERVED_DISPATCH_SHA,
            "status": "completed",
            "conclusion": "success",
            "head_repository": {"full_name": REPO},
        }
        code, out, err = check({"workflow_runs": [observed]}, sha=OBSERVED_DISPATCH_SHA)
        self.assertEqual(code, 0, err)
        self.assertIn(OBSERVED_DISPATCH_SHA, out)

    def test_a_superseded_cancelled_push_with_a_green_dispatch_is_accepted(self):
        # The observed bug: the push run ended `cancelled` at run level while its
        # jobs were green, and the documented recovery is an exact-head dispatch.
        runs = {"workflow_runs": [record(conclusion="cancelled"),
                                  record(event="workflow_dispatch")]}
        code, out, err = check(runs)
        self.assertEqual(code, 0, err)
        self.assertIn("1 green", out)

    def test_a_qualifier_at_either_end_of_a_mixed_list_is_found(self):
        for qualifier_first in (True, False):
            with self.subTest(qualifier_first=qualifier_first):
                bad = record(event="pull_request")
                good = record(event="workflow_dispatch")
                runs = [good, bad] if qualifier_first else [bad, good]
                self.assertEqual(check({"workflow_runs": runs})[0], 0)

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
                self.assertIn("no green main run for " + SHA, err)

    def test_cancelled_failure_queued_and_skipped_runs_are_rejected(self):
        for conclusion in ("cancelled", "failure", "queued", "skipped"):
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
        self.assertIn("no green main run", err)

    def test_a_malformed_head_repository_is_rejected(self):
        for source in (None, "owner/repo", 42):
            with self.subTest(source=source):
                self.assertEqual(check({"workflow_runs": [record(
                    head_repository=source)]})[0], 1)

    def test_malformed_records_are_ignored_not_crashed_on(self):
        runs = {"workflow_runs": ["not a run", None, 42, record(event="workflow_dispatch")]}
        code, out, err = check(runs)
        self.assertEqual(code, 0, err)
        self.assertIn("1 green", out)

    def test_malformed_records_are_ignored_in_the_failure_diagnostic(self):
        # No green run: the diagnostic comprehensions must skip the bad entries.
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

    def test_invalid_json_and_wrong_shape_exit_two_with_distinct_messages(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = checker.main(["--sha", SHA, "--branch", "main", "--repo", REPO],
                                stream=io.StringIO("{broken"))
        self.assertEqual(code, 2)
        self.assertIn("invalid runs JSON", err.getvalue())

        for payload in ([], None, {"workflow_runs": "not a list"}):
            with self.subTest(payload=payload):
                out, err = io.StringIO(), io.StringIO()
                with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                    code = checker.main(["--sha", SHA, "--branch", "main", "--repo", REPO],
                                        stream=io.StringIO(json.dumps(payload)))
                self.assertEqual(code, 2)
                self.assertIn("expected a runs object", err.getvalue())


class CommandLineTests(unittest.TestCase):
    """Exercise the executable entry point so its branch is covered."""

    def run_script(self, payload):
        """Run check-release-base.py as __main__ and return its exit code."""
        script = Path(__file__).resolve().parents[1] / "check-release-base.py"
        argv = [str(script), "--sha", SHA, "--branch", "main", "--repo", REPO]
        with mock.patch.object(sys, "argv", argv), \
                mock.patch.object(sys, "stdin", io.StringIO(json.dumps(payload))), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as raised:
            runpy.run_path(str(script), run_name="__main__")
        return raised.exception.code

    def test_the_entry_point_accepts_a_dispatch_success(self):
        self.assertEqual(self.run_script(
            {"workflow_runs": [record(event="workflow_dispatch")]}), 0)

    def test_the_entry_point_rejects_a_pull_request_success(self):
        self.assertEqual(self.run_script(
            {"workflow_runs": [record(event="pull_request")]}), 1)


if __name__ == "__main__":
    unittest.main()
