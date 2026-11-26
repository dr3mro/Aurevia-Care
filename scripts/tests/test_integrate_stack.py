"""Red/green tests for the integrator's stacked-branch gate step.

Each case runs the real ``scripts/agent-run.sh integrate-stack`` subcommand
inside a tiny synthetic Git repository. No GitHub token, network or real
checkout is used; gate scripts are local stubs. The failure cases prove that
the step explains itself in the Actions log instead of exiting with a bare
status, and that untrusted log text cannot become a workflow command.
"""

import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest
from pathlib import Path

from helpers import ROOT

TRACKER_SENTINEL = "TRACKER-DUMP-SENTINEL-01336000"
TAIL_HEAD = "TAIL-HEAD-SENTINEL-01336000"
TAIL_MARK = "TAIL-SENTINEL-01336000"
FAKE_TOKEN = "ghp_" + "A" * 24
FAKE_GATE = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "${GATE_LOG:-/dev/null}"
count_file=${GATE_COUNT_FILE:-/dev/null}
n=0
if [[ -f "$count_file" ]]; then n=$(cat "$count_file"); fi
n=$((n + 1))
printf '%s' "$n" > "$count_file"
printf '%s\\n' '@HEAD@'
for filler in $(seq 1 84); do printf 'filler-%s\\n' "$filler"; done
printf '%s\\n' 'gate diagnostic line' '##[error]untrusted gate marker' \\
  '::error::untrusted gate marker' '  ##[warning]indented gate marker' \\
  '@TOKEN@' '@TAIL@'
