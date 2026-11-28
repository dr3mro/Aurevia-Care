"""Contract tests for refreshing new pull requests without rewriting their history.

A worker note binds evidence to a tested commit SHA. Rewriting that commit with
``git rebase`` breaks the binding and fails the trusted Swarm gate, so the
``opened`` refresh must merge the default branch instead: the tested commit
stays in the branch ancestry and the compare API keeps reporting it as ahead.
"""
import subprocess
import tempfile
import unittest
from pathlib import Path

import yaml

from helpers import ROOT

WORKFLOW = ROOT / ".github" / "workflows" / "pr-rebase.yml"
DOC = ROOT / "docs" / "SWARM.md"


def load_workflow():
    """Parse the workflow without executing any of its shell steps."""
    return yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))


def refresh_script():
    """Return the run block of the refresh step."""
    steps = load_workflow()["jobs"]["refresh"]["steps"]
    return next(step["run"] for step in steps if "run" in step and "$BASE" in step["run"])


class PullRequestRefreshTest(unittest.TestCase):
    """The refresh step keeps worker evidence ancestry and stays least-privilege."""

    def test_refresh_merges_the_base_instead_of_rewriting_commits(self):
        script = refresh_script()
        self.assertIn('git merge --no-edit "origin/$BASE"', script)
        self.assertIn('Merged \\`$BASE\\` into this branch so it starts conflict-free.', script)
        self.assertNotIn("git rebase", script)
        self.assertNotIn("--force", script)

    def test_already_current_branch_exits_before_merge_and_push(self):
        script = refresh_script()
        self.assertIn('if git merge-base --is-ancestor "origin/$BASE" HEAD; then', script)
        self.assertIn('Already contains main; nothing to merge.', script)
        self.assertLess(script.index("nothing to merge"), script.index("git merge --no-edit"))
        self.assertLess(script.index("nothing to merge"), script.index("push origin"))

    def test_refresh_pushes_the_merged_branch_without_force(self):
        script = refresh_script()
        self.assertIn('push origin "HEAD:$(git rev-parse --abbrev-ref HEAD)"', script)
        self.assertNotIn("force-with-lease", script)

    def test_opened_only_trigger_and_least_privilege_are_unchanged(self):
        workflow = load_workflow()
        triggers = workflow.get(True, workflow.get("on"))
        self.assertEqual(triggers, {"pull_request": {"types": ["opened"]}})
        self.assertEqual(workflow["permissions"], {"contents": "read"})
        job = workflow["jobs"]["refresh"]
        self.assertEqual(job["permissions"], {"contents": "write", "pull-requests": "write"})
        self.assertIs(workflow["concurrency"]["cancel-in-progress"], False)

    def test_checkout_drops_credentials_and_fetch_disables_hooks(self):
        steps = load_workflow()["jobs"]["refresh"]["steps"]
        checkout = next(step for step in steps if "actions/checkout" in step.get("uses", ""))
        self.assertIs(checkout["with"]["persist-credentials"], False)
        self.assertIn("core.hooksPath=/dev/null", refresh_script())

    def test_conflicts_abort_and_report_without_force(self):
        script = refresh_script()
        self.assertIn("git merge --abort || true", script)
        self.assertIn('Merge of \\`$BASE\\` into this branch hit conflicts.', script)
        self.assertIn("exit 0", script)
        self.assertLess(script.index("git merge --abort"), script.index("hit conflicts"))

    def test_only_same_repository_pull_requests_are_refreshed(self):
        job = load_workflow()["jobs"]["refresh"]
        self.assertEqual(job["if"],
                         "github.event.pull_request.head.repo.full_name == github.repository")

    def test_documentation_describes_the_merge_contract(self):
        text = DOC.read_text(encoding="utf-8")
        self.assertIn("merges `main`", text)
        self.assertNotIn("That rebase rewrites commit SHAs", text)

    def test_merging_base_keeps_the_tested_revision_an_ancestor(self):
        """Git invariant behind swarm_gate.py::validate_verification_evidence.

        The gate accepts a tested revision only when compare(tested, head) is
        ahead with behind_by 0; a merge keeps both commits in the ancestry.
        """
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            env = {"GIT_AUTHOR_NAME": "Test", "GIT_AUTHOR_EMAIL": "test@example.invalid",
                   "GIT_COMMITTER_NAME": "Test", "GIT_COMMITTER_EMAIL": "test@example.invalid",
                   "GIT_CONFIG_GLOBAL": "/dev/null", "GIT_CONFIG_SYSTEM": "/dev/null",
                   "PATH": "/usr/bin:/bin"}

            def git(*args):
                return subprocess.run(("git", *args), cwd=root, env=env,
                                      capture_output=True, text=True)

            self.assertEqual(git("init", "-b", "main").returncode, 0)
            (root / "base.txt").write_text("base\n", encoding="utf-8")
            self.assertEqual(git("add", "base.txt").returncode, 0)
            self.assertEqual(git("commit", "-m", "base").returncode, 0)
            base = git("rev-parse", "HEAD").stdout.strip()
            self.assertEqual(git("checkout", "-b", "worker").returncode, 0)
            (root / "worker.txt").write_text("worker\n", encoding="utf-8")
            self.assertEqual(git("add", "worker.txt").returncode, 0)
            self.assertEqual(git("commit", "-m", "worker").returncode, 0)
            tested = git("rev-parse", "HEAD").stdout.strip()
            git("checkout", "main")
            (root / "main.txt").write_text("main\n", encoding="utf-8")
            git("add", "main.txt")
            git("commit", "-m", "main")
            git("checkout", "worker")
            self.assertEqual(git("merge", "--no-edit", "main").returncode, 0)
            self.assertEqual(git("merge-base", "--is-ancestor", tested, "HEAD").returncode, 0)
            self.assertEqual(git("merge-base", "--is-ancestor", base, "HEAD").returncode, 0)


if __name__ == "__main__":
    unittest.main()
