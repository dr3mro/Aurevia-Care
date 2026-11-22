"""Unit tests for recovering ordinary draft pull requests in the swarm.

Every pull request, label and Delivery run here is a synthetic fixture: no network,
credentials, token or real GitHub data is read or written.
"""
import json
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")

HEAD = "c" * 40


def draft(number=7, title="WQUALITY-02: Recover drafts", labels=(), head=HEAD, **extra):
    """Build one synthetic open swarm draft as returned by ``gh pr list``."""
    info = {"number": number, "title": title, "isDraft": True, "isCrossRepository": False,
            "labels": [{"name": n} for n in ("swarm", *labels)], "headRefOid": head}
    info.update(extra)
    return info


def delivery_run(sha=HEAD, conclusion="success", **extra):
    """Build one synthetic Delivery workflow run record."""
    return {"headSha": sha, "status": "completed", "conclusion": conclusion, **extra}


class DraftRecoveryTest(unittest.TestCase):
    """draft_recovery classifies a swarm draft so the plan step can act on it."""

    def classify(self, info, state="passed", runs=None):
        with mock.patch.object(swarm, "delivery_state", return_value=state) as delivered, \
             mock.patch.object(swarm, "delivery_runs", return_value=runs or []) as listed:
            decision = swarm.draft_recovery(info, runs=runs or [])
        return decision, delivered, listed

    def test_recoverable_draft_is_marked_ready_and_queued(self):
        # Delivery passed on the current head and no blocking label: recover it.
        runs = [delivery_run()]
        decision, delivered, _ = self.classify(draft(), state="passed", runs=runs)
        self.assertEqual(decision, swarm.DRAFT_RECOVER)
        delivered.assert_called_once_with(runs, HEAD)

    def test_still_failing_draft_goes_to_a_fix_worker(self):
        decision, _, _ = self.classify(draft(), state="failed")
        self.assertEqual(decision, swarm.DRAFT_FIX)

    def test_reconciler_draft_is_left_for_a_person(self):
        info = draft(title="Notes 2026-11-22: instruction updates for review")
        decision, delivered, _ = self.classify(info, state="passed")
        self.assertEqual(decision, swarm.DRAFT_LEAVE)
        delivered.assert_not_called()

    def test_labelled_draft_is_untouched(self):
        for label in ("needs-fix", "needs-human"):
            with self.subTest(label=label):
                decision, delivered, _ = self.classify(draft(labels=(label,)), state="passed")
                self.assertEqual(decision, swarm.DRAFT_LEAVE)
                delivered.assert_not_called()

    def test_repair_draft_is_untouched(self):
        for info in (draft(title="FIX-42: Repair main"),
                     draft(title="DLV-02: Repair main"),
                     draft(labels=("autofix",))):
            with self.subTest(title=info["title"], labels=info["labels"]):
                decision, delivered, _ = self.classify(info, state="passed")
                self.assertEqual(decision, swarm.DRAFT_LEAVE)
                delivered.assert_not_called()

    def test_waiting_draft_is_left_alone(self):
        for state in ("pending", "missing", "approval-required"):
            with self.subTest(state=state):
                decision, _, _ = self.classify(draft(), state=state)
                self.assertEqual(decision, swarm.DRAFT_LEAVE)

    def test_delivery_runs_are_fetched_when_not_supplied(self):
        # The plan step reuses a shared listing; a standalone call fetches its own runs.
        with mock.patch.object(swarm, "delivery_runs", return_value=[delivery_run()]) as listed, \
             mock.patch.object(swarm, "delivery_state", return_value="passed"):
            decision = swarm.draft_recovery(draft())
        self.assertEqual(decision, swarm.DRAFT_RECOVER)
        listed.assert_called_once_with(HEAD)


class PlanDraftTest(unittest.TestCase):
    """The integrator's plan step acts on a draft's recovery decision."""

    def plan(self, drafts, decision):
        other = {"number": 8, "title": "CW-002: Ordinary", "isDraft": False,
                 "labels": [{"name": "swarm"}], "headRefOid": "d" * 40,
                 "isCrossRepository": False}
        prs = [*drafts, other]
        fake = mock.Mock(return_value=json.dumps(prs))
        with mock.patch.object(swarm, "gh", fake), \
             mock.patch.object(swarm, "draft_recovery", return_value=decision) as recovery, \
             mock.patch.object(swarm, "fail") as failed, \
             mock.patch.object(swarm, "add_labels") as added:
            queue = swarm.plan_integration()
        return queue, fake, recovery, failed, added

    def test_recovered_draft_is_marked_ready_and_queued(self):
        queue, fake, recovery, failed, added = self.plan([draft()], swarm.DRAFT_RECOVER)
        self.assertIn(7, queue)
        self.assertIn(8, queue)
        self.assertTrue(any(c.args[:2] == ("pr", "ready") for c in fake.call_args_list))
        recovery.assert_called_once()
        failed.assert_not_called()
        added.assert_not_called()

    def test_failing_draft_goes_to_a_fix_worker_and_is_not_queued(self):
        queue, fake, _, failed, added = self.plan([draft()], swarm.DRAFT_FIX)
        self.assertEqual(queue, [8])
        failed.assert_called_once()
        added.assert_not_called()
        self.assertFalse(any(c.args[:2] == ("pr", "ready") for c in fake.call_args_list))

    def test_left_draft_is_not_queued_or_touched(self):
        for decision in (swarm.DRAFT_LEAVE,):
            with self.subTest(decision=decision):
                queue, fake, _, failed, added = self.plan([draft()], decision)
                self.assertEqual(queue, [8])
                failed.assert_not_called()
                added.assert_not_called()
                self.assertFalse(any(c.args[:2] == ("pr", "ready") for c in fake.call_args_list))

    def test_draft_with_a_blocking_label_is_skipped_before_recovery(self):
        info = draft(labels=("needs-human",))
        prs = [info]
        fake = mock.Mock(return_value=json.dumps(prs))
        with mock.patch.object(swarm, "gh", fake), \
             mock.patch.object(swarm, "draft_recovery") as recovery:
            self.assertEqual(swarm.plan_integration(), [])
        recovery.assert_not_called()


if __name__ == "__main__":
    unittest.main()
