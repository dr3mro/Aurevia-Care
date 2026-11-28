"""Regression tests for the independent, fail-closed Swarm gate verifier.

Each test names the objective fact that decides the verdict. Worker-authored
evidence is inspected only for shape and revision binding; these tests prove it
can never declare a mandatory check passed.
"""
import contextlib
import io
import runpy
import json
import subprocess
import tempfile
import sys
import unittest
from pathlib import Path
from unittest import mock

from helpers import ROOT, load

gate = load("swarm_gate")
HEAD, BASE, WORKER = "a" * 40, "b" * 40, "c" * 40
EVIDENCE = ("# notes\n\n## Test evidence\nscripts/quick-check.sh passed\n\n## Coverage\n100%\n\n"
            "## Review verdicts\nGO\n\nTested revision: " + HEAD + "\n")
ANCESTOR, DIVERGED, STALE = "e" * 40, "f" * 40, "d" * 40
# The exact shape that hid a valid revision in PR #1815: the SHA is correct and
# in the head's ancestry, but the canonical line had trailing explanation.
PROSE_LINE = (f"Tested revision: {ANCESTOR} (`git rev-parse HEAD` when checks ran; "
              "reconciled before push)")


def binding_evidence(sha):
    """Return a worker note whose canonical field names one tested revision."""
    return EVIDENCE.replace(f"Tested revision: {HEAD}", f"Tested revision: {sha}")


def compare_result(status, merge_base, behind_by=0):
    """Return the bounded compare payload the trusted collector keeps."""
    return {"status": status, "merge_base": merge_base, "behind_by": behind_by}


def context(**overrides):
    """A valid worker context whose applicable checks all pass."""
    data = dict(pr_number=42, event_name="pull_request", head_ref="swarm/TASK-01-7",
                head_sha=HEAD, base_sha=BASE, tested_sha=HEAD,
                labels=(), title="TASK-01: work the story", files=("src/main.py",),
                task_text="- [-] TASK-01 · work it\n  - evidence: tool evidence\n  - pr: #42\n",
                evidence_text=EVIDENCE, required_checks=("Policy and tooling",),
                check_runs={"Policy and tooling": "success"},
                note_sha=HEAD,
                revision_evidence=compare_result("identical", HEAD),
                cross_repo_head=False,
                verified_at="2026-01-01T00:00:00Z")
    data.update(overrides)
    return gate.GateContext(**data)


def manifest_data(**overrides):
    """A valid one-worker integration manifest as read from the tested tree."""
    data = {"batch_id": "b1", "base_sha": BASE,
            "worker_prs": [{"number": 42, "head_sha": WORKER, "task_id": "TASK-01"}],
            "verification": {"passed": True}}
    data.update(overrides)
    return data


def run(**overrides):
    return gate.evaluate(context(**overrides))


def integration(**overrides):
    data = dict(head_ref="swarm/integration/b1", task_text="", evidence_text="",
                manifest=manifest_data(), ancestors={WORKER: True},
                required_checks=(), check_runs={})
    data.update(overrides)
    return run(**data)


class ClassificationTests(unittest.TestCase):
    def test_worker_branch_and_title(self):
        self.assertEqual(gate.classify_pull_request("swarm/TASK-01-7")[0], gate.WORKER)
        self.assertEqual(gate.classify_pull_request("odd/x", title="OTHER-9: fix")[0], gate.WORKER)

    def test_integration_reconcile_and_manual(self):
        self.assertEqual(gate.classify_pull_request("swarm/integration/b1")[0], gate.INTEGRATION)
        self.assertEqual(gate.classify_pull_request("swarm/reconcile/b1")[0], gate.RECONCILIATION)
        self.assertEqual(gate.classify_pull_request("feature/x", title="Add thing")[0], gate.HUMAN)

    def test_trusted_repair_pr_is_not_a_worker(self):
        title = "FIX-45920447774: correct ownership documentation path"
        self.assertEqual(gate.classify_pull_request("fix/ownership", title=title,
                         author_association="OWNER")[0], gate.HUMAN)
        self.assertEqual(gate.classify_pull_request("fix/ownership", title=title,
                         labels=("autofix",), author_association="NONE")[0], gate.HUMAN)
        self.assertEqual(gate.classify_pull_request("fix/ownership", title=title,
                         author_association="NONE")[0], gate.UNKNOWN)
        self.assertEqual(gate.classify_pull_request("fix/dlv", title="DLV-7: delivery repair",
                         author_association="OWNER")[0], gate.HUMAN)
        self.assertEqual(gate.classify_pull_request("fix/dlv", title="DLV-7: delivery repair",
                         author_association="NONE")[0], gate.UNKNOWN)
        self.assertEqual(gate.classify_pull_request("swarm/FIX-7-1", title=title,
                         author_association="OWNER")[0], gate.WORKER)

    def test_repair_can_touch_protected_paths_without_a_worker_note(self):
        result = run(head_ref="fix/ownership",
                     title="FIX-45920447774: correct ownership documentation path",
                     author_association="OWNER", files=("docs/swarm/QA-SOURCE-04.md",),
                     task_text="", evidence_text="", required_checks=(), check_runs={})
        self.assertTrue(result.passed, result.failures)
        self.assertEqual(result.check("protected_files").state, gate.NOT_APPLICABLE)

    def test_malformed_automation_fails_closed(self):
        for args, kwargs in ((("swarm/!!bad",), {}),
                             (("feature/x",), {"labels": ("swarm",)}),
                             (("feature/x",), {"labels": ("autofix",)}),
                             (("feature/x",), {"commit_authors": ("swarm_bot",)})):
            with self.subTest(args=args, kwargs=kwargs):
                self.assertEqual(gate.classify_pull_request(*args, **kwargs)[0], gate.UNKNOWN)

    def test_merge_group_is_integration(self):
        result = gate.evaluate(gate.GateContext(
            event_name="merge_group", head_ref="swarm/integration/merge-group",
            head_sha=HEAD, base_sha=BASE, tested_sha=HEAD,
            manifest=manifest_data(), ancestors={WORKER: True}))
        self.assertEqual(result.kind, gate.INTEGRATION)
        self.assertTrue(result.passed, result.failures)


