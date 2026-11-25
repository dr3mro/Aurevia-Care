"""Required gates queue exact revisions; independent workflows stay per-run."""
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"
DELIVERY_GROUP = "${{ github.event.pull_request.head.sha || github.ref }}"
PLAN_GROUP = "swarm-plan-${{ github.repository }}"
INTEGRATOR_GROUP = "swarm-integrator-${{ github.repository }}"
SWARM_GROUP = "swarm-${{ github.ref }}"


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def revision_group(pr_sha, ref):
    """Model the Delivery expression: PR head when present, otherwise ref."""
    return pr_sha or ref


class WorkflowConcurrencyTest(unittest.TestCase):
    def workflows(self):
        return sorted(WORKFLOWS.glob("*.yml"))

    def assert_queue(self, concurrency, group):
        self.assertEqual(concurrency["group"], group)
        self.assertIs(concurrency["cancel-in-progress"], False)
        self.assertEqual(concurrency["queue"], "max")

    def test_delivery_queues_each_pr_head_and_ref_without_cancelling(self):
        self.assert_queue(load(WORKFLOWS / "delivery.yml")["concurrency"], DELIVERY_GROUP)
        self.assertEqual(revision_group("head-a", "refs/heads/main"), "head-a")
        self.assertEqual(revision_group("", "refs/heads/main"), "refs/heads/main")
        self.assertNotEqual(revision_group("head-a", "refs/heads/main"),
                            revision_group("head-b", "refs/heads/main"))

    def test_other_workflows_run_concurrently_per_run(self):
        for path in self.workflows():
            if path.name in {"delivery.yml", "swarm-integrator.yml", "swarm.yml"}:
                continue
            with self.subTest(workflow=path.name):
                concurrency = load(path)["concurrency"]
                self.assertIn("github.run_id", concurrency["group"])
                self.assertIs(concurrency["cancel-in-progress"], False)
                self.assertNotIn("queue", concurrency)

    def test_swarm_workers_have_a_group_per_slot(self):
        group = load(WORKFLOWS / "swarm-worker.yml")["concurrency"]["group"]
        self.assertIn("github.run_id", group)
        self.assertIn("inputs.slot", group)

    def test_swarm_allows_one_wave_per_branch(self):
        self.assert_queue(load(WORKFLOWS / "swarm.yml")["concurrency"], SWARM_GROUP)

    def test_swarm_plan_serializes_all_waves_in_the_repository(self):
        plan = load(WORKFLOWS / "swarm.yml")["jobs"]["plan"]["concurrency"]
        self.assert_queue(plan, PLAN_GROUP)

    def test_integrator_serializes_called_and_dispatched_runs(self):
        workflow = load(WORKFLOWS / "swarm-integrator.yml")
        self.assert_queue(workflow["concurrency"], INTEGRATOR_GROUP)
        self.assertIn("workflow_call", workflow[True])
        self.assertIn("workflow_dispatch", workflow[True])

    def test_serialized_groups_reject_legacy_keys_and_invalid_queue(self):
        blocks = {
            "delivery": load(WORKFLOWS / "delivery.yml")["concurrency"],
            "plan": load(WORKFLOWS / "swarm.yml")["jobs"]["plan"]["concurrency"],
            "integrator": load(WORKFLOWS / "swarm-integrator.yml")["concurrency"],
        }
        for name, concurrency in blocks.items():
            with self.subTest(name=name):
                self.assertNotIn("github.run_id", concurrency["group"])
                self.assertNotIn("github.head_ref", concurrency["group"])
                self.assertEqual(concurrency["queue"], "max")
                self.assertIs(concurrency["cancel-in-progress"], False)

    def test_delivery_reports_a_check_for_every_pull_request(self):
        trigger = load(WORKFLOWS / "delivery.yml")[True]["pull_request"]
        self.assertIsNone(trigger)

    def test_delivery_reports_a_check_for_every_branch_and_tag_push(self):
        trigger = load(WORKFLOWS / "delivery.yml")[True]["push"]
        self.assertIsInstance(trigger, dict)
        self.assertNotIn("paths", trigger)
        self.assertNotIn("paths-ignore", trigger)
