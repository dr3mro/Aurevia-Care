"""Complete branch coverage for the OpenCode configuration generator (OPS-06).

The generator has two independent duties: render the committed local and CI
profiles from the agent inventory, and validate the runtime profile that
unattended swarm workers actually load. This suite exercises each public
function and both command-line modes with temporary directories and synthetic
policy data, so it is deterministic, needs no network and never mutates the
repository's real configuration.
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

READ_ALL = {"action": "read", "resource": "*", "effect": "allow"}
SHELL_ALL = {"action": "shell", "resource": "*", "effect": "allow"}
ALLOWED_MODEL = "opencode-go/hy4-flash-free"
ZERO_COST = {
    "input": 0, "output": 0, "cache_read": 0, "reasoning": 0, "tool": 0, "request": 0,
    "subscription": False, "overage": False, "authorized": True,
    "pricing_source": "models.dev/api.json", "verified_at": "2026-11-23",
    "roles": ["*"], "phases": ["*"],
}


def catch_all_deny():
    """Return a fresh copy of the reviewer catch-all rule.

    A copy keeps one test's mutation from leaking into another: the module
    constant is a shared dict object.
    """
    return dict(gen.CATCH_ALL_DENY)


class BuildAndRenderCoverageTests(unittest.TestCase):
    """Cover build() and render(), including their zero-item and many-item loops."""

    def test_rule_returns_a_permission_mapping(self):
        self.assertEqual(
            gen.rule("read", "*.env", "deny"),
            {"action": "read", "resource": "*.env", "effect": "deny"},
        )

    def test_build_without_specialists_has_no_subagent_grants(self):
        for ci in (False, True):
            with self.subTest(ci=ci):
                config = gen.build([], ci=ci)
                self.assertEqual(set(config["agents"]), {gen.PRIMARY})
                self.assertEqual(config["default_agent"], gen.PRIMARY)
                self.assertEqual(config["instructions"], gen.INSTRUCTIONS)
                permissions = config["agents"][gen.PRIMARY]["permissions"]
                subagent_rules = [p for p in permissions if p["action"] == "subagent"]
                self.assertEqual(subagent_rules, [{"action": "subagent",
                                                   "resource": "*", "effect": "deny"}])
                shell_denies = [p["resource"] for p in permissions
                                if p["action"] == "shell" and p["effect"] == "deny"]
                self.assertIn("git push*--force*", shell_denies)
                self.assertEqual("gh *" in shell_denies, ci)

    def test_build_with_many_specialists_grants_each_one(self):
        names = ["z-synthetic", gen.PRIMARY, "a-synthetic", "a-synthetic"]
        config = gen.build(names)
        self.assertEqual(set(config["agents"]),
                         {gen.PRIMARY, "a-synthetic", "z-synthetic"})
        primary_rules = config["agents"][gen.PRIMARY]["permissions"]
        subagent_allows = [p["resource"] for p in primary_rules
                           if p["action"] == "subagent" and p["effect"] == "allow"]
        self.assertEqual(subagent_allows, ["a-synthetic", "z-synthetic"])
        for name in ("a-synthetic", "z-synthetic"):
            rules = config["agents"][name]["permissions"]
            self.assertEqual(rules[0], {"action": "*", "resource": "*", "effect": "deny"})
            self.assertEqual(rules[1], READ_ALL)
            self.assertIn(gen.rule("glob", "*", "allow"), rules)
            self.assertIn(gen.rule("grep", "*", "allow"), rules)
            self.assertIn(gen.rule("skill", "*", "allow"), rules)
            for secret_rule in gen.SECRET_READ_RULES:
                self.assertIn(secret_rule, rules)

    def test_render_handles_empty_and_populated_agent_directories(self):
        root = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, root)
        agents = root / ".opencode" / "agents"
        agents.mkdir(parents=True)
        for ci in (False, True):
            with self.subTest(ci=ci, agents=0):
                text = gen.render(root, ci)
                self.assertEqual(json.loads(text),
                                 gen.build([], ci))
                self.assertTrue(text.endswith("\n"))
        (agents / "aurevia-z.md").write_text("synthetic\n")
        (agents / "aurevia-a.md").write_text("synthetic\n")
        for ci in (False, True):
            with self.subTest(ci=ci, agents=2):
                config = json.loads(gen.render(root, ci))
                self.assertEqual(set(config["agents"]),
                                 {gen.PRIMARY, "aurevia-a", "aurevia-z"})


class ReadJsonCoverageTests(unittest.TestCase):
    """Cover the success and both failure shapes of the policy-file reader."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def test_valid_json_is_returned_without_violation(self):
        path = self.tmp / "valid.json"
        path.write_text('{"agents": {"a": 1}}')
        violations = []
        self.assertEqual(gen._read_json(path, "label", violations), {"agents": {"a": 1}})
        self.assertEqual(violations, [])

    def test_missing_file_adds_a_labelled_violation(self):
        violations = []
        result = gen._read_json(self.tmp / "absent.json", "swarm slots", violations)
        self.assertIsNone(result)
        self.assertEqual(len(violations), 1)
        self.assertIn("swarm slots", violations[0])
        self.assertIn("missing or not valid JSON", violations[0])

    def test_malformed_json_adds_a_labelled_violation(self):
        path = self.tmp / "bad.json"
        path.write_text("not json")
        violations = []
        self.assertIsNone(gen._read_json(path, "runtime profile", violations))
        self.assertTrue(any("runtime profile" in v and "not valid JSON" in v
                            for v in violations), violations)


