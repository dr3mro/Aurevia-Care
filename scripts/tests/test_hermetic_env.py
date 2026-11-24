"""Hermetic worker environment and fake-command Git safety regressions."""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
ROOT = TESTS.parents[1]
RUNNER = ROOT / "scripts/agent-run.sh"

FAKE_GIT = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$GIT_CALL_LOG"
env > "$GIT_ENV_SNAPSHOT"
if [[ -n "${GIT_DIR:-}" || -n "${GIT_INDEX_FILE:-}" ]]; then
  printf 'contaminated\\n' >> "$GIT_ENV_LOG"
else
  printf 'clean\\n' >> "$GIT_ENV_LOG"
fi
case "$*" in
  *"rev-parse --show-toplevel"*) printf '%s\\n' "$FAKE_TOP" ;;
  *"remote get-url --push origin"*) printf '%s\\n' "$FAKE_PUSH_URL" ;;
  *"remote get-url origin"*) printf '%s\\n' "$FAKE_FETCH_URL" ;;
  *"status --porcelain"*)
    count=0
    [[ ! -f "$STATUS_COUNT" ]] || read -r count < "$STATUS_COUNT"
    count=$((count + 1))
    printf '%s' "$count" > "$STATUS_COUNT"
    [[ "${FAIL_STATUS_AT:-0}" != "$count" ]] || exit 1
    if [[ "${SIGNAL_AT_STATUS:-0}" == "$count" ]]; then
      parent=$(ps -o ppid= -p "$PPID" | tr -d ' ')
      kill -"${SIGNAL_STATUS:-TERM}" "$parent"
      exit 0
    fi
    [[ -f "$DIRTY_MARKER" ]] && printf ' M feature.txt\\n'
    ;;
  *"symbolic-ref --quiet --short HEAD"*) [[ "${DETACHED_HEAD:-0}" != 1 ]] || exit 1; printf '%s\\n' "$FAKE_BRANCH" ;;
  *"ls-files -v -- .opencode/opencode.json"*) [[ "${FAIL_CONFIG_FLAG:-0}" != 1 ]] || exit 1; printf '%s .opencode/opencode.json\\n' "$FAKE_INDEX_FLAG" ;;
  *"check-ref-format --branch"*) [[ "${FAKE_REF_VALID:-1}" != 0 ]] || exit 1 ;;
  *"update-index --"*)
    count=0
    [[ ! -f "$INDEX_COUNT" ]] || read -r count < "$INDEX_COUNT"
    count=$((count + 1))
    printf '%s' "$count" > "$INDEX_COUNT"
    [[ "${FAIL_UPDATE_INDEX:-0}" != 1 ]] || exit 1
    [[ "${FAIL_UPDATE_INDEX_AT:-0}" != "$count" ]] || exit 1
    [[ "${FAIL_UPDATE_INDEX_FROM:-0}" == 0 || "$count" -lt "${FAIL_UPDATE_INDEX_FROM}" ]] || exit 1
    ;;
  *"switch -q -C resolve-5"*) [[ "${DIRTY_AFTER_SWITCH:-0}" != 1 ]] || touch "$DIRTY_MARKER" ;;
  *"diff --quiet"*) [[ "${DIRTY_AFTER_DIFF:-0}" != 1 ]] || touch "$DIRTY_MARKER" ;;
  *"merge-base --is-ancestor"*) [[ "${FAKE_BASE_ANCESTOR:-1}" != 0 ]] || exit 1 ;;
