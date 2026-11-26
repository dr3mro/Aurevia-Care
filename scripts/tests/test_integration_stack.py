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


class OptionalArtifactTests(unittest.TestCase):
    def test_every_trail_download_tolerates_a_missing_artifact(self):
        import yaml
        from helpers import ROOT
        for name in ("swarm.yml", "swarm-worker.yml", "swarm-integrator.yml"):
            doc = yaml.safe_load((ROOT / ".github/workflows" / name).read_text())
            for job in doc["jobs"].values():
                for step in job.get("steps", []):
                    uses = str(step.get("uses", ""))
                    if uses.startswith("actions/download-artifact@"):
                        with self.subTest(workflow=name, step=step.get("name", uses)):
                            self.assertIs(step.get("continue-on-error"), True)

    def test_optional_artifacts_are_downloaded_by_pattern_not_by_name(self):
        """A missing pattern match exits 0; a missing name calls core.setFailed.

        Producers such as ``Allocate tasks (SM)`` are skipped when no feature is
        selected, so their artifact never exists.  Downloading it by ``name``
        makes ``actions/download-artifact`` emit a red ``##[error]`` annotation
        even when ``continue-on-error`` keeps the job green.  A glob pattern
        that matches nothing logs a normal "0 artifact(s) downloaded" instead.
        """
        import yaml
        from helpers import ROOT

        # Job id, artifact and destination path are pinned so a future edit
        # cannot silently relocate the trail while still using a harmless
        # pattern. With one match the action extracts directly into `path`:
        # swarm.yml and swarm-worker.yml keep `.agent/work-trail.jsonl`,
        # swarm-integrator.yml keeps `trails/trail-allocation/work-trail.jsonl`
        # and `validated/manifest.json`.
        expected = (
            ("swarm.yml", "allocate", "trail-feature", ".agent"),
            ("swarm.yml", "loop", "trail-allocation", ".agent"),
            ("swarm-worker.yml", "work", "trail-allocation", ".agent"),
            ("swarm-integrator.yml", "validate", "trail-allocation", "trails/trail-allocation"),
            ("swarm-integrator.yml", "publish", "validated-batch", "validated"),
        )
        for filename, job_name, artifact, path in expected:
            with self.subTest(workflow=filename, job=job_name, artifact=artifact):
                doc = yaml.safe_load((ROOT / ".github/workflows" / filename).read_text())
                steps = doc["jobs"][job_name]["steps"]
                step = next(
                    step
                    for step in steps
                    if str(step.get("uses", "")).startswith("actions/download-artifact@")
                    and artifact in (
                        (step.get("with") or {}).get("pattern"),
                        (step.get("with") or {}).get("name"),
                    )
                )
                self.assertEqual(
                    step["uses"],
                    "actions/download-artifact@9000827ccba6bdab643e8b6fd33ac0654aef8333",
                )
                with_options = step["with"]
                self.assertEqual(with_options.get("pattern"), artifact)
                self.assertNotIn("name", with_options)
                # A literal pattern matches at most one artifact, so the action
                # extracts it straight into `path` without a subdirectory.
                self.assertFalse(any(char in artifact for char in "*?["))
                self.assertNotIn("merge-multiple", with_options)
                self.assertEqual(with_options.get("path"), path)
                self.assertIs(step.get("continue-on-error"), True)