class RuntimeCoverageTests(unittest.TestCase):
    """Cover every branch of check_runtime with a synthetic policy profile."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        (self.tmp / ".github" / "swarm").mkdir(parents=True)
        self.profile = {
            "instructions": list(gen.INSTRUCTIONS),
            "model": None,
            "agents": {
                "editor": {"permissions": [dict(SHELL_ALL)]},
                "reviewer": {"permissions": [catch_all_deny(), dict(READ_ALL)]},
            },
        }
        self.registry = {"models": {ALLOWED_MODEL: dict(ZERO_COST)}}
        for name in gen.INSTRUCTIONS:
            target = self.tmp / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text("synthetic instruction\n")

    def write(self):
        """Persist the synthetic profile and model registry into the temporary root."""
        (self.tmp / gen.RUNTIME_PROFILE).write_text(json.dumps(self.profile))
        (self.tmp / gen.MODEL_REGISTRY).write_text(json.dumps(self.registry))

    def violations(self):
        """Write the synthetic files and return check_runtime's findings."""
        self.write()
        return gen.check_runtime(self.tmp)

    def editor(self):
        return self.profile["agents"]["editor"]

    def reviewer(self):
        return self.profile["agents"]["reviewer"]

    def test_missing_profile_and_registry_accumulate_both_violations(self):
        found = gen.check_runtime(self.tmp)
        self.assertEqual(len(found), 2)
        self.assertTrue(any("runtime profile" in v for v in found), found)
        self.assertTrue(any("model registry" in v for v in found), found)

    def test_missing_registry_alone_returns_early(self):
        self.write()
        (self.tmp / gen.MODEL_REGISTRY).unlink()
        found = gen.check_runtime(self.tmp)
        self.assertEqual(len(found), 1)
        self.assertIn("model registry", found[0])

    def test_clean_synthetic_profile_has_no_violations(self):
        self.assertEqual(self.violations(), [])

    def test_missing_and_empty_instruction_lists(self):
        missing = gen.INSTRUCTIONS[0]
        (self.tmp / missing).unlink()
        self.assertTrue(any(missing in v and "instruction" in v
                            for v in self.violations()), self.violations())
        self.profile["instructions"] = []
        self.assertFalse(any("instruction" in v for v in self.violations()))

    def test_absent_optional_profile_keys_are_treated_as_empty(self):
        self.profile.pop("instructions")
        self.profile.pop("agents")
        self.assertEqual(self.violations(), [])

    def test_a_registry_without_a_models_mapping_fails_closed(self):
        self.registry.pop("models")
        found = self.violations()
        self.assertTrue(any("model registry" in v for v in found), found)

    def test_a_non_mapping_models_entry_fails_closed(self):
        self.registry["models"] = ["opencode-go/hy4-flash-free"]
        found = self.violations()
        self.assertTrue(any("model registry" in v for v in found), found)

    def test_model_declaration_branches(self):
        self.reviewer()["model"] = "other/expensive-model"
        found = self.violations()
        self.assertTrue(any("reviewer" in v and "model" in v for v in found), found)
        self.reviewer()["model"] = ALLOWED_MODEL
        self.assertEqual(self.violations(), [])
        self.reviewer().pop("model")
        self.profile["model"] = "other/expensive-model"
        found = self.violations()
        self.assertTrue(any("runtime profile" in v and "model" in v for v in found), found)
        self.profile["model"] = None
        self.assertEqual(self.violations(), [])

    def test_reviewer_permission_branches(self):
        self.reviewer()["permissions"].append(
            {"action": "write", "resource": "src/*", "effect": "allow"})
        found = self.violations()
        self.assertTrue(any("reviewer" in v and "edit permission" in v for v in found), found)
        self.reviewer()["permissions"][-1] = {"action": "read", "resource": "src/*",
                                              "effect": "allow"}
        self.assertEqual(self.violations(), [])
        self.reviewer()["permissions"][-1] = {"action": "write", "resource": "src/*",
                                              "effect": "deny"}
        self.assertEqual(self.violations(), [])
        self.reviewer()["permissions"][-1] = {"action": "shell", "resource": "*",
                                              "effect": "allow"}
        found = self.violations()
        self.assertTrue(any("reviewer" in v and "edit permission" in v for v in found), found)
        self.reviewer().pop("permissions")
        found = self.violations()
        self.assertTrue(any("reviewer" in v and "no bash permission list" in v
                            for v in found), found)

    def test_editor_bash_list_branches(self):
        self.editor().pop("permissions")
        found = self.violations()
        self.assertTrue(any("editor" in v and "no bash permission list" in v
                            for v in found), found)
        self.editor()["permissions"] = [{"resource": "*", "effect": "allow"}]
        found = self.violations()
        self.assertTrue(any("editor" in v and "no bash permission list" in v
                            for v in found), found)
        self.editor()["permissions"] = [dict(SHELL_ALL)]
        self.assertEqual(self.violations(), [])

    def test_write_protection_for_every_protected_file(self):
        for resource, protected in (
            (".github/workflows/delivery.yml", ".github/"),
            (".opencode/config/MEMORY.md", ".opencode/config/MEMORY.md"),
            (".opencode/agents/example.md", ".opencode/"),
        ):
            with self.subTest(resource=resource):
                self.editor()["permissions"] = [dict(SHELL_ALL),
                                                 {"action": "write", "resource": resource,
                                                  "effect": "allow"}]
                found = self.violations()
                self.assertTrue(any("editor" in v and protected in v for v in found), found)

    def test_write_permission_guards(self):
        self.editor()["permissions"] = [dict(SHELL_ALL),
                                        {"action": "edit", "resource": ".opencode/config/MEMORY.md",
                                         "effect": "allow"}]
        self.assertTrue(any(".opencode/config/MEMORY.md" in v for v in self.violations()))
        self.editor()["permissions"] = [dict(SHELL_ALL),
                                        {"action": "write", "resource": ".opencode/config/MEMORY.md",
                                         "effect": "deny"}]
        self.assertEqual(self.violations(), [])
        self.editor()["permissions"] = [dict(SHELL_ALL),
                                        {"action": "read", "resource": ".opencode/config/MEMORY.md",
                                         "effect": "allow"}]
        self.assertEqual(self.violations(), [])
        self.editor()["permissions"] = [dict(SHELL_ALL),
                                        {"action": "write", "effect": "allow"}]
        self.assertEqual(self.violations(), [])
        self.editor()["permissions"] = [dict(SHELL_ALL),
                                        {"action": "write", "resource": "src/*",
                                         "effect": "allow"}]
        self.assertEqual(self.violations(), [])

    def test_all_secret_patterns_in_agents_and_profile_metadata(self):
        examples = (
            ("api key/token variable", {"note": "SYNTHETIC_SERVICE_KEY"}),
            ("GitHub token", {"note": "ghp_" + "a" * 24}),
            ("GitHub fine-grained token", {"note": "github_pat_" + "b" * 24}),
            ("service API key", {"note": "sk-" + "c" * 24}),
            ("AWS access key", {"note": "AK" + "IA" + "D" * 16}),
        )
        for label, fields in examples:
            with self.subTest(where="agent", label=label):
                self.editor().update(fields)
                found = self.violations()
                self.assertTrue(any("editor" in v and label in v for v in found), found)
                for key in fields:
                    self.editor().pop(key)
            with self.subTest(where="profile", label=label):
                self.profile.update(fields)
                found = self.violations()
                self.assertTrue(any("runtime profile" in v and label in v
                                    for v in found), found)
                for key in fields:
                    self.profile.pop(key)
        self.assertEqual(self.violations(), [])

    def test_repository_runtime_profile_is_clean(self):
        self.assertEqual(gen.check_runtime(ROOT), [])


