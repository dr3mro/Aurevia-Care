"""Coverage for the planning half of scripts/swarm.py (constants, issues, reports,
watchdog issue closure and hand-offs).

Every GitHub call is scripted through the fake below and every test uses synthetic
identifiers, so the suite never touches the network or a real repository. The fake
is intentionally local to this file: parallel workers own their own fixtures.
"""
import io
import json
import os
import subprocess
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")


class Gh:
    """A scripted gh CLI: records calls and answers the queries the planning code makes."""

    def __init__(self, run_info=None, issue_lists=None, delivery_runs=None, fail_on=None):
        self.calls = []
        self.run_info = run_info or {}
        self.issue_lists = issue_lists or {}
        self.delivery_runs = delivery_runs if delivery_runs is not None else []
        self.fail_on = fail_on

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if self.fail_on and self.fail_on(args):
            raise subprocess.CalledProcessError(1, list(args), "", "HTTP 500: Boom")
        if args[0] == "api":
            return "[]"
        if args[:2] == ("run", "view"):
            return "" if "--log-failed" in args else json.dumps(self.run_info)
        if args[:2] == ("run", "list"):
            if "--workflow" in args and args[args.index("--workflow") + 1] == "Delivery":
                return json.dumps(self.delivery_runs)
            return "[]"
        if args[:2] == ("issue", "list"):
            search = args[args.index("--search") + 1] if "--search" in args else ""
            for needle, result in self.issue_lists.items():
                if needle in search:
                    return json.dumps(result)
            return "[]"
        return ""

    def said(self, *needle):
        return any(all(n in " ".join(map(str, call)) for n in needle) for call in self.calls)


def issue(number, title):
    """A synthetic open issue with a trusted author and the swarm-issue label."""
    return {"number": number, "title": title, "body": "",
            "user": {"login": "github-actions[bot]"}, "author_association": "NONE",
            "labels": [{"name": "swarm-issue"}]}


SUCCESS = {"name": "Delivery", "conclusion": "success", "url": "https://x/run/5",
           "headBranch": "main", "headSha": "abcdef123", "event": "push", "attempt": 1,
           "jobs": []}


class GhRetryTests(unittest.TestCase):
    """A failed gh call with no stderr still raises (the print is skipped)."""

    def test_a_silent_failure_raises_without_printing(self):
        failed = subprocess.CompletedProcess([], 1, "", "")
        with mock.patch.object(swarm.subprocess, "run", return_value=failed), \
                self.assertRaises(subprocess.CalledProcessError):
            swarm.gh("pr", "view")
        stream = io.StringIO()
        with mock.patch.object(swarm.subprocess, "run", return_value=failed), \
                redirect_stderr(stream), self.assertRaises(subprocess.CalledProcessError):
            swarm.gh("pr", "view")
        self.assertEqual(stream.getvalue(), "")


class HandoffIssueTests(unittest.TestCase):
    """Handing an ISS-n task off comments on that issue, unless writing is off."""

    TASK = {"id": "ISS-5", "kind": "fix", "branch": "swarm/ISS-5-1"}

    def directory(self):
        tmp = tempfile.mkdtemp()
        Path(tmp, "publish-notes.md").write_text("## Why this is not finished\nNo coverage yet.")
        return tmp

    def test_an_issue_task_comments_on_its_own_issue(self):
        fake = Gh()
        with mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.report_worker_handoff(self.TASK, "timeout", "failed", "no",
                                                         self.directory(), "7"), "5")
        self.assertTrue(fake.said("issue", "comment", "5", "A worker stopped"))
        self.assertFalse(fake.said("issue", "create"))

    def test_the_kill_switch_stops_an_issue_task_handoff(self):
        fake = Gh()
        with mock.patch.dict(os.environ, {"SWARM_ISSUES": "off"}), \
                mock.patch.object(swarm, "gh", fake):
            self.assertEqual(swarm.report_worker_handoff(self.TASK, "timeout", "failed", "no",
                                                         self.directory(), "7"), "")
        self.assertEqual(fake.calls, [])


