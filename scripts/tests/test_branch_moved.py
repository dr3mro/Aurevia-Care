"""A pull request branch that moved after its test no longer fails the whole batch."""
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")


class RecordMergesTests(unittest.TestCase):
    def test_moved_branch_is_skipped_and_the_rest_recorded(self):
        merged = [(1, "A-01", "aaa"), (2, "B-01", "bbb")]
        gates = {1: ("g", {}), 2: ("g", {})}

        def record(pr, task_id, sha, gate):
            if pr == 1:
                raise swarm.BranchMoved("moved")
            return f"comment {pr}"

        with mock.patch.object(swarm, "record_merge", side_effect=record):
            recorded, comments, moved = swarm.record_merges(merged, gates)
        self.assertEqual(recorded, [merged[1]])
        self.assertEqual(comments, ["comment 2"])
        self.assertEqual(moved, [merged[0]])

    def test_branch_moved_is_still_a_system_exit(self):
        self.assertTrue(issubclass(swarm.BranchMoved, SystemExit))


if __name__ == "__main__":
    unittest.main()
