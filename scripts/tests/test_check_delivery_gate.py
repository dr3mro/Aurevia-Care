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
        code, _, err = check(data)
        self.assertEqual(code, 1)
        self.assertIn("missing job results: script-tests", err)

    def test_unknown_job_fails_the_gate(self):
        code, _, err = check(results(**{"new-job": "success"}))
        self.assertEqual(code, 1)
        self.assertIn("unexpected job results: new-job", err)

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
