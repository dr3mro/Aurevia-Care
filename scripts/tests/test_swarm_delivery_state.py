"""Unit tests for the integrator's Delivery evidence classifier and gate.

All data is synthetic: these records model what ``gh run list`` and ``gh pr``
return, with no network, credentials or real pull requests involved.
"""
import json
import unittest
from unittest import mock

from helpers import load

swarm = load("swarm")

HEAD = "a" * 40
OLD = "b" * 40


def delivery_run(sha=HEAD, status="completed", conclusion="success", jobs=None, database_id=7, **extra):
    """Build one synthetic Delivery workflow run record."""
    record = {"headSha": sha, "status": status, "conclusion": conclusion}
    if database_id is not None:
        record["databaseId"] = database_id
    if jobs is not None:
        record["jobs"] = jobs
    record.update(extra)
    return record


class DeliveryStateTest(unittest.TestCase):
    """Every one of the five states, the SHA rule, supersession and the docs rule."""

    def test_passed_when_newest_run_succeeded(self):
        runs = [delivery_run(conclusion="success"), delivery_run(conclusion="failure")]
        self.assertEqual(swarm.delivery_state(runs, HEAD), "passed")

    def test_failed_for_failure_timeout_and_cancelled(self):
        for conclusion in ("failure", "timed_out", "cancelled"):
            with self.subTest(conclusion=conclusion):
                self.assertEqual(
                    swarm.delivery_state([delivery_run(conclusion=conclusion)], HEAD), "failed")

    def test_pending_for_queued_and_in_progress(self):
        for status, conclusion in (("queued", None), ("in_progress", None), ("completed", "neutral")):
            with self.subTest(status=status, conclusion=conclusion):
                record = delivery_run(status=status, conclusion=conclusion)
                self.assertEqual(swarm.delivery_state([record], HEAD), "pending")

    def test_missing_when_no_run_tested_the_head(self):
        self.assertEqual(swarm.delivery_state([delivery_run(sha=OLD)], HEAD), "missing")

    def test_missing_when_there_are_no_runs_at_all(self):
        self.assertEqual(swarm.delivery_state(None, HEAD), "missing")
        self.assertEqual(swarm.delivery_state([], HEAD), "missing")

    def test_approval_required_when_run_waits_for_approval(self):
        for status in ("waiting", "action_required"):
            with self.subTest(status=status):
                record = delivery_run(status=status, conclusion=None)
                self.assertEqual(swarm.delivery_state([record], HEAD), "approval-required")

    def test_approval_required_when_run_conclusion_is_action_required(self):
        record = delivery_run(conclusion="action_required")
        self.assertEqual(swarm.delivery_state([record], HEAD), "approval-required")

    def test_approval_required_when_a_job_waits_for_an_environment_gate(self):
        for job in ({"status": "waiting", "conclusion": None},
                    {"status": "in_progress", "conclusion": "action_required"}):
            with self.subTest(job=job):
                record = delivery_run(status="in_progress", conclusion=None, jobs=[job])
                self.assertEqual(swarm.delivery_state([record], HEAD), "approval-required")

    def test_job_level_approval_inspects_only_dicts(self):
        record = delivery_run(status="in_progress", conclusion=None,
                              jobs=["malformed", {"status": "completed", "conclusion": None}])
        self.assertEqual(swarm.delivery_state([record], HEAD), "pending")

    def test_no_jobs_means_no_environment_gate(self):
        record = delivery_run(status="in_progress", conclusion=None)
        self.assertEqual(swarm.delivery_state([record], HEAD), "pending")

    def test_rerun_supersedes_an_earlier_failure(self):
        runs = [delivery_run(conclusion="success"), delivery_run(conclusion="failure")]
        self.assertEqual(swarm.delivery_state(runs, HEAD), "passed")
        # Newest first is the contract: reversing the list makes the stale run win.
        self.assertEqual(swarm.delivery_state(list(reversed(runs)), HEAD), "failed")

    def test_skipped_run_has_no_verification_evidence(self):
        # The documentation exception is applied by the gate from the changed
        # files, never from a bare "skipped" label which can have other causes.
        record = delivery_run(conclusion="skipped", status="completed")
        self.assertEqual(swarm.delivery_state([record], HEAD), "missing")

    def test_synthetic_head_sha_key_and_malformed_records(self):
        runs = ["not a run", {"head_sha": HEAD, "status": "completed", "conclusion": "success"},
                {"status": "completed", "conclusion": "failure"}]
        self.assertEqual(swarm.delivery_state(runs, HEAD), "passed")


