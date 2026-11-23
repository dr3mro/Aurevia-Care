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
