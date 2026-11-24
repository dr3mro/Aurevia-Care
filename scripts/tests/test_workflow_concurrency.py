"""Every workflow keeps one run per workflow per branch.

A newer trigger on the same branch cancels the older run instead of overlapping
it or queueing behind it. GitHub rejects ``queue: max`` together with
``cancel-in-progress: true`` at validation time, so no concurrency group
declares a queue. The group includes the repository so forks with the same
branch name stay independent. Cancelling a run on main means that exact SHA
gets no verdict, so branch protection must be configured with that trade-off.
"""
import unittest

import yaml

from helpers import ROOT

WORKFLOWS = ROOT / ".github" / "workflows"
GROUP = "${{ github.workflow }}-${{ github.repository }}-${{ github.head_ref || github.ref }}"


def load(path):
    return yaml.safe_load(path.read_text(encoding="utf-8"))


class WorkflowConcurrencyTest(unittest.TestCase):
    def workflows(self):
        return sorted(WORKFLOWS.glob("*.yml"))

    def test_every_workflow_has_one_branch_scoped_cancelling_group(self):
        paths = self.workflows()
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(workflow=path.name):
                concurrency = load(path)["concurrency"]
                self.assertEqual(concurrency["group"], GROUP)
                self.assertIs(concurrency["cancel-in-progress"], True)
                # GitHub rejects queue:max together with cancellation at load time.
                self.assertNotIn("queue", concurrency)

    def test_job_level_concurrency_uses_the_same_branch_group(self):
        """A job-level group must not reintroduce a second overlapping run."""
        jobs = load(WORKFLOWS / "swarm.yml")["jobs"]
        checks = 0
        for job in jobs.values():
            if "concurrency" not in job:
                continue
            checks += 1
            concurrency = job["concurrency"]
            self.assertEqual(concurrency["group"], GROUP)
            self.assertIs(concurrency["cancel-in-progress"], True)
            self.assertNotIn("queue", concurrency)
        self.assertGreater(checks, 0)

    def test_delivery_reports_a_check_for_every_pull_request(self):
        """A bare trigger covers every PR; a filter map would omit the check."""
        trigger = load(WORKFLOWS / "delivery.yml")[True]["pull_request"]
        self.assertIsNone(trigger)

    def test_delivery_reports_a_check_for_every_branch_and_tag_push(self):
        """A path-filtered push also leaves the exact-SHA gate unreported."""
        trigger = load(WORKFLOWS / "delivery.yml")[True]["push"]
        self.assertIsInstance(trigger, dict)
        self.assertNotIn("paths", trigger)
        self.assertNotIn("paths-ignore", trigger)
