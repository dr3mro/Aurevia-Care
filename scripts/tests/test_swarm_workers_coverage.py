"""Coverage tests for the worker-facing half of scripts/swarm.py.

Five tasks split swarm.py by line range; this module owns lines 1250-1850:
task parsing, secret and docs sanitising, task status edits and claim updates.
Every test drives a public function with a synthetic fixture and a mocked
boundary (no network, no real gh, no real push), so the previously uncovered
lines and arcs in that range are exercised deterministically.
"""
import subprocess
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")


class WorkerCoverageTests(unittest.TestCase):
    """Exercise the uncovered branches of the worker section of swarm.py."""

    def test_parse_tasks_ignores_an_unknown_field_and_keeps_reading(self):
        """A field key not in a task record is skipped and the loop continues.

        Fault injection: an unrecognised key takes the false arc of the known-field
        check, returns to the next line, and later valid fields are still parsed.
        """
        text = ("- [ ] CW-001 · Staff\n"
                "  - scope: apps/api/staff\n"
                "  - mystery: not-a-task-field\n"
                "  - agent: swarm-1\n")
        tasks = swarm.parse_tasks(text)
        self.assertEqual(len(tasks), 1)
        task = tasks[0]
        self.assertEqual(task["id"], "CW-001")
        self.assertEqual(task["scope"], ["apps/api/staff"])
        self.assertEqual(task["agent"], "swarm-1")
        self.assertNotIn("mystery", task)

    def test_parse_tasks_keeps_inline_details(self):
        """A one-line details field is the body, not an empty continuation."""
        text = "- [ ] CW-001 · Staff\n  - scope: apps/api/staff\n  - details: Full tooling suite passes.\n"
        task = swarm.parse_tasks(text)[0]
        self.assertEqual(task["details"], "Full tooling suite passes.")

    def test_incomplete_tasks_parse_and_keep_details_when_marked(self):
        text = ("- [-] CW-001 · Staff\n"
                "  - agent: swarm-1\n"
                "  - scope: apps/api/staff\n"
                "  - details:\n    Waiting for owner input.\n"
                "  - attempts: 2\n"
                "  - evidence: stale completion evidence\n"
                "  - pr: #7\n")
        task = swarm.parse_tasks(text)[0]
        self.assertEqual(task["status"], "taken")
        self.assertEqual(task["details"], "Waiting for owner input.")
        self.assertEqual(task["attempts"], "2")
        self.assertEqual(task["evidence"], "stale completion evidence")
        marked = swarm.set_task_status(text, "CW-001", "incomplete", evidence="must not be emitted")
        incomplete = swarm.parse_tasks(marked)[0]
        self.assertEqual(incomplete["status"], "incomplete")
        self.assertEqual(incomplete["agent"], "")
        self.assertEqual(incomplete["pr"], "")
        self.assertEqual(incomplete["evidence"], "")
        self.assertEqual(incomplete["attempts"], "2")
        self.assertEqual(incomplete["details"], "Waiting for owner input.")
        self.assertIn("- [!] CW-001 · Staff\n", marked)
        self.assertNotIn("evidence:", marked)

    def test_incomplete_status_removes_multiline_evidence_before_and_after_details(self):
        cases = (
            ("- [-] CW-001 · Staff\n"
             "  - evidence: stale first line\n    stale continuation\n"
             "  - details:\n    Keep this body.\n"
             "  - scope: apps/api/staff\n"),
            ("- [-] CW-001 · Staff\n"
             "  - details:\n    Keep this body.\n"
             "  - evidence: stale first line\n    stale continuation\n"
             "  - scope: apps/api/staff\n"),
        )
        for text in cases:
            with self.subTest(text=text):
                marked = swarm.set_task_status(text, "CW-001", "incomplete", evidence="new stale value")
                task = swarm.parse_tasks(marked)[0]
                self.assertNotIn("stale", marked)
                self.assertNotIn("evidence:", marked)
                self.assertEqual(task["details"], "Keep this body.")
                self.assertEqual(task["scope"], ["apps/api/staff"])

    def test_any_two_space_field_ends_details_but_four_space_lines_remain_body(self):
        text = ("- [ ] CW-001 · Staff\n"
                "  - details:\n    First body line.\n"
                "    Second body line.\n"
                "  - mystery: unknown field\n"
                "  - scope: apps/api/staff\n")
        task = swarm.parse_tasks(text)[0]
        self.assertEqual(task["details"], "First body line.\nSecond body line.")
        self.assertEqual(task["scope"], ["apps/api/staff"])
        self.assertNotIn("mystery", task)

    def test_task_lookup_prefers_canonical_ids_and_resolves_unique_aliases(self):
        text = ("- [!] PARENT-1 · Parent\n"
                "  - aliases: SHARED, PARENT-ALIAS\n"
                "- [ ] SHARED · Canonical\n"
                "- [ ] CHILD-1 · Child\n"
                "  - aliases: CHILD-ALIAS\n")
        lookup = swarm.task_lookup(text)
        self.assertEqual(lookup["SHARED"]["id"], "SHARED")
        self.assertEqual(lookup["PARENT-ALIAS"]["id"], "PARENT-1")
        self.assertEqual(lookup["CHILD-ALIAS"]["id"], "CHILD-1")
        self.assertNotIn("MISSING-1", lookup)

    def test_task_lookup_allows_a_repeated_alias_on_one_task(self):
        lookup = swarm.task_lookup(
            "- [ ] TASK-1 · Work\n  - aliases: SHARED, SHARED\n"
        )
        self.assertEqual(lookup["SHARED"]["id"], "TASK-1")

    def test_task_lookup_fails_closed_on_duplicate_ids_and_ambiguous_aliases(self):
        cases = (
            "- [ ] SAME-1 · First\n- [ ] SAME-1 · Duplicate\n",
            "- [ ] ONE-1 · First\n  - aliases: SHARED\n"
            "- [ ] TWO-2 · Second\n  - aliases: SHARED\n",
        )
        for text in cases:
            with self.subTest(text=text):
                with self.assertRaises(swarm.ReconciliationError):
                    swarm.task_lookup(text)

    def test_redact_secrets_returns_text_when_the_checker_cannot_load(self):
        """If check-secrets.py is missing or broken, text is returned unchanged.

        A worker must still be able to write its notes when a checker file is
        absent; the exception is swallowed rather than failing the publish step.
        """
        for error in (OSError("missing"), AttributeError("no patterns"), ImportError("bad")):
            with self.subTest(error=type(error).__name__):
                with mock.patch.object(swarm, "_checker", side_effect=error):
                    self.assertEqual(swarm.redact_secrets("plain text"), "plain text")

    def test_set_task_status_keeps_the_blank_separator_after_an_entry(self):
        """The end pointer walks back over a trailing blank line before inserting fields.

        The blank line stays as the separator between entries, but the new field
        is inserted before it instead of overwriting it. The fixture places a
        blank line before the next task to drive the walk-back branch.
        """
        text = ("- [ ] CW-001 · Staff\n"
                "  - scope: apps/api/staff\n"
                "\n"
                "- [ ] CW-002 · Config\n"
                "  - scope: apps/api/config\n")
        result = swarm.set_task_status(text, "CW-001", "done", pr="#7")
        self.assertEqual(
            result,
            "- [x] CW-001 · Staff\n"
            "  - pr: #7\n"
            "  - scope: apps/api/staff\n"
            "\n"
            "- [ ] CW-002 · Config\n"
            "  - scope: apps/api/config\n",
        )

    def test_update_todo_raises_when_every_push_fails(self):
        """A push failure on the final attempt is re-raised for CI to see.

        An earlier failure is retried after re-fetching main; only the last one
        is fatal, so the failure has already been surfaced in the trusted log.
        """
        pushes = []

        def authed_git(*args, **kwargs):
            if args[0] == "push":
                pushes.append(args)
                raise subprocess.CalledProcessError(1, args)
            return ""

        with mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write"), \
                mock.patch.object(swarm, "git"), \
                mock.patch.object(swarm, "authed_git", side_effect=authed_git), \
                mock.patch.object(swarm.time, "sleep"):
            with self.assertRaises(subprocess.CalledProcessError):
                swarm.update_todo(lambda text: text + "after\n", "change", attempts=2)
        self.assertEqual(len(pushes), 2)

    def test_update_todo_with_no_attempts_does_nothing(self):
        """A non-positive attempt budget returns False without touching TODO.md.

        The loop body never runs, so no read, write or git call is made; this
        covers the fall-through return after the retry loop.
        """
        with mock.patch.object(swarm, "todo_read") as read, \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "git") as git:
            self.assertFalse(swarm.update_todo(lambda text: text + "after\n", "change", attempts=0))
        read.assert_not_called()
        write.assert_not_called()
        git.assert_not_called()


if __name__ == "__main__":
    unittest.main()
