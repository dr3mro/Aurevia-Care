"""Branch coverage for the workers and notes helpers of scripts/swarm.py.

The regular swarm tests exercise the happy paths of these helpers. This file
covers the paths a worker run does not reach: an unknown TODO field, a checker
that cannot be loaded, a blank separator after an entry, and the two terminal
states of the todo-push retry loop. All fixtures are synthetic text; every
external call is mocked so the suite never uses the network or a real token.
"""
import subprocess
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")


class SwarmWorkersCoverage(unittest.TestCase):
    """Failure and boundary paths of the swarm workers region (OPS-06 tooling)."""

    def test_parse_tasks_ignores_unknown_fields(self):
        """An unrecognised field is skipped without ending the entry.

        FIELD matches any lower-case key, but parse_tasks stores only the keys it
        knows. The unknown key must not be added and the following known field
        must still be read, which proves the loop returned to the next line.
        """
        text = ("- [ ] CW-001 · Title\n"
                "  - unknownfield: value\n"
                "  - requirements: IAM-02\n")
        tasks = swarm.parse_tasks(text)
        self.assertEqual(len(tasks), 1)
        self.assertEqual(tasks[0]["id"], "CW-001")
        self.assertNotIn("unknownfield", tasks[0])
        self.assertEqual(tasks[0]["requirements"], "IAM-02")

    def test_redact_secrets_returns_text_when_the_checker_cannot_load(self):
        """A missing or broken checker leaves the worker's notes text intact.

        Publishing notes must still work when the checker module cannot be read;
        catching all three load failures keeps a tooling problem from losing a
        worker's report. Any other behaviour would be observable as changed text.
        """
        text = "plain synthetic text for the checker probe"
        for error in (OSError("missing"), AttributeError("no patterns"), ImportError("bad")):
            with self.subTest(error=type(error).__name__):
                with mock.patch.object(swarm, "_checker", side_effect=error):
                    self.assertEqual(swarm.redact_secrets(text), text)

    def test_set_task_status_keeps_the_blank_separator_after_an_entry(self):
        """A trailing blank line remains the separator between two entries.

        This drives the end-pointer walk-back in set_task_status. Because the
        field loop re-appends blank lines, the resulting text is identical even
        without the walk-back, so the assertions pin the observable output and
        that the next entry is not duplicated rather than the pointer arithmetic.
        """
        text = ("- [ ] CW-001 · Staff\n"
                "  - scope: apps/api/staff\n"
                "\n"
                "- [ ] CW-002 · Other\n"
                "  - scope: apps/api/other\n")
        result = swarm.set_task_status(text, "CW-001", "taken", agent="swarm-1")
        self.assertEqual(
            result,
            "- [-] CW-001 · Staff\n"
            "  - agent: swarm-1\n"
            "  - attempts: 1\n"
            "  - scope: apps/api/staff\n"
            "\n"
            "- [ ] CW-002 · Other\n"
            "  - scope: apps/api/other\n",
        )
        self.assertEqual(result.count("- [ ] CW-002 · Other"), 1)

    def test_update_todo_raises_when_every_push_fails(self):
        """A rejected final push is re-raised so CI sees the failed write.

        An earlier failure is retried after re-fetching main; only the final
        attempt is fatal. The fetch between attempts is outside the guarded
        block and must succeed, so the same error instance reaches the caller.
        """
        error = subprocess.CalledProcessError(1, ["git", "push"])
        calls = []

        def authed_git(*args, **kwargs):
            calls.append(args[0])
            if args[0] == "push":
                raise error
            return ""

        with mock.patch.object(swarm, "todo_read", return_value="before\n"), \
                mock.patch.object(swarm, "todo_write") as write, \
                mock.patch.object(swarm, "git"), \
                mock.patch.object(swarm, "authed_git", side_effect=authed_git), \
                mock.patch.object(swarm.time, "sleep") as sleep:
            with self.assertRaises(subprocess.CalledProcessError) as raised:
                swarm.update_todo(lambda text: text + "after\n", "change",
                                  path="manual.md", attempts=2)
        self.assertIs(raised.exception, error)
        self.assertEqual(calls, ["push", "fetch", "push"])
        self.assertEqual(write.call_count, 2)
        sleep.assert_called_once()

    def test_update_todo_with_no_attempts_does_nothing(self):
        """A non-positive attempt budget changes nothing and touches no file.

        The retry loop never starts, so the editor, the tracker reads and writes
        and every git call are skipped; the function falls through to False.
        """
        for attempts in (0, -1):
            with self.subTest(attempts=attempts):
                edit = mock.Mock(side_effect=AssertionError("the edit must not run"))
                with mock.patch.object(swarm, "todo_read") as read, \
                        mock.patch.object(swarm, "todo_write") as write, \
                        mock.patch.object(swarm, "git") as git, \
                        mock.patch.object(swarm, "authed_git") as authed:
                    self.assertIs(
                        swarm.update_todo(edit, "change", path="manual.md", attempts=attempts),
                        False,
                    )
                    edit.assert_not_called()
                    read.assert_not_called()
                    write.assert_not_called()
                    git.assert_not_called()
                    authed.assert_not_called()


if __name__ == "__main__":
    unittest.main()
