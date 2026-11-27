"""Regression tests for the single required Delivery status check.

Every scenario names the event and ref type so the tests pin down *why* a skip
is legitimate. A job that is merely absent, cancelled or skipped without a
documented reason must never let the gate pass.
"""
import contextlib
import io
import json
import runpy
import sys
import unittest
from pathlib import Path
from unittest import mock

from helpers import load

gate = load("check-delivery-gate")


def results(**overrides):
    """A clean run where every job succeeded unless a job is overridden."""
    base = {name: {"result": "success"} for name in gate.EXPECTED}
    base.update({name: value if isinstance(value, dict) else {"result": value}
                 for name, value in overrides.items()})
    return base


def check(data, event_name="push", ref_type="branch"):
    """Call main with a JSON payload and capture both output streams."""
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = gate.main(["--results", json.dumps(data),
                          "--event-name", event_name, "--ref-type", ref_type])
    return code, out.getvalue(), err.getvalue()


class GateTests(unittest.TestCase):
    def test_all_jobs_succeed(self):
        code, out, err = check(results())
        self.assertEqual(code, 0, err)
        self.assertIn("all required checks passed", out)
        self.assertEqual(err, "")

    def test_mandatory_failure_fails_the_gate(self):
        for name in sorted(gate.MANDATORY):
            with self.subTest(name=name):
                code, _, err = check(results(**{name: "failure"}))
                self.assertEqual(code, 1)
                self.assertIn(f"mandatory job {name} is failure", err)

    def test_mandatory_cancellation_fails_the_gate(self):
        code, _, err = check(results(**{"java-verify": "cancelled"}))
        self.assertEqual(code, 1)
        self.assertIn("mandatory job java-verify is cancelled", err)

    def test_mandatory_skip_fails_the_gate(self):
        code, _, err = check(results(**{"frontend": "skipped"}))
        self.assertEqual(code, 1)
        self.assertIn("mandatory job frontend is skipped", err)

    def test_unexpected_result_fails_the_gate(self):
        code, _, err = check(results(**{"docs": "neutral"}))
        self.assertEqual(code, 1)
        self.assertIn("mandatory job docs is neutral", err)

    def test_missing_job_fails_the_gate(self):
        data = results()
        del data["script-tests"]
        code, out, err = check(data)
        self.assertEqual(code, 1)
        self.assertIn("missing job results: script-tests", err)
        self.assertNotIn("script-tests:", out)

    def test_unknown_job_fails_the_gate(self):
        code, out, err = check(results(**{
            "new-job": "success",
            "another-job": "success",
        }))
        self.assertEqual(code, 1)
        self.assertIn("unexpected job results: another-job, new-job", err)
        self.assertEqual(out.splitlines()[-2:], [
            "another-job: success",
            "new-job: success",
        ])

    def test_script_tests_failure_precedes_dependent_skips(self):
        data = results(**{
            "script-tests": "failure",
            "frontend": "skipped",
            "java-verify": "skipped",
            "docs": "skipped",
            "verify": "skipped",
            "release": "skipped",
        })
        code, out, err = check(data, event_name="workflow_dispatch", ref_type="branch")

        self.assertEqual(code, 1)
        self.assertEqual(out.splitlines(), [
            "policy: success",
            "script-tests: failure",
            "frontend: skipped",
            "java-verify: skipped",
            "docs: skipped",
            "verify: skipped",
            "release: skipped",
        ])
        self.assertEqual(err.splitlines(), [
            "delivery gate: dependency root cause: script-tests is failure; "
            "downstream jobs were skipped",
            "delivery gate: mandatory job script-tests is failure, expected success",
            "delivery gate: mandatory job frontend is skipped, expected success",
            "delivery gate: mandatory job java-verify is skipped, expected success",
            "delivery gate: mandatory job docs is skipped, expected success",
            "delivery gate: job verify was skipped but workflow_dispatch/branch requires it",
        ])

    def test_each_failed_dependency_precedes_its_downstream_skips(self):
        cases = (
            ("policy", {
                "script-tests": "skipped", "frontend": "skipped",
                "java-verify": "skipped", "docs": "skipped",
                "verify": "skipped", "release": "skipped",
            }),
            ("script-tests", {
                "frontend": "skipped", "java-verify": "skipped",
                "docs": "skipped", "verify": "skipped", "release": "skipped",
            }),
            ("frontend", {
                "docs": "skipped", "verify": "skipped", "release": "skipped",
            }),
            ("java-verify", {
                "docs": "skipped", "verify": "skipped", "release": "skipped",
            }),
            ("docs", {"verify": "skipped", "release": "skipped"}),
            ("verify", {"release": "skipped"}),
        )
        for root, skips in cases:
            with self.subTest(root=root):
                code, _, err = check(
                    results(**{root: "failure", **skips}),
                    event_name="push", ref_type="tag")
                self.assertEqual(code, 1)
                self.assertEqual(
                    err.splitlines()[0],
                    f"delivery gate: dependency root cause: {root} is failure; "
                    "downstream jobs were skipped")

    def test_multiple_failed_dependencies_are_named_in_workflow_order(self):
        data = results(**{
            "frontend": "failure",
            "java-verify": "failure",
            "docs": "skipped",
            "verify": "skipped",
            "release": "skipped",
        })
        code, _, err = check(data, event_name="push", ref_type="tag")

        self.assertEqual(code, 1)
        self.assertEqual(
            err.splitlines()[0],
            "delivery gate: dependency root causes: frontend is failure, "
            "java-verify is failure; downstream jobs were skipped")

    def test_only_the_transitive_root_blocker_is_named(self):
        data = results(**{
            "script-tests": "failure",
            "frontend": "failure",
            "docs": "skipped",
            "verify": "skipped",
            "release": "skipped",
        })
        code, _, err = check(data, event_name="push", ref_type="tag")

        self.assertEqual(code, 1)
        self.assertEqual(
            err.splitlines()[0],
            "delivery gate: dependency root cause: script-tests is failure; "
            "downstream jobs were skipped")
        self.assertIn("mandatory job frontend is failure", err)

    def test_legal_optional_skips_do_not_create_a_dependency_diagnosis(self):
        data = results(**{
            "script-tests": "failure",
            "frontend": "skipped",
            "java-verify": "skipped",
            "docs": "skipped",
            "verify": "skipped",
            "release": "skipped",
        })
        code, _, err = check(data, event_name="pull_request", ref_type="branch")

        self.assertEqual(code, 1)
        self.assertEqual(err.splitlines(), [
            "delivery gate: dependency root cause: script-tests is failure; "
            "downstream jobs were skipped",
            "delivery gate: mandatory job script-tests is failure, expected success",
            "delivery gate: mandatory job frontend is skipped, expected success",
            "delivery gate: mandatory job java-verify is skipped, expected success",
            "delivery gate: mandatory job docs is skipped, expected success",
        ])

    def test_independent_failure_or_skip_has_no_root_cause_summary(self):
        code, _, err = check(results(frontend="skipped"))
        self.assertEqual(code, 1)
        self.assertEqual(err, "delivery gate: mandatory job frontend is skipped, expected success\n")

        code, _, err = check(results(**{"java-verify": "failure"}))
        self.assertEqual(code, 1)
        self.assertEqual(err, "delivery gate: mandatory job java-verify is failure, expected success\n")

    def test_unrelated_failure_and_skip_have_no_root_cause_summary(self):
        code, _, err = check(results(**{
            "frontend": "skipped",
            "java-verify": "failure",
        }))
        self.assertEqual(code, 1)
        self.assertNotIn("dependency root cause", err)
        self.assertIn("mandatory job frontend is skipped", err)
        self.assertIn("mandatory job java-verify is failure", err)

    def test_cancelled_and_unknown_blockers_are_named_first(self):
        for value, displayed in (("cancelled", "cancelled"), (None, "unknown")):
            with self.subTest(result=value):
                data = results(**{
                    "script-tests": value,
                    "frontend": "skipped",
                    "java-verify": "skipped",
                    "docs": "skipped",
                    "verify": "skipped",
                    "release": "skipped",
                })
                code, _, err = check(data, event_name="workflow_dispatch", ref_type="branch")
                self.assertEqual(code, 1)
                self.assertEqual(
                    err.splitlines()[0],
                    f"delivery gate: dependency root cause: script-tests is {displayed}; "
                    "downstream jobs were skipped")

    def test_missing_result_is_not_inferred_as_a_dependency_root(self):
        data = results(**{
            "frontend": "skipped",
            "java-verify": "skipped",
            "docs": "skipped",
            "verify": "skipped",
            "release": "skipped",
        })
        del data["script-tests"]
        code, _, err = check(data, event_name="workflow_dispatch", ref_type="branch")

        self.assertEqual(code, 1)
        self.assertEqual(err.splitlines()[0], "delivery gate: missing job results: script-tests")
        self.assertNotIn("dependency root cause", err)

    def test_verify_may_be_skipped_only_on_a_pull_request(self):
        code, _, err = check(results(verify="skipped"), event_name="pull_request")
        self.assertEqual(code, 0, err)

    def test_verify_skip_on_other_events_fails(self):
        for event_name in ("push", "workflow_dispatch", "merge_group"):
            with self.subTest(event_name=event_name):
                code, _, err = check(results(verify="skipped"), event_name=event_name)
                self.assertEqual(code, 1)
                self.assertIn(f"job verify was skipped but {event_name}", err)

    def test_verify_success_is_accepted_everywhere(self):
        for event_name in ("pull_request", "push", "workflow_dispatch", "merge_group"):
            with self.subTest(event_name=event_name):
                code, _, err = check(results(), event_name=event_name)
                self.assertEqual(code, 0, err)

    def test_release_may_be_skipped_only_off_a_tag(self):
        for ref_type in ("branch", "", "unknown"):
            with self.subTest(ref_type=ref_type):
                code, _, err = check(results(release="skipped"), ref_type=ref_type)
                self.assertEqual(code, 0, err)

    def test_release_skip_on_a_tag_fails(self):
        code, _, err = check(results(release="skipped"), event_name="push", ref_type="tag")
        self.assertEqual(code, 1)
        self.assertIn("job release was skipped but push/tag requires it", err)

    def test_release_failure_fails_every_event(self):
        for event_name, ref_type in (("pull_request", "branch"), ("push", "branch"),
                                     ("push", "tag"), ("merge_group", "branch")):
            with self.subTest(event=event_name, ref=ref_type):
                code, _, err = check(results(release="cancelled"), event_name, ref_type)
                self.assertEqual(code, 1)
                self.assertIn("job release is cancelled", err)

    def test_a_tag_run_with_all_jobs_is_accepted(self):
        code, out, err = check(results(), event_name="push", ref_type="tag")
        self.assertEqual(code, 0, err)
        self.assertIn("release: success", out)

    def test_a_merge_group_run_with_verify_is_accepted(self):
        code, _, err = check(results(verify="success"), event_name="merge_group", ref_type="branch")
        self.assertEqual(code, 0, err)

    def test_malformed_result_values_fail_closed(self):
        code, _, err = check(results(**{"policy": None}))
        self.assertEqual(code, 1)
        self.assertIn("mandatory job policy is unknown", err)

    def test_a_non_object_payload_is_rejected(self):
        for payload in ([], "nope", None, 42):
            with self.subTest(payload=payload):
                code, _, err = check(payload)
                self.assertEqual(code, 1)
                self.assertIn("needs results are not an object", err)

    def test_invalid_json_exits_with_a_parse_error(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = gate.main(["--results", "{broken", "--event-name", "push",
                              "--ref-type", "branch"])
        self.assertEqual(code, 2)
        self.assertIn("invalid results JSON", err.getvalue())


class CommandLineTests(unittest.TestCase):
    """Run the script as an executable so the ``__main__`` entry point is exercised."""

    def run_script(self, payload):
        """Execute check-delivery-gate.py via runpy and return its exit code."""
        script = Path(__file__).resolve().parents[1] / "check-delivery-gate.py"
        argv = [str(script), "--results", json.dumps(payload),
                "--event-name", "push", "--ref-type", "branch"]
        with mock.patch.object(sys, "argv", argv), \
                contextlib.redirect_stdout(io.StringIO()), \
                contextlib.redirect_stderr(io.StringIO()), \
                self.assertRaises(SystemExit) as raised:
            runpy.run_path(str(script), run_name="__main__")
        return raised.exception.code

    def test_the_entry_point_passes_for_a_clean_run(self):
        self.assertEqual(self.run_script(results()), 0)

    def test_the_entry_point_fails_for_a_failed_job(self):
        self.assertEqual(self.run_script(results(**{"java-verify": "failure"})), 1)


if __name__ == "__main__":
    unittest.main()
