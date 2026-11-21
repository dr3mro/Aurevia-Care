"""Regression test for issue 231: the suite must not inherit the worker's environment."""
import os
import subprocess
import sys
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent


class HermeticEnvironmentTest(unittest.TestCase):
    def test_worker_variables_do_not_reach_the_tests(self):
        """A worker exports BRANCH, STORY_*, SWARM and tokens; the helpers must drop them."""
        leaked = {"BRANCH": "new branch", "BRANCH_PREFIX": "swarm", "STORY_ID": "READY-07",
                  "SWARM": "1", "MODE": "story", "GH_TOKEN": "t", "PUSH_TOKEN": "p",
                  "GITHUB_REPOSITORY": "o/r", "OPENCODE_API_KEY": "k", "GIT_DIR": "/nowhere",
                  "BASE_BRANCH": "main", "SWARM_WORKFLOW_PUSH": "true"}
        code = ("import os, sys; sys.path.insert(0, %r); import helpers; "
                "print(sorted(k for k in %r if k in os.environ))" % (str(TESTS), sorted(leaked)))
        out = subprocess.run([sys.executable, "-I", "-c", code], capture_output=True, text=True,
                             env={**os.environ, **leaked}, check=True).stdout
        self.assertEqual(out.strip(), "[]")

    def test_path_and_home_survive(self):
        code = ("import os, sys; sys.path.insert(0, %r); import helpers; "
                "print('PATH' in os.environ and 'HOME' in os.environ)" % str(TESTS))
        out = subprocess.run([sys.executable, "-I", "-c", code], capture_output=True, text=True,
                             check=True).stdout
        self.assertEqual(out.strip(), "True")


if __name__ == "__main__":
    unittest.main()
