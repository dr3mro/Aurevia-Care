"""Coverage for the uncovered branches of the swarm delivery/CLI slice.

This file exercises the delivery-listing fallback and the worker-only failure
early return in ``scripts/swarm.py`` (assigned range lines 2450-2800). All
fixtures are synthetic and every ``gh``/``gh_json`` call is replaced by a local
fake, so the tests never touch the network or a real repository.
"""
import json
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")


class Gh:
    """Record gh calls; answer the run-view queries the two covered helpers make."""

    def __init__(self, run=None):
        self.calls = []
        self.run = run or {}

    def __call__(self, *args, check=True):
        self.calls.append(args)
        if args[:2] == ("run", "view"):
            return json.dumps(self.run)
        return ""

    def said(self, *needle):
        return any(all(n in " ".join(map(str, call)) for n in needle) for call in self.calls)


class DeliveryListingCoverageTests(unittest.TestCase):
    """The shared Delivery listing converts a non-list gh answer to an empty list."""

    def test_a_list_answer_is_returned_with_the_shared_query(self):
        listing = [{"headSha": "a", "conclusion": "success"}]
        with mock.patch.object(swarm, "gh_json", return_value=listing) as gh:
            self.assertEqual(swarm.delivery_listing(), listing)
        gh.assert_called_once_with(
            "run", "list", "--workflow", "Delivery",
            "--limit", str(swarm.DELIVERY_LISTING_LIMIT),
            "--json", "databaseId,headSha,status,conclusion")

    def test_a_non_list_answer_becomes_an_empty_list(self):
        # gh can answer an object (for example an error envelope); a whole pr-flow
        # pass must still have a well-formed empty listing to iterate.
        with mock.patch.object(swarm, "gh_json", return_value={"message": "rate limited"}):
            self.assertEqual(swarm.delivery_listing(), [])


class RepairTaskWorkerOnlyCoverageTests(unittest.TestCase):
    """A run that failed only in worker jobs is routine and needs no repair task."""

    def run_of(self):
        return {"name": "Agent swarm", "headBranch": "main", "headSha": "a" * 40,
                "conclusion": "failure", "url": "https://example.invalid/runs/9",
                "jobs": [
                    {"name": "Plan the wave", "conclusion": "success"},
                    {"name": "Swarm worker (story, CW-001, 0, t / Work the task", "conclusion": "failure"},
                ]}

    def test_worker_only_failure_returns_none_without_fetching_the_log(self):
        fake = Gh(run=self.run_of())
        with mock.patch.object(swarm, "gh_json", return_value=self.run_of()), \
                mock.patch.object(swarm, "gh", fake):
            self.assertIsNone(swarm.repair_task("9"))
        self.assertFalse(fake.said("--log-failed"))


if __name__ == "__main__":
    unittest.main()