printf 'safe\\r::error::carriage gate marker\\r\\n'
printf '\\033[31m##[error]ansi gate marker\\033[0m\\n'
if [[ -n "${SELF_DELETE:-}" ]]; then rm -f "$0"; fi
if [[ -n "${GATE_FAIL_ON:-}" && "$n" == "$GATE_FAIL_ON" ]]; then exit 1; fi
exit ${GATE_EXIT:-0}
"""
FAKE_TASKSTORE = f'print("{TRACKER_SENTINEL}")\n'
FAKE_PLANNER = """import sys
print('##[error]planner raw marker', file=sys.stderr)
print('::error::planner raw annotation', file=sys.stderr)
print('planner tail sentinel', file=sys.stderr)
sys.exit(3)
"""


def expected_neutralised_tail(log_text):
    """Model the last-80-lines contract independently of the shell helper."""
    lines = log_text.split("\n")
    if lines and lines[-1] == "":
        lines.pop()
    tail = "\n".join(lines[-80:]) + "\n"
    tail = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", tail)
    tail = tail.replace("\r", "\n")
    tail = re.sub(r"(?m)^([ \t]*)(##\[|::)", r"\1> \2", tail)
    return re.sub(
        r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|sk-[A-Za-z0-9_-]{20,}",
        "***",
        tail,
    )


class IntegrateStackTests(unittest.TestCase):
    """Exercise every failure branch of ``cmd_integrate_stack`` end to end."""

    def setUp(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        self.tmp = tmp
        self.origin = tmp / "origin.git"
        self.repo = tmp / "repo"
        self.bin_dir = tmp / "bin"
        subprocess.run(["git", "init", "-q", "--bare", "-b", "main", str(self.origin)],
                       check=True, capture_output=True)
        subprocess.run(["git", "clone", "-q", str(self.origin), str(self.repo)],
                       check=True, capture_output=True)
        (self.repo / "scripts").mkdir()
        for name in ("agent-run.sh", "integration_stack.py"):
            shutil.copy(ROOT / "scripts" / name, self.repo / "scripts" / name)
        (self.repo / "scripts/taskstore.py").write_text(FAKE_TASKSTORE, encoding="utf-8")
        (self.repo / ".agent").mkdir()
        (self.repo / ".gitignore").write_text("/.agent/\n", encoding="utf-8")
        (self.repo / "shared.txt").write_text("base\n", encoding="utf-8")
        self.git("config", "user.name", "t")
        self.git("config", "user.email", "t@t")
        self.git("add", "-A")
        self.git("commit", "-qm", "init")
        self.git("push", "-q", "origin", "main")
        self.base = self.git_out("rev-parse", "HEAD").strip()
        self.bin_dir.mkdir()
        # The gate stub lives outside the checkout so switching branches never
        # sees it as an uncommitted local change.
        self.gate = self.bin_dir / "fake-gate.sh"
        self.install_gate()
        self.gate_log = tmp / "gate.log"
        self.gate_count = tmp / "gate.count"
        self.gate_log.touch()

    def git(self, *args, check=True):
        return subprocess.run(["git", *args], cwd=self.repo, check=check,
                              capture_output=True, text=True)

    def git_out(self, *args):
        return self.git(*args).stdout

    def install_gate(self):
        script = (FAKE_GATE.replace("@HEAD@", TAIL_HEAD)
                  .replace("@TAIL@", TAIL_MARK).replace("@TOKEN@", FAKE_TOKEN))
        self.gate.write_text(script, encoding="utf-8")
        self.gate.chmod(0o755 | stat.S_IXUSR)

    def add_branch(self, name, file_name="shared.txt", content="branch\n"):
        """Create a PR branch from the pristine base that changes one file."""
        self.git("branch", name, self.base)
        self.git("switch", "-q", name)
        (self.repo / file_name).write_text(content, encoding="utf-8")
        self.git("add", "-A")
        self.git("commit", "-qm", name)
        self.git("switch", "-q", "main")

    def advance_base(self):
        """Move the protected ref ahead of the PR branches' merge base."""
        self.git("switch", "-q", "main")
        (self.repo / "shared.txt").write_text("protected\n", encoding="utf-8")
        self.git("add", "-A")
        self.git("commit", "-qm", "protected change")
        self.git("update-ref", "refs/remotes/origin/main", "HEAD")

    def run_stack(self, branches, gate=None, close_stderr=False, **extra):
        env = {name: os.environ[name] for name in ("TMPDIR", "TMP", "TEMP")
               if name in os.environ}
        env.update({
            "PATH": f"{self.bin_dir}:/usr/bin:/bin",
            "HOME": str(self.tmp / "home"),
            "GITHUB_WORKSPACE": str(self.repo),
            "BASE_BRANCH": "main",
            "BRANCHES": branches,
            "CHECK_SCRIPT": str(gate or self.gate),
            "GATE_EXIT": "0",
            "GATE_LOG": str(self.gate_log),
            "GATE_COUNT_FILE": str(self.gate_count),
            "LANG": "C",
            "LC_ALL": "C",
        })
        env.update(extra)
        command = ["bash", "scripts/agent-run.sh", "integrate-stack"]
        if close_stderr:
            command = ["bash", "-c", "bash scripts/agent-run.sh integrate-stack 2>&-"]
        return subprocess.run(command, cwd=self.repo, env=env,
                              capture_output=True, text=True)

    def assert_diagnosed(self, result, reason, tail, status=1):
        """Assert the single trusted annotation, the tailed log and the status."""
        self.assertEqual(result.returncode, status, result.stderr)
        stderr_lines = result.stderr.splitlines()
        diagnostics = [line for line in stderr_lines if line.lstrip().startswith("::error::")]
        self.assertEqual(diagnostics, [f"::error::integrate-stack: {reason}"])
        self.assertIn(tail, result.stderr)
        output = result.stdout + result.stderr
        all_diagnostics = [line for line in output.splitlines()
                           if line.lstrip().startswith("::error::")]
        self.assertEqual(all_diagnostics, diagnostics)
        for line in output.splitlines():
            if line in diagnostics:
                continue
            self.assertFalse(line.lstrip().startswith("##["), line)
            self.assertFalse(line.lstrip().startswith("::"), line)
        self.assertNotIn(TRACKER_SENTINEL, output)
        self.assertNotIn(FAKE_TOKEN, output)
        self.assertNotIn("\r", output)

    def assert_neutralised_gate_tail(self, result):
        output = result.stdout + result.stderr
        self.assertIn(TAIL_MARK, output)
        self.assertNotIn(TAIL_HEAD, output)
        self.assertIn("> ##[error]untrusted gate marker", output)
        self.assertIn("> ::error::untrusted gate marker", output)
        self.assertIn("  > ##[warning]indented gate marker", output)
        self.assertIn("> ::error::carriage gate marker", output)
        self.assertIn("> ##[error]ansi gate marker", output)
        self.assertIn("***", output.splitlines())
        self.assertNotIn("\r", output)

    def test_escape_workflow_command_encodes_newlines(self):
        source = (ROOT / "scripts/agent-run.sh").read_text(encoding="utf-8")
        start = source.index("escape_workflow_command() {")
        end = source.index("\n}\n", start) + 3
        result = subprocess.run(
            ["bash", "-c", source[start:end] + "\nescape_workflow_command $'first\\nsecond'\n"],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "first%0Asecond")

    def test_missing_ref_writes_its_own_context_line(self):
        result = self.run_stack("pr-404")
        self.assert_diagnosed(result, "missing-ref pr-404", "missing ref: pr-404")
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(),
                         ["missing-ref pr-404"])

    def test_missing_ref_reports_the_branch_and_exits_nonzero(self):
        self.add_branch("pr-1768", file_name="first.txt")
        result = self.run_stack("pr-1768\npr-404")
        self.assert_diagnosed(result, "missing-ref pr-404", "missing ref: pr-404")
        self.assert_neutralised_gate_tail(result)
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(),
                         ["green", "missing-ref pr-404"])

    def test_tail_failure_does_not_replace_the_caller_status(self):
        tail = self.bin_dir / "tail"
        tail.write_text("#!/usr/bin/env bash\nexit 42\n", encoding="utf-8")
        tail.chmod(0o755)
        result = self.run_stack("pr-404")
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(
            result.stderr.splitlines(),
            ["::error::integrate-stack: missing-ref pr-404"],
        )

    def test_missing_ref_reason_escapes_workflow_command_characters(self):
        branch = "bad\r::error::injected%0A"
        result = self.run_stack(branch)
        self.assert_diagnosed(
            result,
            "missing-ref bad%0D::error::injected%250A",
            "missing ref: bad",
        )
        self.assertIn("> ::error::injected%0A", result.stderr)

    def test_merge_conflict_reports_the_branch_and_git_reason(self):
        self.advance_base()
        self.add_branch("pr-1768", file_name="first.txt", content="first\n")
        self.add_branch("pr-1769", file_name="shared.txt", content="conflicting\n")
        result = self.run_stack("pr-1768\npr-1769")
        self.assert_diagnosed(result, "merge conflict pr-1769", "CONFLICT (content)")
        self.assert_neutralised_gate_tail(result)
        log = (self.repo / ".agent/stack-check.log").read_text().splitlines()
        self.assertIn("conflict: pr-1769 at position 2", log)
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines()[-1],
                         "conflict")
        self.assertEqual(self.git("rev-parse", "-q", "--verify", "MERGE_HEAD", check=False)
                         .returncode, 1)

    def test_merge_cleanup_failures_do_not_hide_the_diagnosis(self):
        self.advance_base()
        self.add_branch("pr-1768", file_name="first.txt", content="first\n")
        self.add_branch("pr-1769", file_name="shared.txt", content="conflicting\n")
        wrapper = self.bin_dir / "git"
        cleanup_log = self.tmp / "cleanup.log"
        wrapper.write_text(
            """#!/usr/bin/env bash
if [[ " $* " == *" merge --abort "* ]]; then
  printf 'merge --abort\\n' >> "$CLEANUP_LOG"
  exit 42
fi
if [[ " $* " == *" reset "* ]]; then
  printf 'reset\\n' >> "$CLEANUP_LOG"
  exit 43
fi
exec "$REAL_GIT" "$@"
""",
            encoding="utf-8",
        )
        wrapper.chmod(0o755)
        result = self.run_stack(
            "pr-1768\npr-1769", REAL_GIT=shutil.which("git"),
            CLEANUP_LOG=str(cleanup_log),
        )
        self.assert_diagnosed(result, "merge conflict pr-1769", "CONFLICT (content)")
        self.assertEqual(cleanup_log.read_text().splitlines(), ["merge --abort", "reset"])
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines()[-1],
                         "conflict")

    def test_annotation_write_failure_still_cleans_up_and_returns_nonzero(self):
        self.advance_base()
        self.add_branch("pr-1768", file_name="first.txt", content="first\n")
        self.add_branch("pr-1769", file_name="shared.txt", content="conflicting\n")
        wrapper = self.bin_dir / "git"
        cleanup_log = self.tmp / "cleanup.log"
        wrapper.write_text(
            """#!/usr/bin/env bash
if [[ " $* " == *" merge --abort "* ]]; then
  printf 'merge --abort\\n' >> "$CLEANUP_LOG"
  exit 42
fi
if [[ " $* " == *" reset "* ]]; then
  printf 'reset\\n' >> "$CLEANUP_LOG"
  exit 43
fi
exec "$REAL_GIT" "$@"
""",
            encoding="utf-8",
        )
        wrapper.chmod(0o755)
        result = self.run_stack(
            "pr-1768\npr-1769",
            close_stderr=True,
            REAL_GIT=shutil.which("git"),
            CLEANUP_LOG=str(cleanup_log),
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(cleanup_log.read_text().splitlines(), ["merge --abort", "reset"])
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines()[-1],
                         "conflict")

    def test_failing_gate_reports_position_and_neutralises_log_markers(self):
        self.add_branch("pr-1768", file_name="first.txt")
        result = self.run_stack("pr-1768", GATE_EXIT="1")
        self.assert_diagnosed(result, "gate failed pr-1768 at position 1", "gate diagnostic line")
        self.assert_neutralised_gate_tail(result)
        log_text = (self.repo / ".agent/stack-check.log").read_bytes().decode("utf-8")
        log = log_text.splitlines()
        self.assertIn("gate failed: pr-1768 at position 1", log)
        self.assertTrue(result.stderr.endswith(expected_neutralised_tail(log_text)), result.stderr)
        self.assertEqual(len(self.gate_log.read_text().splitlines()), 1)
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(), ["failed"])

    def test_silent_gate_failure_still_writes_context_to_the_log(self):
        self.add_branch("pr-1768", file_name="first.txt")
        silent_gate = self.bin_dir / "silent-gate.sh"
        silent_gate.write_text("#!/usr/bin/env bash\nexit 1\n", encoding="utf-8")
        silent_gate.chmod(0o755)
        result = self.run_stack("pr-1768", gate=silent_gate)
        self.assert_diagnosed(
            result,
            "gate failed pr-1768 at position 1",
            "gate failed: pr-1768 at position 1",
        )
        self.assertEqual(
            (self.repo / ".agent/stack-check.log").read_text().splitlines(),
            ["gate failed: pr-1768 at position 1"],
        )

    def test_gate_failure_on_the_second_branch_reports_its_position(self):
        self.add_branch("pr-1768", file_name="first.txt")
        self.add_branch("pr-1769", file_name="second.txt")
        result = self.run_stack("pr-1768\npr-1769", GATE_FAIL_ON="2")
        self.assert_diagnosed(result, "gate failed pr-1769 at position 2", "gate diagnostic line")
        self.assert_neutralised_gate_tail(result)
        self.assertEqual(len(self.gate_log.read_text().splitlines()), 2)
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(),
                         ["green", "failed"])

    def test_missing_gate_script_reports_and_exits_nonzero(self):
        self.add_branch("pr-1768", file_name="first.txt")
        self.add_branch("pr-1769", file_name="second.txt")
        result = self.run_stack("pr-1768\npr-1769", SELF_DELETE="1")
        self.assert_diagnosed(result, "gate script missing", "gate script missing:")
        self.assert_neutralised_gate_tail(result)
        self.assertEqual(len(self.gate_log.read_text().splitlines()), 1)
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(),
                         ["green", "failed"])

    def test_planner_failure_reports_and_exits_nonzero(self):
        (self.repo / "scripts/integration_stack.py").write_text(FAKE_PLANNER, encoding="utf-8")
        result = self.run_stack("pr-1768")
        self.assert_diagnosed(result, "integration_stack.py failed", "planner tail sentinel")
        log = (self.repo / ".agent/stack-check.log").read_text().splitlines()
        self.assertIn("integration_stack.py failed for base origin/main", log)
        self.assertIn("> ##[error]planner raw marker", result.stderr)
        self.assertIn("> ::error::planner raw annotation", result.stderr)
        self.assertEqual(len(self.gate_log.read_text().splitlines()), 0)

    def test_switch_failure_reports_and_exits_nonzero(self):
        self.add_branch("pr-1768", file_name="first.txt")
        result = self.run_stack("pr-1768", BASE_BRANCH="missing-base")
        log = (self.repo / ".agent/stack-check.log").read_text().splitlines()
        self.assert_diagnosed(result, "switch failed", "switch failed: stack-1-pr-1768 from missing-base")
        self.assertEqual(log[-1], "switch failed: stack-1-pr-1768 from missing-base")
        self.assertTrue(any("missing-base" in line for line in log[:-1]), log)
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(), ["failed"])

    def test_hostile_missing_base_warning_is_neutralised(self):
        self.add_branch("pr-1768", file_name="first.txt")
        result = self.run_stack("pr-1768", BASE_BRANCH="missing\r::error::injected")
        self.assert_diagnosed(result, "switch failed", "switch failed: stack-1-pr-1768 from missing")
        self.assertIn("> ::error::injected absent; using local base for stacking", result.stderr)

    def test_gate_runs_without_credential_shaped_environment(self):
        self.add_branch("pr-1768", file_name="first.txt")
        scrub_gate = self.bin_dir / "scrub-gate.sh"
        scrub_gate.write_text(
            """#!/usr/bin/env bash
if [[ -n "${GH_TOKEN:-}" || -n "${GITHUB_TOKEN:-}" || \
      -n "${SWARM_TOKEN:-}" || -n "${OPENCODE_API_KEY:-}" || \
      -n "${lower_token:-}" || -n "${FAKE_PASSWORD:-}" || \
      -n "${FAKE_SECRET:-}" || -n "${FAKE_CREDENTIAL:-}" || \
      -n "${AWS_ACCESS_KEY_ID:-}" || -n "${GPG_KEY_ID:-}" || \
      -n "${NVD_API_KEY_EXTRA:-}" || -n "${nvd_api_key:-}" || \
      -n "${Nvd_Api_Key:-}" || -n "${git_config_key_extra:-}" || \
      -n "${GITHUB_ENV:-}" || -n "${GITHUB_OUTPUT:-}" || \
      -n "${GITHUB_PATH:-}" || -n "${GITHUB_STATE:-}" || \
      -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
  echo 'credential leaked to candidate gate'
  exit 23
fi
[[ "${NVD_API_KEY:-}" == allowed ]] || exit 24
[[ "${GIT_CONFIG_KEY_0:-}" == safe.directory && "${GIT_CONFIG_VALUE_0:-}" == '*' ]] || exit 25
printf 'scrubbed\\n' >> "$GATE_LOG"
""",
            encoding="utf-8",
        )
        scrub_gate.chmod(0o755)
        result = self.run_stack(
            "pr-1768",
            gate=scrub_gate,
            GH_TOKEN="github-secret",
            GITHUB_TOKEN="actions-secret",
            SWARM_TOKEN="swarm-secret",
            OPENCODE_API_KEY="opencode-secret",
            lower_token="lower-secret",
            FAKE_PASSWORD="password-secret",
            FAKE_SECRET="secret-value",
            FAKE_CREDENTIAL="credential-value",
            AWS_ACCESS_KEY_ID="access-key-id",
            GPG_KEY_ID="gpg-key-id",
            NVD_API_KEY_EXTRA="extra-key",
            nvd_api_key="lower-nvd-key",
            Nvd_Api_Key="mixed-nvd-key",
            git_config_key_extra="mixed-git-key",
            GITHUB_ENV=str(self.tmp / "github-env"),
            GITHUB_OUTPUT=str(self.tmp / "github-output"),
            GITHUB_PATH=str(self.tmp / "github-path"),
            GITHUB_STATE=str(self.tmp / "github-state"),
            GITHUB_STEP_SUMMARY=str(self.tmp / "github-summary"),
            NVD_API_KEY="allowed",
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.gate_log.read_text().splitlines(), ["scrubbed"])

    def test_warning_output_failure_does_not_skip_switch_diagnosis(self):
        self.add_branch("pr-1768", file_name="first.txt")
        tr = self.bin_dir / "tr"
        count_file = self.tmp / "tr.count"
        tr.write_text(
            """#!/usr/bin/env bash
n=0
if [[ -f "$TR_COUNT_FILE" ]]; then n=$(cat "$TR_COUNT_FILE"); fi
n=$((n + 1))
printf '%s' "$n" > "$TR_COUNT_FILE"
if [[ "$n" == 1 ]]; then exit 42; fi
exec /usr/bin/tr "$@"
""",
            encoding="utf-8",
        )
        tr.chmod(0o755)
        result = self.run_stack(
            "pr-1768", BASE_BRANCH="missing-base", TR_COUNT_FILE=str(count_file)
        )
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertEqual(
            [line for line in result.stderr.splitlines()
             if line.startswith("::error::integrate-stack:")],
            ["::error::integrate-stack: switch failed"],
        )
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(), ["failed"])

    def test_readonly_credential_fails_closed_before_the_gate(self):
        self.add_branch("pr-1768", file_name="first.txt")
        bash_env = self.tmp / "bash-env"
        bash_env.write_text(
            "readonly GH_TOKEN=readonly-secret\nexport GH_TOKEN\n", encoding="utf-8"
        )
        result = self.run_stack("pr-1768", BASH_ENV=str(bash_env))
        self.assert_diagnosed(
            result,
            "gate failed pr-1768 at position 1",
            "gate failed: pr-1768 at position 1",
        )
        self.assertEqual(self.gate_log.read_text().splitlines(), [])
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(), ["failed"])

    def test_readonly_workflow_control_variable_fails_closed(self):
        self.add_branch("pr-1768", file_name="first.txt")
        bash_env = self.tmp / "bash-env"
        bash_env.write_text(
            "readonly GITHUB_ENV=/synthetic/env\n"
            "readonly GITHUB_OUTPUT=/synthetic/output\n"
            "export GITHUB_ENV GITHUB_OUTPUT\n",
            encoding="utf-8",
        )
        result = self.run_stack("pr-1768", BASH_ENV=str(bash_env))
        self.assert_diagnosed(
            result,
            "gate failed pr-1768 at position 1",
            "gate failed: pr-1768 at position 1",
        )
        self.assertEqual(self.gate_log.read_text().splitlines(), [])
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(), ["failed"])

    def test_credential_scrub_conversion_failure_fails_closed(self):
        self.add_branch("pr-1768", file_name="first.txt")
        tr = self.bin_dir / "tr"
        count_file = self.tmp / "scrub-tr.count"
        tr.write_text(
            """#!/usr/bin/env bash
n=0
if [[ -f "$TR_COUNT_FILE" ]]; then n=$(cat "$TR_COUNT_FILE"); fi
n=$((n + 1))
printf '%s' "$n" > "$TR_COUNT_FILE"
if [[ "$n" == 1 ]]; then exit 42; fi
exec /usr/bin/tr "$@"
""",
            encoding="utf-8",
        )
        tr.chmod(0o755)
        result = self.run_stack("pr-1768", TR_COUNT_FILE=str(count_file))
        self.assert_diagnosed(
            result,
            "gate failed pr-1768 at position 1",
            "gate failed: pr-1768 at position 1",
        )
        self.assertEqual(self.gate_log.read_text().splitlines(), [])
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(), ["failed"])

    def test_credential_scrub_enumeration_failure_fails_closed(self):
        self.add_branch("pr-1768", file_name="first.txt")
        bash_env = self.tmp / "bash-env"
        bash_env.write_text(
            "GH_TOKEN=synthetic-secret\ncompgen() { return 42; }\n", encoding="utf-8"
        )
        result = self.run_stack("pr-1768", BASH_ENV=str(bash_env))
        self.assert_diagnosed(
            result,
            "gate failed pr-1768 at position 1",
            "gate failed: pr-1768 at position 1",
        )
        self.assertEqual(self.gate_log.read_text().splitlines(), [])
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(), ["failed"])

    def test_two_branch_stack_goes_green(self):
        self.add_branch("pr-1768", file_name="first.txt")
        self.add_branch("pr-1769", file_name="second.txt")
        result = self.run_stack("pr-1768\npr-1769")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual((self.repo / ".agent/stack-checks").read_text().splitlines(),
                         ["green", "green"])
        self.assertEqual((self.repo / ".agent/stack-branches").read_text().splitlines(),
                         ["stack-1-pr-1768", "stack-2-pr-1769"])
        plan = json.loads((self.repo / ".agent/stack-plan.json").read_text())
        self.assertEqual([item["base"] for item in plan], ["origin/main", "pr-1768"])
        self.assertEqual([item["branch"] for item in plan], ["pr-1768", "pr-1769"])
        self.assertEqual(len(self.gate_log.read_text().splitlines()), 2)
        self.assertEqual(self.git("show", "stack-2-pr-1769:first.txt").stdout.strip(), "branch")
        self.assertEqual(self.git("show", "stack-2-pr-1769:second.txt").stdout.strip(), "branch")
        self.assertNotIn(TRACKER_SENTINEL, result.stdout + result.stderr)
        for line in (result.stdout + result.stderr).splitlines():
            self.assertFalse(line.lstrip().startswith("##["), line)
            self.assertFalse(line.lstrip().startswith("::"), line)

    def test_tracker_dump_is_not_echoed_by_integrate_stack(self):
        self.add_branch("pr-1768", file_name="first.txt")
        result = self.run_stack("pr-1768")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn(TRACKER_SENTINEL, result.stdout)
        self.assertNotIn(TRACKER_SENTINEL, result.stderr)
        self.assertEqual(
            (self.repo / ".agent/tasks.txt").read_text(encoding="utf-8"),
            f"{TRACKER_SENTINEL}\n",
        )