class CheckTests(unittest.TestCase):
    def test_valid_worker_passes(self):
        result = run()
        self.assertTrue(result.passed, result.failures)
        self.assertEqual(result.task_id, "TASK-01")
        self.assertEqual(result.check("evidence_schema").state, gate.PASSED)
        self.assertIsNone(result.check("missing"))
        self.assertEqual(json.loads(json.dumps(result.as_dict()))["overall"], gate.PASSED)

    def test_invalid_identifier_automation_fails(self):
        result = run(head_ref="swarm/!!bad")
        self.assertEqual(result.check("pr_classification").state, gate.FAILED)
        self.assertFalse(result.passed)

    def test_unknown_task_fails(self):
        result = run(head_ref="swarm/NOPE-1-7", title="NOPE-1: x", task_text="")
        self.assertEqual(result.check("task_exists").state, gate.FAILED)
        self.assertEqual(result.check("task_eligible").state, gate.FAILED)

    def test_incomplete_task_is_recognized_but_not_eligible(self):
        text = "- [!] TASK-01 · incomplete work\n  - evidence: tool evidence\n"
        mark = gate.TASK_MARK.search(text)
        self.assertIsNotNone(mark)
        self.assertEqual(mark.group(1), "!")
        result = run(task_text=text)
        self.assertEqual(result.check("task_exists").state, gate.PASSED)
        self.assertEqual(result.check("task_identifier").state, gate.PASSED)
        self.assertEqual(result.check("task_eligible").state, gate.FAILED)
        self.assertFalse(result.passed)
        without_base_evidence = run(task_text="- [!] TASK-01 · incomplete work\n", evidence_text=EVIDENCE)
        self.assertEqual(without_base_evidence.check("task_evidence").state, gate.FAILED)

    def test_done_task_and_missing_evidence_field_fail(self):
        # Behavior changed on purpose: a taken task may now use valid head notes.
        # A done task and an invalid base evidence value still fail closed.
        self.assertEqual(run(task_text="- [x] TASK-01 · work it\n").
                         check("task_eligible").state, gate.FAILED)
        self.assertEqual(run(task_text="- [x] TASK-01 · work it\n").
                         check("task_evidence").state, gate.FAILED)
        self.assertEqual(run(task_text="- [-] TASK-01 · work it\n  - evidence: none\n").
                         check("task_evidence").state, gate.FAILED)
        self.assertEqual(run(task_text="- [-] TASK-01 · work it\n  - evidence: pending\n").
                         check("task_evidence").state, gate.FAILED)

    def test_taken_task_without_base_evidence_uses_head_notes(self):
        """A first-time worker cannot edit the base task; its notes supply evidence."""
        result = run(task_text="- [-] TASK-01 · work it\n")
        self.assertEqual(result.check("task_evidence").state, gate.PASSED)
        self.assertIn("worker notes", result.check("task_evidence").reason)
        self.assertTrue(result.passed, result.failures)

    def test_taken_task_without_notes_still_fails(self):
        result = run(task_text="- [-] TASK-01 · work it\n", evidence_text="")
        self.assertEqual(result.check("task_evidence").state, gate.FAILED)
        self.assertIn("task_evidence", "\n".join(result.failures))

    def test_notes_missing_a_heading_or_command_do_not_satisfy_task_evidence(self):
        plain_task = "- [-] TASK-01 · work it\n"
        no_heading = EVIDENCE.replace("## Coverage\n100%\n\n", "")
        self.assertEqual(run(task_text=plain_task, evidence_text=no_heading).
                         check("task_evidence").state, gate.FAILED)
        no_command = EVIDENCE.replace("scripts/quick-check.sh passed", "all good")
        self.assertEqual(run(task_text=plain_task, evidence_text=no_command).
                         check("task_evidence").state, gate.FAILED)

    def test_base_evidence_source_is_reported(self):
        result = run()
        self.assertEqual(result.check("task_evidence").state, gate.PASSED)
        self.assertIn("task record", result.check("task_evidence").reason)

    def test_reconciliation_cannot_use_the_notes_fallback(self):
        result = run(head_ref="swarm/reconcile/b1", task_text="", evidence_text=EVIDENCE,
                     required_checks=(), check_runs={})
        self.assertEqual(result.check("task_evidence").state, gate.NOT_APPLICABLE)

    def test_task_pr_reference(self):
        plain = "- [-] TASK-01 · work it\n"
        self.assertEqual(run(task_text=plain).check("task_pr_reference").state, gate.PASSED)
        self.assertEqual(run(task_text=plain + "  - pr: #7\n").
                         check("task_pr_reference").state, gate.FAILED)

    def test_evidence_schema_command_and_binding_fail(self):
        self.assertEqual(run(evidence_text="").check("evidence_schema").state, gate.FAILED)
        self.assertEqual(run(evidence_text="chatter").check("evidence_schema").state, gate.FAILED)
        self.assertEqual(run(evidence_text=EVIDENCE.replace(HEAD, "f" * 40)).
                         check("revision_binding").state, gate.FAILED)
        no_command = "## Test evidence\nx\n## Coverage\ny\n## Review verdicts\nz\n"
        self.assertEqual(run(evidence_text=no_command).check("mandatory_commands").state,
                         gate.FAILED)

    def test_failed_missing_and_cancelled_checks_fail(self):
        for conclusion in ("failure", "", "cancelled", "pending"):
            with self.subTest(conclusion=conclusion):
                self.assertEqual(run(check_runs={"Policy and tooling": conclusion}).
                                 check("check_results").state, gate.FAILED)
        self.assertEqual(run(check_runs={"Policy and tooling": "neutral"}).
                         check("check_results").state, gate.PASSED)
        self.assertEqual(run(required_checks=()).check("check_results").state, gate.NOT_APPLICABLE)

    def test_worker_evidence_cannot_override_a_failed_check(self):
        result = run(evidence_text=EVIDENCE + "\nALL CHECKS PASSED\n",
                     check_runs={"Policy and tooling": "failure"})
        self.assertFalse(result.passed)

    def test_protected_files_fail(self):
        for path, name in (("scripts/swarm.py", "protected_files"),
                           ("apps/api/pom.xml", "coverage_config"),
                           (".github/workflows/delivery.yml", "workflow_config"),
                           ("tasks/001-X.md", "protected_files")):
            with self.subTest(path=path):
                self.assertEqual(run(files=(path,)).check(name).state, gate.FAILED)

    def test_manual_and_reconcile_are_exempt(self):
        manual = run(head_ref="feature/x", title="Add thing",
                     files=(".github/workflows/delivery.yml",), task_text="",
                     evidence_text="", required_checks=(), check_runs={})
        self.assertTrue(manual.passed, manual.failures)
        self.assertEqual(manual.check("protected_files").state, gate.NOT_APPLICABLE)
        reconcile = run(head_ref="swarm/reconcile/b1", files=("tasks/001-X.md",),
                        task_text="", evidence_text="", required_checks=(), check_runs={},
                        manifest=None)
        self.assertTrue(reconcile.passed, reconcile.failures)

    def test_manual_pr_with_failed_check_fails(self):
        self.assertEqual(run(head_ref="feature/x", title="Add thing", task_text="",
                             evidence_text="", check_runs={"Check": "failure"},
                             required_checks=("Check",)).check("check_results").state, gate.FAILED)

    def test_stale_and_malformed_revision_fail(self):
        self.assertEqual(run(tested_sha="d" * 40).check("tested_revision").state, gate.FAILED)
        self.assertEqual(run(tested_sha="").check("tested_revision").state, gate.FAILED)


