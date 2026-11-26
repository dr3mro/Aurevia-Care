"""Stacked integration plans keep worker order and never skip a base."""
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from helpers import load

stack = load("integration_stack")


class IntegrationStackTests(unittest.TestCase):
    def test_trail_stages_keep_record_order_and_skip_malformed_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "work-trail.jsonl"
            path.write_text(
                '{"step":"feature"}\n'
                "not json\n"
                '{"step":"allocation"}\n'
                '{"step":"feature"}\n'
                '{"step":"brief"}\n', encoding="utf-8")
            self.assertEqual(stack.trail_stages(path), ("feature", "allocation", "brief"))

    def test_missing_trail_gives_empty_stages(self):
        self.assertEqual(stack.trail_stages(Path("/does/not/exist")), ())

    def test_stack_plan_bases_each_pr_on_the_previous_branch(self):
        plan = stack.stack_plan(["w1", "w2", "w3"], "main", stages=("brief",))
        self.assertEqual([item["base"] for item in plan], ["main", "w1", "w2"])
        self.assertEqual([item["position"] for item in plan], [1, 2, 3])
        self.assertTrue(all(item["stages"] == ["brief"] for item in plan))

    def test_empty_wave_plans_nothing(self):
        self.assertEqual(stack.stack_plan([], "main"), ())


class StackWiringTests(unittest.TestCase):
    def test_integrator_publishes_a_stack_plan(self):
        import yaml
        from helpers import ROOT
        workflow = yaml.safe_load((ROOT / ".github/workflows/swarm-integrator.yml").read_text())
        steps = workflow["jobs"]["validate"]["steps"]
        plan = next(step for step in steps if step.get("name", "").startswith("Plan stacked"))
        self.assertIn("from scripts.integration_stack import stack_plan", plan["run"])
        artifact = next(step for step in steps if step.get("name") == "Upload validated batch"
                        or step.get("with", {}).get("name") == "validated-batch")
        self.assertIn("validated", artifact["with"]["path"])
        self.assertIn("validated/stack-plan.json", plan["run"])

    def test_agent_run_exposes_integrate_stack(self):
        from helpers import ROOT
        source = (ROOT / "scripts/agent-run.sh").read_text()
        self.assertIn("cmd_integrate_stack()", source)
        self.assertIn("integrate-stack) cmd_integrate_stack", source)


class StackCliTests(unittest.TestCase):
    def test_main_prints_a_stacked_plan(self):
        import contextlib
        import io
        import json
        import runpy
        import sys
        from helpers import ROOT
        argv = ["integration_stack.py", "--base", "main", "--trail", "/nonexistent",
                "--branches", "w1", "w2"]
        out = io.StringIO()
        with mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(out):
            runpy.run_path(str(ROOT / "scripts/integration_stack.py"), run_name="__main__")
        plan = json.loads(out.getvalue())
        self.assertEqual([item["base"] for item in plan], ["main", "w1"])


class TrailOrderingTests(unittest.TestCase):
    def test_integrator_merges_trail_before_building_stacks(self):
        import yaml
        from helpers import ROOT
        steps = yaml.safe_load((ROOT / ".github/workflows/swarm-integrator.yml").read_text())["jobs"]["validate"]["steps"]
        names = [step.get("name", "") for step in steps]
        self.assertLess(names.index("Merge worker trails for the integrator"),
                        names.index("Build and gate stacked branches"))
        self.assertLess(names.index("Build and gate stacked branches"),
                        names.index("Plan stacked pull requests from the validated batch"))


class AcceptedQueueTests(unittest.TestCase):
    def test_integrator_uses_accepted_prs_not_the_full_queue(self):
        from helpers import ROOT
        source = (ROOT / ".github/workflows/swarm-integrator.yml").read_text()
        self.assertIn('ACCEPTED: ${{ steps.candidate.outputs.accepted }}', source)
        self.assertIn('os.environ["ACCEPTED"]', source)
        self.assertNotIn('os.environ["QUEUE"]', source.split("Plan stacked pull requests")[0])
        plan = source.split("Plan stacked pull requests")[1]
        self.assertIn("ACCEPTED: ${{ steps.candidate.outputs.accepted }}", plan)


class AgentRunBranchTests(unittest.TestCase):
    def test_stack_outcomes_are_recorded(self):
        import os
        import subprocess
        from helpers import ROOT
        fixture = ROOT / "scripts/tests/test_agent_run.py"
        self.assertTrue(fixture.is_file())
        source = (ROOT / "scripts/agent-run.sh").read_text()
        for token in ("missing-ref %s", "conflict", "failed", "green", "BRANCHES:-"):
            self.assertIn(token, source)