class MarkdownOnlyTest(unittest.TestCase):
    """The documented exception must not swallow a real code change."""

    def test_docs_and_markdown_paths(self):
        self.assertTrue(swarm.markdown_only(["docs/operations/runbook.md", "README.md"]))

    def test_empty_change_list_is_not_documentation(self):
        self.assertFalse(swarm.markdown_only([]))
        self.assertFalse(swarm.markdown_only([""]))
        self.assertTrue(swarm.markdown_only(["", "docs/a.md"]))

    def test_code_change_breaks_the_exception(self):
        self.assertFalse(swarm.markdown_only(["docs/a.md", "scripts/swarm.py"]))


class DeliveryRunsTest(unittest.TestCase):
    """The run listing is filtered by commit and enriched with job-level gates."""

    def gh_for(self, runs, jobs=None):
        def fake(*args, check=True):
            if args[:2] == ("run", "list"):
                return json.dumps(runs)
            if args[:2] == ("run", "view"):
                return json.dumps({"jobs": jobs or []})
            raise AssertionError(f"unexpected gh call: {args}")
        return mock.Mock(side_effect=fake)

    def test_newest_matching_run_gets_its_jobs_and_gate(self):
        runs = [delivery_run(jobs=None), delivery_run(sha=OLD)]
        with mock.patch.object(swarm, "gh", self.gh_for(
                runs, jobs=[{"status": "waiting", "conclusion": None}])) as gh:
            enriched = swarm.delivery_runs(HEAD)
        self.assertEqual(swarm.delivery_state(enriched, HEAD), "approval-required")
        self.assertTrue(any(c.args[:2] == ("run", "view") for c in gh.call_args_list))

    def test_no_runs_returns_empty_list_without_a_job_lookup(self):
        with mock.patch.object(swarm, "gh", self.gh_for(None)) as gh:
            self.assertEqual(swarm.delivery_runs(HEAD), [])
        self.assertFalse(any(c.args[:2] == ("run", "view") for c in gh.call_args_list))

    def test_no_matching_run_does_not_look_up_jobs(self):
        with mock.patch.object(swarm, "gh", self.gh_for([delivery_run(sha=OLD)])) as gh:
            self.assertEqual(swarm.delivery_runs(HEAD), [delivery_run(sha=OLD)])
        self.assertFalse(any(c.args[:2] == ("run", "view") for c in gh.call_args_list))

    def test_run_without_database_id_is_not_enriched(self):
        record = delivery_run(database_id=None)
        with mock.patch.object(swarm, "gh", self.gh_for([record])) as gh:
            self.assertEqual(swarm.delivery_runs(HEAD), [record])
        self.assertFalse(any(c.args[:2] == ("run", "view") for c in gh.call_args_list))

    def test_empty_jobs_payload_is_treated_as_no_jobs(self):
        record = delivery_run()
        with mock.patch.object(swarm, "gh", self.gh_for([record], jobs=None)):
            enriched = swarm.delivery_runs(HEAD)
        self.assertEqual(enriched[0]["jobs"], [])

    def test_malformed_listing_entries_are_ignored_by_the_classifier(self):
        record = delivery_run()
        with mock.patch.object(swarm, "gh", self.gh_for(["malformed", record])):
            enriched = swarm.delivery_runs(HEAD)
        self.assertEqual(swarm.delivery_state(enriched, HEAD), "passed")