esac
exit 0
"""

FAKE_COMMAND = """#!/usr/bin/env bash
name=${0##*/}
if [[ "${FAIL_COMMAND:-}" == "$name" ]]; then
  for arg in "$@"; do
    [[ -n "${FAIL_MATCH:-}" && "$arg" != *"$FAIL_MATCH"* ]] || exit 1
  done
fi
real="REAL_${name^^}"
"${!real}" "$@" || exit 1
if [[ "$name" == cp && "${MAKE_CONFIG_DIRECTORY_DURING_COPY:-0}" == 1 ]]; then
  for arg in "$@"; do
    if [[ "$arg" == */.opencode-ci.* ]]; then
      /bin/rm -f "$CONFIG_PATH"
      /bin/mkdir "$CONFIG_PATH"
      exit 0
    fi
  done
fi
"""

FAKE_PYTHON = """#!/usr/bin/env bash
if [[ "${FAIL_RESTORE_REPLACE:-0}" == 1 && "$1" == -c && "$2" == *os.replace* ]]; then
  exit 1
fi
exec "$REAL_PYTHON" "$@"
"""

FAKE_CHMOD = """#!/usr/bin/env bash
if [[ "${FAIL_BACKUP_CHMOD:-0}" == 1 && "$1" == 700 && "$2" == */aurevia-backup.* ]]; then
  exit 1
fi
if [[ "${FAIL_AUTH_CHMOD:-0}" == 1 && "$1" == 600 && "$2" == */opencode/auth.json.* ]]; then
  exit 1
fi
if [[ "${FAIL_AUTH_RESTORE_CHMOD:-0}" == 1 && "$2" == */opencode/auth.json ]]; then
  exit 1
fi
exec /bin/chmod "$@"
"""

FAKE_GH = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$GH_LOG"
if [[ "$1 $2" == "pr view" ]]; then printf '7\\n'; fi
"""

FAKE_OPENCODE = """#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$OPENCODE_LOG"
cp "$XDG_DATA_HOME/opencode/auth.json" "$AUTH_SEEN"
if [[ "${EXPECT_AUTH_BACKUP_MODE:-0}" == 1 ]]; then
  for file in "$TMPDIR"/aurevia-backup.*/auth; do
    [[ -f "$file" ]] || continue
    mode=$(python3 -c 'import os,sys; print(oct(os.stat(sys.argv[1]).st_mode & 0o777))' "$file")
    [[ "$mode" == 0o600 ]] || exit 1
  done
fi
printf 'changed\\n' > feature.txt
touch "$DIRTY_MARKER"
"""


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

    def test_agent_runner_safety_contract(self):
        text = RUNNER.read_text(encoding="utf-8")
        for name in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
                     "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_NAMESPACE",
                     "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS", "GIT_EXEC_PATH",
                     "GIT_SSH", "GIT_SSH_COMMAND", "GIT_ASKPASS", "GIT_TRACE_CURL",
                     "GIT_TRACE_CURL_NO_DATA", "GIT_TRACE_SHALLOW"):            self.assertIn(name, text)
        self.assertIn("GIT_CONFIG_NOSYSTEM=1", text)
        self.assertIn("GIT_CONFIG_GLOBAL=/dev/null", text)
        self.assertIn("GIT_CONFIG_KEY_0=safe.directory", text)
        self.assertIn("GIT_CONFIG_KEY_1=core.hooksPath", text)
        self.assertIn("GIT_CONFIG_VALUE_1=/dev/null", text)
        self.assertIn("GIT_CONFIG_KEY_2=core.fsmonitor", text)
        self.assertIn("GIT_CONFIG_VALUE_2=false", text)
        self.assertNotIn("git config --global --add safe.directory", text)
        self.assertIn("require_clean_worktree", text)
        self.assertIn("expected_branch", text)
        self.assertIn("unique", text)
        self.assertIn("trap cleanup EXIT", text)
        self.assertIn("trap 'exit 130' INT", text)
        self.assertIn("trap 'exit 143' TERM", text)
        self.assertIn("auth_tmp", text)
        self.assertIn("[[ -s .agent/notes-stamp ]]", text)
        self.assertNotIn('"$out/notes-stamp"', text)

    def workspace(self, directory, *, opencode=False):
        root = Path(directory)
        (root / ".opencode").mkdir()
        (root / ".opencode/opencode.json").write_text('{"real": true}\n')
        (root / ".opencode/opencode.ci.json").write_text('{"ci": true}\n')
        (root / "feature.txt").write_text("original\n")
        bin_dir = root / "bin"
        bin_dir.mkdir()
        (root / "tmp").mkdir()
        (root / "xdg").mkdir()
        git = bin_dir / "git"
        git.write_text(FAKE_GIT)
        git.chmod(0o755)
        if opencode:
            command = bin_dir / "opencode"
            command.write_text(FAKE_OPENCODE)
            command.chmod(0o755)
        for name, body in (("python3", FAKE_PYTHON), ("chmod", FAKE_CHMOD), ("gh", FAKE_GH)):
            command = bin_dir / name
            command.write_text(body)
            command.chmod(0o755)
        real_commands = {}
        for name in ("mkdir", "mktemp", "cp", "jq", "rm"):
            command = bin_dir / name
            command.write_text(FAKE_COMMAND)
            command.chmod(0o755)
            real_commands[f"REAL_{name.upper()}"] = shutil.which(name) or f"/usr/bin/{name}"
        host = {name: os.environ[name] for name in ("PATH", "LANG", "LC_ALL", "TZ")
                if name in os.environ}
        env = {
            **host,
            **real_commands,
            "PATH": f"{bin_dir}:{os.environ['PATH']}",
            "HOME": str(root / "home"),
            "XDG_DATA_HOME": str(root / "xdg"),
            "TMPDIR": str(root / "tmp"),
            "GITHUB_OUTPUT": str(root / "github-output"),
            "GITHUB_WORKSPACE": str(root),
            "CONFIG_PATH": str(root / ".opencode/opencode.json"),
            "MAKE_CONFIG_DIRECTORY_DURING_COPY": "0",
            "GITHUB_REPOSITORY": "synthetic-owner/synthetic-repo",
            "GIT_DIR": "/synthetic/wrong.git",
            "GIT_INDEX_FILE": "/synthetic/wrong-index",
            "GIT_CONFIG_COUNT": "9",
            "GIT_CONFIG_KEY_0": "core.sshCommand",
            "GIT_CONFIG_VALUE_0": "malicious-command",
            "FAKE_TOP": str(root.resolve()),
            "FAKE_FETCH_URL": "https://github.com/synthetic-owner/synthetic-repo.git",
            "FAKE_PUSH_URL": "git@github.com:synthetic-owner/synthetic-repo.git",
            "FAKE_BRANCH": "opencode/synthetic-1",
            "FAKE_INDEX_FLAG": "H",
            "FAKE_REF_VALID": "1",
            "FAKE_BASE_ANCESTOR": "1",
            "DETACHED_HEAD": "0",
            "GIT_CALL_LOG": str(root / "git-calls"),
            "GIT_ENV_LOG": str(root / "git-env"),
            "GIT_ENV_SNAPSHOT": str(root / "git-env-snapshot"),
            "DIRTY_MARKER": str(root / "dirty"),
            "STATUS_COUNT": str(root / "status-count"),
            "FAIL_STATUS_AT": "0",
            "SIGNAL_AT_STATUS": "0",
            "SIGNAL_STATUS": "TERM",
            "FAIL_UPDATE_INDEX": "0",
            "FAIL_UPDATE_INDEX_AT": "0",
            "FAIL_UPDATE_INDEX_FROM": "0",
            "INDEX_COUNT": str(root / "index-count"),
            "FAIL_CONFIG_FLAG": "0",
            "FAIL_BACKUP_CHMOD": "0",
            "FAIL_AUTH_RESTORE_CHMOD": "0",
            "FAIL_RESTORE_REPLACE": "0",
            "FAIL_AUTH_CHMOD": "0",
            "FAIL_COMMAND": "",
            "FAIL_MATCH": "",
            "DIRTY_AFTER_SWITCH": "0",
            "DIRTY_AFTER_DIFF": "0",
            "REAL_PYTHON": sys.executable,
            "EXPECT_AUTH_BACKUP_MODE": "1",
            "OPENCODE_LOG": str(root / "opencode-calls"),
            "AUTH_SEEN": str(root / "auth-seen.json"),
            "GH_LOG": str(root / "gh-calls"),
            "SWARM_MODEL": "opencode/test",
            "OPENCODE_API_KEY": "synthetic-key",
            "REVIEWERS": "a b",
        }
        return root, env

    def test_agent_runner_sanitizes_full_git_environment(self):
        from test_process_env import CONFIG, EXECUTABLES, ROUTING
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory)
            for name in (*ROUTING, *CONFIG, *EXECUTABLES):
                env[name] = f"/synthetic/{name.lower()}"
            env.update({"GIT_CONFIG_KEY_3": "core.sshCommand", "GIT_CONFIG_VALUE_3": "evil",
                        "GIT_TRACE_CUSTOM": "/synthetic/trace", "GIT_OPTIONAL_LOCKS": "1",
                        "GIT_CONFIG_NOSYSTEM": "0", "GIT_CONFIG_GLOBAL": "/synthetic/config",
                        "GIT_NO_REPLACE_OBJECTS": "0"})
            result = subprocess.run(["bash", str(RUNNER), "help"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 2, result.stderr)
            snapshot = dict(line.split("=", 1) for line in
                            (root / "git-env-snapshot").read_text().splitlines() if "=" in line)
            for name in (*ROUTING, *CONFIG, *EXECUTABLES):
                if name not in {"GIT_CONFIG_COUNT", "GIT_CONFIG_GLOBAL"}:
                    self.assertNotIn(name, snapshot)
            self.assertEqual(snapshot["GIT_CONFIG_NOSYSTEM"], "1")
            self.assertEqual(snapshot["GIT_CONFIG_GLOBAL"], "/dev/null")
            self.assertEqual(snapshot["GIT_NO_REPLACE_OBJECTS"], "1")
            self.assertEqual(snapshot["GIT_CONFIG_COUNT"], "3")
            self.assertEqual(snapshot["GIT_CONFIG_KEY_0"], "safe.directory")
            self.assertEqual(snapshot["GIT_CONFIG_VALUE_0"], "*")
            self.assertEqual(snapshot["GIT_CONFIG_KEY_1"], "core.hooksPath")
            self.assertEqual(snapshot["GIT_CONFIG_VALUE_1"], "/dev/null")
            self.assertEqual(snapshot["GIT_CONFIG_KEY_2"], "core.fsmonitor")
            self.assertEqual(snapshot["GIT_CONFIG_VALUE_2"], "false")
            self.assertEqual(snapshot["GIT_OPTIONAL_LOCKS"], "0")
            self.assertNotIn("GIT_TRACE_CUSTOM", snapshot)
            self.assertNotIn("GIT_CONFIG_KEY_3", snapshot)
            self.assertNotIn("GIT_CONFIG_VALUE_3", snapshot)

    def test_agent_runner_rejects_missing_repository_root(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            env["FAKE_TOP"] = ""
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertEqual(result.returncode, 1)
            self.assertIn("repository context is unsafe", result.stderr.lower())
            self.assertFalse((root / "opencode-calls").exists())

    def test_agent_runner_removes_config_when_originally_absent(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            (root / ".opencode/opencode.json").unlink()
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertFalse((root / ".opencode/opencode.json").exists())

    def test_config_activation_refuses_dangling_and_directory_symlinks(self):
        for kind in ("dangling", "directory"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                config = root / ".opencode/opencode.json"
                config.unlink()
                if kind == "dangling":
                    config.symlink_to("missing-target")
                else:
                    target = root / ".opencode/target"
                    target.mkdir()
                    config.symlink_to(target)
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("non-regular opencode config", result.stderr.lower())
                self.assertTrue(config.is_symlink())
                self.assertFalse((root / "opencode-calls").exists())
                self.assertEqual(list((root / ".opencode").glob(".opencode-ci.*")), [])

    def test_config_activation_failure_matrix(self):
        for command in ("mktemp", "cp"):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                env.update({"FAIL_COMMAND": command, "FAIL_MATCH": ".opencode-ci"})
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((root / ".opencode/opencode.json").read_text(), '{"real": true}\n')
                self.assertEqual(list((root / ".opencode").glob(".opencode-ci.*")), [])
                self.assertFalse((root / "opencode-calls").exists())
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            config = root / ".opencode/opencode.json"
            config.unlink()
            config.mkdir()
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertTrue(config.is_dir())
            self.assertEqual(list((root / ".opencode").glob(".opencode-ci.*")), [])
            self.assertFalse((root / "opencode-calls").exists())
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            env["MAKE_CONFIG_DIRECTORY_DURING_COPY"] = "1"
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("directory target", result.stderr.lower())
            self.assertTrue((root / ".opencode/opencode.json").is_dir())
            self.assertEqual(list((root / ".opencode").glob(".opencode-ci.*")), [])
            self.assertFalse((root / "opencode-calls").exists())

    def test_config_activation_preserves_symlink_and_hardlink_targets(self):
        for kind in ("symlink", "hardlink"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                config = root / ".opencode/opencode.json"
                external = root / "outside-config"
                external.write_text("external sentinel\n")
                config.unlink()
                if kind == "symlink":
                    config.symlink_to(external)
                else:
                    os.link(external, config)
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(external.read_text(), "external sentinel\n")
                self.assertEqual(config.read_text(), "external sentinel\n")
                self.assertFalse(config.is_symlink())

    def test_agent_runner_restores_preexisting_skip_worktree_flag(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            env["FAKE_INDEX_FLAG"] = "S"
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual((root / ".opencode/opencode.json").read_text(), '{"real": true}\n')
            calls = (root / "git-calls").read_text().splitlines()
            self.assertTrue(any(call.endswith("update-index --skip-worktree .opencode/opencode.json")
                                for call in calls))

    def test_agent_runner_writes_private_auth_without_key_in_argv(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            seen = (root / "auth-seen.json").read_text()
            self.assertIn("synthetic-key", seen)
            self.assertEqual((root / "auth-seen.json").stat().st_mode & 0o777, 0o600)
            self.assertNotIn("synthetic-key", (root / "opencode-calls").read_text())

    def test_agent_runner_rejects_invalid_branch_refs(self):
        for branch in ("bad ref", "opencode/fix.lock"):
            with self.subTest(branch=branch), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory)
                env.update({"MODE": "fix", "BRANCH": branch, "GH_TOKEN": "synthetic-token",
                            "FAKE_REF_VALID": "0" if branch.endswith(".lock") else "1"})
                (root / ".agent").mkdir()
                (root / ".agent/branch").write_text(branch)
                (root / ".agent/result.bundle").write_text("synthetic")
                result = subprocess.run(["bash", str(RUNNER), "adopt"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                calls = (root / "git-calls").read_text()
                self.assertNotIn(" fetch ", calls)
                self.assertNotIn(" switch ", calls)

    def test_agent_runner_accepts_expected_fix_branch_for_adopt_and_publish(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory)
            branch = "opencode/fix"
            env.update({"MODE": "fix", "BRANCH": branch, "FAKE_BRANCH": branch,
                        "GH_TOKEN": "synthetic-token", "PR_NUMBER": "7"})
            (root / ".agent").mkdir()
            (root / ".agent/branch").write_text(branch)
            (root / ".agent/result.bundle").write_text("synthetic")
            (root / ".agent/commits").write_text("1")
            (root / ".agent/result").write_text("ok")
            (root / ".agent/checks").write_text("passed")
            adopted = subprocess.run(["bash", str(RUNNER), "adopt"], cwd=root, env=env,
                                     capture_output=True, text=True, check=False)
            self.assertEqual(adopted.returncode, 0, adopted.stderr)
            published = subprocess.run(["bash", str(RUNNER), "publish"], cwd=root, env=env,
                                       capture_output=True, text=True, check=False)
            self.assertEqual(published.returncode, 0, published.stderr)
            calls = (root / "git-calls").read_text()
            self.assertIn(f"fetch -q origin {branch}", calls)
            self.assertIn(f"push origin HEAD:refs/heads/{branch}", calls)
            self.assertIn("pr comment 7", (root / "gh-calls").read_text())

    def test_publish_rejects_detached_head(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory)
            branch = "opencode/issue-5-9"
            env.update({"STORY_ID": "issue-5", "GITHUB_RUN_NUMBER": "9",
                        "BRANCH_PREFIX": "opencode", "FAKE_BRANCH": branch,
                        "DETACHED_HEAD": "1", "GH_TOKEN": "synthetic-token"})
            (root / ".agent").mkdir()
            (root / ".agent/branch").write_text(branch)
            (root / ".agent/commits").write_text("1")
            (root / ".agent/result").write_text("ok")
            (root / ".agent/checks").write_text("passed")
            result = subprocess.run(["bash", str(RUNNER), "publish"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("detached", result.stderr.lower())
            self.assertNotIn(" push ", (root / "git-calls").read_text())

    def test_agent_runner_rejects_non_array_conflicted_input(self):
        for value in ("{}", "null", "5"):
            with self.subTest(value=value), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory)
                env.update({"CONFLICTED": value, "GH_TOKEN": "synthetic-token"})
                result = subprocess.run(["bash", str(RUNNER), "fetch-prs"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertNotIn(" fetch ", (root / "git-calls").read_text())

    def test_agent_runner_accepts_matching_fetch_and_push_urls(self):
        urls = (
            "https://github.com/synthetic-owner/synthetic-repo",
            "https://github.com/synthetic-owner/synthetic-repo.git",
            "git@github.com:synthetic-owner/synthetic-repo",
            "git@github.com:synthetic-owner/synthetic-repo.git",
            "ssh://git@github.com/synthetic-owner/synthetic-repo",
            "ssh://git@github.com/synthetic-owner/synthetic-repo.git",
        )
        for url in urls:
            with self.subTest(url=url), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory)
                env.update({"FAKE_FETCH_URL": url, "FAKE_PUSH_URL": url})
                result = subprocess.run(["bash", str(RUNNER), "help"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, 2, result.stderr)
                self.assertIn("usage:", result.stderr)
                self.assertNotIn("repository context is unsafe", result.stderr.lower() + result.stdout.lower())
                calls = (root / "git-calls").read_text()
                self.assertIn(f"-C {root.resolve()}", calls)
                self.assertIn("remote get-url origin", calls)
                self.assertIn("remote get-url --push origin", calls)
                self.assertNotIn("config --global", calls)
                self.assertEqual(set((root / "git-env").read_text().splitlines()), {"clean"})

    def test_agent_runner_rejects_missing_or_mismatched_urls(self):
        cases = (
            ("top", {"FAKE_TOP": "/synthetic/other"}),
            ("fetch-missing", {"FAKE_FETCH_URL": ""}),
            ("fetch", {"FAKE_FETCH_URL": "https://github.com/synthetic-owner/other.git"}),
            ("push-missing", {"FAKE_PUSH_URL": ""}),
            ("push", {"FAKE_PUSH_URL": "git@github.com:synthetic-owner/other.git"}),
        )
        for name, overrides in cases:
            with self.subTest(case=name), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory)
                env.update(overrides)
                result = subprocess.run(["bash", str(RUNNER), "help"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 2)
                self.assertIn("repository context is unsafe", result.stderr.lower())
                self.assertNotIn("usage:", result.stderr)

    def test_agent_runner_refuses_status_failure_before_agent(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            env.update({"FAIL_STATUS_AT": "1", "STORY_ID": "SYNTHETIC-1",
                        "STORY_TITLE": "Synthetic story", "STORY_BODY": "body",
                        "GITHUB_RUN_NUMBER": "1", "BRANCH_PREFIX": "opencode"})
            result = subprocess.run(["bash", str(RUNNER), "implement"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unable to determine workspace status", result.stderr.lower())
            self.assertFalse((root / "opencode-calls").exists())

    def test_review_status_failure_preserves_workspace(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            env["FAIL_STATUS_AT"] = "2"
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("unable to determine workspace status", result.stderr.lower())
            self.assertEqual((root / "feature.txt").read_text(), "changed\n")
            self.assertFalse((root / ".agent/review-b.md").exists())

    def test_agent_commands_refuse_dirty_workspace_before_mutation(self):
        for command in ("triage", "reconcile", "resolve"):
            with self.subTest(command=command), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                (root / "dirty").touch()
                sentinel = root / ".agent/triage/keep.txt"
                sentinel.parent.mkdir(parents=True)
                sentinel.write_text("keep\n")
                env.update({"GH_TOKEN": "synthetic-token", "CONFLICTED": "[5]"})
                result = subprocess.run(["bash", str(RUNNER), command], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("dirty workspace", result.stderr.lower())
                self.assertFalse((root / "opencode-calls").exists())
                self.assertEqual(sentinel.read_text(), "keep\n")
                self.assertNotIn(" switch ", (root / "git-calls").read_text())

    def test_auth_temp_is_removed_when_chmod_fails(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            auth = root / "xdg/opencode/auth.json"
            auth.parent.mkdir(parents=True)
            auth.write_text("original auth\n")
            env["FAIL_AUTH_CHMOD"] = "1"
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(auth.read_text(), "original auth\n")
            self.assertEqual(list((root / "xdg/opencode").glob("auth.json.*")), [])

    def test_agent_runner_cleanup_failure_matrix(self):
        cases = (
            ("mktemp", "aurevia-backup"),
            ("cp", ".opencode/opencode.json"),
            ("mkdir", "/xdg/opencode"),
            ("mktemp", "auth.json."),
            ("jq", ""),
            ("rm", "auth.json"),
        )
        for command, match in cases:
            with self.subTest(command=command, match=match), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                env.update({"FAIL_COMMAND": command, "FAIL_MATCH": match})
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((root / ".opencode/opencode.json").read_text(), '{"real": true}\n')
                self.assertFalse((root / ".agent/review-b.md").exists())

    def test_agent_runner_backup_setup_failure_matrix(self):
        for flag in ("FAIL_BACKUP_CHMOD", "FAIL_CONFIG_FLAG"):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                env[flag] = "1"
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual((root / ".opencode/opencode.json").read_text(), '{"real": true}\n')
                self.assertEqual(list((root / "tmp").glob("aurevia-backup.*")), [])
                self.assertFalse((root / "opencode-calls").exists())

    def test_agent_runner_atomic_restore_failure_matrix(self):
        cases = (("mkdir", None), ("mktemp", ".aurevia-restore"), ("cp", ".aurevia-restore"))
        for command, match in cases:
            with self.subTest(command=command), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                env.update({"FAIL_COMMAND": command,
                            "FAIL_MATCH": match or ".opencode"})
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("failed to restore opencode config", result.stderr.lower())
                self.assertTrue(list((root / "tmp").glob("aurevia-backup.*")))
                self.assertEqual(list((root / ".opencode").glob(".aurevia-restore.*")), [])

    def test_agent_runner_auth_cleanup_failure_matrix(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            env.update({"FAIL_AUTH_CHMOD": "1", "FAIL_COMMAND": "rm", "FAIL_MATCH": "auth.json."})
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("failed to remove temporary opencode key file", result.stderr.lower())
            self.assertTrue(list((root / "tmp").glob("aurevia-backup.*")))
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            auth = root / "xdg/opencode/auth.json"
            auth.parent.mkdir(parents=True)
            auth.write_text("original auth\n")
            env["FAIL_AUTH_RESTORE_CHMOD"] = "1"
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("failed to restore opencode credential mode", result.stderr.lower())
            self.assertTrue(list((root / "tmp").glob("aurevia-backup.*")))

    def test_agent_runner_config_restore_failure_matrix(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            (root / ".opencode/opencode.json").unlink()
            env.update({"FAIL_COMMAND": "rm", "FAIL_MATCH": ".opencode/opencode.json"})
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("failed to remove temporary opencode config", result.stderr.lower())
            self.assertTrue(list((root / "tmp").glob("aurevia-backup.*")))
        for flag in ("H", "S"):
            with self.subTest(flag=flag), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                env.update({"FAKE_INDEX_FLAG": flag, "FAIL_UPDATE_INDEX_FROM": "2"})
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("cleanup failed", result.stderr.lower())
                self.assertTrue(list((root / "tmp").glob("aurevia-backup.*")))
                self.assertFalse((root / ".agent/review-b.md").exists())

    def test_auth_rejects_directory_and_symlink_directory_targets(self):
        for kind in ("directory", "symlink"):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                auth = root / "xdg/opencode/auth.json"
                auth.parent.mkdir(parents=True)
                if kind == "directory":
                    auth.mkdir()
                else:
                    target = root / "xdg/opencode/target"
                    target.mkdir()
                    auth.symlink_to(target)
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("non-regular", result.stderr.lower())
                if kind == "directory":
                    self.assertTrue(auth.is_dir())
                else:
                    self.assertTrue(auth.is_symlink())
                    self.assertTrue(auth.resolve().is_dir())
                self.assertEqual(list((root / "xdg/opencode").glob("auth.json.*")), [])

    def test_resolve_rechecks_cleanliness_before_every_switch(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            env.update({"CONFLICTED": "[5,6]", "GH_TOKEN": "synthetic-token",
                        "DIRTY_AFTER_DIFF": "1", "FAKE_BASE_ANCESTOR": "0"})
            result = subprocess.run(["bash", str(RUNNER), "resolve"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            calls = (root / "git-calls").read_text()
            self.assertIn("switch -q -C resolve-5", calls)
            self.assertNotIn("switch -q -C resolve-6", calls)

    def test_auth_restore_failure_retains_backup_and_removes_key_temp(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            auth = root / "xdg/opencode/auth.json"
            auth.parent.mkdir(parents=True)
            auth.write_text("original auth\n")
            env["FAIL_RESTORE_REPLACE"] = "1"
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("cleanup failed", result.stderr.lower())
            backups = list((root / "tmp").glob("aurevia-backup.*/auth"))
            self.assertEqual(len(backups), 1)
            self.assertEqual(backups[0].read_text(), "original auth\n")
            self.assertEqual(backups[0].stat().st_mode & 0o777, 0o600)
            self.assertEqual(list((root / "xdg/opencode").glob("auth.json.*")), [])

    def test_agent_runner_restores_config_and_auth_on_signals(self):
        for signal, code in (("INT", 130), ("TERM", 143)):
            with self.subTest(signal=signal), tempfile.TemporaryDirectory() as directory:
                root, env = self.workspace(directory, opencode=True)
                config = root / ".opencode/opencode.json"
                config.chmod(0o640)
                auth = root / "xdg/opencode/auth.json"
                auth.parent.mkdir(parents=True)
                auth.write_text("original auth\n")
                auth.chmod(0o644)
                env.update({"SIGNAL_AT_STATUS": "2", "SIGNAL_STATUS": signal})
                result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                        capture_output=True, text=True, check=False)
                self.assertEqual(result.returncode, code, result.stderr)
                self.assertEqual(config.read_text(), '{"real": true}\n')
                self.assertEqual(config.stat().st_mode & 0o777, 0o640)
                self.assertEqual(auth.read_text(), "original auth\n")
                self.assertEqual(auth.stat().st_mode & 0o777, 0o644)
                self.assertEqual(list((root / "xdg/opencode").glob("auth.json.*")), [])

    def test_config_activation_failure_restores_bytes_and_mode(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            config = root / ".opencode/opencode.json"
            config.chmod(0o640)
            env["FAIL_UPDATE_INDEX"] = "1"
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(config.read_text(), '{"real": true}\n')
            self.assertEqual(config.stat().st_mode & 0o777, 0o640)
            self.assertFalse((root / "opencode-calls").exists())

    def test_review_cleanup_refuses_dirty_worktree_without_deletion(self):
        with tempfile.TemporaryDirectory() as directory:
            root, env = self.workspace(directory, opencode=True)
            auth = root / "xdg/opencode/auth.json"
            auth.parent.mkdir(parents=True)
            auth.write_text("original auth\n")
            auth.chmod(0o644)
            result = subprocess.run(["bash", str(RUNNER), "review"], cwd=root, env=env,
                                    capture_output=True, text=True, check=False)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("reviewer changed the workspace", result.stderr.lower())
            self.assertEqual((root / "feature.txt").read_text(), "changed\n")
            self.assertEqual(len((root / "opencode-calls").read_text().splitlines()), 1)
            calls = (root / "git-calls").read_text()
            self.assertNotIn(" clean ", calls)
            self.assertNotIn("checkout -q -- .", calls)
            self.assertEqual((root / ".opencode/opencode.json").read_text(), '{"real": true}\n')
            self.assertEqual(auth.read_text(), "original auth\n")
            self.assertEqual(auth.stat().st_mode & 0o777, 0o644)


if __name__ == "__main__":
    unittest.main()