class RevisionBindingTests(unittest.TestCase):
    """A note may name only a revision proven to be in the pull request history."""

    def test_head_sha_claim_alone_no_longer_passes(self):
        """The old substring rule accepted a bare SHA; the new field rule must not."""
        bare = EVIDENCE.replace(f"Tested revision: {HEAD}\n", f"I tested {HEAD}\n")
        self.assertEqual(gate.extract_note_revision(bare), "")
        result = run(evidence_text=bare, note_sha=HEAD,
                     revision_evidence=compare_result("identical", HEAD))
        self.assertEqual(result.check("revision_binding").state, gate.FAILED)

    def test_ancestor_and_identical_revisions_pass(self):
        for sha, status in ((ANCESTOR, "ahead"), (HEAD, "identical")):
            with self.subTest(sha=sha):
                result = run(evidence_text=binding_evidence(sha), note_sha=sha,
                             revision_evidence=compare_result(status, sha))
                self.assertEqual(result.check("revision_binding").state, gate.PASSED)
                self.assertTrue(result.passed, result.failures)

    def test_malformed_missing_and_ambiguous_revisions_fail(self):
        for sha in ("short", "g" * 40, HEAD.upper(), "e" * 39):
            with self.subTest(sha=sha):
                result = run(evidence_text=binding_evidence(sha), note_sha=sha,
                             revision_evidence=compare_result("ahead", sha))
                self.assertEqual(result.check("revision_binding").state, gate.FAILED)
        ambiguous = EVIDENCE + f"Tested revision: {ANCESTOR}\n"
        self.assertEqual(gate.extract_note_revision(ambiguous), "")
        self.assertEqual(run(evidence_text=ambiguous, note_sha=ANCESTOR).check("revision_binding").state,
                         gate.FAILED)
        repeated = EVIDENCE + f"Tested revision: {HEAD}\n"
        self.assertEqual(gate.extract_note_revision(repeated), "")
        self.assertEqual(run(evidence_text=repeated, note_sha=HEAD,
                             revision_evidence=compare_result("identical", HEAD)
                             ).check("revision_binding").state, gate.FAILED)

    def test_diverged_behind_and_incomplete_compare_fail(self):
        cases = (
            compare_result("diverged", BASE, 3),
            compare_result("behind", HEAD, 2),
            compare_result("ahead", ANCESTOR, 1),
            compare_result("ahead", ANCESTOR, False),
            {"status": "ahead", "behind_by": 0},
        )
        for index, evidence in enumerate(cases):
            with self.subTest(case=index):
                result = run(evidence_text=binding_evidence(ANCESTOR), note_sha=ANCESTOR,
                             revision_evidence=evidence)
                self.assertEqual(result.check("revision_binding").state, gate.FAILED)

    def test_cross_repository_head_fails(self):
        result = run(evidence_text=binding_evidence(ANCESTOR), note_sha=ANCESTOR,
                     revision_evidence=compare_result("ahead", ANCESTOR), cross_repo_head=True)
        self.assertEqual(result.check("revision_binding").state, gate.FAILED)

    def test_compare_failure_reason_is_reported(self):
        """An API outage must be distinguishable from a bad revision claim."""
        result = run(evidence_text=binding_evidence(ANCESTOR), note_sha=ANCESTOR,
                     revision_evidence={"status": "error", "reason": "compare evidence unavailable"})
        self.assertEqual(result.check("revision_binding").state, gate.FAILED)
        self.assertIn("compare evidence unavailable", result.check("revision_binding").reason)

    def test_non_worker_revision_binding_is_not_applicable(self):
        result = run(head_ref="feature/x", title="Add thing", task_text="",
                     evidence_text="", note_sha="", revision_evidence=None)
        self.assertEqual(result.check("revision_binding").state, gate.NOT_APPLICABLE)

    def test_tested_revision_stays_independent(self):
        for tested, expected in ((HEAD, gate.PASSED), (STALE, gate.FAILED), ("", gate.FAILED)):
            with self.subTest(tested=tested):
                result = run(evidence_text=binding_evidence(ANCESTOR), note_sha=ANCESTOR,
                             revision_evidence=compare_result("ahead", ANCESTOR), tested_sha=tested)
                self.assertEqual(result.check("revision_binding").state, gate.PASSED)
                self.assertEqual(result.check("tested_revision").state, expected)

    def test_tested_revision_failure_names_both_head_shas_and_supersession(self):
        """A stale event head must be diagnosable from the gate reason alone."""
        result = run(tested_sha=DIVERGED)
        check = result.check("tested_revision")
        self.assertEqual(check.state, gate.FAILED)
        self.assertIn(f"event head {HEAD[:12]}", check.reason)
        self.assertIn(f"live headRefOid {DIVERGED[:12]}", check.reason)
        self.assertIn("head moved after the event; a newer run will supersede this one",
                      check.reason)
        self.assertIn(f"tested_revision: {check.reason}", result.failures)

    def test_tested_revision_equal_shas_still_pass(self):
        """An unchanged head must keep the original passing reason."""
        check = run(tested_sha=HEAD).check("tested_revision")
        self.assertEqual(check.state, gate.PASSED)
        self.assertEqual(check.reason, "the verified revision is the current GitHub head")

    def test_tested_revision_missing_sha_fails_without_supersession_claim(self):
        for head, tested, missing in (("", DIVERGED, "event head"),
                                      (HEAD, "", "live headRefOid")):
            with self.subTest(head=head, tested=tested):
                check = run(head_sha=head, tested_sha=tested).check("tested_revision")
                self.assertEqual(check.state, gate.FAILED)
                self.assertIn(f"{missing} missing", check.reason)
                self.assertNotIn("head moved after the event", check.reason)

    def test_compare_api_error_and_bad_payload_fail_closed(self):
        for effect in (subprocess.CalledProcessError(1, "gh"), ValueError("bad json"),
                       OSError("gh unavailable"), []):
            with self.subTest(effect=effect):
                with mock.patch.object(gate, "_gh_json", side_effect=effect if isinstance(effect, Exception) else None,
                                       return_value=effect if isinstance(effect, list) else None):
                    result = gate.check_revision_ancestry(ANCESTOR, HEAD, "owner/repo")
                self.assertTrue(result.get("status") == "error" or
                                result.get("in_ancestry") is False)
                self.assertTrue(result.get("reason") or result.get("error"))

    def test_compare_call_is_repo_scoped_and_validated(self):
        with mock.patch.object(gate, "_gh_json", return_value={"status": "ahead"}) as call:
            payload = gate.check_revision_ancestry(ANCESTOR, HEAD, "owner/repo")
            self.assertEqual(payload["status"], "ahead")
            args = call.call_args.args
            self.assertEqual(args[1:3],
                             ("repos/owner/repo/compare/" + ANCESTOR + "..." + HEAD, "--jq"))
            for bad in (("short", HEAD, "owner/repo"), (ANCESTOR, "short", "owner/repo"),
                        (ANCESTOR, HEAD, "../bad"), (ANCESTOR, HEAD, "owner/.."),
                        (ANCESTOR, HEAD, "owner/repo/extra"), (ANCESTOR, HEAD, "bad slug")):
                with self.subTest(bad=bad):
                    self.assertEqual(gate.check_revision_ancestry(*bad)["status"], "error")
            call.assert_called_once()

    def test_note_selection_uses_newest_timestamp_not_lexicographic_order(self):
        """A stale numeric-ID fix note sorts last but has the older timestamp."""
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "01320000.md").write_text("- [-] 01320000 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "01320000-fix-20261125-060501.md").write_text("# stale fix\n")
            (notes / "01320000-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            ctx = gate._collect_task_and_evidence(
                gate.GateContext(head_ref="swarm/01320000-7"), str(base), str(head))
        self.assertIn(binding_evidence(ANCESTOR), ctx.evidence_text)
        self.assertEqual(gate.extract_note_revision(ctx.evidence_text), ANCESTOR)

    def test_note_key_handles_undated_and_numeric_prefix_names(self):
        self.assertEqual(gate._note_key(Path("01320000-1.md")), ("", "01320000-1.md"))
        self.assertEqual(gate._note_key(Path("01320000-20261126-051824.md")),
                         ("20261126-051824", "01320000-20261126-051824.md"))
        self.assertEqual(gate._note_key(Path("01320000-fix-20261125-060501.md")),
                         ("20261125-060501", "01320000-fix-20261125-060501.md"))

    def test_legacy_notes_directory_is_used_as_fallback(self):
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / "docs" / "pr" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            ctx = gate._collect_task_and_evidence(
                gate.GateContext(head_ref="swarm/TASK-01-7"), str(base), str(head))
        self.assertEqual(ctx.evidence_text, binding_evidence(ANCESTOR))

    def test_collection_binds_note_and_detects_fork(self):
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                      "head": {"sha": HEAD, "ref": "swarm/TASK-01-7",
                                               "repo": {"full_name": "fork/evil"}},
                                      "base": {"sha": BASE,
                                               "repo": {"full_name": "owner/repo"}},
                                      "labels": []}}
            details = {"title": "TASK-01: x", "labels": [], "commits": [],
                       "headRefOid": HEAD}
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate, "check_revision_ancestry",
                                   return_value=compare_result("ahead", ANCESTOR)) as checker, \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"):
                ctx = gate.collect_context(event, "pull_request", str(base), head_root=str(head))
            self.assertEqual(ctx.note_sha, ANCESTOR)
            self.assertEqual(ctx.tested_sha, HEAD)
            self.assertTrue(ctx.cross_repo_head)
            checker.assert_not_called()
            result = gate.evaluate(ctx)
            self.assertEqual(result.check("revision_binding").state, gate.FAILED)

    def test_collection_accepts_injected_ancestry_checker(self):
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                      "head": {"sha": HEAD, "ref": "swarm/TASK-01-7",
                                               "repo": {"full_name": "owner/repo"}},
                                      "base": {"sha": BASE,
                                               "repo": {"full_name": "owner/repo"}},
                                      "labels": []}}
            details = {"title": "TASK-01: x", "labels": [], "commits": [],
                       "headRefOid": HEAD}
            observed = []

            def checker(note_sha, head_sha, repo):
                observed.append((note_sha, head_sha, repo))
                return compare_result("ahead", ANCESTOR)

            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate, "check_revision_ancestry",
                                   side_effect=AssertionError("injected checker must be used")), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"):
                ctx = gate.collect_context(event, "pull_request", str(base),
                                           head_root=str(head), ancestry_checker=checker)
            self.assertEqual(observed, [(ANCESTOR, HEAD, "owner/repo")])
            self.assertEqual(ctx.revision_evidence["merge_base"], ANCESTOR)
            self.assertEqual(gate.evaluate(ctx).check("revision_binding").state, gate.PASSED)

    def test_collection_uses_trusted_compare_by_default(self):
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                      "head": {"sha": HEAD, "ref": "swarm/TASK-01-7",
                                               "repo": {"full_name": "owner/repo"}},
                                      "base": {"sha": BASE,
                                               "repo": {"full_name": "owner/repo"}},
                                      "labels": []}}
            details = {"title": "TASK-01: x", "labels": [], "commits": [],
                       "headRefOid": HEAD}
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate, "check_revision_ancestry",
                                   return_value=compare_result("ahead", ANCESTOR)) as checker, \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"):
                ctx = gate.collect_context(event, "pull_request", str(base), head_root=str(head))
            checker.assert_called_once_with(ANCESTOR, HEAD, "owner/repo")
            self.assertEqual(ctx.revision_evidence["merge_base"], ANCESTOR)

    def test_injected_checker_and_environment_repo_are_used(self):
        """The test seam and GITHUB_REPOSITORY reach the real compare query."""
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                      "head": {"sha": HEAD, "ref": "swarm/TASK-01-7",
                                               "repo": {"full_name": "owner/repo"}},
                                      "base": {"sha": BASE,
                                               "repo": {"full_name": "owner/repo"}},
                                      "labels": []}}
            details = {"title": "TASK-01: x", "labels": [], "commits": [],
                       "headRefOid": HEAD}
            fake = mock.Mock(return_value=compare_result("ahead", ANCESTOR))
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.dict(gate.os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"):
                ctx = gate.collect_context(event, "pull_request", str(base),
                                           head_root=str(head), ancestry_checker=fake)
            fake.assert_called_once_with(ANCESTOR, HEAD, "owner/repo")
            self.assertEqual(ctx.revision_evidence["status"], "ahead")
            self.assertEqual(gate.evaluate(ctx).check("revision_binding").state, gate.PASSED)

    def test_same_repository_requires_all_known_names_to_agree(self):
        with mock.patch.dict(gate.os.environ, {"GITHUB_REPOSITORY": "owner/repo"}):
            self.assertTrue(gate._same_repository("", ""))
            self.assertTrue(gate._same_repository("owner/repo", "owner/repo"))
            self.assertFalse(gate._same_repository("fork/evil", "owner/repo"))

    def test_live_head_ref_is_the_tested_revision(self):
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                      "head": {"sha": HEAD, "ref": "swarm/TASK-01-7",
                                               "repo": {"full_name": "owner/repo"}},
                                      "base": {"sha": BASE,
                                               "repo": {"full_name": "owner/repo"}},
                                      "labels": []}}
            details = {"title": "TASK-01: x", "labels": [], "commits": [],
                       "headRefOid": DIVERGED}
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate, "check_revision_ancestry",
                                   return_value=compare_result("ahead", ANCESTOR)), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"):
                ctx = gate.collect_context(event, "pull_request", str(base), head_root=str(head))
            self.assertEqual(ctx.tested_sha, DIVERGED)
            self.assertFalse(ctx.cross_repo_head)
            result = gate.evaluate(ctx)
            self.assertEqual(result.check("tested_revision").state, gate.FAILED)

    def test_merge_group_does_not_call_compare(self):
        with mock.patch.object(gate, "check_revision_ancestry") as checker:
            result = integration()
        checker.assert_not_called()
        self.assertEqual(result.check("revision_binding").state, gate.NOT_APPLICABLE)


