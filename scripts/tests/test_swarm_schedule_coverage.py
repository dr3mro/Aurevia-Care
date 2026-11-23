"""Coverage for the uncovered branches of the swarm schedule/issue-monitor slice.

This file exercises the failure and fallback paths in ``scripts/swarm.py``
around the monitor selection, task status updates and slot-image readiness.
All fixtures are synthetic and every ``gh`` call is replaced by a local fake,
so the tests never touch the network or a real repository.
"""
import datetime
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

swarm = load("swarm")

NOW = datetime.datetime(2026, 11, 20, 12, 0, tzinfo=datetime.timezone.utc)

TODO = """# TODO

## Tasks

- [ ] CW-001 · Staff
  - attempts: 5
  - scope: apps/api/staff
"""

GOOD_ANSWER = {"title": "Reject expired tokens in the staff API",
               "scope": ["apps/api/staff"],
               "plan": "Expired tokens pass.\nAdd a failing test first."}


def issue(number, body, labels=("swarm-issue", "agent-fixable")):
    """Build a synthetic, trusted swarm issue with a body marker."""
    return {"number": number, "title": f"t{number}", "body": body,
            "user": {"login": "github-actions[bot]"}, "author_association": "NONE",
            "labels": [{"name": name} for name in labels]}


def aged(number, minutes, body=""):
    """Build an issue whose timestamps are `minutes` old relative to NOW."""
    stamp = (NOW - datetime.timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
    found = issue(number, body)
    found["created_at"] = stamp
    found["updated_at"] = stamp
    return found


class Gh:
    """Record gh calls and answer the few listing queries the monitor makes."""

    def __init__(self, issues=None, comments=None):
        self.calls = []
        self.issues = issues or []
        self.comments = comments or []

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[0] == "api" and "issues?state=open" in args[-1]:
            return json.dumps(self.issues)
        if args[0] == "api" and "/comments" in args[-1]:
            return json.dumps(self.comments)
        if args[:2] == ("pr", "view"):
            return "OPEN" if "--jq" in args else "{}"
        return ""

    def said(self, *needle):
        return any(all(n in " ".join(map(str, call)) for n in needle) for call in self.calls)


class MonitorSelectCoverageTests(unittest.TestCase):
    """The monitor selection path for an issue that names a task with no entry."""

    def select(self, fake, text=TODO, mode="daily"):
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "live_workers", lambda: []):
            return swarm.monitor_select(mode, text, tempfile.mkdtemp(), now=NOW)

    def test_an_issue_naming_an_unknown_task_is_nudged(self):
        # No TODO entry and no pull request: the task marker alone sends the
        # issue back to a wave instead of creating a task or dropping it.
        fake = Gh(issues=[aged(5, 3000, body="<!-- swarm-task:CW-999 -->")])
        chosen = self.select(fake)
        self.assertEqual(chosen["nudge"], [5])
        self.assertEqual(chosen["triage"], [])


class ResetAttemptsCoverageTests(unittest.TestCase):
    """Every scan outcome of reset_attempts, including its three early exits."""

    def test_an_unknown_task_leaves_the_text_unchanged(self):
        # The entry search exhausts without finding the id, so the original
        # text is returned byte for byte and no field is rewritten.
        self.assertEqual(swarm.reset_attempts(TODO, "CW-404"), TODO)

    def test_a_task_with_no_details_or_following_entry_reaches_the_end(self):
        text = "- [ ] A-1 · a\n  - attempts: 5\n"
        self.assertEqual(swarm.reset_attempts(text, "A-1"),
                         "- [ ] A-1 · a\n  - attempts: 0\n")

    def test_the_scan_stops_at_the_next_unindented_line(self):
        text = ("- [ ] A-1 · a\n  - attempts: 5\n"
                "- [ ] A-2 · b\n  - attempts: 7\n")
        self.assertEqual(swarm.reset_attempts(text, "A-1"),
                         "- [ ] A-1 · a\n  - attempts: 0\n"
                         "- [ ] A-2 · b\n  - attempts: 7\n")


class MonitorAssignCoverageTests(unittest.TestCase):
    """The monitor assignment paths for nudges, resets and duplicate entries."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.todo = self.dir / "TODO.md"
        self.todo.write_text(TODO)
        self.fake = Gh()

    def assign(self, **kwargs):
        def update(edit, message, path="TODO.md"):
            self.todo.write_text(edit(self.todo.read_text()))

        with mock.patch.object(swarm, "gh", self.fake), \
                mock.patch.object(swarm, "update_todo", update), \
                mock.patch.object(swarm, "wave_has_room", lambda: False):
            kwargs.setdefault("triaged", [])
            return swarm.monitor_assign(self.dir / "triage", todo=str(self.todo), **kwargs)

    def test_nudged_issues_get_a_requeue_comment(self):
        result = self.assign(nudge=[5])
        self.assertEqual(result["nudged"], [5])
        self.assertTrue(self.fake.said("issue", "comment", "5", "swarm-requeue"))

    def test_a_reset_leaves_every_other_task_alone(self):
        self.todo.write_text(TODO + "\n- [ ] CW-002 · Other\n  - attempts: 7\n  - scope: apps/api/other\n")
        result = self.assign(reset=[(2, "CW-001")])
        tasks = {t["id"]: t for t in swarm.parse_tasks(self.todo.read_text())}
        self.assertEqual(result["reset"], [2])
        self.assertEqual(tasks["CW-001"]["attempts"], "0")
        self.assertEqual(tasks["CW-002"]["attempts"], "7")

    def test_a_malformed_triage_answer_is_treated_as_unusable(self):
        # Untrusted worker output: invalid JSON is caught and refused, so the
        # issue goes to a person instead of failing the whole monitor job.
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text("not json")
        result = self.assign(triaged=[5])
        self.assertEqual(result["failed"], [5])
        self.assertTrue(self.fake.said("needs-human"))

    def test_an_entry_already_added_by_another_merge_needs_no_second_edit(self):
        # Selection reads an older TODO; by the time the edit runs, another
        # merge has added the task. The edit must leave that text untouched.
        (self.dir / "triage").mkdir()
        (self.dir / "triage" / "triage-5.json").write_text(json.dumps(GOOD_ANSWER))
        already = TODO + "\n" + swarm.triage_entry(5, GOOD_ANSWER, TODO) + "\n"
        seen = []

        def fake_update(edit, message, path="TODO.md"):
            seen.append(edit(already))
            return False

        with mock.patch.object(swarm, "gh", self.fake), \
                mock.patch.object(swarm, "update_todo", fake_update), \
                mock.patch.object(swarm, "wave_has_room", lambda: False):
            result = swarm.monitor_assign(self.dir / "triage", triaged=[5], todo=str(self.todo))
        self.assertEqual(seen, [already])
        self.assertEqual(result["tasks"], [5])


class SlotImageCoverageTests(unittest.TestCase):
    """Fallback paths for slot config lookup and the GHCR manifest check."""

    def test_an_unknown_slot_number_has_no_config(self):
        with self.assertRaises(StopIteration):
            swarm.slot_config([{"slot": 1, "model": "m1"}], 2)

    def test_a_registry_error_means_the_image_is_missing(self):
        class FailingGh:
            def __init__(self):
                self.calls = []

            def __call__(self, *args, check=True):
                self.calls.append(args)
                raise subprocess.CalledProcessError(1, "gh")

        fake = FailingGh()
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "repo_slug", lambda: "o/r"):
            self.assertFalse(swarm.slot_manifests_exist([{"slot": 1}]))
        self.assertEqual(len(fake.calls), 1)


if __name__ == "__main__":
    unittest.main()
