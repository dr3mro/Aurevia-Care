"""Workflow concurrency must report a verdict for every commit that can merge.

Branch protection checks the exact SHA, so a push to main or a tag must never
cancel an older run: that SHA would receive no verdict and unverified code
could land on the protected branch. A pull request is different -- only its
current head can be merged, and an older head is never merged. Queueing a new
head behind a stale, in-progress run leaves the required ``Delivery gate``
check unreported ("Expected -- Waiting for status to be reported") and blocks
the PR while the stale work finishes. Delivery therefore cancels stale runs
only for pull_request events and still queues every other trigger.
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

    def test_every_workflow_declares_a_queueing_concurrency_group(self):
        paths = self.workflows()
        self.assertTrue(paths)
        for path in paths:
            with self.subTest(workflow=path.name):
                config = load(path)
                self.assertIn("concurrency", config)
                concurrency = config["concurrency"]
                self.assertTrue(concurrency.get("group"))
                if path.name == "delivery.yml":
                    self.assertEqual(concurrency.get("cancel-in-progress"),
                                     "${{ github.event_name == 'pull_request' }}")
                else:
                    self.assertIs(concurrency.get("cancel-in-progress"), False)
                # For every non-cancelling trigger, cancel-in-progress:false
                # protects only the running run. Without queue:max GitHub keeps
                # a single pending run and replaces it with each newer trigger,
                # so a frequently dispatched workflow can be starved indefinitely
                # (the 18 unmerged green PRs incident). PRs are the exception:
                # they cancel stale runs so the current head reports promptly.
                self.assertEqual(concurrency.get("queue"), "max")

    def test_swarm_waves_overlap_with_a_group_per_run(self):
        """Waves run side by side; the plan job and the 20-worker cap keep them safe."""
        concurrency = load(WORKFLOWS / "swarm.yml")["concurrency"]
        self.assertIn("github.run_id", concurrency["group"])

    def test_delivery_is_scoped_per_ref_not_per_sha(self):
        """Per-ref groups serialize; per-SHA groups would run fully in parallel."""
        group = load(WORKFLOWS / "delivery.yml")["concurrency"]["group"]
        self.assertIn("github.ref", group)
        self.assertNotIn("github.sha", group)

    def test_delivery_reports_a_check_for_every_pull_request(self):
        """A path-filtered PR never runs Delivery, so the required check is absent."""
        trigger = load(WORKFLOWS / "delivery.yml")[True]["pull_request"]
        if trigger is not None:
            self.assertIsInstance(trigger, dict)
            self.assertNotIn("paths", trigger)
            self.assertNotIn("paths-ignore", trigger)

    def test_delivery_does_not_cancel_verified_branch_pushes(self):
        """Main and tag pushes keep every verdict; only stale PR runs are cancelled."""
        cancel = load(WORKFLOWS / "delivery.yml")["concurrency"]["cancel-in-progress"]
        self.assertIsInstance(cancel, str)
        self.assertIn("github.event_name == 'pull_request'", cancel)


if __name__ == "__main__":
    unittest.main()
