"""Regression tests for repository-safe child process environments.

Every subprocess boundary is mocked, limited to ``--help``, or import-only via
``runpy``; these tests never run Git, a gate, a network client or a real worker command.
"""
import ast
import os
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT, load

process_env = load("process_env")
swarm = load("swarm")

ROUTING = (
    "GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
    "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES", "GIT_NAMESPACE",
    "GIT_IMPLICIT_WORK_TREE", "GIT_PREFIX", "GIT_SHALLOW_FILE", "GIT_GRAFT_FILE",
    "GIT_REPLACE_REF_BASE", "GIT_QUARANTINE_PATH", "GIT_CEILING_DIRECTORIES",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
)
CONFIG = (
    "GIT_CONFIG", "GIT_CONFIG_COUNT", "GIT_CONFIG_PARAMETERS", "GIT_CONFIG_SYSTEM",
    "GIT_CONFIG_GLOBAL", "GIT_EXEC_PATH", "GIT_TEMPLATE_DIR",
)
EXECUTABLES = (
    "GIT_PROXY_COMMAND", "GIT_SSH", "GIT_SSH_COMMAND", "GIT_ASKPASS", "GIT_EDITOR",
    "GIT_SEQUENCE_EDITOR", "GIT_PAGER", "GIT_EXTERNAL_DIFF", "GIT_TRACE",
    "GIT_TRACE_PACKET", "GIT_TRACE_PERFORMANCE", "GIT_TRACE_SETUP", "GIT_TRACE_CURL",
    "GIT_TRACE_CURL_NO_DATA", "GIT_TRACE_SHALLOW", "GIT_TRACE_FSMONITOR",
    "GIT_TRACE_PACK_ACCESS", "GIT_TRACE2", "GIT_TRACE2_EVENT", "GIT_TRACE2_PERF",
)


