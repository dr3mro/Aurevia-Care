"""Coverage for the schedule/monitor slice of scripts/swarm.py.

Each test reaches one branch that the sibling swarm test files leave uncovered:
the issue-task nudge, task-file reset boundaries, triage/monitor assignment
failure paths and the registry error path. Every input is synthetic and every
``gh`` and subprocess boundary is mocked, so no test reaches the network, a
credential store or patient data.
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
  - requirements: IAM-02
  - scope: apps/api/staff
  - details:
    Details for staff.
"""

GOOD_FINDING = {
    "title": "Reject expired tokens in the staff API",
    "scope": ["apps/api/staff"],
    "plan": "Expired tokens pass.\nAdd a failing test first.",
}


def issue(number, body, labels=("swarm-issue", "agent-fixable")):
    """Build a synthetic open issue written by the trusted automation account."""
    return {"number": number, "title": f"t{number}", "body": body,
            "user": {"login": "github-actions[bot]"}, "author_association": "NONE",
            "labels": [{"name": name} for name in labels]}


def aged(number, minutes_created, minutes_updated, body="", labels=("swarm-issue", "agent-fixable")):
    """Build a synthetic issue with fixed timestamps relative to ``NOW``."""
    def stamp(minutes):
        return (NOW - datetime.timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")
    found = issue(number, body, labels=labels)
    found["created_at"] = stamp(minutes_created)
    found["updated_at"] = stamp(minutes_updated)
    return found


class Gh:
    """A scripted gh that records calls and answers the monitor's read queries."""

    def __init__(self, issues=None):
        self.calls, self.issues = [], issues or []

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[0] == "api" and "issues?state=open" in args[-1]:
            return json.dumps(self.issues)
        if args[0] == "api" and "/comments" in args[-1]:
            return "[]"
        if args[:2] == ("run", "list"):
            return "[]"
        return ""

    def said(self, *needle):
        """True when one recorded call contains every supplied fragment."""
        return any(all(n in " ".join(map(str, call)) for n in needle) for call in self.calls)


def select(mode, fake, text=TODO, live=()):
    """Run monitor_select with fixed time, a scripted gh and a temporary output folder."""
    out = tempfile.mkdtemp()
    with mock.patch.object(swarm, "gh", fake), \
            mock.patch.object(swarm, "live_workers", lambda: list(live)):
        return swarm.monitor_select(mode, text, out, now=NOW), out


class MonitorSelectNudgeTests(unittest.TestCase):
    """The issue-task marker branch: an issue whose task is not in the task file."""

    def test_an_issue_with_a_task_marker_but_no_task_is_nudged(self):
        """Reach `elif task_id` (swarm.py:873) and prove the triage branch was skipped."""
        issue_with_marker = aged(2, 3000, 1500, body="<!-- swarm-task:CW-099 -->")
        chosen, out = select("daily", Gh(issues=[issue_with_marker]))
        self.assertEqual(chosen["nudge"], [2])
        self.assertEqual(chosen["triage"], [])
        self.assertEqual(list(Path(out).glob("issue-*.md")), [])


class ResetAttemptsTests(unittest.TestCase):
    """The entry-boundary branches of reset_attempts, reached through its public API."""

    def test_an_unknown_task_id_leaves_the_text_untouched(self):
        """Reach `if start is None: return text` (swarm.py:926)."""
        text = "- [ ] CW-001 · Staff\n  - attempts: 3\n  - details:\n    body\n"
        self.assertEqual(swarm.reset_attempts(text, "NOPE-1"), text)

    def test_an_entry_with_no_trailing_field_reaches_the_loop_end(self):
        """Take the for-loop exhaustion arc (swarm.py:927->935)."""
        text = "- [ ] CW-001 · Staff\n  - attempts: 3\n"
        self.assertEqual(swarm.reset_attempts(text, "CW-001"),
                         "- [ ] CW-001 · Staff\n  - attempts: 0\n")

    def test_a_following_entry_stops_the_reset_at_the_entry_boundary(self):
        """Take the `break` on a non-indented non-empty line (swarm.py:929)."""
        text = ("- [ ] CW-001 · Staff\n  - attempts: 3\n"
                "- [ ] CW-002 · Other\n  - attempts: 5\n")
        tasks = swarm.parse_tasks(swarm.reset_attempts(text, "CW-001"))
        self.assertEqual([t["id"] for t in tasks], ["CW-001", "CW-002"])
        self.assertEqual(swarm.attempts_of(tasks[0]), 0)
        self.assertEqual(swarm.attempts_of(tasks[1]), 5)


class MonitorAssignTests(unittest.TestCase):
    """Failure and race branches of monitor_assign, with a scripted gh and a temp TODO."""

    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        self.todo = self.dir / "TODO.md"
        self.todo.write_text(TODO)
        (self.dir / "triage").mkdir()
        self.fake = Gh()

    def run_assign(self, **kwargs):
        """Call monitor_assign with the gh, TODO updater and wave gate mocked out."""
        def update(edit, message, path="TODO.md"):
            self.todo.write_text(edit(self.todo.read_text()))
        with mock.patch.object(swarm, "gh", self.fake), \
                mock.patch.object(swarm, "update_todo", update), \
                mock.patch.object(swarm, "waiting_waves", lambda: 0):
            return swarm.monitor_assign(self.dir / "triage", todo=str(self.todo), **kwargs)

    def test_a_malformed_triage_answer_is_reported_as_unusable(self):
        """Take `except (OSError, ValueError): finding = None` (swarm.py:951-952)."""
        (self.dir / "triage" / "triage-5.json").write_text("not json")
        result = self.run_assign(triaged=[5])
        self.assertEqual(result["failed"], [5])
        self.assertEqual(self.todo.read_text(), TODO)
        self.assertTrue(self.fake.said("needs-human"))
        self.assertFalse(self.fake.said("workflow", "run"))

    def test_a_concurrent_issue_entry_is_not_duplicated(self):
        """Take `if not fresh: return text` (swarm.py:963) on a read/update race."""
        dirty = TODO + "\n- [ ] ISS-5 · Already here\n  - scope: a\n"
        self.todo.write_text(dirty)
        (self.dir / "triage" / "triage-5.json").write_text(json.dumps(GOOD_FINDING))
        edited = []

        def update(edit, message, path="TODO.md"):
            # The fresh read made by update_todo already contains the entry a
            # concurrent worker added after triage_entry read the clean text.
            result_text = edit(self.todo.read_text())
            edited.append(result_text)
            self.todo.write_text(result_text)

        with mock.patch.object(swarm, "gh", self.fake), \
                mock.patch.object(swarm, "update_todo", update), \
                mock.patch.object(swarm, "waiting_waves", lambda: 0), \
                mock.patch.object(swarm, "todo_read", return_value=TODO):
            result = swarm.monitor_assign(self.dir / "triage", todo=str(self.todo), triaged=[5])
        self.assertEqual(result["tasks"], [5])
        self.assertEqual(len(edited), 1)
        self.assertEqual(edited[0], dirty)
        self.assertEqual(self.todo.read_text().count("ISS-5 ·"), 1)
        self.assertNotIn("Reject expired tokens", self.todo.read_text())

    def test_a_nudged_issue_gets_a_requeue_comment(self):
        """Take the nudge comment loop (swarm.py:987)."""
        result = self.run_assign(triaged=[], nudge=[2])
        self.assertEqual(result["nudged"], [2])
        self.assertTrue(self.fake.said("issue", "comment", "2", "swarm-requeue", "No progress"))

    def test_only_an_exhausted_matching_task_gets_its_attempts_back(self):
        """Take the false side of the give_back condition (swarm.py:994->993)."""
        second = "\n- [ ] CW-002 · Other\n  - attempts: 2\n  - scope: a\n"
        text = TODO.replace("  - requirements: IAM-02",
                            f"  - attempts: {swarm.MAX_ATTEMPTS}\n  - requirements: IAM-02") + second
        self.todo.write_text(text)
        result = self.run_assign(triaged=[], reset=[(2, "CW-001")])
        self.assertEqual(result["reset"], [2])
        tasks = {t["id"]: t for t in swarm.parse_tasks(self.todo.read_text())}
        self.assertEqual(swarm.attempts_of(tasks["CW-001"]), 0)
        self.assertEqual(swarm.attempts_of(tasks["CW-002"]), 2)


class SlotManifestFailureTests(unittest.TestCase):
    """A registry API error must be treated as a missing image, not a crash."""

    def test_a_registry_api_error_stops_the_slot_scan(self):
        """Take `except subprocess.CalledProcessError: return False` (swarm.py:1147-1148)."""
        fake = mock.Mock(side_effect=subprocess.CalledProcessError(1, "gh"))
        with mock.patch.object(swarm, "gh", fake), \
                mock.patch.object(swarm, "repo_slug", lambda: "o/r"):
            self.assertFalse(swarm.slot_manifests_exist([{"slot": 1}, {"slot": 2}]))
        self.assertEqual(fake.call_count, 1)


if __name__ == "__main__":
    unittest.main()
