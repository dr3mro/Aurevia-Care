"""Line and branch coverage for the uncovered paths of ``scripts/check-pack.py``.

The repository-wide suite exercises the pack validation helpers, but it drives
``check()`` against the real worktree. In a cloud worker the worktree
``opencode.json`` is the CI profile placed under skip-worktree, so ``check()``
reports it stale and ``main()`` always returns early at its ``if errors``
guard. That leaves the success path (agent count, validation message and
``return 0``) and its false branch arc unobserved.

These tests close that gap through the script's public functions only. They
use temporary synthetic config files and a mocked ``check`` result, so no
network, real credential, or change to the real pack is involved. They also
pin the helper branches (frontmatter parsing and duplicate-key detection)
that the remaining coverage depends on.
"""
import contextlib
import io
import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

pack = load("check-pack")


class FrontmatterCoverageTests(unittest.TestCase):
    """Exercise every return path and loop shape of ``frontmatter``."""

    def test_empty_block_returns_empty_mapping(self):
        # The splitlines loop runs zero times: the block has no key/value rows.
        self.assertEqual(pack.frontmatter("---\n\n---\n"), {})

    def test_text_without_a_block_returns_none(self):
        # FRONTMATTER.match fails - the "if not match" true arc.
        self.assertIsNone(pack.frontmatter("no frontmatter here"))

    def test_malformed_row_without_separator_returns_none(self):
        # The "if not sep" true arc aborts a block that otherwise matched.
        self.assertIsNone(pack.frontmatter("---\nbroken line\n---\n"))

    def test_quoted_values_are_unwrapped_and_whitespace_trimmed(self):
        # Several rows take the sep-present arc and the quote stripping path.
        parsed = pack.frontmatter('---\n name : "value" \n mode: subagent\n---\n')
        self.assertEqual(parsed, {"name": "value", "mode": "subagent"})


class LoadConfigCoverageTests(unittest.TestCase):
    """Cover both arms of the duplicate-name guard in ``load_config``."""

    def write_config(self, text):
        """Write a synthetic opencode.json and return its path."""
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        path = tmp / "opencode.json"
        path.write_text(text)
        return path

    def test_duplicate_specialist_key_is_recorded(self):
        # key in result AND startswith("orevia-") -> append to duplicates.
        path = self.write_config('{"agents": {"orevia-qa": 1, "orevia-qa": 2}}')
        config, duplicates = pack.load_config(path)
        self.assertEqual(duplicates, ["orevia-qa"])
        self.assertEqual(config["agents"]["orevia-qa"], 2)

    def test_duplicate_non_specialist_key_is_ignored(self):
        # key in result is true but startswith is false: the short-circuit
        # skips the append, so ordinary duplicate keys stay unreported.
        path = self.write_config('{"meta": {"tool": 1, "tool": 2}}')
        config, duplicates = pack.load_config(path)
        self.assertEqual(duplicates, [])
        self.assertEqual(config["meta"]["tool"], 2)

    def test_empty_objects_cover_the_zero_iteration_hook(self):
        # The object_pairs loop runs zero times for an empty JSON object.
        path = self.write_config('{"agents": {}, "list": []}')
        config, duplicates = pack.load_config(path)
        self.assertEqual(config, {"agents": {}, "list": []})
        self.assertEqual(duplicates, [])


class MainCoverageTests(unittest.TestCase):
    """Cover both arms of ``main`` and the populated/empty reporting loop."""

    def run_main(self, errors):
        """Call ``main`` with a controlled ``check`` result; return (rc, out, err)."""
        stdout, stderr = io.StringIO(), io.StringIO()
        with mock.patch.object(pack, "check", return_value=errors):
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                status = pack.main()
        return status, stdout.getvalue(), stderr.getvalue()

    def test_success_path_reports_the_agent_count_and_returns_zero(self):
        # Empty errors: the reporting loop is skipped, the if-errors false arc
        # is taken and the agent count / validation message lines execute.
        status, stdout, stderr = self.run_main([])
        self.assertEqual(status, 0)
        self.assertIn("Delivery tooling validated:", stdout)
        self.assertIn("agents,", stdout)
        self.assertEqual(stderr, "")

    def test_error_path_prints_every_problem_and_returns_one(self):
        # Populated errors: the loop body runs for each item and the true arc
        # returns 1 before the success lines can execute.
        status, stdout, stderr = self.run_main(["first problem", "second problem"])
        self.assertEqual(status, 1)
        self.assertEqual(stdout, "")
        self.assertEqual(stderr, "check-pack: first problem\ncheck-pack: second problem\n")

    def test_single_error_is_reported_once(self):
        # A one-element iterable still enters the reporting loop body.
        status, _stdout, stderr = self.run_main(["only problem"])
        self.assertEqual(status, 1)
        self.assertEqual(stderr, "check-pack: only problem\n")


if __name__ == "__main__":
    unittest.main()
