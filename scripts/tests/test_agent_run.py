import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT

FAKE_OPENCODE = """#!/usr/bin/env bash
cp opencode.json "$SEEN"
case "$FAKE_MODE" in
  commit) echo work > feature.txt; git add feature.txt; git commit -qm feature; echo done ;;
  rate) echo "Error: 429 rate limit exceeded"; exit 1 ;;
  dirty) echo work > feature.txt; echo done ;;
  edit) echo more >> feature.txt; echo findings ;;
  none) echo nothing ;;
esac
"""
FAKE_GH = """#!/usr/bin/env bash
echo "$*" >> "$GH_LOG"
if [[ "$1 $2" == "pr view" ]]; then echo 7; fi
"""


class AgentRunTests(unittest.TestCase):
    def setUp(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        self.tmp = tmp
        origin, self.repo, bin_dir = tmp / "origin.git", tmp / "repo", tmp / "bin"
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(origin)], check=True)
        subprocess.run(["git", "clone", "-q", str(origin), str(self.repo)], check=True,
                       capture_output=True)
        (self.repo / "scripts").mkdir()
        (self.repo / ".github").mkdir()
        shutil.copy(ROOT / "scripts/agent-run.sh", self.repo / "scripts/agent-run.sh")
        (self.repo / "scripts/check.sh").write_text("#!/usr/bin/env bash\nexit ${CHECK_EXIT:-0}\n")
        (self.repo / ".github/pull_request_template.md").write_text("## Summary\n")
        (self.repo / ".github/opencode.ci.json").write_text('{"ci": true}')
        (self.repo / "opencode.json").write_text('{"real": true}')
        (self.repo / ".gitignore").write_text("/.agent/\n")
        for name in ("agent-run.sh", "check.sh"):
            (self.repo / "scripts" / name).chmod(0o755)
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init")
        self.git("push", "-q", "origin", "main")
        bin_dir.mkdir()
        for name, body in (("opencode", FAKE_OPENCODE), ("gh", FAKE_GH)):
            (bin_dir / name).write_text(body)
            (bin_dir / name).chmod(0o755 | stat.S_IXUSR)
        self.gh_log = tmp / "gh.log"
        self.env = {**os.environ, "PATH": f"{bin_dir}:{os.environ['PATH']}",
                    "HOME": str(tmp / "home"), "GITHUB_WORKSPACE": str(self.repo),
                    "GITHUB_OUTPUT": str(tmp / "output"), "GITHUB_RUN_NUMBER": "9",
                    "GH_LOG": str(self.gh_log), "SEEN": str(tmp / "seen.json"), "MODEL": "opencode/test",
                    "OPENCODE_API_KEY": "k-test", "STORY_ID": "issue-5",
                    "STORY_TITLE": "Add login", "STORY_BODY": "body", "ISSUE_NUMBER": "5",
                    "GH_TOKEN": "t-test", "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                    "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True)

    def run_cmd(self, command, **env):
        return subprocess.run(["bash", "scripts/agent-run.sh", command], cwd=self.repo,
                              env={**self.env, **env}, capture_output=True, text=True)

    def outputs(self):
        lines = (self.tmp / "output").read_text().splitlines()
        return dict(line.split("=", 1) for line in lines)

    def test_commit_is_checked_pushed_and_opened_as_pr(self):
        self.assertEqual(self.run_cmd("implement", FAKE_MODE="commit").returncode, 0)
        out = self.outputs()
        self.assertEqual((out["result"], out["commits"], out["checks"]), ("ok", "1", "passed"))
        self.assertEqual(out["branch"], "opencode/issue-5-9")
        self.assertEqual(self.run_cmd("publish").returncode, 0)
        log = self.gh_log.read_text()
        self.assertIn("pr create --base main --head opencode/issue-5-9", log)
        self.assertNotIn("--draft", log)
        heads = subprocess.run(["git", "ls-remote", "--heads", "origin"], cwd=self.repo,
                               capture_output=True, text=True).stdout
        self.assertIn("refs/heads/opencode/issue-5-9", heads)

    def test_agent_runs_with_ci_profile_and_real_config_is_restored(self):
        self.run_cmd("implement", FAKE_MODE="edit")
        self.assertEqual((self.tmp / "seen.json").read_text(), '{"ci": true}')
        self.assertEqual((self.repo / "opencode.json").read_text(), '{"real": true}')
        changed = subprocess.run(["git", "diff", "--name-only", "main..HEAD"], cwd=self.repo,
                                 capture_output=True, text=True).stdout
        self.assertNotIn("opencode.json", changed)

    def test_push_token_overrides_gh_token_for_git_only(self):
        self.assertEqual(self.run_cmd("implement", FAKE_MODE="commit").returncode, 0)
        # PUSH_TOKEN is used by github_git; GH_TOKEN stays for gh CLI calls.
        done = self.run_cmd("publish", PUSH_TOKEN="pat-workflow")
        self.assertEqual(done.returncode, 0)
        heads = subprocess.run(["git", "ls-remote", "--heads", "origin"], cwd=self.repo,
                               capture_output=True, text=True).stdout
        self.assertIn("refs/heads/opencode/issue-5-9", heads)
        log = self.gh_log.read_text()
        self.assertIn("pr create --base main", log)

    def test_failing_checks_open_a_draft(self):
        self.run_cmd("implement", FAKE_MODE="commit", CHECK_EXIT="1")
        self.assertEqual(self.outputs()["checks"], "failed")
        self.run_cmd("publish")
        self.assertIn("--draft", self.gh_log.read_text())

    def test_uncommitted_work_is_committed(self):
        self.run_cmd("implement", FAKE_MODE="dirty")
        self.assertEqual(self.outputs()["commits"], "1")

    def test_rate_limit_stops_without_pr_and_reports_on_issue(self):
        self.assertEqual(self.run_cmd("implement", FAKE_MODE="rate").returncode, 0)
        self.assertEqual(self.outputs()["result"], "rate_limited")
        self.run_cmd("publish")
        log = self.gh_log.read_text()
        self.assertIn("issue comment 5", log)
        self.assertNotIn("pr create", log)

    def test_reviews_discard_edits_and_stop_on_rate_limit(self):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.assertEqual(self.run_cmd("review", FAKE_MODE="edit", REVIEWERS="a b").returncode, 0)
        status = subprocess.run(["git", "status", "--porcelain"], cwd=self.repo,
                                capture_output=True, text=True).stdout
        self.assertEqual(status, "")
        self.assertEqual((self.repo / "feature.txt").read_text(), "work\n")
        for review in (self.repo / ".agent").glob("review-*.md"):
            review.unlink()
        self.run_cmd("review", FAKE_MODE="rate", REVIEWERS="a b")
        self.assertIn("rate limit", (self.repo / ".agent/review-a.md").read_text())
        self.assertFalse((self.repo / ".agent/review-b.md").exists())
        self.run_cmd("post-reviews", PR_NUMBER="7")
        self.assertIn("pr comment 7", self.gh_log.read_text())

    def test_unknown_command_is_a_usage_error(self):
        self.assertEqual(self.run_cmd("bogus").returncode, 2)


if __name__ == "__main__":
    unittest.main()
