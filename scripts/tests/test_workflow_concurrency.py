"""Only Delivery is branch-scoped; every other workflow stays concurrent.

Delivery keeps every run on a branch in one queue, so a re-pushed pull request
head still receives its own exact-SHA verdict. Other workflows use a group
unique to each run, so parallel runs, matrix workers and scheduled jobs never
cancel or queue behind one another. GitHub rejects ``queue: max`` together with
cancellation, so a queuing workflow keeps cancellation off. The in-scope operator
guide and workflow skill mirror this policy: stale instructions have repeatedly
reintroduced a filter or a cancellation that left the required check unreported.
The swarm efficiency baseline is owned by the integrator and is filed separately.
"""
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"
SKILL = ROOT / ".opencode" / "skills" / "aurevia-github-workflow" / "SKILL.md"
TESTING_GUIDE = ROOT / "docs" / "reference" / "testing-guide.md"
DELIVERY_GROUP = "${{ github.workflow }}-${{ github.repository }}-${{ github.head_ref || github.ref }}"


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


def read_text(path):
    """Read an in-scope document as UTF-8 for the policy consistency checks."""
    return path.read_text(encoding="utf-8")


class WorkflowConcurrencyTest(unittest.TestCase):
    def workflows(self):
        return sorted(WORKFLOWS.glob("*.yml"))

    def test_delivery_queues_one_run_per_branch_without_cancelling(self):
        concurrency = load(WORKFLOWS / "delivery.yml")["concurrency"]
        self.assertEqual(concurrency["group"], DELIVERY_GROUP)
        self.assertIs(concurrency["cancel-in-progress"], False)
        self.assertEqual(concurrency["queue"], "max")

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


class DeliveryCheckContractTest(unittest.TestCase):
    """The gate is unconditional and its documented policy matches the workflow."""

    def test_delivery_gate_is_unconditional_and_depends_on_all_jobs(self):
        gate = load(WORKFLOWS / "delivery.yml")["jobs"]["gate"]
        self.assertEqual(gate["name"], "Delivery gate")
        self.assertEqual(gate["if"], "always()")
        self.assertEqual(
            gate["needs"],
            ["policy", "script-tests", "frontend", "java-verify", "docs", "verify", "release"],
        )

    def test_workflow_comments_match_the_queueing_policy(self):
        workflow = read_text(WORKFLOWS / "delivery.yml")
        self.assertNotIn("a newer run cancels", workflow)
        self.assertNotIn("no queue is declared", workflow)
        self.assertIn("queue:max keeps up to 100 pending runs", workflow)
        self.assertIn("Delivery queues and that other workflows declare no queue", workflow)

    def test_skill_documents_the_live_concurrency_group_without_cancellation(self):
        skill = read_text(SKILL)
        self.assertIn("- Concurrency groups that must not drop a waiting run", skill)
        # Derive the expected expression from the workflow: a one-sided edit to
        # either file leaves the required check unreachable and must fail here.
        group = load(WORKFLOWS / "delivery.yml")["concurrency"]["group"]
        self.assertIn(group, skill)
        self.assertNotIn("head.sha", skill)
        self.assertNotIn("per-SHA", skill)

    def test_testing_guide_describes_the_current_delivery_triggers(self):
        guide = read_text(TESTING_GUIDE)
        self.assertIn("Delivery gate", guide)
        self.assertIn("queue: max", guide)
        self.assertNotIn("paths-ignore", guide)
