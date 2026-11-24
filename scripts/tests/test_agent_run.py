import os
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from helpers import ROOT

FAKE_OPENCODE = """#!/usr/bin/env bash
cp .opencode/opencode.json "$SEEN"
printf '%s\\n' "$*" >> "${PROMPT_LOG:-/dev/null}"
if [[ -n "${ARGV_LOG:-}" ]]; then printf '%s\\0' "$@" >> "$ARGV_LOG"; fi
case "$*" in *aurevia-task-advisor*)
  case "${BRIEF_MODE:-none}" in
    good) printf 'chatter\\n<<<BRIEF\\nGoal: advised goal\\nBRIEF>>>\\n' ;;
    rate) echo "Error: 429 rate limit exceeded"; exit 1 ;;
  esac
  exit 0 ;;
esac
case "$FAKE_MODE" in
  commit) echo work > feature.txt; git add feature.txt; git commit -qm feature; echo done ;;
  rate) echo "Error: 429 rate limit exceeded"; exit 1 ;;
  fail) echo "provider failed"; exit 1 ;;
  rate-slow) echo "Error: 429 rate limit exceeded"; sleep 5 ;;
  dirty) echo work > feature.txt; echo done ;;
  edit) echo more >> feature.txt; echo findings ;;
  none) echo nothing ;;
  resolve) echo merged > feature.txt; git add feature.txt; git commit -qm resolved --no-edit; echo done ;;
  markers) git add feature.txt; git commit -qm bad --no-edit; echo done ;;
  stubborn) echo nothing ;;
  slow) sleep 5 ;;
  report) mkdir -p .agent; printf 'Root cause found: yes\\n## Fix plan\\n1. fix it\\n' > .agent/debug-report.md; echo done ;;
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
        shutil.copy(ROOT / "scripts/route-task.py", self.repo / "scripts/route-task.py")
        (self.repo / "scripts/check.sh").write_text("#!/usr/bin/env bash\nexit ${CHECK_EXIT:-0}\n")
        (self.repo / "scripts/coverage-check.sh").write_text(
            "#!/usr/bin/env bash\necho x >> \"${COVERAGE_LOG:-/dev/null}\"\nexit ${COVERAGE_EXIT:-0}\n")
        (self.repo / "scripts/frontend-typecheck-check.sh").write_text(
            "#!/usr/bin/env bash\necho t >> \"${FRONTEND_TYPECHECK_LOG:-/dev/null}\"\n"
            "exit ${FRONTEND_TYPECHECK_EXIT:-0}\n")
        (self.repo / "scripts/quality-gate.sh").write_text(
            "#!/usr/bin/env bash\necho q >> \"${QUALITY_LOG:-/dev/null}\"\n"
            "echo \"$*\" >> \"${QUALITY_ARGS:-/dev/null}\"\nexit ${QUALITY_EXIT:-0}\n")
        (self.repo / ".github/pull_request_template.md").write_text("## Summary\n")
        (self.repo / ".opencode").mkdir(parents=True, exist_ok=True)
        (self.repo / ".opencode/opencode.ci.json").write_text('{"ci": true}')
        (self.repo / ".opencode/opencode.json").write_text('{"real": true}')
        (self.repo / ".gitignore").write_text("/.agent/\n")
        for name in ("agent-run.sh", "check.sh", "coverage-check.sh",
                     "frontend-typecheck-check.sh", "quality-gate.sh"):
            (self.repo / "scripts" / name).chmod(0o755)
        self.git("add", "-A")
        self.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-qm", "init")
        self.git("push", "-q", "origin", "main")
        bin_dir.mkdir()
        for name, body in (("opencode", FAKE_OPENCODE), ("gh", FAKE_GH)):
            (bin_dir / name).write_text(body)
            (bin_dir / name).chmod(0o755 | stat.S_IXUSR)
        self.gh_log = tmp / "gh.log"
        self.bin_dir = bin_dir

    def fixture_environment(self):
        tmp = self.tmp
        host = {name: os.environ[name] for name in ("TMPDIR", "TMP", "TEMP", "LANG", "LC_ALL")
                if name in os.environ}
        return {**host, "PATH": f"{self.bin_dir}:{os.environ['PATH']}",
                    "MODE": "story", "BRANCH_PREFIX": "opencode", "CHECK_SCRIPT": "scripts/check.sh",
                    "HOME": str(tmp / "home"), "GITHUB_WORKSPACE": str(self.repo),
                    "GITHUB_OUTPUT": str(tmp / "output"), "GITHUB_RUN_NUMBER": "9",
                    "GH_LOG": str(self.gh_log), "SEEN": str(tmp / "seen.json"),
                    "SWARM_MODEL": "opencode/test",
                    "OPENCODE_API_KEY": "k-test", "STORY_ID": "issue-5",
                    "STORY_TITLE": "Add login", "STORY_BODY": "body", "ISSUE_NUMBER": "5",
                    "GH_TOKEN": "t-test", "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                    "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}

    def git(self, *args):
        subprocess.run(["git", *args], cwd=self.repo, check=True, capture_output=True)

    def run_cmd(self, command, **env):
        merged = {**self.fixture_environment(), **env}
        for name in ("MODEL", "SWARM_FORCE_MODEL", "SWARM_MODEL"):
            if name in env and env[name] is None:
                merged.pop(name, None)
        return subprocess.run(["bash", "scripts/agent-run.sh", command], cwd=self.repo,
                              env=merged, capture_output=True, text=True)

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

    def test_fixture_uses_its_own_story_settings_inside_a_worker(self):
        worker_settings = {
            "MODE": "fix", "BRANCH": "swarm/synthetic-fix", "BRANCH_PREFIX": "swarm",
            "SWARM": "1", "CHECK_SCRIPT": "scripts/worker-check.sh", "PR_NUMBER": "123",
            "SEGMENT": "2", "SEGMENTS": "4",
        }
        with patch.dict(os.environ, worker_settings):
            result = self.run_cmd("implement", FAKE_MODE="commit")
            self.assertEqual(result.returncode, 0, result.stderr)
            out = self.outputs()
            self.assertEqual(out["branch"], "opencode/issue-5-9")
            self.assertEqual(out["result"], "ok")
            self.assertEqual(out["checks"], "passed")
            self.assertEqual(self.run_cmd("publish").returncode, 0)
            self.assertIn("pr create --base main", self.gh_log.read_text())

    def push_to_main(self, name, text):
        other = self.tmp / "other"
        if not other.exists():
            subprocess.run(["git", "clone", "-q", str(self.tmp / "origin.git"), str(other)],
                           check=True, capture_output=True)
        (other / name).parent.mkdir(parents=True, exist_ok=True)
        (other / name).write_text(text)
        for args in (("add", name), ("commit", "-qm", f"main {name}"), ("push", "-q", "origin", "main")):
            subprocess.run(["git", *args], cwd=other, check=True, capture_output=True,
                           env=self.fixture_environment())

    def merge_main(self, mode, **env):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.before = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo,
                                     capture_output=True, text=True).stdout
        self.assertEqual(self.run_cmd("fetch-main").returncode, 0)
        return self.run_cmd("merge-main", FAKE_MODE=mode, MERGE_ATTEMPTS="2", **env)

    def test_merge_main_without_conflicts_merges_and_rechecks(self):
        self.push_to_main("other.txt", "from main\n")
        self.assertEqual(self.merge_main("none").returncode, 0)
        self.assertEqual(self.outputs()["merge"], "merged")
        self.assertTrue((self.repo / "other.txt").exists())
        self.assertTrue((self.repo / "feature.txt").exists())
        self.assertEqual((self.repo / ".agent/checks").read_text(), "passed")

    def test_merge_main_has_the_agent_fix_conflicts(self):
        self.push_to_main("feature.txt", "main version\n")
        self.assertEqual(self.merge_main("resolve").returncode, 0)
        self.assertEqual(self.outputs()["merge"], "merged")
        self.assertEqual((self.repo / "feature.txt").read_text(), "merged\n")
        status = subprocess.run(["git", "status", "--porcelain"], cwd=self.repo,
                                capture_output=True, text=True).stdout
        self.assertEqual(status.strip(), "")
        self.assertTrue((self.repo / ".git/MERGE_HEAD").exists() is False)

    def test_conflict_prompt_loads_the_dedicated_merge_skill(self):
        script = (ROOT / "scripts/agent-run.sh").read_text()
        skill = ROOT / ".opencode/skills/aurevia-merge-conflict/SKILL.md"
        self.assertIn(str(skill.relative_to(ROOT)), script)
        self.push_to_main(str(skill.relative_to(ROOT)), skill.read_text())
        self.push_to_main("feature.txt", "main version\n")
        prompt = self.tmp / "merge-prompt"
        self.assertEqual(self.merge_main("resolve", PROMPT_LOG=str(prompt)).returncode, 0)
        self.assertIn("git ls-files -u", prompt.read_text())
        self.assertEqual(self.outputs()["merge"], "merged")

    def test_unresolved_conflict_is_rolled_back_after_the_attempt_limit(self):
        self.push_to_main("feature.txt", "main version\n")
        self.assertEqual(self.merge_main("stubborn").returncode, 0)
        self.assertEqual(self.outputs()["merge"], "conflict")
        after = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, capture_output=True,
                               text=True)
        self.assertEqual(after.stdout, self.before)
        self.assertEqual((self.repo / "feature.txt").read_text(), "work\n")
        self.assertFalse((self.repo / ".git/MERGE_HEAD").exists())

    def test_committed_conflict_markers_are_not_accepted(self):
        self.push_to_main("feature.txt", "main version\n")
        self.merge_main("markers")
        self.assertEqual(self.outputs()["merge"], "conflict")
        self.assertNotIn("<<<<<<<", (self.repo / "feature.txt").read_text())

    def test_resolve_merges_main_into_a_conflicted_pr_branch(self):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.git("branch", "pr-5")
        self.git("switch", "-q", "main")
        self.push_to_main("feature.txt", "main version\n")
        self.assertEqual(self.run_cmd("fetch-main").returncode, 0)
        done = self.run_cmd("resolve", FAKE_MODE="resolve", CONFLICTED="[5]", MERGE_ATTEMPTS="2")
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertEqual((self.repo / ".agent/resolve/5").read_text(), "merged")
        self.assertTrue((self.repo / ".agent/resolve/5.bundle").exists())
        shown = subprocess.run(["git", "show", "resolve-5:feature.txt"], cwd=self.repo,
                               capture_output=True, text=True).stdout
        self.assertEqual(shown, "merged\n")

    def test_resolve_reports_an_unresolvable_conflict(self):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.git("branch", "pr-5")
        self.git("switch", "-q", "main")
        self.push_to_main("feature.txt", "main version\n")
        self.run_cmd("fetch-main")
        self.run_cmd("resolve", FAKE_MODE="stubborn", CONFLICTED="[5]", MERGE_ATTEMPTS="2")
        self.assertEqual((self.repo / ".agent/resolve/5").read_text(), "conflict")

    def adopt_setup(self):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.assertEqual(self.run_cmd("bundle").returncode, 0)
        self.git("switch", "-q", "main")
        self.git("branch", "-D", "opencode/issue-5-9")

    def test_adopt_rebuilds_the_agent_branch_from_its_bundle(self):
        self.adopt_setup()
        done = self.run_cmd("adopt")
        self.assertEqual(done.returncode, 0, done.stderr)
        branch = subprocess.run(["git", "branch", "--show-current"], cwd=self.repo,
                                capture_output=True, text=True).stdout.strip()
        self.assertEqual(branch, "opencode/issue-5-9")
        self.assertEqual((self.repo / "feature.txt").read_text(), "work\n")
        self.assertEqual(self.run_cmd("publish").returncode, 0)
        self.assertIn("pr create --base main --head opencode/issue-5-9", self.gh_log.read_text())

    def test_adopt_refuses_a_branch_name_the_run_was_not_meant_to_use(self):
        self.adopt_setup()
        for name in ("main", "evil/x", "opencode/../x", "-x"):
            (self.repo / ".agent/branch").write_text(name)
            self.assertNotEqual(self.run_cmd("adopt").returncode, 0, name)

    def test_publish_without_any_run_files_reports_no_changes(self):
        shutil.rmtree(self.repo / ".agent", ignore_errors=True)
        (self.repo / ".agent").mkdir()
        self.assertEqual(self.run_cmd("publish").returncode, 0)
        self.assertEqual(self.outputs()["pr"], "")

    def test_merge_main_is_skipped_without_commits_and_when_current(self):
        self.run_cmd("implement", FAKE_MODE="none")
        self.run_cmd("fetch-main")
        self.run_cmd("merge-main", FAKE_MODE="none")
        self.assertEqual(self.outputs()["merge"], "skipped")

    def test_agent_runs_with_ci_profile_and_real_config_is_restored(self):
        self.run_cmd("implement", FAKE_MODE="edit")
        self.assertEqual((self.tmp / "seen.json").read_text(), '{"ci": true}')
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(), '{"real": true}')
        changed = subprocess.run(["git", "diff", "--name-only", "main..HEAD"], cwd=self.repo,
                                 capture_output=True, text=True).stdout
        self.assertNotIn(".opencode/opencode.json", changed)

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

    def test_a_coverage_miss_gets_two_repairs_then_opens_a_draft(self):
        log = self.repo.parent / "coverage.log"
        self.run_cmd("implement", FAKE_MODE="commit", COVERAGE_EXIT="1", COVERAGE_LOG=str(log))
        self.assertEqual(self.outputs()["checks"], "failed")
        self.assertEqual(len(log.read_text().split()), 3)

    def test_the_quality_gate_gets_the_base_and_the_notes_file(self):
        args = self.repo.parent / "quality-args.log"
        self.run_cmd("implement", FAKE_MODE="commit", QUALITY_ARGS=str(args))
        base, notes = args.read_text().split()
        self.assertRegex(base, r"^[0-9a-f]{40}$")
        self.assertRegex(notes, r"^.agents/notes/issue-5-.*\.md$")
        self.assertEqual(self.outputs()["checks"], "passed")

    def test_a_quality_gate_miss_gets_two_repairs_then_opens_a_draft(self):
        log = self.repo.parent / "quality.log"
        prompts = self.repo.parent / "prompts.log"
        self.run_cmd("implement", FAKE_MODE="commit", QUALITY_EXIT="1",
                     QUALITY_LOG=str(log), PROMPT_LOG=str(prompts))
        self.assertEqual(self.outputs()["checks"], "failed")
        # Initial failure + two repair re-runs = three invocations.
        self.assertEqual(len(log.read_text().split()), 3)
        self.assertEqual(prompts.read_text().count("The quality gate failed"), 2)
        self.assertIn("## Review verdicts", prompts.read_text())

    def brief_prompts(self, **env):
        prompts = self.repo.parent / "prompts.log"
        self.run_cmd("implement", FAKE_MODE="commit", SWARM="1", PROMPT_LOG=str(prompts), **env)
        return prompts.read_text()

    def test_the_worker_gets_the_senior_developer_brief(self):
        text = self.brief_prompts(BRIEF_MODE="good", STORY_BODY="  - scope: apps/api\n")
        self.assertIn("<<<BRIEF-FOR-WORKER", text)
        self.assertIn("Goal: advised goal", text)
        self.assertIn("## Routing", text)
        self.assertIn("aurevia-task-advisor", text)
        self.assertNotIn("load the full project context", text)

    def test_a_failed_advisor_falls_back_to_the_routing_block(self):
        text = self.brief_prompts(BRIEF_MODE="rate")
        self.assertIn("<<<BRIEF-FOR-WORKER", text)
        self.assertIn("## Routing", text)
        self.assertNotIn("Goal: advised goal", text)
        self.assertEqual(self.outputs()["checks"], "passed")

    def test_brief_off_skips_the_advisor(self):
        text = self.brief_prompts(BRIEF="off")
        self.assertNotIn("--agent aurevia-task-advisor", text)
        self.assertIn("## Routing", text)

    def test_a_non_swarm_run_keeps_the_full_context_prompt(self):
        prompts = self.repo.parent / "prompts.log"
        self.run_cmd("implement", FAKE_MODE="commit", PROMPT_LOG=str(prompts))
        self.assertIn("load the full project context", prompts.read_text())
        self.assertNotIn("BRIEF-FOR-WORKER", prompts.read_text())

    def test_the_worker_prompt_asks_for_the_reviewers_and_their_verdicts(self):
        prompts = self.repo.parent / "prompts.log"
        self.run_cmd("implement", FAKE_MODE="commit", SWARM="1", PROMPT_LOG=str(prompts))
        text = prompts.read_text()
        for name in ("aurevia-plan-reviewer", "aurevia-code-reviewer",
                     "aurevia-coverage-auditor", "aurevia-devsecops"):
            self.assertIn(name, text)
        self.assertIn("Quality loop (mandatory)", text)

    def test_a_frontend_typecheck_miss_gets_four_repairs_then_opens_a_draft(self):
        log = self.repo.parent / "frontend-typecheck.log"
        self.run_cmd("implement", FAKE_MODE="commit", FRONTEND_TYPECHECK_EXIT="1",
                     FRONTEND_TYPECHECK_LOG=str(log))
        self.assertEqual(self.outputs()["checks"], "failed")
        # Initial failure + four repair re-runs = five invocations.
        self.assertEqual(len(log.read_text().split()), 5)
        # Reset to main so the second run actually changes files and triggers the gate.
        self.git("checkout", "-q", "main")
        self.git("branch", "-D", "opencode/issue-5-9")
        prompt_log = self.tmp / "prompts.log"
        # The repair prompt must name typecheck, not only coverage.
        self.run_cmd("implement", FAKE_MODE="commit", FRONTEND_TYPECHECK_EXIT="1",
                     FRONTEND_TYPECHECK_LOG=str(self.repo.parent / "ft2.log"),
                     PROMPT_LOG=str(prompt_log))
        self.assertIn("frontend typecheck", prompt_log.read_text().lower())

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

    def test_a_timed_out_segment_continues_on_the_same_branch(self):
        self.run_cmd("implement", FAKE_MODE="slow", AGENT_TIMEOUT="1", SEGMENT="1", SEGMENTS="2")
        out = self.outputs()
        self.assertEqual((out["result"], out["more"]), ("timeout", "true"))
        first = out["branch"]
        self.run_cmd("implement", FAKE_MODE="commit", SEGMENT="2", SEGMENTS="2")
        out = self.outputs()
        self.assertEqual((out["result"], out["more"], out["commits"]), ("ok", "false", "1"))
        self.assertEqual(out["branch"], first)
        self.assertEqual((self.repo / ".agent/result").read_text(), "ok")

    def test_the_last_segment_never_asks_for_another(self):
        self.run_cmd("implement", FAKE_MODE="slow", AGENT_TIMEOUT="1", SEGMENT="2", SEGMENTS="2")
        self.assertEqual(self.outputs()["more"], "false")

    def test_a_finished_or_failed_segment_stops_the_chain(self):
        self.run_cmd("implement", FAKE_MODE="commit", SEGMENT="1", SEGMENTS="4")
        self.assertEqual(self.outputs()["more"], "false")

    def test_a_mid_run_sync_that_cannot_merge_leaves_the_branch_and_the_verdict_alone(self):
        self.push_to_main("feature.txt", "main version\n")
        self.run_cmd("implement", FAKE_MODE="commit")
        before = (self.repo / ".agent/checks").read_text()
        self.run_cmd("fetch-main")
        self.run_cmd("merge-main", FAKE_MODE="stubborn", MERGE_ATTEMPTS="1", SYNC_ONLY="1")
        self.assertEqual((self.repo / ".agent/checks").read_text(), before)
        self.assertEqual((self.repo / "feature.txt").read_text(), "work\n")
        self.assertFalse((self.repo / ".git/MERGE_HEAD").exists())

    def test_a_mid_run_sync_merges_main_without_the_full_check(self):
        self.push_to_main("other.txt", "from main\n")
        self.run_cmd("implement", FAKE_MODE="commit", CHECK_EXIT="0")
        self.run_cmd("fetch-main")
        self.run_cmd("merge-main", FAKE_MODE="none", SYNC_ONLY="1", CHECK_EXIT="1")
        self.assertEqual(self.outputs()["merge"], "merged")
        self.assertTrue((self.repo / "other.txt").exists())
        self.assertEqual((self.repo / ".agent/checks").read_text(), "passed")

    def test_a_start_sync_merges_main_before_the_first_commit(self):
        self.push_to_main("other.txt", "from main\n")
        self.run_cmd("fetch-main")
        self.run_cmd("merge-main", FAKE_MODE="none", SYNC_ONLY="1")
        self.assertEqual(self.outputs()["merge"], "merged")
        self.assertTrue((self.repo / "other.txt").exists())

    def test_the_bundle_is_built_on_the_branch_tip_not_on_the_start_merge(self):
        # A fix worker merges main before its base is recorded; the publish job only has the
        # remote branch tip, so the bundle's prerequisite must be that tip.
        self.git("push", "-q", "origin", "HEAD:refs/heads/opencode/fix")
        self.git("checkout", "-q", "-b", "fix-work")
        tip = subprocess.run(["git", "rev-parse", "HEAD"], cwd=self.repo, capture_output=True,
                             text=True).stdout.strip()
        self.push_to_main("other.txt", "from main\n")
        self.run_cmd("fetch-main")
        self.run_cmd("merge-main", FAKE_MODE="none", SYNC_ONLY="1")
        self.assertEqual((self.repo / ".agent/bundle-base").read_text(), tip)
        self.run_cmd("implement", FAKE_MODE="commit", MODE="fix", BRANCH="opencode/fix")
        self.assertEqual(self.run_cmd("bundle").returncode, 0)
        verify = subprocess.run(["git", "bundle", "verify", ".agent/result.bundle"], cwd=self.repo,
                                capture_output=True, text=True)
        self.assertIn(tip, verify.stdout + verify.stderr)
        self.run_cmd("merge-main", FAKE_MODE="none", SYNC_ONLY="1")
        self.assertEqual((self.repo / ".agent/bundle-base").read_text(), tip)

    def test_a_sync_between_segments_never_becomes_the_bundle_base(self):
        # By then the base is recorded and HEAD holds the worker's own, unpublished commits.
        self.run_cmd("implement", FAKE_MODE="commit")
        self.push_to_main("other.txt", "from main\n")
        self.run_cmd("fetch-main")
        self.run_cmd("merge-main", FAKE_MODE="none", SYNC_ONLY="1")
        self.assertFalse((self.repo / ".agent/bundle-base").exists())
        self.assertEqual(self.run_cmd("bundle").returncode, 0)
        verify = subprocess.run(["git", "bundle", "verify", ".agent/result.bundle"], cwd=self.repo,
                                capture_output=True, text=True)
        self.assertEqual(verify.returncode, 0, verify.stderr)

    def test_the_story_prompt_tells_the_agent_to_keep_merging_main(self):
        text = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertIn("Keep the branch current with the base branch", text)
        self.assertIn("after each stage of the story and", text)

    def debug(self, mode="report", **env):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.run_cmd("fetch-main")
        return self.run_cmd("debug", FAKE_MODE=mode, PR_NUMBER="9", DEBUG_ROUND="2", PROMPT_LOG=str(self.tmp / "prompt.log"), **env)

    def test_the_debugger_merges_main_runs_the_agent_and_reports_the_file(self):
        self.push_to_main("other.txt", "from main\n")
        self.assertEqual(self.debug().returncode, 0)
        out = self.outputs()
        self.assertEqual((out["result"], out["merge"], out["report"]), ("ok", "clean", "yes"))
        self.assertTrue((self.repo / "other.txt").exists())
        self.assertIn("Root cause found: yes", (self.repo / ".agent/debug-report.md").read_text())
        prompt = (self.tmp / "prompt.log").read_text()
        self.assertIn("You are the debugger, round 2 of 3", prompt)
        self.assertIn("Pull request #9", prompt)
        self.assertIn('merge state is "clean"', prompt)
        self.assertIn("Run all the tests", prompt)
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(), '{"real": true}')

    def test_the_debugger_survives_a_conflict_with_main_and_a_missing_report(self):
        self.push_to_main("feature.txt", "main version\n")
        self.assertEqual(self.debug("none").returncode, 0)
        out = self.outputs()
        self.assertEqual((out["result"], out["merge"], out["report"]), ("ok", "conflict", "no"))
        self.assertFalse((self.repo / ".git/MERGE_HEAD").exists())
        self.assertEqual((self.repo / "feature.txt").read_text(), "work\n")
        self.assertIn('merge state is "conflict"', (self.tmp / "prompt.log").read_text())

    def test_the_debugger_without_a_fetched_main_still_runs(self):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.git("update-ref", "-d", "refs/remotes/origin/main")
        self.assertEqual(self.run_cmd("debug", FAKE_MODE="rate").returncode, 0)
        out = self.outputs()
        self.assertEqual((out["result"], out["merge"], out["report"]), ("rate_limited", "none", "no"))

    def test_the_strategist_runs_read_only_and_restores_the_config(self):
        self.assertEqual(self.run_cmd("strategy", FAKE_MODE="none", PROMPT_LOG=str(self.tmp / "prompt.log")).returncode, 0)
        self.assertEqual(self.outputs()["result"], "ok")
        prompt = (self.tmp / "prompt.log").read_text()
        self.assertIn("six-hourly strategist", prompt)
        self.assertIn("strategy.json", prompt)
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(), '{"real": true}')

    def test_unknown_command_is_a_usage_error(self):
        self.assertEqual(self.run_cmd("bogus").returncode, 2)

    # Notes file names (the daily reconciliation groups notes by task id).
    def notes_path(self, **env):
        import re
        log = self.tmp / "prompt.log"
        done = self.run_cmd("implement", FAKE_MODE="none", PROMPT_LOG=str(log), SWARM="1", **env)
        self.assertTrue(log.exists(), done.stderr[-600:])
        found = re.findall(r".agents/notes/[A-Za-z0-9._-]+\.md", log.read_text())
        self.assertTrue(found, log.read_text()[:400])
        return found[0]

    def test_a_story_names_its_notes_after_the_task(self):
        self.assertRegex(self.notes_path(STORY_ID="CW-001"), r"^.agents/notes/CW-001-\d{8}-\d{6}\.md$")

    def test_a_fix_pass_keeps_the_task_id(self):
        self.assertRegex(self.notes_path(STORY_ID="CW-001", MODE="fix", BRANCH="main", FIX_COMMENT="x"),
                         r"^.agents/notes/CW-001-fix-\d{8}-\d{6}\.md$")

    def test_a_fix_without_a_task_id_uses_the_pull_request_number(self):
        self.assertRegex(self.notes_path(STORY_ID="", MODE="fix", BRANCH="main", PR_NUMBER="42", FIX_COMMENT="x"),
                         r"^.agents/notes/pr-42-fix-\d{8}-\d{6}\.md$")



class DirectModelEnvironmentTests(__import__("test_agent_run", fromlist=["AgentRunTests"]).AgentRunTests):
    """The real wrapper passes one SWARM_MODEL value and never rotates models."""

    def argv(self):
        return (self.tmp / "argv.log").read_bytes().decode().split("\0")[:-1]

    def assert_configuration_error(self, command, **env):
        prompts = self.tmp / f"{command}-prompts.log"
        argv = self.tmp / f"{command}-argv.log"
        result = self.run_cmd(command, SWARM_MODEL=None, PROMPT_LOG=str(prompts),
                              ARGV_LOG=str(argv), **env)
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "configuration_error")
        self.assertFalse(prompts.exists())
        self.assertFalse(argv.exists())
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')
        return result

    def test_swarm_model_is_passed_exactly_and_legacy_values_are_ignored(self):
        model = "opencode/custom model\nnext"
        result = self.run_cmd("implement", FAKE_MODE="commit", SWARM_MODEL=model,
                              MODEL="opencode/ignored", SWARM_FORCE_MODEL="1",
                              PROMPT_LOG=str(self.tmp / "prompts.log"),
                              ARGV_LOG=str(self.tmp / "argv.log"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "ok")
        argv = self.argv()
        self.assertEqual(argv.count("--model"), 1)
        self.assertEqual(argv[argv.index("--model") + 1], model)
        self.assertNotIn("opencode/ignored", argv)
        self.assertEqual(argv.count("run"), 1)

    def test_unset_swarm_model_fails_before_invocation(self):
        prompts = self.tmp / "missing-unset.log"
        argv = self.tmp / "missing-unset.argv"
        result = self.run_cmd("implement", FAKE_MODE="commit", SWARM_MODEL=None,
                              SWARM="1", STORY_BODY="  - scope: apps/api\n",
                              BRIEF_MODE="good", PROMPT_LOG=str(prompts), ARGV_LOG=str(argv))
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "configuration_error")
        self.assertEqual(self.outputs()["checks"], "skipped")
        self.assertEqual(self.outputs()["commits"], "0")
        self.assertEqual(self.outputs()["more"], "false")
        self.assertFalse(prompts.exists())
        self.assertFalse(argv.exists())
        for name in ("coverage.log", "frontend-typecheck.log", "quality.log"):
            self.assertFalse((self.repo / ".agent" / name).exists(), name)
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')

    def test_empty_swarm_model_fails_before_invocation(self):
        prompts = self.tmp / "missing-empty.log"
        argv = self.tmp / "missing-empty.argv"
        result = self.run_cmd("implement", FAKE_MODE="commit", SWARM_MODEL="",
                              PROMPT_LOG=str(prompts), ARGV_LOG=str(argv))
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "configuration_error")
        self.assertEqual(self.outputs()["checks"], "skipped")
        self.assertEqual(self.outputs()["commits"], "0")
        self.assertEqual(self.outputs()["more"], "false")
        self.assertFalse(prompts.exists())
        self.assertFalse(argv.exists())
        for name in ("coverage.log", "frontend-typecheck.log", "quality.log"):
            self.assertFalse((self.repo / ".agent" / name).exists(), name)
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')

    def test_debugger_configuration_error_is_terminal(self):
        self.assert_configuration_error("debug", PR_NUMBER="9", DEBUG_ROUND="2")
        self.assertEqual(self.outputs()["merge"], "clean")
        self.assertEqual(self.outputs()["report"], "no")

    def test_triage_configuration_error_is_terminal(self):
        self.assert_configuration_error("triage")

    def test_triage_success_emits_ok(self):
        result = self.run_cmd("triage", FAKE_MODE="none",
                              PROMPT_LOG=str(self.tmp / "triage-success.log"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "ok")
        self.assertTrue((self.tmp / "triage-success.log").exists())
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')

    def test_reconcile_configuration_error_is_terminal(self):
        self.assert_configuration_error("reconcile")
        self.assertFalse((self.repo / ".agent/reconcile/rules.patch").exists())

    def test_reconcile_success_emits_ok_and_creates_patch(self):
        result = self.run_cmd("reconcile", FAKE_MODE="none",
                              PROMPT_LOG=str(self.tmp / "reconcile-success.log"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "ok")
        self.assertTrue((self.repo / ".agent/reconcile/rules.patch").exists())
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')

    def test_audit_configuration_error_is_terminal(self):
        self.assert_configuration_error("audit")

    def test_audit_success_emits_ok(self):
        result = self.run_cmd("audit", FAKE_MODE="none",
                              PROMPT_LOG=str(self.tmp / "audit-success.log"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "ok")
        self.assertTrue((self.tmp / "audit-success.log").exists())
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')

    def test_daily_review_configuration_error_is_terminal(self):
        for role in ("qa", "devsecops"):
            with self.subTest(role=role):
                self.assert_configuration_error(f"daily-{role}")

    def test_daily_review_success_emits_ok(self):
        for role in ("qa", "devsecops"):
            with self.subTest(role=role):
                result = self.run_cmd(f"daily-{role}", FAKE_MODE="none",
                                      PROMPT_LOG=str(self.tmp / f"daily-{role}-success.log"))
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertEqual(self.outputs()["result"], "ok")
                self.assertTrue((self.tmp / f"daily-{role}-success.log").exists())
                self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                                 '{"real": true}')

    def test_strategy_configuration_error_is_terminal(self):
        self.assert_configuration_error("strategy")

    def test_read_only_review_configuration_error_does_not_clean_workspace(self):
        (self.repo / "keep.txt").write_text("keep\n")
        (self.repo / ".agent").mkdir(exist_ok=True)
        (self.repo / ".agent/base-sha").write_text("synthetic-base\n")
        self.assert_configuration_error("review", REVIEWERS="a")
        self.assertEqual((self.repo / "keep.txt").read_text(), "keep\n")
        self.assertFalse((self.repo / ".agent/review-a.md").exists())

    def test_conflict_resolution_configuration_error_is_terminal(self):
        self.push_to_main("feature.txt", "main version\n")
        result = self.merge_main("resolve", SWARM_MODEL=None,
                                 PROMPT_LOG=str(self.tmp / "merge-prompts.log"),
                                 ARGV_LOG=str(self.tmp / "merge-argv.log"))
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "configuration_error")
        self.assertEqual(self.outputs()["merge"], "conflict")
        self.assertFalse((self.tmp / "merge-prompts.log").exists())
        self.assertFalse((self.tmp / "merge-argv.log").exists())
        self.assertFalse((self.repo / ".agent/merge-2.log").exists())
        self.assertFalse((self.repo / ".git/MERGE_HEAD").exists())
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')

    def test_resolve_command_configuration_error_is_terminal(self):
        self.run_cmd("implement", FAKE_MODE="commit")
        self.git("branch", "pr-5")
        self.git("switch", "-q", "main")
        self.push_to_main("feature.txt", "main version\n")
        self.assertEqual(self.run_cmd("fetch-main").returncode, 0)
        result = self.run_cmd("resolve", CONFLICTED="[5]", MERGE_ATTEMPTS="2",
                              SWARM_MODEL=None,
                              PROMPT_LOG=str(self.tmp / "resolve-prompts.log"),
                              ARGV_LOG=str(self.tmp / "resolve-argv.log"))
        self.assertNotEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.repo / ".agent/resolve/5").read_text(), "failed")
        self.assertEqual((self.repo / ".agent/resolve/5.checks").read_text(),
                         "configuration_error")
        self.assertFalse((self.tmp / "resolve-prompts.log").exists())
        self.assertFalse((self.tmp / "resolve-argv.log").exists())
        self.assertEqual((self.repo / ".opencode/opencode.json").read_text(),
                         '{"real": true}')

    def test_rate_limit_is_terminal_after_one_invocation(self):
        result = self.run_cmd("implement", FAKE_MODE="rate", SWARM_MODEL="opencode/test",
                              PROMPT_LOG=str(self.tmp / "prompts.log"),
                              ARGV_LOG=str(self.tmp / "argv.log"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "rate_limited")
        self.assertEqual(self.argv().count("run"), 1)
        self.assertEqual(self.argv().count("--model"), 1)

    def test_generic_failure_is_terminal_after_one_invocation(self):
        result = self.run_cmd("implement", FAKE_MODE="fail", SWARM_MODEL="opencode/test",
                              PROMPT_LOG=str(self.tmp / "prompts.log"),
                              ARGV_LOG=str(self.tmp / "argv.log"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "failed")
        self.assertEqual(self.argv().count("run"), 1)
        self.assertEqual(self.argv().count("--model"), 1)

    def test_timeout_wins_over_rate_limit_output(self):
        result = self.run_cmd("implement", FAKE_MODE="rate-slow", AGENT_TIMEOUT="1",
                              SWARM_MODEL="opencode/test",
                              PROMPT_LOG=str(self.tmp / "prompts.log"),
                              ARGV_LOG=str(self.tmp / "argv.log"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.outputs()["result"], "timeout")
        self.assertEqual(self.argv().count("run"), 1)
        self.assertEqual(self.argv().count("--model"), 1)

    def test_production_has_one_direct_model_invocation(self):
        text = (__import__("helpers").ROOT / "scripts/agent-run.sh").read_text()
        lines = [line for line in text.splitlines()
                 if "opencode run" in line and not line.strip().startswith("#")]
        self.assertEqual(len(lines), 1)
        self.assertIn('--model "$SWARM_MODEL"', lines[0])
        self.assertNotIn("route_models", text)
        self.assertNotIn("SWARM_FORCE_MODEL", text)


if __name__ == "__main__":
    unittest.main()