class DeliveryGateTest(unittest.TestCase):
    """The integrator policy: merge, wait, fix, or request a fresh run."""

    def gate(self, state, files="apps/api/A.java\n", head=HEAD, runs=None, gh=None, **kwargs):
        with mock.patch.object(swarm, "delivery_runs", return_value=runs or []) as listed, \
             mock.patch.object(swarm, "delivery_state", return_value=state), \
             mock.patch.object(swarm, "gh", gh or mock.Mock(return_value="")), \
             mock.patch.object(swarm, "fail") as failed:
            decision = swarm.delivery_gate(7, head=head, **kwargs)
        return decision, listed, failed

    def test_passed_merges_without_asking_github_for_anything(self):
        with mock.patch.object(swarm, "delivery_runs") as listed, \
             mock.patch.object(swarm, "gh") as gh, mock.patch.object(swarm, "fail") as failed:
            self.assertEqual(swarm.delivery_gate(7, HEAD, runs=[delivery_run()]), "merge")
        listed.assert_not_called()
        gh.assert_not_called()
        failed.assert_not_called()

    def test_failed_goes_to_the_fix_workers_with_exact_run_evidence(self):
        with mock.patch.object(swarm, "delivery_runs", return_value=[]), \
             mock.patch.object(swarm, "gh", return_value="FAIL: synthetic_test") as gh, \
             mock.patch.object(swarm, "fail") as failed:
            self.assertEqual(swarm.delivery_gate(7, HEAD, runs=[delivery_run(conclusion="failure")]), "fix")
        gh.assert_called_once_with("run", "view", "7", "--log-failed", check=False)
        self.assertEqual(failed.call_args.args, (7, "Delivery failed on the current head"))
        self.assertTrue(failed.call_args.kwargs["simple"])
        self.assertIn(HEAD, failed.call_args.kwargs["evidence"])
        self.assertIn("synthetic_test", failed.call_args.kwargs["evidence"])

    def test_pending_waits_without_a_label(self):
        with mock.patch.object(swarm, "delivery_runs", return_value=[]), \
             mock.patch.object(swarm, "gh") as gh, mock.patch.object(swarm, "fail") as failed:
            record = delivery_run(status="in_progress", conclusion=None)
            self.assertEqual(swarm.delivery_gate(7, HEAD, runs=[record]), "wait")
        gh.assert_not_called()
        failed.assert_not_called()

    def test_missing_docs_only_change_uses_the_documentation_exception(self):
        gh = mock.Mock(return_value="docs/runbook.md\nREADME.md\n")
        with mock.patch.object(swarm, "delivery_runs", return_value=[]), \
             mock.patch.object(swarm, "gh", gh), mock.patch.object(swarm, "fail") as failed:
            self.assertEqual(swarm.delivery_gate(7, HEAD), "merge")
        failed.assert_not_called()
        gh.assert_any_call("pr", "diff", "7", "--name-only")

    def test_skipped_run_on_markdown_only_change_uses_the_documentation_exception(self):
        gh = mock.Mock(return_value="docs/runbook.md\nREADME.md\n")
        with mock.patch.object(swarm, "gh", gh), mock.patch.object(swarm, "fail") as failed:
            self.assertEqual(
                swarm.delivery_gate(7, HEAD, runs=[delivery_run(conclusion="skipped")]), "merge")
        failed.assert_not_called()

    def test_skipped_run_on_code_change_requests_a_run(self):
        def fake(*args, check=True):
            if args[:2] == ("pr", "diff"):
                return "scripts/swarm.py\n"
            if args[:2] == ("pr", "view") and "--json" in args:
                return json.dumps({"headRefName": "swarm/WQUALITY-01-1"})
            return ""
        with mock.patch.object(swarm, "gh", side_effect=fake) as gh:
            self.assertEqual(
                swarm.delivery_gate(7, HEAD, runs=[delivery_run(conclusion="skipped")]), "request")
        self.assertTrue(any(c.args[:2] == ("workflow", "run") for c in gh.call_args_list))

    def test_missing_code_change_requests_a_run_and_never_merges(self):
        def fake(*args, check=True):
            if args[:2] == ("pr", "diff"):
                return "scripts/swarm.py\n"
            if args[:2] == ("pr", "view") and "--json" in args:
                return json.dumps({"headRefName": "swarm/WQUALITY-01-1"})
            return ""
        with mock.patch.object(swarm, "delivery_runs", return_value=[]), \
             mock.patch.object(swarm, "gh", side_effect=fake) as gh, \
             mock.patch.object(swarm, "fail") as failed:
            self.assertEqual(swarm.delivery_gate(7, HEAD), "request")
        failed.assert_not_called()
        self.assertTrue(any(c.args[:4] == ("workflow", "run", "delivery.yml", "--ref")
                            and c.args[4] == "swarm/WQUALITY-01-1" for c in gh.call_args_list))
        self.assertTrue(any(c.args[:2] == ("pr", "comment") for c in gh.call_args_list))

    def test_approval_required_requests_a_run_and_never_merges(self):
        def fake(*args, check=True):
            if args[:2] == ("pr", "view") and "--json" in args:
                return json.dumps({"headRefName": "swarm/WQUALITY-01-1"})
            return ""
        with mock.patch.object(swarm, "delivery_runs", return_value=[]), \
             mock.patch.object(swarm, "gh", side_effect=fake) as gh:
            self.assertEqual(swarm.delivery_gate(7, HEAD, runs=[delivery_run(status="waiting")]), "request")
        self.assertTrue(any(c.args[:2] == ("workflow", "run") for c in gh.call_args_list))

    def test_head_and_branch_are_fetched_when_not_supplied(self):
        def fake(*args, check=True):
            if args[:2] == ("pr", "view") and "--json" in args:
                key = "headRefOid" if "headRefOid" in args else "headRefName"
                return json.dumps({key: HEAD if key == "headRefOid" else "swarm/WQUALITY-01-1"})
            return ""
        with mock.patch.object(swarm, "delivery_runs",
                               return_value=[delivery_run(status="waiting")]) as listed, \
             mock.patch.object(swarm, "gh", side_effect=fake):
            self.assertEqual(swarm.delivery_gate(7), "request")
        listed.assert_called_once_with(HEAD)

    def test_missing_pr_fields_fall_back_to_empty_strings(self):
        with mock.patch.object(swarm, "delivery_runs", return_value=[]), \
             mock.patch.object(swarm, "gh", return_value="") as gh:
            self.assertEqual(swarm.delivery_gate(7, HEAD, runs=[delivery_run(status="waiting")]), "request")
        self.assertTrue(any(c.args[:2] == ("workflow", "run") for c in gh.call_args_list))