class MainCoverageTests(unittest.TestCase):
    """Cover main()'s --check-runtime, --check and generate modes."""

    def test_check_runtime_flag_success_and_failure(self):
        err = io.StringIO()
        with patch.object(gen, "check_runtime", return_value=[]):
            with redirect_stderr(err):
                self.assertEqual(gen.main(["--check-runtime"]), 0)
        self.assertEqual(err.getvalue(), "")
        err = io.StringIO()
        with patch.object(gen, "check_runtime", return_value=["first", "second"]):
            with redirect_stderr(err):
                self.assertEqual(gen.main(["--check-runtime"]), 1)
        self.assertIn("first", err.getvalue())
        self.assertIn("second", err.getvalue())

    def test_generate_mode_writes_both_profiles(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        for name in gen.OUTPUTS:
            (tmp / name).unlink()
        with patch.object(gen, "ROOT", tmp):
            self.assertEqual(gen.main([]), 0)
        for name, ci in gen.OUTPUTS.items():
            self.assertEqual((tmp / name).read_text(), gen.render(tmp, ci))

    def test_check_mode_clean_stale_and_missing(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        # copy_pack copies the worktree config, which a worker may hold as the
        # CI profile under skip-worktree. Regenerate both outputs so "clean"
        # means generated-by-this-script rather than a worker's current file.
        for name, ci in gen.OUTPUTS.items():
            (tmp / name).write_text(gen.render(tmp, ci))
        with patch.object(gen, "ROOT", tmp), redirect_stderr(io.StringIO()) as err:
            self.assertEqual(gen.main(["--check"]), 0)
        self.assertEqual(err.getvalue(), "")
        (tmp / ".opencode/opencode.json").write_text("stale\n")
        with patch.object(gen, "ROOT", tmp), redirect_stderr(io.StringIO()) as err:
            self.assertEqual(gen.main(["--check"]), 1)
        self.assertIn("opencode.json is out of date", err.getvalue())
        (tmp / ".opencode/opencode.ci.json").unlink()
        with patch.object(gen, "ROOT", tmp), redirect_stderr(io.StringIO()) as err:
            self.assertEqual(gen.main(["--check"]), 1)
        self.assertIn(".opencode/opencode.ci.json is out of date", err.getvalue())

    def test_script_entry_point_exits_cleanly(self):
        with patch.object(sys, "argv", ["gen-opencode.py", "--check-runtime"]):
            with self.assertRaises(SystemExit) as raised:
                runpy.run_path(str(ROOT / "scripts/gen-opencode.py"), run_name="__main__")
        self.assertEqual(raised.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
