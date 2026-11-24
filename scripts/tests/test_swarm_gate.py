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
            "## Review verdicts\nGO\n\n" + HEAD + "\n")


def context(**overrides):
    """A valid worker context whose applicable checks all pass."""
    data = dict(pr_number=42, event_name="pull_request", head_ref="swarm/TASK-01-7",
                head_sha=HEAD, base_sha=BASE, tested_sha=HEAD,
                labels=(), title="TASK-01: work the story", files=("src/main.py",),
                task_text="- [-] TASK-01 · work it\n  - evidence: tool evidence\n  - pr: #42\n",
                evidence_text=EVIDENCE, required_checks=("Policy and tooling",),
                check_runs={"Policy and tooling": "success"},
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

    def test_done_task_and_missing_evidence_field_fail(self):
        self.assertEqual(run(task_text="- [x] TASK-01 · work it\n").
                         check("task_eligible").state, gate.FAILED)
        self.assertEqual(run(task_text="- [-] TASK-01 · work it\n").
                         check("task_evidence").state, gate.FAILED)
        self.assertEqual(run(task_text="- [-] TASK-01 · work it\n  - evidence: none\n").
                         check("task_evidence").state, gate.FAILED)
        self.assertEqual(run(task_text="- [-] TASK-01 · work it\n  - evidence: pending\n").
                         check("task_evidence").state, gate.FAILED)

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
            notes = base_path / "docs" / "pr" / "notes"
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


if __name__ == "__main__":
    unittest.main()