class ManifestTests(unittest.TestCase):
    def test_valid_manifest_passes(self):
        result = integration()
        self.assertTrue(result.passed, result.failures)
        self.assertEqual(result.check("integration_manifest").state, gate.PASSED)

    def test_missing_malformed_and_mismatched_manifest_fail(self):
        self.assertEqual(integration(manifest=None).check("integration_manifest").state, gate.FAILED)
        self.assertEqual(integration(manifest={}).check("integration_manifest").state, gate.FAILED)
        self.assertEqual(integration(manifest=manifest_data(base_sha="e" * 40)).
                         check("integration_manifest").state, gate.FAILED)
        self.assertEqual(integration(manifest=manifest_data(worker_prs=[])).
                         check("integration_manifest").state, gate.FAILED)

    def test_bad_worker_record_and_failed_verification_fail(self):
        bad = manifest_data()
        bad["worker_prs"] = [{"number": 42, "head_sha": "short", "task_id": "TASK-01"}]
        self.assertEqual(integration(manifest=bad).check("integration_manifest").state, gate.FAILED)
        failed = manifest_data()
        failed["verification"] = {"passed": False}
        self.assertEqual(integration(manifest=failed).check("integration_manifest").state, gate.FAILED)

    def test_unintegrated_revision_fails(self):
        self.assertIn("not integrated", integration(ancestors={WORKER: False}).
                      check("manifest_ancestry").reason)

    def test_manifest_not_applicable_to_worker(self):
        result = run()
        self.assertEqual(result.check("integration_manifest").state, gate.NOT_APPLICABLE)
        self.assertEqual(result.check("manifest_ancestry").state, gate.NOT_APPLICABLE)


