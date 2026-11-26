import contextlib
import io
import json
import runpy
import shutil
import unittest

from helpers import ROOT, copy_pack, load

pack = load("check-pack")
gen = load("gen-opencode")


class PackTests(unittest.TestCase):
    def test_repository_pack_is_valid(self):
        self.assertEqual(pack.check(ROOT), [])

    def test_committed_opencode_json_is_generated(self):
        self.assertEqual((ROOT / ".opencode/opencode.json").read_text(), gen.render(ROOT))

    def test_missing_skill_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        shutil.rmtree(tmp / ".opencode/skills/aurevia-debugger")
        errors = pack.check(tmp)
        self.assertTrue(any("aurevia-debugger" in e for e in errors))

    def test_wrong_mode_and_stale_config_are_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        agent = tmp / ".opencode/agents/debugger.md"
        agent.write_text(agent.read_text().replace("subagent", "primary"))
        (tmp / ".opencode/opencode.json").write_text(
            (tmp / ".opencode/opencode.json").read_text().replace('"skill"', '"edit"', 1))
        errors = pack.check(tmp)
        self.assertTrue(any("mode must be subagent" in e for e in errors))
        self.assertTrue(any("stale" in e for e in errors))

    def test_frontmatter_parser_rejects_garbage(self):
        self.assertIsNone(pack.frontmatter("no block"))
        self.assertIsNone(pack.frontmatter("---\nbroken line\n---\n"))
        self.assertEqual(pack.frontmatter('---\na: "b"\n---\n'), {"a": "b"})

    def test_agent_file_not_in_opencode_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / ".opencode/agents/aurevia-ghost.md").write_text(
            '---\ndescription: "ghost"\nmode: subagent\n---\n\n'
            "Load aurevia-delivery. Behave.\n")
        errors = pack.check(tmp)
        self.assertTrue(any("aurevia-ghost.md" in e and "not in opencode.json" in e
                            for e in errors), errors)

    def test_configured_agent_with_no_file_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / ".opencode/agents/debugger.md").unlink()
        errors = pack.check(tmp)
        self.assertTrue(any("debugger" in e and "no file" in e
                            for e in errors), errors)

    def test_unknown_agent_in_a_workflow_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        workflows = tmp / ".github/workflows"
        workflows.mkdir(exist_ok=True)
        (workflows / "swarm.yml").write_text(
            "jobs:\n  select:\n    run: opencode run --agent aurevia-product-owner\n",
            encoding="utf-8")
        errors = pack.check(tmp)
        self.assertTrue(any("aurevia-product-owner" in e and "no file" in e
                            for e in errors), errors)

    def test_unknown_run_agent_and_skill_delegation_are_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        script = tmp / "scripts/agent-run.sh"
        script.write_text(script.read_text(encoding="utf-8") +
                          '\nrun_agent ghost-agent "work" /tmp/log\n'
                          "ask aurevia-code-reviewer to review\n", encoding="utf-8")
        errors = pack.check(tmp)
        self.assertTrue(any("ghost-agent" in e and "no file" in e
                            for e in errors), errors)
        self.assertTrue(any("ask aurevia-code-reviewer" in e and "skill" in e
                            for e in errors), errors)

    def test_missing_skill_reference_names_agent_file(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        shutil.rmtree(tmp / ".opencode/skills/aurevia-debugger")
        errors = pack.check(tmp)
        self.assertTrue(any("debugger.md" in e and "skill" in e
                            for e in errors), errors)

    def test_skill_without_skill_md_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / ".opencode/skills/aurevia-debugger/SKILL.md").unlink()
        errors = pack.check(tmp)
        self.assertTrue(any("aurevia-debugger/SKILL.md is missing" in e
                            for e in errors), errors)

    def test_duplicate_agent_name_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        path = tmp / ".opencode/opencode.json"
        text = path.read_text()
        needle = '"orca": {'
        self.assertIn(needle, text)
        path.write_text(text.replace(needle, needle + '}, "orca": {', 1))
        errors = pack.check(tmp)
        self.assertTrue(any("duplicate agent name orca" in e
                            and "opencode.json" in e for e in errors), errors)

    def test_missing_agent_for_named_role_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        primary = tmp / ".opencode/agents/orca.md"
        primary.write_text(primary.read_text() +
                           "\nThe Tester reviews the evidence independently.\n")
        (tmp / ".opencode/agents/test-engineer.md").unlink()
        errors = pack.check(tmp)
        self.assertTrue(any("Tester" in e and "test-engineer.md" in e
                            for e in errors), errors)

    def test_default_agent_without_definition_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / ".opencode/agents/orca.md").unlink()
        errors = pack.check(tmp)
        self.assertTrue(any("default_agent" in e for e in errors), errors)

    def test_agent_without_frontmatter_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        (tmp / ".opencode/agents/debugger.md").write_text("no frontmatter\n")
        errors = pack.check(tmp)
        self.assertTrue(any("missing or malformed frontmatter" in e for e in errors), errors)

    def test_agent_without_description_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        path = tmp / ".opencode/agents/debugger.md"
        path.write_text("\n".join(
            line for line in path.read_text().splitlines()
            if not line.startswith("description:")) + "\n")
        errors = pack.check(tmp)
        self.assertTrue(any("description is required" in e for e in errors), errors)

    def test_agent_with_bad_name_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        source = (tmp / ".opencode/agents/debugger.md").read_text()
        (tmp / ".opencode/agents/aurevia-Bad.md").write_text(source)
        errors = pack.check(tmp)
        self.assertTrue(any("name must be kebab-case" in e for e in errors), errors)

    def test_orchestrator_allow_list_drift_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        path = tmp / ".opencode/opencode.json"
        config = json.loads(path.read_text())
        rules = config["agents"]["orca"]["permissions"]
        rules[:] = [r for r in rules
                    if not (r["action"] == "subagent" and r["effect"] == "allow")]
        path.write_text(json.dumps(config, indent=2) + "\n")
        errors = pack.check(tmp)
        self.assertTrue(any("allow list differs" in e for e in errors), errors)

    def test_specialist_first_rule_drift_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        path = tmp / ".opencode/opencode.json"
        config = json.loads(path.read_text())
        rules = config["agents"]["debugger"]["permissions"]
        rules[0] = {"action": "read", "resource": "*", "effect": "allow"}
        path.write_text(json.dumps(config, indent=2) + "\n")
        errors = pack.check(tmp)
        self.assertTrue(any("first rule must deny everything" in e for e in errors), errors)

    def test_skill_bad_directory_name_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        bad = tmp / ".opencode/skills/aurevia-Bad"
        bad.mkdir()
        (bad / "SKILL.md").write_text(
            '---\nname: aurevia-Bad\ndescription: "x"\n---\n')
        errors = pack.check(tmp)
        self.assertTrue(any("directory must be kebab-case" in e for e in errors), errors)

    def test_skill_with_bad_frontmatter_is_reported(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        path = tmp / ".opencode/skills/aurevia-debugger/SKILL.md"
        path.write_text("\n".join(
            "name: aurevia-other" if line.startswith("name:") else line
            for line in path.read_text().splitlines()) + "\n")
        errors = pack.check(tmp)
        self.assertTrue(any("frontmatter needs matching name" in e for e in errors), errors)


    def edit_skill(self, name, change):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        path = tmp / f".opencode/skills/{name}/SKILL.md"
        path.write_text(change(path.read_text()))
        return pack.check(tmp)

    def test_skill_description_without_use_when_is_reported(self):
        errors = self.edit_skill("aurevia-debugger", lambda t: t.replace("Use when", "Needed if", 1))
        self.assertTrue(any("must say when to use it" in e for e in errors), errors)

    def test_skill_description_over_the_limit_is_reported(self):
        errors = self.edit_skill("aurevia-debugger", lambda t: t.replace(
            "Use when", "x" * pack.SKILL_DESCRIPTION_LIMIT + " Use when", 1))
        self.assertTrue(any("over 1024 characters" in e for e in errors), errors)

    def test_process_skill_without_each_section_is_reported(self):
        for heading in pack.PROCESS_SECTIONS:
            with self.subTest(heading=heading):
                errors = self.edit_skill("aurevia-test-engineer",
                                         lambda t: t.replace(f"## {heading}", "### " + heading))
                self.assertTrue(any(f'missing the section "## {heading}"' in e for e in errors), errors)

    def test_simulated_user_skills_need_no_process_sections(self):
        errors = self.edit_skill("aurevia-patient-user",
                                 lambda t: t.split("##")[0].rstrip() + "\n")
        self.assertEqual(errors, [])
        self.assertIn("aurevia-patient-user", pack.NO_PROCESS_SECTIONS)

    def test_every_skill_is_either_exempt_or_has_the_sections(self):
        skills = {p.parent.name for p in (ROOT / ".opencode/skills").glob("*/SKILL.md")}
        self.assertLessEqual(pack.NO_PROCESS_SECTIONS, skills)


    def test_system_namespace_skill_is_not_a_project_skill(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        folder = tmp / ".opencode/skills/extra-system"
        folder.mkdir()
        (folder / "SKILL.md").write_text(
            '---\nname: extra-system\ndescription: "Use when a workflow fans out"\n'
            'metadata:\n    namespace: system\n---\n\n# Extra\n')
        self.assertEqual(pack.check(tmp), [])

    def test_project_skill_still_needs_process_sections_and_an_inventory_entry(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        folder = tmp / ".opencode/skills/extra-project"
        folder.mkdir()
        (folder / "SKILL.md").write_text(
            '---\nname: extra-project\ndescription: "Use when a task needs it"\n---\n\n# Extra\n')
        errors = pack.check(tmp)
        self.assertTrue(any('missing the section "## Common rationalizations"' in e
                            for e in errors), errors)
        self.assertTrue(any("skills differ from specialists" in e for e in errors), errors)

    def test_skill_without_frontmatter_is_not_treated_as_system(self):
        tmp = copy_pack()
        self.addCleanup(shutil.rmtree, tmp)
        folder = tmp / ".opencode/skills/extra-broken"
        folder.mkdir()
        (folder / "SKILL.md").write_text("no frontmatter\n")
        errors = pack.check(tmp)
        self.assertTrue(any("frontmatter needs matching name" in e for e in errors), errors)

class GeneratorTests(unittest.TestCase):
    def test_force_push_is_denied_and_env_files_unreadable(self):
        config = gen.build(["orca", "qa-reviewer"])
        rules = config["agents"]["orca"]["permissions"]
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
                ["orca", "aurevia-qa-reviewer"], ci=ci)
            rules = config["agents"]["orca"]["permissions"]
            for pattern in patterns:
                self.assertIn(
                    {"action": "shell", "resource": pattern, "effect": "deny"},
                    rules, f"missing deny for {pattern!r} in CI={ci}",
                )

    def test_deny_rules_follow_allow_rules(self):
        """Last match wins, so denies must come after the broad allows."""
        rules = gen.build(["orca"])["agents"][
            "orca"]["permissions"]
        shell = [r for r in rules if r["action"] == "shell"]
        self.assertEqual(shell[0]["effect"], "allow")
        self.assertLess(max(i for i, r in enumerate(shell) if r["effect"] == "allow"),
                        min(i for i, r in enumerate(shell) if r["effect"] == "deny"))

    def test_check_mode_detects_drift(self):
        self.assertEqual(gen.main(["--check"]), 0)


class MainTests(unittest.TestCase):
    def test_main_reports_success(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            status = pack.main()
        self.assertEqual(status, 0)
        self.assertIn("agents,", stdout.getvalue())

    def test_main_reports_errors(self):
        original = pack.check
        pack.check = lambda: ["synthetic problem"]
        self.addCleanup(setattr, pack, "check", original)
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            status = pack.main()
        self.assertEqual(status, 1)
        self.assertIn("check-pack: synthetic problem", stderr.getvalue())

    def test_cli_entry_point(self):
        stdout = io.StringIO()
        with contextlib.redirect_stdout(stdout):
            with self.assertRaises(SystemExit) as ctx:
                runpy.run_path(str(ROOT / "scripts/check-pack.py"), run_name="__main__")
        self.assertEqual(ctx.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