class StackWorkflowTests(unittest.TestCase):
    """Keep the stack failure visible without blocking the validated batch."""

    def setUp(self):
        import yaml
        self.workflow = yaml.safe_load(
            (ROOT / ".github/workflows/swarm-integrator.yml").read_text(encoding="utf-8"))

    def step(self, name):
        return next(step for step in self.workflow["jobs"]["validate"]["steps"]
                    if step.get("name") == name)

    def step_upload(self, name):
        return next(step for step in self.workflow["jobs"]["validate"]["steps"]
                    if (step.get("with") or {}).get("name") == name)

    def test_stack_failure_does_not_fail_the_validated_batch(self):
        validate = self.workflow["jobs"]["validate"]
        step = self.step("Build and gate stacked branches")
        # Step-level tolerance keeps publish reachable while outcome=failure and
        # the script's ::error:: annotation remain visible.
        self.assertIs(step.get("continue-on-error"), True)
        self.assertEqual(step.get("id"), "stack")
        self.assertEqual(step.get("timeout-minutes"), 45)
        self.assertEqual(validate.get("timeout-minutes"), 180)
        candidate = self.step("Merge each PR locally and gate it without credentials")
        self.assertEqual(candidate.get("timeout-minutes"), 120)
        plan = self.step("Plan stacked pull requests from the validated batch")
        self.assertGreaterEqual(
            validate["timeout-minutes"],
            candidate["timeout-minutes"] + step["timeout-minutes"]
            + plan["timeout-minutes"] + 5,
        )
        self.assertNotIn("|| true", step["run"])
        self.assertNotIn("exit 0", step["run"])
        self.assertEqual(
            step["run"].splitlines()[-1],
            'BRANCHES="$branches" scripts/agent-run.sh integrate-stack',
        )
        self.assertEqual(
            step["env"],
            {
                "ACCEPTED": "${{ steps.candidate.outputs.accepted }}",
                "BASE_BRANCH": "${{ github.event.repository.default_branch }}",
                "CHECK_SCRIPT": "scripts/integrator-gate.sh",
            },
        )
        self.assertNotIn("continue-on-error", validate)
        tolerated = {
            step.get("id") for step in validate["steps"]
            if "run" in step and step.get("continue-on-error") is True
        }
        self.assertEqual(tolerated, {"stack", "stack-plan"})
        for name in ("Merge each PR locally and gate it without credentials",
                     "Report stacked-branch failure",
                     "Report stacked-plan failure"):
            self.assertNotIn("continue-on-error", self.step(name))
        publish = self.workflow["jobs"]["publish"]
        self.assertEqual(publish["needs"], ["pick", "validate", "seal"])
        condition = " ".join(publish["if"].split())
        self.assertEqual(
            condition,
            "(needs.validate.outputs.accepted != '' && needs.validate.outputs.accepted != '[]') "
            "|| (needs.validate.outputs.conflicted != '' && needs.validate.outputs.conflicted != '[]') "
            "|| (needs.validate.outputs.report != '' && needs.validate.outputs.report != '[]')",
        )
        self.assertIsNone(publish.get("continue-on-error"))
        gate = self.step_publish("Push only the exact validated commit SHAs")
        self.assertEqual(gate["env"]["GATE"], "scripts/integrator-gate.sh")
        self.assertIn("integrate-publish --manifest validated/manifest.json", gate["run"])
        self.assertIn("--bundle validated/main.bundle --gate \"$GATE\"", gate["run"])

    def test_stack_failure_is_reported_after_the_tolerated_step(self):
        steps = self.workflow["jobs"]["validate"]["steps"]
        report = next(step for step in steps
                      if step.get("name") == "Report stacked-branch failure")
        self.assertIn("::error::integrate-stack:", report["run"])
        self.assertEqual(report["if"], "always() && steps.stack.outcome == 'failure'")
        names = [step.get("name", "") for step in steps]
        self.assertLess(names.index("Build and gate stacked branches"), names.index(report["name"]))

    def test_plan_step_is_non_blocking_and_captures_planner_stderr(self):
        step = self.step("Plan stacked pull requests from the validated batch")
        self.assertEqual(step["if"], "always()")
        self.assertEqual(step.get("id"), "stack-plan")
        self.assertIs(step.get("continue-on-error"), True)
        self.assertEqual(step.get("timeout-minutes"), 5)
        self.assertEqual(
            step["env"],
            {
                "ACCEPTED": "${{ steps.candidate.outputs.accepted }}",
                "BASE_BRANCH": "${{ github.event.repository.default_branch }}",
            },
        )
        self.assertTrue(
            step["run"].endswith(
                "> .agent/stack-plan.json 2>> .agent/stack-check.log\n"
            )
        )
        report = self.step("Report stacked-plan failure")
        self.assertIn("::error::integrate-stack:", report["run"])
        self.assertEqual(report["if"], "always() && steps.stack-plan.outcome == 'failure'")
        steps = self.workflow["jobs"]["validate"]["steps"]
        self.assertLess(
            steps.index(self.step("Build and gate stacked branches")), steps.index(step)
        )
        self.assertLess(steps.index(step), steps.index(report))
        self.assertLess(steps.index(report), steps.index(self.step_upload("stack-plan")))

    def test_validated_batch_is_built_and_uploaded_in_a_fresh_trusted_job(self):
        validate_steps = self.workflow["jobs"]["validate"]["steps"]
        self.assertFalse(any(
            (step.get("with") or {}).get("name") == "validated-batch"
            for step in validate_steps
        ))
        seal = self.workflow["jobs"]["seal"]
        self.assertEqual(seal["needs"], ["validate"])
        self.assertEqual(seal["outputs"]["conflicted"],
                         "${{ steps.rebuild.outputs.conflicted }}")
        self.assertNotIn("continue-on-error", seal)
        self.assertEqual(seal["permissions"], {
            "contents": "read", "pull-requests": "read", "actions": "read",
        })
        self.assertEqual(seal["env"]["GH_TOKEN"], "${{ github.token }}")
        self.assertEqual(seal["env"]["SEAL"], "${{ needs.validate.outputs.seal }}")
        self.assertEqual(seal["env"]["BASE_SHA"], "${{ github.sha }}")
        self.assertEqual(
            self.workflow["jobs"]["validate"]["outputs"]["seal"],
            "${{ steps.candidate.outputs.seal }}",
        )
        checkout = next(step for step in seal["steps"]
                        if str(step.get("uses", "")).startswith("actions/checkout@"))
        self.assertEqual(checkout["with"]["ref"], "${{ github.sha }}")
        self.assertIs(checkout["with"]["persist-credentials"], False)
        self.assertFalse(any(
            str(step.get("uses", "")).startswith("actions/download-artifact@")
            for step in seal["steps"]
        ))
        rebuild = self.step_job("seal", "Rebuild and bundle exact validated heads")
        self.assertEqual(rebuild["id"], "rebuild")
        self.assertIn("scripts/swarm.py integrate-seal", rebuild["run"])
        self.assertIn('--base "$BASE_SHA"', rebuild["run"])
        self.assertIn('--payload "$RUNNER_TEMP/validated-seal.json"', rebuild["run"])
        self.assertIn('--out validated', rebuild["run"])
        uploads = [step for step in seal["steps"]
                   if (step.get("with") or {}).get("name") == "validated-batch"]
        self.assertEqual(len(uploads), 1)
        self.assertEqual(uploads[0]["with"]["path"], "validated")
        self.assertEqual(uploads[0]["with"]["if-no-files-found"], "error")
        self.assertNotIn("if", uploads[0])
        self.assertEqual(self.workflow["jobs"]["publish"]["needs"],
                         ["pick", "validate", "seal"])

    def test_pick_validate_and_seal_share_the_trusted_dispatch_sha(self):
        pick = self.workflow["jobs"]["pick"]
        self.assertEqual(
            pick["if"],
            "github.event_name != 'workflow_dispatch' || github.ref == 'refs/heads/main'",
        )
        for job in ("pick", "validate", "seal"):
            checkout = next(step for step in self.workflow["jobs"][job]["steps"]
                            if str(step.get("uses", "")).startswith("actions/checkout@"))
            self.assertEqual(checkout["with"]["ref"], "${{ github.sha }}")
            self.assertIs(checkout["with"]["persist-credentials"], False)

    def test_github_token_is_scoped_to_the_candidate_step(self):
        validate = self.workflow["jobs"]["validate"]
        self.assertNotIn("GH_TOKEN", validate.get("env", {}))
        candidate = self.step("Merge each PR locally and gate it without credentials")
        self.assertEqual(candidate["env"]["GH_TOKEN"], "${{ github.token }}")
        self.assertNotIn("GH_TOKEN", self.step("Build and gate stacked branches").get("env", {}))

    def test_seal_job_is_skipped_for_an_empty_wave(self):
        seal = self.workflow["jobs"]["seal"]
        self.assertIn("needs.validate.result == 'success'", seal["if"])
        self.assertIn("needs.validate.outputs.seal != ''", seal["if"])

    def test_stack_diagnostics_upload_on_failure(self):
        uploads = [step for step in self.workflow["jobs"]["validate"]["steps"]
                   if (step.get("with") or {}).get("name") == "stack-plan"]
        self.assertEqual(len(uploads), 1)
        upload = uploads[0]
        self.assertEqual(upload["if"], "always()")
        self.assertEqual(upload["with"]["if-no-files-found"], "ignore")
        for path in (".agent/stack-plan.json", ".agent/stack-branches",
                     ".agent/stack-check.log", ".agent/stack-checks"):
            self.assertIn(path, upload["with"]["path"])
        batch = next(step for step in self.workflow["jobs"]["seal"]["steps"]
                     if (step.get("with") or {}).get("name") == "validated-batch")
        self.assertEqual(batch["with"]["if-no-files-found"], "error")

    def step_job(self, job, name):
        return next(step for step in self.workflow["jobs"][job]["steps"]
                    if step.get("name") == name)

    def step_publish(self, name):
        return next(step for step in self.workflow["jobs"]["publish"]["steps"]
                    if step.get("name") == name)


if __name__ == "__main__":
    unittest.main()