class CollectionTests(unittest.TestCase):
    def test_field_and_matches(self):
        self.assertEqual(gate._field("- [-] X · y\n  - pr: #7\n", "pr"), "#7")
        self.assertEqual(gate._field("- [-] X · y\n", "pr"), "")
        self.assertTrue(gate._matches("scripts/a.py", ("scripts/*",)))
        self.assertFalse(gate._matches("src/a.py", ("scripts/*",)))

    def test_collect_manifest_and_ancestry(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / gate.MANIFEST_PATH).parent.mkdir(parents=True)
            (root / gate.MANIFEST_PATH).write_text(json.dumps(manifest_data()))
            with mock.patch.object(gate.subprocess, "run",
                                   return_value=subprocess.CompletedProcess([], 0)):
                ctx = gate.collect_context({"merge_group": {"head_sha": HEAD, "base_sha": BASE}},
                                           "merge_group", str(root))
        self.assertEqual(ctx.head_sha, HEAD)
        self.assertEqual(ctx.manifest["batch_id"], "b1")
        self.assertTrue(ctx.ancestors[WORKER])

    def test_collect_manifest_ignores_invalid_shas(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / gate.MANIFEST_PATH).parent.mkdir(parents=True)
            bad = manifest_data()
            bad["worker_prs"] = [{"number": 42, "head_sha": "short", "task_id": "TASK-01"}]
            (root / gate.MANIFEST_PATH).write_text(json.dumps(bad))
            ctx = gate.collect_context({"merge_group": {"head_sha": HEAD, "base_sha": BASE}},
                                       "merge_group", str(root))
            self.assertEqual(ctx.ancestors, {})

    def test_collect_context_pr_without_number(self):
        ctx = gate.collect_context({"pull_request": {"number": 0, "head": {"sha": HEAD}}},
                                   "pull_request", ".")
        self.assertEqual(ctx.pr_number, 0)
        self.assertEqual(ctx.files, ())

    def test_collect_context_worker_pr(self):
        with tempfile.TemporaryDirectory() as base, tempfile.TemporaryDirectory() as head:
            base_path, head_path = Path(base), Path(head)
            (base_path / "tasks").mkdir()
            (base_path / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head_path / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-1.md").write_text(EVIDENCE)
            (head_path / gate.MANIFEST_PATH).parent.mkdir(parents=True)
            (head_path / gate.MANIFEST_PATH).write_text(json.dumps(manifest_data()))
            event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                      "head": {"sha": HEAD, "ref": "swarm/TASK-01-7"},
                                      "base": {"sha": BASE}, "labels": []}}
            details = {"title": "TASK-01: x", "labels": [], "commits": [{"author": {"login": "bot"}}]}
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"), \
                 mock.patch.object(gate.subprocess, "run",
                                   return_value=subprocess.CompletedProcess([], 0)):
                ctx = gate.collect_context(event, "pull_request", str(base_path),
                                           head_root=str(head_path))
        self.assertEqual(ctx.files, ("src/a.py",))
        self.assertIn("TASK-01", ctx.task_text)
        self.assertIn("## Coverage", ctx.evidence_text)
        self.assertEqual(ctx.manifest["batch_id"], "b1")


class ModelAndCLITests(unittest.TestCase):
    def test_bad_check_state_is_rejected(self):
        with self.assertRaises(ValueError):
            gate.CheckResult("x", "maybe", "reason")

    def test_main_reports_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "event.json")
            path.write_text(json.dumps({"pull_request": {
                "number": 7, "title": "TASK-01: x", "draft": False,
                "head": {"sha": HEAD, "ref": "swarm/TASK-01-7"},
                "base": {"sha": BASE}, "labels": []}}))
            out, err = io.StringIO(), io.StringIO()
            details = {"title": "TASK-01: x", "labels": [], "commits": []}
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="README.md\n"), \
                 contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                code = gate.main(["evaluate", "--event-path", str(path),
                                  "--event-name", "pull_request", "--root", tmp])
            self.assertEqual(code, 1)
            self.assertIn("evidence_schema: failed", out.getvalue())

    def test_main_accepts_head_root_for_the_tested_checkout(self):
        """The Swarm gate workflow checks the manifest out of --head-root, not --root."""
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            path = base / "event.json"
            path.write_text(json.dumps({"pull_request": {
                "number": 42, "title": "TASK-01: x", "draft": False,
                "head": {"sha": HEAD, "ref": "swarm/TASK-01-7"},
                "base": {"sha": BASE}, "labels": []}}))
            (head / gate.MANIFEST_PATH).parent.mkdir(parents=True)
            (head / gate.MANIFEST_PATH).write_text(json.dumps(manifest_data()))
            details = {"title": "TASK-01: x", "labels": [], "commits": []}
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"), \
                 mock.patch.object(gate.subprocess, "run",
                                   return_value=subprocess.CompletedProcess([], 0)), \
                 mock.patch.object(gate, "collect_context", wraps=gate.collect_context) as collect, \
                 contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                try:
                    code = gate.main(["evaluate", "--event-path", str(path),
                                      "--event-name", "pull_request", "--root", str(base),
                                      "--head-root", str(head)])
                except SystemExit as exit_error:
                    code = exit_error.code
            self.assertNotEqual(code, 2, err.getvalue())
            self.assertEqual(collect.call_args.kwargs["head_root"], str(head))

    def test_main_handles_bad_event(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "event.json")
            path.write_text("not json")
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                code = gate.main(["evaluate", "--event-path", str(path),
                                  "--event-name", "pull_request"])
            self.assertEqual(code, 2)
            self.assertIn("verification error", err.getvalue())


class CoverageTests(unittest.TestCase):
    """Cover the I/O helpers and the module entry point."""

    def test_gh_json_parses_output_and_empty(self):
        with mock.patch.object(gate.subprocess, "check_output", return_value='{"a": 1}'):
            self.assertEqual(gate._gh_json("pr", "view"), {"a": 1})
        with mock.patch.object(gate.subprocess, "check_output", return_value=""):
            self.assertEqual(gate._gh_json("pr", "view"), {})

    def test_collect_task_returns_unchanged_context_without_task(self):
        ctx = gate.GateContext(head_ref="feature/x", title="Manual change")
        self.assertIs(gate._collect_task_and_evidence(ctx, "."), ctx)

    def test_module_main_entry_point(self):
        # A merge-group event needs no gh calls, so the freshly imported module
        # can run end-to-end under runpy. No manifest means a failed verdict.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "event.json")
            path.write_text(json.dumps({"merge_group": {"head_sha": HEAD, "base_sha": BASE}}))
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.object(sys, "argv", ["swarm_gate.py", "evaluate", "--event-path", str(path),
                                                  "--event-name", "merge_group", "--root", tmp]), \
                 contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                with self.assertRaises(SystemExit) as caught:
                    runpy.run_path(str(Path(__file__).parents[1] / "swarm_gate.py"), run_name="__main__")
            self.assertEqual(caught.exception.code, 1)


class AdditionalCoverageTests(unittest.TestCase):
    """Exercise the few branches not reached by the behaviour tests."""

    def test_gh_json_empty_output_is_an_empty_object(self):
        with mock.patch.object(gate.subprocess, "check_output", return_value=""):
            self.assertEqual(gate._gh_json("pr", "view"), {})

    def test_collect_context_without_a_task_id_returns_unchanged(self):
        event = {"pull_request": {"number": 9, "title": "Manual change",
                                  "head": {"sha": HEAD, "ref": "feature/x"},
                                  "base": {"sha": BASE}, "labels": []}}
        details = {"title": "Manual change", "labels": [], "commits": []}
        with mock.patch.object(gate, "_gh_json", return_value=details), \
             mock.patch.object(gate.subprocess, "check_output", return_value="README.md\n"):
            ctx = gate.collect_context(event, "pull_request", ".")
        self.assertEqual(ctx.task_text, "")
        self.assertEqual(ctx.evidence_text, "")

    def test_module_entry_point(self):
        # A merge-group event needs no gh calls, so the freshly imported module
        # can run end-to-end under runpy. No manifest means a failed verdict.
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp, "event.json")
            path.write_text(json.dumps({"merge_group": {"head_sha": HEAD, "base_sha": BASE}}))
            out, err = io.StringIO(), io.StringIO()
            with mock.patch.object(sys, "argv", ["swarm_gate.py", "evaluate",
                                                  "--event-path", str(path),
                                                  "--event-name", "merge_group",
                                                  "--root", tmp]), \
                 contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                with self.assertRaises(SystemExit) as caught:
                    runpy.run_path(str(ROOT / "scripts" / "swarm_gate.py"), run_name="__main__")
            self.assertEqual(caught.exception.code, 1)