class ChildEnvironmentTest(unittest.TestCase):
    """The helper removes redirection without changing the parent process."""

    def contaminated(self, names):
        return {name: f"/synthetic/{name.lower()}" for name in names}

    def assert_safe_git_config(self, env):
        self.assertEqual(env["GIT_CONFIG_COUNT"], "3")
        self.assertEqual(env["GIT_CONFIG_KEY_0"], "safe.directory")
        self.assertEqual(env["GIT_CONFIG_VALUE_0"], "*")
        self.assertEqual(env["GIT_CONFIG_KEY_1"], "core.hooksPath")
        self.assertEqual(env["GIT_CONFIG_VALUE_1"], "/dev/null")
        self.assertEqual(env["GIT_CONFIG_KEY_2"], "core.fsmonitor")
        self.assertEqual(env["GIT_CONFIG_VALUE_2"], "false")

    def test_empty_environment_still_installs_safe_git_config(self):
        with mock.patch.dict(os.environ, {}, clear=True):
            env = process_env.child_environment()
        self.assert_safe_git_config(env)

    def test_repository_routing_variables_are_removed(self):
        contaminated = self.contaminated(ROUTING)
        with mock.patch.dict(os.environ, contaminated):
            env = process_env.child_environment()
        for name in ROUTING:
            self.assertNotIn(name, env)

    def test_indexed_configuration_and_grafts_are_removed(self):
        contaminated = {
            **self.contaminated(CONFIG),
            "GIT_CONFIG_KEY_0": "core.sshCommand",
            "GIT_CONFIG_VALUE_0": "malicious-command",
            "GIT_CONFIG_KEY_9": "url.evil.insteadOf",
            "GIT_CONFIG_VALUE_9": "https://evil.test/",
        }
        with mock.patch.dict(os.environ, contaminated):
            env = process_env.child_environment()
        for name in CONFIG:
            if name not in {"GIT_CONFIG_COUNT", "GIT_CONFIG_GLOBAL"}:
                self.assertNotIn(name, env)
        self.assertNotIn("GIT_CONFIG_KEY_9", env)
        self.assertNotIn("GIT_CONFIG_VALUE_9", env)
        self.assertEqual(env["GIT_CONFIG_KEY_0"], "safe.directory")
        self.assertEqual(env["GIT_CONFIG_VALUE_0"], "*")

    def test_executable_and_trace_overrides_are_removed(self):
        with mock.patch.dict(os.environ, self.contaminated(EXECUTABLES)):
            env = process_env.child_environment()
        for name in EXECUTABLES:
            self.assertNotIn(name, env)

    def test_safe_configuration_overrides_win(self):
        contaminated = {
            "GIT_CONFIG_NOSYSTEM": "0", "GIT_CONFIG_GLOBAL": "/synthetic/config",
            "GIT_NO_REPLACE_OBJECTS": "0", "GIT_CONFIG_COUNT": "9",
            "GIT_CONFIG_KEY_0": "core.hooksPath", "GIT_CONFIG_VALUE_0": "/synthetic/hooks",
        }
        extra = {"GIT_CONFIG_KEY_1": "core.fsmonitor", "GIT_CONFIG_VALUE_1": "evil"}
        with mock.patch.dict(os.environ, contaminated):
            env = process_env.child_environment(extra)
        self.assertEqual(env["GIT_CONFIG_NOSYSTEM"], "1")
        self.assertEqual(env["GIT_CONFIG_GLOBAL"], "/dev/null")
        self.assertEqual(env["GIT_NO_REPLACE_OBJECTS"], "1")
        self.assertEqual(env["GIT_CONFIG_COUNT"], "3")
        self.assertEqual(env["GIT_CONFIG_KEY_0"], "safe.directory")
        self.assertEqual(env["GIT_CONFIG_VALUE_0"], "*")
        self.assertEqual(env["GIT_CONFIG_KEY_1"], "core.hooksPath")
        self.assertEqual(env["GIT_CONFIG_VALUE_1"], "/dev/null")
        self.assertEqual(env["GIT_CONFIG_KEY_2"], "core.fsmonitor")
        self.assertEqual(env["GIT_CONFIG_VALUE_2"], "false")

    def test_runtime_and_auth_settings_survive(self):
        allowed = {
            "SWARM_GIT_NAME": "synthetic-name", "SWARM_GIT_EMAIL": "synthetic@example.test",
            "GH_TOKEN": "synthetic-token", "SSH_AUTH_SOCK": "/synthetic/ssh-agent.sock",
            "NVD_API_KEY": "synthetic-nvd", "PATH": "/synthetic/bin", "HOME": "/synthetic/home",
        }
        with mock.patch.dict(os.environ, allowed):
            env = process_env.child_environment()
        for name, value in allowed.items():
            self.assertEqual(env[name], value)

    def test_extra_environment_cannot_restore_overrides(self):
        extra = {"GIT_DIR": "/synthetic/wrong.git", "GIT_WORK_TREE": "/synthetic/wrong",
                 "GIT_CONFIG_KEY_0": "core.sshCommand", "GIT_CONFIG_VALUE_0": "evil",
                 "SAFE_VALUE": "kept"}
        with mock.patch.dict(os.environ, {}, clear=True):
            env = process_env.child_environment(extra)
        self.assertNotIn("GIT_DIR", env)
        self.assertNotIn("GIT_WORK_TREE", env)
        self.assertEqual(env["GIT_CONFIG_KEY_0"], "safe.directory")
        self.assertEqual(env["GIT_CONFIG_VALUE_0"], "*")
        self.assertEqual(env["SAFE_VALUE"], "kept")

    def test_read_only_environment_disables_optional_locks(self):
        with mock.patch.dict(os.environ, {"GIT_OPTIONAL_LOCKS": "1"}):
            writable = process_env.child_environment()
            readonly = process_env.child_environment(read_only=True)
        self.assertEqual(writable["GIT_OPTIONAL_LOCKS"], "1")
        self.assertEqual(readonly["GIT_OPTIONAL_LOCKS"], "0")

    def test_parent_environment_is_unchanged(self):
        contaminated = {**self.contaminated(ROUTING), "GIT_CONFIG_COUNT": "7",
                        "GIT_OPTIONAL_LOCKS": "1", "SAFE_VALUE": "parent"}
        with mock.patch.dict(os.environ, contaminated):
            before = dict(os.environ)
            process_env.child_environment({"GIT_DIR": "/synthetic/extra"}, read_only=True)
            self.assertEqual(dict(os.environ), before)


