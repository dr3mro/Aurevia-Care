"""Policy validation for the CI runtime OpenCode profile (OPS-06).

The generated local config is covered by check-pack; these tests exercise the
separate runtime profile that unattended swarm workers actually load.
"""
import io
import json
import runpy
import shutil
import sys
import tempfile
import unittest
from contextlib import redirect_stderr
from pathlib import Path
from unittest.mock import patch

from helpers import ROOT, copy_pack, load

gen = load("gen-opencode")


class RuntimeProfileTests(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        (self.tmp / ".github" / "swarm").mkdir(parents=True)
        self.profile = json.loads((ROOT / ".opencode/opencode.ci.json").read_text())
        for path in gen.INSTRUCTIONS:
            target = self.tmp / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("synthetic instruction\n")
        self.write_files()

    def write_files(self):
        (self.tmp / gen.RUNTIME_PROFILE).write_text(json.dumps(self.profile))

    def violations(self):
        self.write_files()
        return gen.check_runtime(self.tmp)

    def agent(self, name):
        return self.profile["agents"][name]

    def test_repository_runtime_profile_passes(self):
        self.assertEqual(gen.check_runtime(ROOT), [])

    def test_missing_profile_is_reported(self):
        (self.tmp / gen.RUNTIME_PROFILE).unlink()
        found = gen.check_runtime(self.tmp)
        self.assertTrue(any("runtime profile" in v and "missing" in v for v in found), found)

    def test_invalid_profile_json_is_reported(self):
        (self.tmp / gen.RUNTIME_PROFILE).write_text("not json")
        found = gen.check_runtime(self.tmp)
        self.assertTrue(any("not valid JSON" in v for v in found), found)

    def test_malformed_model_registry_is_ignored(self):
        (self.tmp / ".opencode/model-pricing.json").write_text("not json")
        self.assertEqual(gen.check_runtime(self.tmp), [])

    def test_missing_instruction_file_is_reported(self):
        missing = gen.INSTRUCTIONS[0]
        (self.tmp / missing).unlink()
        found = self.violations()
        self.assertTrue(any(missing in v and "instruction" in v for v in found), found)

    def test_editing_agent_without_bash_list_is_reported(self):
        self.profile["agents"]["aurevia-editor"] = {
            "permissions": [{"action": "read", "resource": "*", "effect": "allow"}]
        }
        found = self.violations()
        self.assertTrue(any("aurevia-editor" in v and "bash" in v for v in found), found)

    def test_reviewer_with_edit_permission_is_reported(self):
        self.agent("qa-reviewer")["permissions"].append(
            {"action": "write", "resource": "src/*", "effect": "allow"})
        found = self.violations()
        self.assertTrue(any("qa-reviewer" in v and "edit" in v for v in found), found)

    def test_reviewer_with_denied_write_is_allowed(self):
        self.agent("qa-reviewer")["permissions"].append(
            {"action": "write", "resource": ".github/*", "effect": "deny"})
        self.assertEqual(self.violations(), [])

    def test_agent_writing_protected_paths_is_reported(self):
        for resource, protected in (
            (".github/workflows/delivery.yml", ".github/"),
            (".opencode/config/MEMORY.md", ".opencode/config/MEMORY.md"),
            (".opencode/agents/example.md", ".opencode/"),
        ):
            with self.subTest(resource=resource):
                self.profile["agents"]["aurevia-editor"] = {
                    "permissions": [
                        {"action": "shell", "resource": "*", "effect": "allow"},
                        {"action": "write", "resource": resource, "effect": "allow"},
                    ]
                }
                found = self.violations()
                self.assertTrue(any("aurevia-editor" in v and protected in v for v in found),
                                found)

    def test_model_declarations_are_not_registry_checked(self):
        self.agent("qa-reviewer")["model"] = "other/expensive-model"
        self.profile["model"] = "another/expensive-model"
        self.assertEqual(self.violations(), [])

    def test_secret_variable_in_agent_is_reported(self):
        self.agent("qa-reviewer")["apiKey"] = "OPENCODE_API_KEY"
        found = self.violations()
        self.assertTrue(any("qa-reviewer" in v and "key" in v.lower() for v in found),
                        found)

    def test_token_value_in_agent_is_reported(self):
        self.agent("qa-reviewer")["header"] = "Bearer ghp_" + "a" * 24
        found = self.violations()
        self.assertTrue(any("qa-reviewer" in v for v in found), found)

    def test_secret_in_profile_metadata_is_reported(self):
        self.profile["credential"] = "NVD_API_KEY"
        found = self.violations()
        self.assertTrue(any("runtime profile" in v and "key" in v.lower() for v in found), found)

    def test_check_runtime_flag_passes_on_repository_profile(self):
        with redirect_stderr(io.StringIO()):
            status = gen.main(["--check-runtime"])
        self.assertEqual(status, 0)

    def test_check_runtime_flag_reports_violations_and_fails(self):
        err = io.StringIO()
        with patch.object(gen, "check_runtime", return_value=["agent aurevia-editor: bad"]):
            with redirect_stderr(err):
                status = gen.main(["--check-runtime"])
        self.assertEqual(status, 1)
        self.assertIn("aurevia-editor", err.getvalue())

    def test_generate_mode_writes_both_configs(self):
        """Cover the existing generation path in the file this change modifies."""
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        for name in (".opencode/opencode.json", ".opencode/opencode.ci.json"):
            (tmp / name).unlink()
        with patch.object(gen, "ROOT", tmp):
            self.assertEqual(gen.main([]), 0)
        self.assertEqual((tmp / ".opencode/opencode.json").read_text(), gen.render(tmp, False))
        self.assertEqual((tmp / ".opencode/opencode.ci.json").read_text(),
                         gen.render(tmp, True))

    def test_check_mode_reports_stale_and_missing_config(self):
        """Cover both failure sides of the existing --check drift guard."""
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / ".opencode/opencode.json").write_text("stale\n")
        err = io.StringIO()
        with patch.object(gen, "ROOT", tmp), redirect_stderr(err):
            self.assertEqual(gen.main(["--check"]), 1)
        self.assertIn("opencode.json is out of date", err.getvalue())
        (tmp / ".opencode/opencode.json").unlink()
        err = io.StringIO()
        with patch.object(gen, "ROOT", tmp), redirect_stderr(err):
            self.assertEqual(gen.main(["--check"]), 1)
        self.assertIn("opencode.json is out of date", err.getvalue())

    def test_script_entry_point_runs_check_runtime(self):
        """Cover the existing __main__ guard by executing the real script in-process."""
        with patch.object(sys, "argv", ["gen-opencode.py", "--check-runtime"]):
            with self.assertRaises(SystemExit) as raised:
                runpy.run_path(str(ROOT / "scripts/gen-opencode.py"), run_name="__main__")
        self.assertEqual(raised.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