class GreenRunIssueTests(unittest.TestCase):
    """A green run closes the run-level issues it opened, including the repair-gave-up one."""

    def run_event(self, info, issue_lists=None):
        fake = Gh(run_info=info, issue_lists=issue_lists or {})
        with mock.patch.object(swarm, "gh", fake):
            result = swarm.run_event(5)
        return result, fake

    def test_a_green_run_closes_matching_run_level_failures_only(self):
        issues = [issue(9, "Agent image failed: old"), issue(10, "unrelated"),
                  issue(11, "Agent image failed: another")]
        result, fake = self.run_event({**SUCCESS, "name": "Agent image"},
                                      {"Agent image failed": issues})
        self.assertIn("closed", result)
        self.assertTrue(fake.said("issue", "close", "9"))
        self.assertTrue(fake.said("issue", "close", "11"))
        self.assertFalse(fake.said("issue", "close", "10"))

    def test_a_green_non_delivery_run_does_not_close_the_repair_issue(self):
        result, fake = self.run_event({**SUCCESS, "name": "Daily audit"},
                                      {"Daily audit failed": [issue(9, "Daily audit failed: old")]})
        self.assertIn("closed", result)
        self.assertFalse(fake.said("Main is red and automatic repair gave up"))

    def test_a_green_delivery_run_closes_the_repair_gave_up_issue(self):
        issues = [issue(12, "Main is red and automatic repair gave up"), issue(13, "other")]
        result, fake = self.run_event(SUCCESS, {"Main is red and automatic repair gave up": issues})
        self.assertIn("closed", result)
        self.assertTrue(fake.said("issue", "close", "12", "Main is green again"))
        self.assertFalse(fake.said("issue", "close", "13"))

    def test_a_green_delivery_run_with_no_matching_issue_closes_nothing_extra(self):
        result, fake = self.run_event(SUCCESS, {"Main is red and automatic repair gave up":
                                                [issue(14, "unrelated")]})
        self.assertEqual(result, "green; nothing open")
        self.assertFalse(fake.said("issue", "close"))


class WatchdogRepairIssueTests(unittest.TestCase):
    """The watchdog closes the repair-gave-up issue once Delivery is green on main."""

    ISSUE = issue(12, "Main is red and automatic repair gave up")

    def run_dog(self, delivery_runs, issue_lists=None, fail_on=None):
        fake = Gh(issue_lists=issue_lists or {}, delivery_runs=delivery_runs, fail_on=fail_on)
        stream = io.StringIO()
        with mock.patch.object(swarm, "swarm_idle", return_value=([], False, 5, "facts")), \
                mock.patch.object(swarm, "gh", fake), redirect_stderr(stream):
            swarm.watchdog()
        return fake, stream.getvalue()

    def test_a_green_delivery_run_closes_every_matching_issue(self):
        issues = [self.ISSUE, issue(13, "other"), issue(14, self.ISSUE["title"])]
        fake, _ = self.run_dog([{"conclusion": "success", "url": "https://x/run/9"}],
                               {"Main is red and automatic repair gave up": issues})
        self.assertTrue(fake.said("issue", "close", "12", "Main is green again"))
        self.assertTrue(fake.said("issue", "close", "14", "Main is green again"))
        self.assertFalse(fake.said("issue", "close", "13"))

    def test_an_empty_or_red_delivery_run_does_not_close_the_issue(self):
        for runs in ([], [{"conclusion": "failure", "url": "u"}]):
            with self.subTest(runs=runs):
                fake, _ = self.run_dog(runs,
                                       {"Main is red and automatic repair gave up": [self.ISSUE]})
                self.assertFalse(fake.said("issue", "close"))

    def test_no_matching_issue_closes_nothing(self):
        fake, _ = self.run_dog([{"conclusion": "success", "url": "u"}],
                               {"Main is red and automatic repair gave up": [issue(15, "other")]})
        self.assertFalse(fake.said("issue", "close"))

    def test_a_gh_error_is_reported_and_does_not_stop_the_watchdog(self):
        fake, err = self.run_dog([], fail_on=lambda args: args[:2] == ("run", "list"))
        self.assertIn("repair-gave-up close check skipped", err)


if __name__ == "__main__":
    unittest.main()