class ProductionBoundaryTest(unittest.TestCase):
    """Production wrappers and gates use the same sanitized boundary."""

    def contaminated(self, names):
        return {name: f"/synthetic/{name.lower()}" for name in names}

    def popen_writing(self, done, stdout="", stderr=""):
        """Return a Popen fake that writes captured gate output to temp files."""
        def popen(*args, **kwargs):
            kwargs["stdout"].write(stdout)
            kwargs["stderr"].write(stderr)
            return done
        return popen

    def assert_safe_git_config(self, env):
        self.assertEqual(env["GIT_CONFIG_COUNT"], "3")
        self.assertEqual(env["GIT_CONFIG_KEY_0"], "safe.directory")
        self.assertEqual(env["GIT_CONFIG_VALUE_0"], "*")
        self.assertEqual(env["GIT_CONFIG_KEY_1"], "core.hooksPath")
        self.assertEqual(env["GIT_CONFIG_VALUE_1"], "/dev/null")
        self.assertEqual(env["GIT_CONFIG_KEY_2"], "core.fsmonitor")
        self.assertEqual(env["GIT_CONFIG_VALUE_2"], "false")
        self.assertNotIn("GIT_CONFIG_KEY_3", env)
        self.assertNotIn("GIT_CONFIG_VALUE_3", env)

    def test_git_wrapper_uses_sanitized_environment(self):
        done = subprocess.CompletedProcess([], 0, "", "")
        contaminated = {**self.contaminated(ROUTING), "SWARM_GIT_NAME": "kept"}
        with mock.patch.dict(os.environ, contaminated), \
                mock.patch.object(swarm.subprocess, "run", return_value=done) as run:
            swarm.git("status")
            readonly = run.call_args.kwargs["env"]
            swarm.git("reset", "--hard", "synthetic")
            writable = run.call_args.kwargs["env"]
        for name in ROUTING:
            self.assertNotIn(name, readonly)
            self.assertNotIn(name, writable)
        self.assertEqual(readonly["GIT_OPTIONAL_LOCKS"], "0")
        self.assertEqual(readonly["SWARM_GIT_NAME"], "kept")
        self.assert_safe_git_config(readonly)
        self.assert_safe_git_config(writable)

    def test_gate_environment_sanitizes_after_extras(self):
        contaminated = {**self.contaminated(ROUTING), "GH_TOKEN": "synthetic-secret",
                        "NVD_API_KEY": "synthetic-nvd", "SWARM_GIT_NAME": "kept",
                        "GIT_CONFIG_KEY_3": "core.sshCommand", "GIT_CONFIG_VALUE_3": "evil",
                        "GITHUB_ENV": "/runner/env", "GITHUB_OUTPUT": "/runner/output",
                        "GITHUB_PATH": "/runner/path", "GITHUB_STATE": "/runner/state",
                        "GITHUB_STEP_SUMMARY": "/runner/summary"}
        extra = {"GIT_INDEX_FILE": "/synthetic/extra-index", "PUSH_TOKEN": "synthetic-token",
                 "GITHUB_ENV": "/synthetic/extra-env"}
        with mock.patch.dict(os.environ, contaminated):
            env = swarm.gate_environment(extra)
        for name in (*ROUTING, "GH_TOKEN", "PUSH_TOKEN", "GITHUB_ENV", "GITHUB_OUTPUT",
                     "GITHUB_PATH", "GITHUB_STATE", "GITHUB_STEP_SUMMARY"):
            self.assertNotIn(name, env)
        self.assertEqual(env["NVD_API_KEY"], "synthetic-nvd")
        self.assertEqual(env["SWARM_GIT_NAME"], "kept")
        self.assertEqual(env["GIT_CONFIG_NOSYSTEM"], "1")
        self.assertEqual(env["GIT_CONFIG_GLOBAL"], "/dev/null")
        self.assertEqual(env["GIT_NO_REPLACE_OBJECTS"], "1")
        self.assertNotIn("GIT_CONFIG_KEY_3", env)
        self.assertNotIn("GIT_CONFIG_VALUE_3", env)
        self.assert_safe_git_config(env)

    def test_run_gate_passes_sanitized_environment(self):
        done = mock.Mock(returncode=0, pid=4242)
        contaminated = {**self.contaminated(ROUTING), "GH_TOKEN": "synthetic-secret"}
        with mock.patch.dict(os.environ, contaminated), \
                mock.patch.object(swarm.subprocess, "Popen",
                                  side_effect=self.popen_writing(done, "ok")) as popen, \
                mock.patch.object(swarm.os, "killpg") as killpg:
            ok, log = swarm.run_gate("synthetic-gate.sh", {"GIT_DIR": "/synthetic/extra"})
        self.assertTrue(ok)
        self.assertEqual(log, "ok")
        self.assertEqual(popen.call_args.args[0], ["bash", "synthetic-gate.sh"])
        self.assertIs(popen.call_args.kwargs["start_new_session"], True)
        self.assertIs(popen.call_args.kwargs["text"], True)
        self.assertTrue(hasattr(popen.call_args.kwargs["stdout"], "write"))
        self.assertTrue(hasattr(popen.call_args.kwargs["stderr"], "write"))
        done.wait.assert_called_once_with()
        done.communicate.assert_not_called()
        env = popen.call_args.kwargs["env"]
        self.assertNotIn("GIT_DIR", env)
        self.assertNotIn("GH_TOKEN", env)
        self.assert_safe_git_config(env)
        self.assertEqual(
            killpg.call_args_list,
            [mock.call(4242, swarm.signal.SIGTERM), mock.call(4242, swarm.signal.SIGKILL)],
        )

    def test_run_gate_tolerates_an_already_reaped_process_group(self):
        done = mock.Mock(returncode=0, pid=4242)
        with mock.patch.object(swarm.subprocess, "Popen",
                               side_effect=self.popen_writing(done, "ok")), \
                mock.patch.object(swarm.os, "killpg", side_effect=ProcessLookupError) as killpg:
            ok, log = swarm.run_gate("synthetic-gate.sh")
        self.assertTrue(ok)
        self.assertEqual(log, "ok")
        self.assertEqual(killpg.call_count, 2)

    def test_run_gate_cleans_up_when_wait_fails(self):
        done = mock.Mock(pid=4242)
        done.wait.side_effect = OSError("synthetic wait failure")
        with mock.patch.object(swarm.subprocess, "Popen",
                               side_effect=self.popen_writing(done)), \
                mock.patch.object(swarm.os, "killpg") as killpg:
            with self.assertRaisesRegex(OSError, "synthetic wait failure"):
                swarm.run_gate("synthetic-gate.sh")
        self.assertEqual(
            killpg.call_args_list,
            [mock.call(4242, swarm.signal.SIGTERM), mock.call(4242, swarm.signal.SIGKILL)],
        )

    def test_run_gate_returns_combined_failure_output(self):
        done = mock.Mock(returncode=1, pid=4242)
        with mock.patch.object(swarm.subprocess, "Popen",
                               side_effect=self.popen_writing(done, "stdout", "stderr")), \
                mock.patch.object(swarm.os, "killpg"):
            ok, log = swarm.run_gate("synthetic-gate.sh")
        self.assertFalse(ok)
        self.assertEqual(log, "stdout\nstderr")

    def test_run_gate_reaps_a_started_background_descendant(self):
        with tempfile.TemporaryDirectory() as directory:
            gate = Path(directory) / "gate.sh"
            pid_file = Path(directory) / "descendant.pid"
            gate.write_text(
                "#!/usr/bin/env bash\n"
                "( sleep 30 ) &\n"
                f"printf '%s' \"$!\" > {pid_file}\n"
                "exit 0\n",
                encoding="utf-8",
            )
            gate.chmod(0o755)
            ok, log = swarm.run_gate(str(gate))
            self.assertTrue(ok, log)
            pid = int(pid_file.read_text())
            for _ in range(50):
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.02)
            else:
                os.kill(pid, swarm.signal.SIGKILL)
                self.fail(f"background descendant {pid} survived gate cleanup")

    def test_production_subprocesses_use_sanitized_helper(self):
        allowed = {"child_environment", "gate_environment"}
        for path in sorted((ROOT / "scripts").glob("*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=str(path))
            for node in ast.walk(tree):
                if not isinstance(node, ast.Call) or not isinstance(node.func, ast.Attribute):
                    continue
                if not isinstance(node.func.value, ast.Name) or node.func.value.id != "subprocess":
                    continue
                if node.func.attr not in {"run", "check_output", "Popen", "call", "check_call"}:
                    continue
                env = next((kw.value for kw in node.keywords if kw.arg == "env"), None)
                with self.subTest(script=path.name, line=node.lineno):
                    self.assertIsNotNone(env, "subprocess must pass an explicit environment")
                    self.assertIsInstance(env, ast.Call)
                    self.assertIsInstance(env.func, ast.Name)
                    self.assertIn(env.func.id, allowed)

    def test_direct_isolated_script_loading(self):
        result = subprocess.run(
            [sys.executable, "-I", str(ROOT / "scripts/check-constraints.py"), "--help"],
            capture_output=True, text=True, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("usage:", result.stdout)
        imported = subprocess.run(
            [sys.executable, "-I", "-c",
             "import runpy; runpy.run_path('scripts/check-names.py', run_name='aurevia_check_names')"],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        self.assertEqual(imported.returncode, 0, imported.stderr)


if __name__ == "__main__":
    unittest.main()