class IntegratorWiringTest(unittest.TestCase):
    """The CLI integrator requires current-head evidence before staging a merge."""

    def test_delivery_verdict_blocks_staging(self):
        fake = mock.Mock(return_value=json.dumps({"headRefOid": HEAD}))
        with mock.patch.object(swarm, "gh", fake), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "is_repair", return_value=False), \
             mock.patch.object(swarm, "delivery_gate", return_value="wait") as gate, \
             mock.patch.object(swarm, "merge_local") as merged, \
             mock.patch.object(swarm, "migrate_tasks", return_value=0), \
             mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_batch([7], check_delivery=True), 0)
        gate.assert_called_once_with(7, HEAD)
        merged.assert_not_called()

    def test_merge_verdict_allows_staging(self):
        fake = mock.Mock(return_value=json.dumps({"headRefOid": HEAD}))
        with mock.patch.object(swarm, "gh", fake), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "is_repair", return_value=False), \
             mock.patch.object(swarm, "delivery_gate", return_value="merge"), \
             mock.patch.object(swarm, "migrate_tasks", return_value=0), \
             mock.patch.object(swarm, "merge_local", return_value=(HEAD, "WQUALITY-01", [])), \
             mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
             mock.patch.object(swarm, "gate_for", return_value=("gate.sh", {})), \
             mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
             mock.patch.object(swarm, "record_merges",
                               return_value=([(7, "WQUALITY-01", HEAD)], ["comment"], [])), \
             mock.patch.object(swarm, "close_merged_issues"), \
             mock.patch.object(swarm, "git"), mock.patch.object(swarm, "authed_git"), \
             mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_batch([7], check_delivery=True), 1)

    def test_ci_integrator_requires_evidence_without_changing_the_cli_contract(self):
        with mock.patch.dict(swarm.os.environ, {"GITHUB_ACTIONS": "true"}), \
             mock.patch.object(swarm, "gh", return_value=json.dumps({"headRefOid": HEAD})), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "is_repair", return_value=False), \
             mock.patch.object(swarm, "delivery_gate", return_value="request") as gate, \
             mock.patch.object(swarm, "merge_local") as merged, \
             mock.patch.object(swarm, "migrate_tasks", return_value=0), \
             mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_batch([7]), 0)
        gate.assert_called_once_with(7, HEAD)
        merged.assert_not_called()

    def test_local_merge_mechanics_caller_has_no_ci_evidence_to_check(self):
        with mock.patch.dict(swarm.os.environ, {}, clear=True), \
             mock.patch.object(swarm, "gh", return_value=json.dumps({"headRefOid": HEAD})), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "is_repair", return_value=False), \
             mock.patch.object(swarm, "delivery_gate") as gate, \
             mock.patch.object(swarm, "merge_local", return_value=(HEAD, "WQUALITY-01", [])), \
             mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
             mock.patch.object(swarm, "migrate_tasks", return_value=0), \
             mock.patch.object(swarm, "gate_for", return_value=("gate.sh", {})), \
             mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
             mock.patch.object(swarm, "record_merges",
                               return_value=([(7, "WQUALITY-01", HEAD)], ["comment"], [])), \
             mock.patch.object(swarm, "close_merged_issues"), \
             mock.patch.object(swarm, "git"), mock.patch.object(swarm, "authed_git"), \
             mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_batch([7]), 1)
        gate.assert_not_called()

    def test_explicit_false_overrides_ci_default(self):
        with mock.patch.dict(swarm.os.environ, {"GITHUB_ACTIONS": "true"}), \
             mock.patch.object(swarm, "gh", return_value=json.dumps({"headRefOid": HEAD})), \
             mock.patch.object(swarm, "guard", return_value=[]), \
             mock.patch.object(swarm, "is_repair", return_value=False), \
             mock.patch.object(swarm, "delivery_gate") as gate, \
             mock.patch.object(swarm, "merge_local", return_value=(HEAD, "WQUALITY-01", [])), \
             mock.patch.object(swarm, "refresh_wiring_baseline", return_value=""), \
             mock.patch.object(swarm, "migrate_tasks", return_value=0), \
             mock.patch.object(swarm, "gate_for", return_value=("gate.sh", {})), \
             mock.patch.object(swarm, "run_gate", return_value=(True, "ok")), \
             mock.patch.object(swarm, "record_merges",
                               return_value=([(7, "WQUALITY-01", HEAD)], ["comment"], [])), \
             mock.patch.object(swarm, "close_merged_issues"), \
             mock.patch.object(swarm, "git"), mock.patch.object(swarm, "authed_git"), \
             mock.patch.object(swarm, "emit"):
            self.assertEqual(swarm.integrate_batch([7], check_delivery=False), 1)
        gate.assert_not_called()

    def test_cli_keeps_its_documented_call_contract(self):
        with mock.patch.object(swarm, "integrate_batch") as batch:
            swarm.main(["integrate-batch", "--queue", "[7]"])
        batch.assert_called_once_with([7], "scripts/quick-check.sh")


if __name__ == "__main__":
    unittest.main()
