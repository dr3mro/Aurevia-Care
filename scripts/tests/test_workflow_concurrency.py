"""Workflow concurrency must report a verdict for every commit that can merge.

Branch protection checks the exact SHA, so a push to main or a tag must never
cancel an older run: that SHA would receive no verdict and unverified code
could land on the protected branch. GitHub also rejects ``queue: max`` together
with ``cancel-in-progress: true`` at workflow validation time, before any job
starts, so every queueing workflow must keep cancellation off. A pull request
is different -- only its current head can be merged -- so Delivery groups PR
runs by head SHA: a new head starts in its own group instead of waiting behind
a stale head, while main, tag, dispatch and merge-group runs stay serialized
per ref and keep every exact-SHA verdict.
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

    def test_delivery_groups_pull_requests_by_head_sha_and_other_events_by_ref(self):
        """A new PR head starts at once; other triggers stay serialized per ref."""
        group = load(WORKFLOWS / "delivery.yml")["concurrency"]["group"]
        self.assertEqual(group, "${{ github.workflow }}-${{ "
                         "github.event_name == 'pull_request' && "
                         "github.event.pull_request.head.sha || github.ref }}")

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

    def test_delivery_never_cancels_a_running_run(self):
        """Cancellation would fail validation with queue:max and lose a verdict."""
        cancel = load(WORKFLOWS / "delivery.yml")["concurrency"]["cancel-in-progress"]
        self.assertIs(cancel, False)


if __name__ == "__main__":
    unittest.main()