class MaintenancePRTests(unittest.TestCase):
    """A GitHub-trusted maintainer may use any branch name for a manual PR."""

    def test_owner_maintenance_branch_is_human(self):
        kind, _ = gate.classify_pull_request(
            "swarm/protected-integration-gates", title="Add independent Swarm gate",
            author_association="OWNER")
        self.assertEqual(kind, gate.HUMAN)
        result = gate.evaluate(gate.GateContext(
            head_ref="swarm/protected-integration-gates", head_sha=HEAD, base_sha=BASE,
            tested_sha=HEAD, title="Add independent Swarm gate",
            author_association="OWNER", files=(".github/workflows/swarm-gate.yml",),
            required_checks=()))
        self.assertEqual(result.overall, gate.PASSED, result.failures)

    def test_collect_context_carries_author_association(self):
        event = {"pull_request": {
            "number": 0, "title": "Add independent Swarm gate",
            "head": {"ref": "swarm/protected-integration-gates", "sha": HEAD},
            "base": {"sha": BASE}, "labels": [], "author_association": "OWNER"}}
        ctx = gate.collect_context(event, "pull_request", ".")
        self.assertEqual(ctx.author_association, "OWNER")

    def test_untrusted_automation_branch_stays_unknown(self):
        kind, _ = gate.classify_pull_request("swarm/odd-branch", author_association="NONE")
        self.assertEqual(kind, gate.UNKNOWN)

    def test_author_association_cannot_be_forged_by_a_label(self):
        # A trusted human is identified by GitHub, not by a worker-editable
        # label; the label alone (no trusted association) still fails closed.
        kind, _ = gate.classify_pull_request(
            "feature/odd-branch", labels=("swarm",), author_association="NONE")
        self.assertEqual(kind, gate.UNKNOWN)


class TaskFileLookupTests(unittest.TestCase):
    """Worker PRs name a task ID; the repository stores numbered task files."""

    def test_numbered_task_file_is_found(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "tasks").mkdir()
            (root / "tasks" / "00906000-START-18.md").write_text(
                "- [-] START-18 · work it\n  - evidence: tool evidence\n")
            ctx = context(head_ref="swarm/START-18-1436", title="START-18: link the index")
            found = gate._collect_task_and_evidence(ctx, str(root))
        self.assertIn("tool evidence", found.task_text)

    def test_exact_task_file_wins_over_numbered(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "tasks").mkdir()
            (root / "tasks" / "START-18.md").write_text("exact")
            (root / "tasks" / "00906000-START-18.md").write_text("numbered")
            self.assertEqual(gate._task_file(str(root), "START-18").read_text(), "exact")

    def test_missing_task_file_has_empty_text(self):
        with tempfile.TemporaryDirectory() as tmp:
            ctx = context(head_ref="swarm/START-18-1436", title="START-18: link the index")
            found = gate._collect_task_and_evidence(ctx, tmp)
        self.assertEqual(found.task_text, "")


class GitHubContextTests(unittest.TestCase):
    """The gate runs outside a worktree, so every gh call names its repository."""

    def test_no_repo_selector_locally(self):
        with mock.patch.dict(gate.os.environ, {}, clear=True):
            self.assertEqual(gate._gh_repo_args(), ())

    def test_repo_selector_from_actions_environment(self):
        with mock.patch.dict(gate.os.environ, {"GITHUB_REPOSITORY": "dr3mro/Aurevia-Care"}):
            self.assertEqual(gate._gh_repo_args(), ("--repo", "dr3mro/Aurevia-Care"))

    def test_gh_json_uses_repo_selector(self):
        with mock.patch.dict(gate.os.environ, {"GITHUB_REPOSITORY": "dr3mro/Aurevia-Care"}), \
             mock.patch.object(gate.subprocess, "check_output", return_value=b"{}") as run:
            gate._gh_json("pr", "view", "42")
        self.assertEqual(run.call_args.args[0][:5],
                         ["gh", "--repo", "dr3mro/Aurevia-Care", "pr", "view"])

    def test_collect_context_uses_repo_selector_for_diff(self):
        event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                  "head": {"sha": HEAD, "ref": "swarm/TASK-01-7"},
                                  "base": {"sha": BASE}, "labels": []}}
        details = {"title": "TASK-01: x", "labels": [], "commits": []}
        with mock.patch.dict(gate.os.environ, {"GITHUB_REPOSITORY": "dr3mro/Aurevia-Care"}), \
             mock.patch.object(gate, "_gh_json", return_value=details), \
             mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n") as run, \
             mock.patch.object(gate.subprocess, "run",
                               return_value=subprocess.CompletedProcess([], 0)):
            gate.collect_context(event, "pull_request", ".")
        self.assertEqual(run.call_args.args[0][:3],
                         ["gh", "--repo", "dr3mro/Aurevia-Care"])


class TrustedVerifierCheckoutTests(unittest.TestCase):
    """The verifier follows current main, not a PR's stale recorded base SHA."""

    def test_workflow_checks_out_current_protected_branch(self):
        workflow = (ROOT / ".github" / "workflows" / "swarm-gate.yml").read_text(encoding="utf-8")
        self.assertIn("github.event.merge_group.base_sha || github.event.repository.default_branch",
                      workflow)
        self.assertNotIn("github.event.pull_request.base.sha", workflow)


class RefreshEventTests(unittest.TestCase):
    """The pure event rewrite used after a trusted live-head checkout."""

    def event(self, **overrides):
        data = {"pull_request": {"number": 42, "head": {"sha": HEAD, "ref": "swarm/TASK-01-7"}},
                "action": "synchronize"}
        data.update(overrides)
        return data

    def write_event(self, data):
        path = Path(tempfile.mkdtemp()) / "event.json"
        path.write_text(json.dumps(data))
        return path

    def test_refresh_rewrites_head_and_records_provenance(self):
        source = self.write_event(self.event())
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", DIVERGED, "--original-sha", HEAD,
                                    "--output", str(output)]), 0)
        refreshed = json.loads(output.read_text())
        self.assertEqual(refreshed["pull_request"]["head"]["sha"], DIVERGED)
        self.assertEqual(refreshed["action"], "synchronize")
        self.assertEqual(refreshed["swarm_gate_head_refresh"],
                         {"from": HEAD, "to": DIVERGED})

    def test_refresh_rejects_malformed_sha(self):
        source = self.write_event(self.event())
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", "not-a-sha", "--original-sha", HEAD,
                                    "--output", str(output)]), 2)
        self.assertFalse(output.exists())

    def test_refresh_rejects_a_malformed_original_sha(self):
        source = self.write_event(self.event())
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", DIVERGED, "--original-sha", "not-a-sha",
                                    "--output", str(output)]), 2)
        self.assertFalse(output.exists())

    def test_refresh_rejects_a_malformed_event_head(self):
        source = self.write_event(self.event(pull_request={
            "number": 42, "head": {"sha": "not-a-sha", "ref": "swarm/TASK-01-7"}}))
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", DIVERGED, "--original-sha", HEAD,
                                    "--output", str(output)]), 2)
        self.assertFalse(output.exists())

    def test_refresh_rejects_an_original_sha_that_is_not_the_event_head(self):
        source = self.write_event(self.event())
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", DIVERGED, "--original-sha", ANCESTOR,
                                    "--output", str(output)]), 2)
        self.assertFalse(output.exists())

    def test_refresh_rejects_a_non_mapping_event_shape(self):
        source = self.write_event(["not", "an", "event"])
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", DIVERGED, "--original-sha", HEAD,
                                    "--output", str(output)]), 2)
        self.assertFalse(output.exists())

    def test_refresh_rejects_a_head_that_is_not_an_object(self):
        source = self.write_event(self.event(pull_request={"head": "not-an-object"}))
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", DIVERGED, "--original-sha", HEAD,
                                    "--output", str(output)]), 2)
        self.assertFalse(output.exists())

    def test_evaluate_still_requires_an_event_name(self):
        source = self.write_event(self.event())
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.main(["evaluate", "--event-path", str(source)]), 2)

    def test_refresh_rejects_a_non_pull_request_event(self):
        source = self.write_event({"merge_group": {"head_sha": HEAD}})
        output = source.parent / "refreshed.json"
        self.assertEqual(gate.main(["refresh-event", "--event-path", str(source),
                                    "--head-sha", DIVERGED, "--original-sha", HEAD,
                                    "--output", str(output)]), 2)
        self.assertFalse(output.exists())


