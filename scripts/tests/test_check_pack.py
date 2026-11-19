import shutil
import unittest

from helpers import ROOT, copy_pack, load

pack = load("check-pack")
gen = load("gen-opencode")


class PackTests(unittest.TestCase):
    def test_repository_pack_is_valid(self):
        self.assertEqual(pack.check(ROOT), [])

    def test_committed_opencode_json_is_generated(self):
        self.assertEqual((ROOT / "opencode.json").read_text(), gen.render(ROOT))

    def test_missing_skill_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        shutil.rmtree(tmp / ".opencode/skills/smartclinic-debugger")
        errors = pack.check(tmp)
        self.assertTrue(any("smartclinic-debugger" in e for e in errors))

    def test_wrong_mode_and_stale_config_are_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        agent = tmp / ".opencode/agents/smartclinic-debugger.md"
        agent.write_text(agent.read_text().replace("subagent", "primary"))
        (tmp / "opencode.json").write_text(
            (tmp / "opencode.json").read_text().replace('"skill"', '"edit"', 1))
        errors = pack.check(tmp)
        self.assertTrue(any("mode must be subagent" in e for e in errors))
        self.assertTrue(any("stale" in e for e in errors))

    def test_frontmatter_parser_rejects_garbage(self):
        self.assertIsNone(pack.frontmatter("no block"))
        self.assertIsNone(pack.frontmatter("---\nbroken line\n---\n"))
        self.assertEqual(pack.frontmatter('---\na: "b"\n---\n'), {"a": "b"})


class GeneratorTests(unittest.TestCase):
    def test_force_push_is_denied_and_env_files_unreadable(self):
        config = gen.build(["smartclinic-orchestrator", "smartclinic-qa-reviewer"])
        rules = config["agents"]["smartclinic-orchestrator"]["permissions"]
        self.assertIn({"action": "shell", "resource": "git push*--force*", "effect": "deny"}, rules)
        for agent in config["agents"].values():
            self.assertIn({"action": "read", "resource": "*.env", "effect": "deny"},
                          agent["permissions"])

    def test_extra_hard_denies_are_present(self):
        """Each safety deny must appear in both local and CI permission sets."""
        patterns = [
            "sudo*", "rm -rf /*", "rm -rf ~*",
            "curl*|*sh*", "wget*|*sh*",
            "git push*:*", "git remote set-url*",
        ]
        for ci in (False, True):
            config = gen.build(
                ["smartclinic-orchestrator", "smartclinic-qa-reviewer"], ci=ci)
            rules = config["agents"]["smartclinic-orchestrator"]["permissions"]
            for pattern in patterns:
                self.assertIn(
                    {"action": "shell", "resource": pattern, "effect": "deny"},
                    rules, f"missing deny for {pattern!r} in CI={ci}",
                )

    def test_deny_rules_follow_allow_rules(self):
        """Last match wins, so denies must come after the broad allows."""
        rules = gen.build(["smartclinic-orchestrator"])["agents"][
            "smartclinic-orchestrator"]["permissions"]
        shell = [r for r in rules if r["action"] == "shell"]
        self.assertEqual(shell[0]["effect"], "allow")
        self.assertLess(max(i for i, r in enumerate(shell) if r["effect"] == "allow"),
                        min(i for i, r in enumerate(shell) if r["effect"] == "deny"))

    def test_check_mode_detects_drift(self):
        self.assertEqual(gen.main(["--check"]), 0)


if __name__ == "__main__":
    unittest.main()
