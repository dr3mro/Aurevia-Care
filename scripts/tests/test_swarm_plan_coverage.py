"""Coverage for the plan half of the swarm script (lines 1-700).

Every external boundary is replaced with a synthetic double: gh calls are
recorded by a local fake, subprocess and the clock are patched where needed and
all files live in temporary directories. No test contacts GitHub or reads a
real token, and only synthetic issues, runs and notes text are used.
"""
import io
import json
import os
import shutil
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")


class Gh:
    """A recorded gh CLI that answers only the listing queries these tests make.

    `api_issues` answers the trusted-issues REST call, `issue_lists` maps a
    substring of a search expression to the issues to return, and `runs` maps a
    workflow name to its run listing. `raise_on` injects a failure for one call.
    """

    def __init__(self, api_issues=None, issue_lists=None, runs=None, run_info=None, raise_on=None):
        self.calls = []
        self.api_issues = api_issues or []
        self.issue_lists = issue_lists or {}
        self.runs = runs or {}
        self.run_info = run_info or {}
        self.raise_on = raise_on

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if self.raise_on and self.raise_on(args):
            raise RuntimeError("synthetic GitHub outage")
        if args[0] == "api" and "issues?state=open" in args[-1]:
            return json.dumps(self.api_issues)
        if args[:2] == ("issue", "list"):
            search = " ".join(str(a) for a in args)
            for needle, result in self.issue_lists.items():
                if needle in search:
                    return json.dumps(result)
            return "[]"
        if args[:2] == ("run", "list"):
            workflow = args[args.index("--workflow") + 1] if "--workflow" in args else ""
            return json.dumps(self.runs.get(workflow, []))
        if args[:2] == ("run", "view"):
            return json.dumps(self.run_info)
        return ""

    def said(self, *needle):
        """True when at least one recorded call contains every needle."""
        return any(all(str(n) in " ".join(map(str, call)) for n in needle) for call in self.calls)


class SilentFailureTests(unittest.TestCase):
    """A failed gh call whose stderr is empty still raises, without a blank error print."""

    def test_a_failed_gh_call_without_stderr_still_raises(self):
        failed = subprocess.CompletedProcess([], 1, "", "")
        with mock.patch.object(swarm.subprocess, "run", return_value=failed), \
                self.assertRaises(subprocess.CalledProcessError) as caught:
            swarm.gh("pr", "view")
        self.assertEqual(caught.exception.returncode, 1)

        stream = io.StringIO()
        with mock.patch.object(swarm.subprocess, "run", return_value=failed), \
                redirect_stderr(stream), self.assertRaises(subprocess.CalledProcessError):
            swarm.gh("pr", "view")
        self.assertEqual(stream.getvalue(), "")


class HandoffIssueTests(unittest.TestCase):
    """Handing an ISS-n task off comments on that issue, unless writing is switched off."""

    TASK = {"id": "ISS-5", "kind": "fix", "branch": "swarm/ISS-5-1"}

    def directory(self):
        """A temporary hand-off directory holding only a synthetic reason."""
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp, ignore_errors=True)
        Path(tmp, "publish-notes.md").write_text("## Why this is not finished\nNo coverage yet.")
        return tmp

    def test_an_issue_task_comments_on_its_own_issue(self):
        fake = Gh()
        with mock.patch.object(swarm, "gh", fake):
            result = swarm.report_worker_handoff(self.TASK, "timeout", "failed", "no",
                                                 self.directory(), "7")
        self.assertEqual(result, "5")
        self.assertTrue(fake.said("issue", "comment", "5", "A worker stopped"))
        self.assertFalse(fake.said("issue", "create"))

    def test_the_kill_switch_stops_an_issue_task_handoff(self):
        fake = Gh()
        with mock.patch.dict(os.environ, {"SWARM_ISSUES": "off"}), \
                mock.patch.object(swarm, "gh", fake):
            result = swarm.report_worker_handoff(self.TASK, "timeout", "failed", "no",
                                                 self.directory(), "7")
        self.assertEqual(result, "")
        self.assertEqual(fake.calls, [])


class RunEventGreenTests(unittest.TestCase):
    """A green run closes the run-level issues that a previous failure opened."""

    GREEN = {"name": "swarm", "conclusion": "success", "url": "https://x/run/5",
             "headBranch": "main", "headSha": "abcdef123", "event": "push", "attempt": 1,
             "jobs": []}

    def event(self, fake):
        """Run the green-run branch with the scripted gh double."""
        with mock.patch.object(swarm, "gh", fake):
            return swarm.run_event(5)

    def test_a_green_non_delivery_run_closes_only_matching_run_level_issues(self):
        fake = Gh(run_info=self.GREEN, issue_lists={
            "swarm failed": [
                {"number": 8, "title": "swarm failed: the build broke"},
                {"number": 9, "title": "a different workflow failed"},
            ],
            "Main is red and automatic repair gave up": [
                {"number": 14, "title": "Main is red and automatic repair gave up"},
            ]})
        result = self.event(fake)
        self.assertIn("closed", result)
        self.assertTrue(fake.said("issue", "close", "8"))
        self.assertFalse(fake.said("issue", "close", "9"))
        # A non-Delivery run must never touch the Delivery-only repair issue.
        self.assertFalse(fake.said("issue", "close", "14"))

    def test_a_green_delivery_run_closes_only_the_repair_gave_up_issue(self):
        info = {**self.GREEN, "name": "Delivery"}
        fake = Gh(run_info=info, issue_lists={
            "Main is red and automatic repair gave up": [
                {"number": 12, "title": "Main is red and automatic repair gave up"},
                {"number": 13, "title": "Main is red and automatic repair gave up (old)"},
            ]})
        result = self.event(fake)
        self.assertIn("closed", result)
        self.assertTrue(fake.said("issue", "close", "12", "Main is green again"))
        self.assertFalse(fake.said("issue", "close", "13"))


class WatchdogGreenTests(unittest.TestCase):
    """The watchdog tolerates a broken repair-gave-up lookup and closes it when main is green."""

    def dog(self, fake):
        """Drive the watchdog with a healthy idle state and no unrelated issue work."""
        with mock.patch.object(swarm, "swarm_idle", return_value=([], False, 5, "facts")), \
                mock.patch.object(swarm, "close_issues", return_value=[]), \
                mock.patch.object(swarm, "sync_issues", return_value=[]) as sync, \
                mock.patch.object(swarm, "gh", fake):
            swarm.watchdog()
            return sync

    def test_a_green_delivery_run_closes_the_repair_gave_up_issue(self):
        fake = Gh(
            runs={"Delivery": [{"conclusion": "success", "url": "https://x/run/5"}]},
            issue_lists={"Main is red and automatic repair gave up": [
                {"number": 12, "title": "Main is red and automatic repair gave up"},
                {"number": 13, "title": "unrelated"},
            ]})
        self.dog(fake)
        self.assertTrue(fake.said("issue", "close", "12", "Main is green again"))
        self.assertFalse(fake.said("issue", "close", "13"))

    def test_a_failed_delivery_listing_does_not_stop_the_watchdog(self):
        fake = Gh(raise_on=lambda args: args[:2] == ("run", "list")
                  and "Delivery" in args)
        stream = io.StringIO()
        with redirect_stderr(stream):
            sync = self.dog(fake)
        self.assertIn("repair-gave-up close check skipped", stream.getvalue())
        sync.assert_called_once_with()  # the failure is reported, then the watchdog continues


if __name__ == "__main__":
    unittest.main()