class RefreshedHeadEvaluationTests(unittest.TestCase):
    """A refreshed head cannot silently accept stale or second-move evidence."""

    def test_refresh_is_audited_in_the_currency_reason(self):
        check = run(head_sha=DIVERGED, tested_sha=DIVERGED, refresh_from=HEAD) \
            .check("tested_revision")
        self.assertEqual(check.state, gate.PASSED)
        self.assertIn(f"refreshed from {HEAD[:12]}", check.reason)

    def test_collect_context_propagates_the_refresh_marker(self):
        """A refreshed event must reach both binding and the currency reason."""
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(binding_evidence(ANCESTOR))
            event = {"pull_request": {"number": 42, "title": "TASK-01: refreshed",
                                      "head": {"sha": DIVERGED, "ref": "swarm/TASK-01-7",
                                               "repo": {"full_name": "owner/repo"}},
                                      "base": {"sha": BASE,
                                               "repo": {"full_name": "owner/repo"}},
                                      "labels": []},
                     "swarm_gate_head_refresh": {"from": HEAD, "to": DIVERGED}}
            details = {"title": "TASK-01: refreshed", "labels": [], "commits": [],
                       "headRefOid": DIVERGED}
            checker = mock.Mock(return_value=compare_result("ahead", ANCESTOR))
            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.dict(gate.os.environ, {"GITHUB_REPOSITORY": "owner/repo"}), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"):
                ctx = gate.collect_context(event, "pull_request", str(base),
                                           head_root=str(head), ancestry_checker=checker)
            checker.assert_called_once_with(ANCESTOR, DIVERGED, "owner/repo")
            self.assertEqual(ctx.refresh_from, HEAD)
            result = gate.evaluate(ctx)
            self.assertEqual(result.check("revision_binding").state, gate.PASSED)
            self.assertEqual(result.check("tested_revision").state, gate.PASSED)
            self.assertIn(f"refreshed from {HEAD[:12]}",
                          result.check("tested_revision").reason)

    def test_second_head_move_still_fails_currency(self):
        check = run(head_sha=DIVERGED, tested_sha=STALE, refresh_from=HEAD,
                    note_sha=HEAD, revision_evidence=compare_result("ahead", HEAD)) \
            .check("tested_revision")
        self.assertEqual(check.state, gate.FAILED)
        self.assertIn("head moved after the event", check.reason)


