"""Only Delivery is branch-scoped; every other workflow stays concurrent.

Delivery keeps one run per workflow per branch: a newer trigger cancels the
older run, so the newest commit on a branch is the only one still building.
Other workflows use a group unique to each run, so parallel runs, matrix
workers and scheduled jobs never cancel or queue behind one another. GitHub
rejects ``queue: max`` together with cancellation, so Delivery declares no
queue; a cancelled exact SHA on main receives no verdict.
"""
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"
DELIVERY_GROUP = "${{ github.workflow }}-${{ github.repository }}-${{ github.head_ref || github.ref }}"


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


class WorkflowConcurrencyTest(unittest.TestCase):
    def workflows(self):
        return sorted(WORKFLOWS.glob("*.yml"))

    def test_delivery_keeps_one_cancelling_run_per_branch(self):
        concurrency = load(WORKFLOWS / "delivery.yml")["concurrency"]
        self.assertEqual(concurrency["group"], DELIVERY_GROUP)
        self.assertIs(concurrency["cancel-in-progress"], True)
        self.assertNotIn("queue", concurrency)

    def test_other_workflows_run_concurrently_per_run(self):
        for path in self.workflows():
            if path.name == "delivery.yml":
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

    def test_swarm_plan_keeps_its_own_run_scoped_group(self):
        jobs = load(WORKFLOWS / "swarm.yml")["jobs"]
        plan = jobs["plan"]["concurrency"]
        self.assertIn("github.run_id", plan["group"])
        self.assertIs(plan["cancel-in-progress"], False)
        self.assertNotIn("queue", plan)

    def test_delivery_reports_a_check_for_every_pull_request(self):
        trigger = load(WORKFLOWS / "delivery.yml")[True]["pull_request"]
        self.assertIsNone(trigger)

    def test_delivery_reports_a_check_for_every_branch_and_tag_push(self):
        trigger = load(WORKFLOWS / "delivery.yml")[True]["push"]
        self.assertIsInstance(trigger, dict)
        self.assertNotIn("paths", trigger)
        self.assertNotIn("paths-ignore", trigger)
