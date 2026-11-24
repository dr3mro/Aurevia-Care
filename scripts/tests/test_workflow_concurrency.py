"""Workflow concurrency groups superseded runs without starving later ones.

Most workflows queue every trigger with ``queue: max`` and keep cancellation
off, so a frequent trigger cannot replace pending work and starve it. Delivery
is the deliberate exception: two runs of the same workflow on the same branch
are redundant, so a newer one cancels the older one instead of either queueing
behind it or running it in parallel. GitHub rejects ``queue: max`` together
with ``cancel-in-progress: true`` at validation time, so Delivery declares no
queue. Its group is scoped by repository and branch (PR source branch for pull
requests, otherwise the pushed ref), which keeps different branches and forks
independent. Cancelling a run on main means that exact SHA gets no verdict, so
branch protection must be configured with that trade-off in mind.
"""
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


class WorkflowConcurrencyTest(unittest.TestCase):
    def workflows(self):
        return sorted(WORKFLOWS.glob("*.yml"))

    def test_every_queuing_workflow_declares_a_queueing_concurrency_group(self):
        """Every workflow except Delivery queues triggers instead of cancelling."""
        paths = [p for p in self.workflows() if p.name != "delivery.yml"]
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(workflow=path.name):
                config = load(path)
                self.assertIn("concurrency", config)
                concurrency = config["concurrency"]
                self.assertTrue(concurrency.get("group"))
                # GitHub validates the pair: queue:max with cancellation of a
                # running run fails the whole workflow before a job is created.
                self.assertIs(concurrency.get("cancel-in-progress"), False)
                # Without queue:max GitHub keeps a single pending run and
                # replaces it with each newer trigger, so a frequently
                # dispatched workflow can be starved indefinitely (the 18
                # unmerged green PRs incident).
                self.assertEqual(concurrency.get("queue"), "max")

    def test_no_queueing_workflow_asks_to_cancel_a_running_run(self):
        """GitHub rejects queue:max with cancel-in-progress: true at load time."""
        for path in self.workflows():
            with self.subTest(workflow=path.name):
                concurrency = load(path)["concurrency"]
                if concurrency.get("queue") == "max":
                    self.assertIs(concurrency.get("cancel-in-progress"), False)

    def test_swarm_waves_overlap_with_a_group_per_run(self):
        """Waves run side by side; the plan job and the 20-worker cap keep them safe."""
        concurrency = load(WORKFLOWS / "swarm.yml")["concurrency"]
        self.assertIn("github.run_id", concurrency["group"])

    def test_delivery_groups_runs_by_repository_and_branch(self):
        """A newer run on the same branch cancels the older one, not other branches."""
        group = load(WORKFLOWS / "delivery.yml")["concurrency"]["group"]
        self.assertEqual(group, "${{ github.workflow }}-${{ github.repository }}-"
                                "${{ github.head_ref || github.ref }}")

    def test_delivery_reports_a_check_for_every_pull_request(self):
        """A bare trigger covers every PR; a filter map would omit the check."""
        trigger = load(WORKFLOWS / "delivery.yml")[True]["pull_request"]
        # PyYAML parses `pull_request:` with no value as None. Any path filter
        # would become a mapping and leave some PR heads without Delivery gate.
        self.assertIsNone(trigger)

    def test_delivery_reports_a_check_for_every_branch_and_tag_push(self):
        """A path-filtered push also leaves the exact-SHA gate unreported."""
        trigger = load(WORKFLOWS / "delivery.yml")[True]["push"]
        self.assertIsInstance(trigger, dict)
        self.assertNotIn("paths", trigger)
        self.assertNotIn("paths-ignore", trigger)

    def test_delivery_cancels_superseded_runs_on_the_same_branch(self):
        """Duplicate runs of the workflow on one branch must not overlap."""
        concurrency = load(WORKFLOWS / "delivery.yml")["concurrency"]
        self.assertIs(concurrency["cancel-in-progress"], True)
        # GitHub rejects queue:max together with cancellation at load time.
        self.assertNotIn("queue", concurrency)


if __name__ == "__main__":
    unittest.main()