class RefreshedCheckoutWorkflowTests(unittest.TestCase):
    """The verifier boundary and live-head validation stay in trusted code."""

    def setUp(self):
        self.workflow = (ROOT / ".github" / "workflows" / "swarm-gate.yml").read_text(
            encoding="utf-8")

    def test_live_head_step_validates_sha_and_has_a_read_token(self):
        step = self.workflow.split("- name: Resolve live PR head", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("github.event_name == 'pull_request'", step)
        self.assertIn("^[0-9a-f]{40}$", step)
        self.assertIn("exit 1", step)
        self.assertIn("GH_TOKEN: ${{ github.token }}", step)
        self.assertIn('gh pr view --repo "$GITHUB_REPOSITORY"', step)

    def test_refresh_uses_a_trusted_pr_head_checkout(self):
        step = self.workflow.split("- name: Refresh tested checkout", 1)[1].split("\n      - ", 1)[0]
        self.assertIn("path: tested", step)
        self.assertIn("refs/pull/${{ github.event.pull_request.number }}/head", step)
        self.assertIn("fetch-depth: 0", step)
        self.assertIn("persist-credentials: false", step)

    def test_gate_still_runs_base_verifier_against_both_roots(self):
        self.assertIn("python3 -I base/scripts/swarm_gate.py evaluate", self.workflow)
        self.assertIn("--root base", self.workflow)
        self.assertIn("--head-root tested", self.workflow)
        self.assertIn("RUNNER_TEMP/swarm-gate-event.json", self.workflow)


class CanonicalRevisionExtractionTests(unittest.TestCase):
    """One canonical line, one revision token; trailing prose is not a second claim."""

    def test_pr1815_line_with_trailing_prose_returns_sha(self):
        self.assertEqual(gate.extract_note_revision(PROSE_LINE), ANCESTOR)

    def test_canonical_line_alone_returns_sha(self):
        self.assertEqual(gate.extract_note_revision(f"Tested revision: {HEAD}"), HEAD)

    def test_two_shas_on_one_line_is_rejected(self):
        line = f"Tested revision: {HEAD} {ANCESTOR} (two claims)"
        self.assertEqual(gate.extract_note_revision(line), "")

    def test_two_canonical_lines_are_rejected(self):
        text = EVIDENCE + f"Tested revision: {ANCESTOR} (later)\n"
        self.assertEqual(gate.extract_note_revision(text), "")

    def test_empty_text_returns_no_revision(self):
        self.assertEqual(gate.extract_note_revision(""), "")
        self.assertEqual(gate.extract_note_revision(None), "")

    def test_missing_sha_is_rejected(self):
        self.assertEqual(gate.extract_note_revision(
            "Tested revision: (`git rev-parse HEAD` was not run)"), "")

    def test_malformed_sha_is_rejected(self):
        for sha in ("g" * 40, HEAD.upper(), "e" * 39):
            with self.subTest(sha=sha):
                self.assertEqual(gate.extract_note_revision(f"Tested revision: {sha}"), "")

    def test_longer_hex_token_and_bare_prose_sha_are_rejected(self):
        self.assertEqual(gate.extract_note_revision(f"Tested revision: {'a' * 41}"), "")
        self.assertEqual(gate.extract_note_revision(f"Tested revision: {HEAD}0"), "")
        self.assertEqual(gate.extract_note_revision(f"I tested {HEAD} in prose"), "")

    def test_trailing_prose_binds_ancestry_and_rejects_divergence(self):
        with tempfile.TemporaryDirectory() as base_tmp, tempfile.TemporaryDirectory() as head_tmp:
            base, head = Path(base_tmp), Path(head_tmp)
            (base / "tasks").mkdir()
            (base / "tasks" / "TASK-01.md").write_text("- [-] TASK-01 · work it\n")
            notes = head / ".agents" / "notes"
            notes.mkdir(parents=True)
            (notes / "TASK-01-20261126-051824.md").write_text(
                EVIDENCE.replace(f"Tested revision: {HEAD}\n", PROSE_LINE + "\n"))
            event = {"pull_request": {"number": 42, "title": "TASK-01: x",
                                      "head": {"sha": HEAD, "ref": "swarm/TASK-01-7",
                                               "repo": {"full_name": "owner/repo"}},
                                      "base": {"sha": BASE,
                                               "repo": {"full_name": "owner/repo"}},
                                      "labels": []}}
            details = {"title": "TASK-01: x", "labels": [], "commits": [],
                       "headRefOid": HEAD}
            observed = []

            def checker(note_sha, head_sha, repo):
                observed.append((note_sha, head_sha, repo))
                return (compare_result("diverged", BASE, 3) if len(observed) == 2
                        else compare_result("ahead", ANCESTOR))

            with mock.patch.object(gate, "_gh_json", return_value=details), \
                 mock.patch.object(gate.subprocess, "check_output", return_value="src/a.py\n"):
                ctx = gate.collect_context(event, "pull_request", str(base),
                                           head_root=str(head), ancestry_checker=checker)
                passed = gate.evaluate(ctx).check("revision_binding").state
                # The same note still fails when the trusted compare says diverged.
                ctx = gate.collect_context(event, "pull_request", str(base),
                                           head_root=str(head), ancestry_checker=checker)
                failed = gate.evaluate(ctx).check("revision_binding").state
            self.assertEqual(observed, [(ANCESTOR, HEAD, "owner/repo")] * 2)
            self.assertEqual(ctx.note_sha, ANCESTOR)
            self.assertEqual(passed, gate.PASSED)
            self.assertEqual(failed, gate.FAILED)


class ValidateNoteCommandTests(unittest.TestCase):
    """Workers can catch an unreadable revision line before publishing a PR."""

    def write_note(self, line):
        path = Path(tempfile.mkdtemp()) / "note.md"
        path.write_text(EVIDENCE.replace(f"Tested revision: {HEAD}\n", line + "\n"),
                        encoding="utf-8")
        return path

    def test_valid_prose_note_returns_zero_and_names_the_sha(self):
        path = self.write_note(PROSE_LINE)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(["validate-note", "--note-path", str(path)])
        self.assertEqual(code, 0)
        self.assertIn(f"Swarm gate: note revision valid: {ANCESTOR}", out.getvalue())
        self.assertEqual(err.getvalue(), "")

    def test_malformed_note_returns_two_without_a_traceback(self):
        path = self.write_note(PROSE_LINE + "\nTested revision: " + DIVERGED)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(["validate-note", "--note-path", str(path)])
        self.assertEqual(code, 2)
        self.assertNotIn("Traceback", err.getvalue())
        self.assertIn("note revision", err.getvalue())

    def test_validate_note_requires_a_path(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(["validate-note"])
        self.assertEqual(code, 2)
        self.assertIn("note revision", err.getvalue())

    def test_unreadable_note_path_returns_two_without_a_traceback(self):
        path = Path(tempfile.mkdtemp()) / "missing.md"
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(["validate-note", "--note-path", str(path)])
        self.assertEqual(code, 2)
        self.assertNotIn("Traceback", err.getvalue())
        self.assertIn("note validation error", err.getvalue())

    def test_evaluate_still_requires_an_event_path(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.main(["evaluate", "--event-name", "pull_request"]), 2)

    def test_refresh_event_still_requires_an_event_path(self):
        with contextlib.redirect_stderr(io.StringIO()):
            self.assertEqual(gate.main(["refresh-event"]), 2)


class ValidateTasksCommandTests(unittest.TestCase):
    """Offline preflight for tracker fields that otherwise abort reconciliation."""

    def write_tasks(self, files):
        root = Path(tempfile.mkdtemp())
        self.addCleanup(lambda: __import__("shutil").rmtree(root, ignore_errors=True))
        for name, text in files.items():
            (root / name).write_text(text, encoding="utf-8")
        return root

    def run_check(self, root):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(["validate-tasks", "--dir", str(root)])
        return code, out.getvalue(), err.getvalue()

    def test_empty_directory_passes_with_zero_count(self):
        code, out, err = self.run_check(self.write_tasks({}))
        self.assertEqual(code, 0)
        self.assertIn("Swarm gate: task validation valid: 0 task files", out)
        self.assertEqual(err, "")

    def test_all_task_states_and_valid_pr_forms_pass(self):
        root = self.write_tasks({
            "a.md": "- [ ] ACTIVE-1 · one\n  - pr: #12\n",
            "b.md": "- [-] TAKEN-1 · two\n  - pr: 13\n",
            "c.md": "- [x] DONE-1 · three\n  - [x] a completed checklist item\n",
            "d.md": "- [!] HELD-1 · four\n  - details:\n    reason\n",
        })
        code, out, err = self.run_check(root)
        self.assertEqual(code, 0)
        self.assertIn("4 task files", out)
        self.assertEqual(err, "")

    def test_unreadable_task_file_is_reported_and_scan_continues(self):
        root = self.write_tasks({"a.md": "- [ ] BAD-9 · one\n  - pr: prose\n"})
        (root / "broken.md").mkdir()
        code, out, err = self.run_check(root)
        self.assertEqual(code, 2)
        self.assertIn("broken.md: unreadable task file", err)
        self.assertIn("BAD-9 has a malformed pr: field", err)
        self.assertEqual(out, "")

    def test_unreadable_directory_glob_is_reported(self):
        with mock.patch.object(Path, "glob", side_effect=OSError("denied")):
            code, out, err = self.run_check("tasks")
        self.assertEqual(code, 2)
        self.assertIn("unreadable task directory: denied", err)
        self.assertEqual(out, "")

    def test_files_without_a_task_line_are_skipped(self):
        root = self.write_tasks({
            "_head.md": "# TODO\n\nThis file holds instructions, not a task entry.\n",
        })
        code, out, err = self.run_check(root)
        self.assertEqual(code, 0)
        self.assertIn("0 task files", out)
        self.assertEqual(err, "")

    def test_prose_pr_field_is_reported_and_exits_two(self):
        root = self.write_tasks({
            "a.md": "- [ ] BAD-1 · one\n  - pr: local scoped commits on main\n",
        })
        code, out, err = self.run_check(root)
        self.assertEqual(code, 2)
        self.assertIn("BAD-1 has a malformed pr: field", err)
        self.assertNotIn("Traceback", err)

    def test_repeated_pr_field_is_reported_and_exits_two(self):
        root = self.write_tasks({"a.md": "- [ ] BAD-2 · two\n  - pr: #12\n  - pr: #13\n"})
        code, _, err = self.run_check(root)
        self.assertEqual(code, 2)
        self.assertIn("BAD-2 has repeated pr: fields", err)

    def test_duplicate_id_across_files_is_reported_and_exits_two(self):
        root = self.write_tasks({
            "a.md": "- [ ] DUP-1 · one\n",
            "b.md": "- [-] DUP-1 · two\n",
        })
        code, _, err = self.run_check(root)
        self.assertEqual(code, 2)
        self.assertIn("DUP-1 appears in more than one task file", err)

    def test_done_file_with_embedded_open_checkbox_is_reported(self):
        root = self.write_tasks({
            "a.md": "- [x] DONE-2 · done\n\n- [ ] Independent review and final checkpoint.\n",
        })
        code, _, err = self.run_check(root)
        self.assertEqual(code, 2)
        self.assertIn("DONE-2 contains an open task line after its task mark", err)

    def test_task_patterns_stay_in_sync_with_reconcile(self):
        """A parser change in reconcile must not silently drift from preflight."""
        source = (Path(__file__).resolve().parents[2] / "scripts" / "swarm.py").read_text(
            encoding="utf-8")
        self.assertIn(r"^- \[[- ]\] (\S+)", source)
        self.assertIn(r"^  - pr:[ \t]*(.*)$", source)
        self.assertIn(r"#?[1-9][0-9]*", source)
        active = "- [ ] SYNC-1 · one\n  - pr: #12\n"
        self.assertEqual(gate.ACTIVE_TASK_LINE.search(active).group(1), "SYNC-1")
        self.assertEqual(gate.PR_FIELD.findall(active), ["#12"])
        self.assertTrue(gate.PR_NUMBER.fullmatch("#12"))

    def test_check_scripts_run_the_preflight(self):
        scripts = Path(__file__).resolve().parents[2] / "scripts"
        for name in ("check.sh", "quick-check.sh"):
            with self.subTest(script=name):
                text = (scripts / name).read_text(encoding="utf-8")
                self.assertIn("swarm_gate.py validate-tasks --dir tasks", text)

    def test_missing_dir_returns_two_without_a_traceback(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(["validate-tasks"])
        self.assertEqual(code, 2)
        self.assertNotIn("Traceback", err.getvalue())

    def test_unreadable_dir_returns_two_without_a_traceback(self):
        root = Path(tempfile.mkdtemp()) / "missing"
        code, _, err = self.run_check(root)
        self.assertEqual(code, 2)
        self.assertNotIn("Traceback", err)
        self.assertIn("unreadable", err.lower())


if __name__ == "__main__":
    unittest.main()
