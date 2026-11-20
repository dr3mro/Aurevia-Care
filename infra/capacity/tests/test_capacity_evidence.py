"""Executable acceptance tests for the OPS-03 capacity evidence evaluator.

All fixtures are synthetic. A conforming artifact must pass; every OPS-03
threshold, profile parameter and measurement-scope omission must fail closed.
The tests enumerate the evaluator's branches so the checker's behaviour is fully
specified without a real deployment.
"""
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "capacity_evidence.py"
SPEC = importlib.util.spec_from_file_location("capacity_evidence", MODULE_PATH)
capacity_evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capacity_evidence)

ce = capacity_evidence


def synthetic_artifact(**overrides):
    """Build a synthetic, conforming OPS-03 evidence artifact."""
    artifact = {
        "schema_version": "1.0",
        "kind": "synthetic_rehearsal",
        "source": "synthetic-load-fixture",
        "profile": {
            "organizations": 20,
            "patient_charts": 100000,
            "concurrent_staff": 200,
            "requests_per_second": 50,
            "duration_minutes": 30,
        },
        "measurement": {
            "scope": "server_side",
            "excluded": ["document_transfer", "document_rendering", "external_calls"],
            "route_class": {
                "patients.search": "read",
                "appointments.list": "read",
                "appointments.book": "command",
                "orders.accept": "command",
            },
        },
        "results": {
            "availability": {"window": "calendar_month", "percent": 99.95},
            "p95_read_ms": 480,
            "p95_command_ms": 870,
            "total_requests": 90000,
            "read_requests": 63000,
            "command_requests": 27000,
        },
    }
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(artifact.get(key), dict):
            artifact[key].update(value)
        else:
            artifact[key] = value
    return artifact


def measured_artifact(**overrides):
    """Build a synthetic artifact that claims a measured pilot with provenance."""
    artifact = synthetic_artifact(kind="measured_pilot", source="staging-load-run")
    artifact["provenance"] = {
        "tool": "synthetic-load-generator",
        "tool_version": "1.0.0",
        "command": "synthetic-run --profile pilot-load-profile.json",
        "utc_timestamp": "2026-11-19T12:00:00Z",
        "environment": "synthetic-staging",
        "source_sha": "0" * 40,
    }
    for key, value in overrides.items():
        artifact[key] = value
    return artifact


def provenance_missing(field):
    """Return a complete provenance object with one required field removed."""
    data = measured_artifact()["provenance"]
    del data[field]
    return data


def failures(report):
    """Return the set of failed check names in a report."""
    return set(report["failed"])


class ConformingArtifactTest(unittest.TestCase):
    def test_synthetic_rehearsal_passes_but_is_not_final_evidence(self):
        report = ce.evaluate(synthetic_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertEqual(report["classification"], "rehearsal")
        self.assertFalse(report["final_evidence"])
        self.assertTrue(all(c["passed"] for c in report["checks"]))

    def test_measured_pilot_kind_is_accepted_as_final_evidence(self):
        report = ce.evaluate(measured_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["classification"], "measured-pilot")
        self.assertTrue(report["final_evidence"])

    def test_unknown_kind_is_never_final_evidence(self):
        report = ce.evaluate(synthetic_artifact(kind="unverified"))
        self.assertEqual(report["classification"], "unknown")
        self.assertFalse(report["final_evidence"])


class StructureTest(unittest.TestCase):
    def test_artifact_must_be_an_object(self):
        report = ce.evaluate([])
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("artifact", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_schema_version_must_match(self):
        report = ce.evaluate(synthetic_artifact(schema_version="2.0"))
        self.assertIn("schema_version", failures(report))

    def test_kind_must_be_known(self):
        report = ce.evaluate(synthetic_artifact(kind="unverified"))
        self.assertIn("kind", failures(report))

    def test_unhashable_kind_fails_closed_without_raising(self):
        # A non-string kind must be reported as a failed check, not crash the
        # evaluator while it builds the report classification.
        for value in ([], {}, ["measured_pilot"], {"kind": "measured_pilot"}):
            with self.subTest(value=value):
                report = ce.evaluate(synthetic_artifact(kind=value))
                self.assertEqual(report["verdict"], "fail")
                self.assertIn("kind", failures(report))
                self.assertEqual(report["classification"], "unknown")
                self.assertFalse(report["final_evidence"])

    def test_measured_pilot_cannot_declare_a_synthetic_source(self):
        report = ce.evaluate(synthetic_artifact(
            kind="measured_pilot", source="SYNTHETIC-fixture"))
        self.assertIn("evidence_conflict", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_measured_pilot_with_neutral_source_has_no_conflict(self):
        report = ce.evaluate(synthetic_artifact(
            kind="measured_pilot", source="staging-load-run"))
        self.assertNotIn("evidence_conflict", failures(report))

    def test_profile_must_be_an_object(self):
        report = ce.evaluate(synthetic_artifact(profile=[]))
        self.assertIn("profile", failures(report))

    def test_measurement_must_be_an_object(self):
        report = ce.evaluate(synthetic_artifact(measurement="server_side"))
        self.assertIn("measurement", failures(report))

    def test_results_must_be_an_object(self):
        report = ce.evaluate(synthetic_artifact(results=None))
        self.assertIn("results", failures(report))


class ProvenanceTest(unittest.TestCase):
    """A measured-pilot claim must carry the provenance the docs require."""

    def test_measured_pilot_without_provenance_fails_closed(self):
        report = ce.evaluate(synthetic_artifact(
            kind="measured_pilot", source="staging-load-run"))
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("provenance", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_each_required_provenance_field_is_enforced(self):
        for field in ce.MEASURED_PROVENANCE_FIELDS:
            with self.subTest(field=field):
                report = ce.evaluate(measured_artifact(
                    provenance=provenance_missing(field)))
                self.assertIn("provenance." + field, failures(report))
        for value in (None, [], "not-an-object", {}):
            with self.subTest(value=value):
                report = ce.evaluate(measured_artifact(provenance=value))
                self.assertIn("provenance", failures(report))

    def test_measured_pilot_source_must_be_present(self):
        artifact = measured_artifact()
        del artifact["source"]
        report = ce.evaluate(artifact)
        self.assertIn("source", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_complete_provenance_is_final_evidence(self):
        report = ce.evaluate(measured_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertTrue(report["final_evidence"])

    def test_synthetic_rehearsal_does_not_require_provenance(self):
        report = ce.evaluate(synthetic_artifact())
        self.assertNotIn("provenance", failures(report))
        self.assertFalse(report["final_evidence"])


class ProfileTest(unittest.TestCase):
    def test_each_target_value_is_enforced(self):
        for key, wrong in (
            ("organizations", 19),
            ("patient_charts", 99999),
            ("concurrent_staff", 199),
            ("requests_per_second", 49),
            ("duration_minutes", 29),
        ):
            with self.subTest(key=key):
                report = ce.evaluate(synthetic_artifact(profile={key: wrong}))
                self.assertIn("profile." + key, failures(report))

    def test_profile_values_must_be_positive_integers(self):
        for value in (0, -1, "20", True, None):
            with self.subTest(value=value):
                report = ce.evaluate(synthetic_artifact(profile={"organizations": value}))
                self.assertIn("profile.organizations", failures(report))


class MeasurementScopeTest(unittest.TestCase):
    def test_scope_must_be_server_side(self):
        for scope in ("client_side", "gateway", None):
            with self.subTest(scope=scope):
                report = ce.evaluate(synthetic_artifact(measurement={"scope": scope}))
                self.assertIn("measurement.scope", failures(report))

    def test_all_required_exclusions_are_enforced(self):
        for excluded in (
            ["document_transfer", "document_rendering"],
            ["document_transfer", "external_calls"],
            ["document_rendering", "external_calls"],
            [],
            None,
            "document_transfer",
        ):
            with self.subTest(excluded=excluded):
                report = ce.evaluate(synthetic_artifact(
                    measurement={"excluded": excluded}))
                self.assertIn("measurement.excluded", failures(report))

    def test_extra_or_duplicate_exclusions_are_rejected(self):
        for excluded in (
            ["document_transfer", "document_rendering", "external_calls",
             "object_storage"],
            ["document_transfer", "document_rendering", "external_calls",
             "external_calls"],
        ):
            with self.subTest(excluded=excluded):
                report = ce.evaluate(synthetic_artifact(
                    measurement={"excluded": excluded}))
                self.assertIn("measurement.excluded", failures(report))

    def test_exclusions_must_be_comparable(self):
        # An unhashable member must fail closed rather than raising TypeError.
        excluded = ["document_transfer", "document_rendering", {"external_calls": 1}]
        report = ce.evaluate(synthetic_artifact(measurement={"excluded": excluded}))
        self.assertIn("measurement.excluded", failures(report))

    def test_route_class_must_declare_both_classes(self):
        for routes in (
            {"patients.search": "read"},
            {"appointments.book": "command"},
            {"a": "other"},
            {},
            None,
            ["read", "command"],
        ):
            with self.subTest(routes=routes):
                report = ce.evaluate(synthetic_artifact(
                    measurement={"route_class": routes}))
                self.assertIn("measurement.route_class", failures(report))

    def test_route_class_rejects_unhashable_values(self):
        routes = {"a": "read", "b": ["command"]}
        report = ce.evaluate(synthetic_artifact(measurement={"route_class": routes}))
        self.assertIn("measurement.route_class", failures(report))


class AvailabilityTest(unittest.TestCase):
    def test_availability_must_declare_a_calendar_month_window(self):
        for window in ("30_minutes", "pilot_run", None):
            with self.subTest(window=window):
                report = ce.evaluate(synthetic_artifact(results={
                    "availability": {"window": window, "percent": 99.95}}))
                self.assertIn("availability.window", failures(report))

    def test_availability_must_be_an_object(self):
        report = ce.evaluate(synthetic_artifact(results={"availability": 99.95}))
        self.assertIn("availability", failures(report))

    def test_availability_boundary(self):
        report = ce.evaluate(synthetic_artifact(results={
            "availability": {"window": "calendar_month", "percent": 99.9}}))
        self.assertNotIn("availability.percent", failures(report))

    def test_availability_below_target_fails(self):
        report = ce.evaluate(synthetic_artifact(results={
            "availability": {"window": "calendar_month", "percent": 99.89}}))
        self.assertIn("availability.percent", failures(report))

    def test_availability_must_be_a_percent(self):
        for value in (0, -1, 100.1, "99.95", None, float("inf")):
            with self.subTest(value=value):
                report = ce.evaluate(synthetic_artifact(results={
                    "availability": {"window": "calendar_month", "percent": value}}))
                self.assertIn("availability.percent", failures(report))


class ThresholdTest(unittest.TestCase):
    def test_p95_read_boundary(self):
        report = ce.evaluate(synthetic_artifact(results={"p95_read_ms": 500}))
        self.assertNotIn("results.p95_read_ms", failures(report))

    def test_p95_read_above_target_fails(self):
        report = ce.evaluate(synthetic_artifact(results={"p95_read_ms": 501}))
        self.assertIn("results.p95_read_ms", failures(report))

    def test_p95_command_boundary(self):
        report = ce.evaluate(synthetic_artifact(results={"p95_command_ms": 900}))
        self.assertNotIn("results.p95_command_ms", failures(report))

    def test_p95_command_above_target_fails(self):
        report = ce.evaluate(synthetic_artifact(results={"p95_command_ms": 901}))
        self.assertIn("results.p95_command_ms", failures(report))

    def test_latencies_must_be_positive_numbers(self):
        for value in (0, -1, "480", True, None, float("nan"), float("inf")):
            with self.subTest(value=value):
                for key in ("p95_read_ms", "p95_command_ms"):
                    report = ce.evaluate(synthetic_artifact(results={key: value}))
                    self.assertIn("results." + key, failures(report))


class WorkloadTest(unittest.TestCase):
    def test_request_counts_must_be_positive_numbers(self):
        for key in ("total_requests", "read_requests", "command_requests"):
            for value in (0, -1, "90000", None, float("nan"), float("inf")):
                with self.subTest(key=key, value=value):
                    report = ce.evaluate(synthetic_artifact(results={key: value}))
                    self.assertIn("results.mixed", failures(report))

    def test_total_must_equal_read_plus_command(self):
        report = ce.evaluate(synthetic_artifact(results={"total_requests": 90001}))
        self.assertIn("results.mixed", failures(report))

    def test_infinite_request_counts_cannot_pass_the_mixed_check(self):
        report = ce.evaluate(synthetic_artifact(results={
            "total_requests": float("inf"),
            "read_requests": float("inf"),
            "command_requests": float("inf"),
        }))
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("results.mixed", failures(report))

    def test_each_class_needs_a_minimum_sample_share(self):
        # A single command sample would make p95_command_ms meaningless.
        at_floor = ce.evaluate(synthetic_artifact(results={
            "total_requests": 90000, "read_requests": 89100,
            "command_requests": 900}))
        self.assertNotIn("results.class_balance", failures(at_floor))
        below_floor = ce.evaluate(synthetic_artifact(results={
            "total_requests": 90000, "read_requests": 89101,
            "command_requests": 899}))
        self.assertIn("results.class_balance", failures(below_floor))

    def test_sustained_rate_is_enforced(self):
        # 50 rps * 30 min = 90,000; 10% ramp tolerance => 81,000 minimum.
        passing = ce.evaluate(synthetic_artifact(results={
            "total_requests": 81000, "read_requests": 56700,
            "command_requests": 24300}))
        self.assertNotIn("results.sustained_rate", failures(passing))
        report = ce.evaluate(synthetic_artifact(results={
            "total_requests": 80999, "read_requests": 56699,
            "command_requests": 24300}))
        self.assertIn("results.sustained_rate", failures(report))

    def test_all_breaches_are_reported_together(self):
        report = ce.evaluate(synthetic_artifact(
            schema_version="2.0",
            profile={"organizations": 1},
            measurement={"scope": "client_side"},
            results={"p95_read_ms": 501, "p95_command_ms": 901},
        ))
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({
            "schema_version", "profile.organizations", "measurement.scope",
            "results.p95_read_ms", "results.p95_command_ms",
        }.issubset(failures(report)))


class ReportAndCliTest(unittest.TestCase):
    def test_format_report_shows_each_check_and_verdict(self):
        passed = ce.format_report(ce.evaluate(synthetic_artifact()))
        self.assertIn("[PASS]", passed)
        self.assertIn("results.p95_read_ms", passed)
        self.assertIn("OPS-03 capacity evidence: PASS", passed)
        self.assertIn("evidence class: rehearsal (final: False)", passed)
        failing = ce.format_report(ce.evaluate(synthetic_artifact(kind="unverified")))
        self.assertIn("[FAIL]", failing)
        self.assertIn("evidence class: unknown (final: False)", failing)
        final = ce.format_report(ce.evaluate(measured_artifact()))
        self.assertIn("final: True", final)

    def test_main_evaluates_a_conforming_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = ce.main([str(path)])
            self.assertEqual(code, 0)
            self.assertIn("PASS", stdout.getvalue())

    def test_main_returns_one_for_a_failing_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact(
                results={"p95_read_ms": 900})), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = ce.main([str(path)])
            self.assertEqual(code, 1)

    def test_main_reads_sys_argv_when_no_arguments_are_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with mock.patch.object(sys, "argv", ["capacity_evidence.py", str(path)]):
                with contextlib.redirect_stdout(stdout):
                    code = ce.main()
            self.assertEqual(code, 0)

    def test_main_rejects_bad_usage(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = ce.main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_a_missing_file(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = ce.main(["/definitely/not/here/evidence.json"])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text("{not json", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = ce.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_utf8(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_bytes(b"\xff\xfe\x00")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = ce.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())


class PilotLoadProfileTest(unittest.TestCase):
    """The declared workload artifact must agree with the enforced OPS-03 targets."""

    @classmethod
    def setUpClass(cls):
        path = MODULE_PATH.parent / "pilot-load-profile.json"
        cls.profile = json.loads(path.read_text(encoding="utf-8"))

    def test_profile_matches_the_enforced_targets(self):
        self.assertEqual(self.profile["profile"], ce.TARGET_PROFILE)
        self.assertEqual(self.profile["requirement"], "OPS-03")

    def test_dataset_is_synthetic_and_consistent(self):
        dataset = self.profile["dataset"]
        self.assertTrue(dataset["synthetic_only"])
        self.assertEqual(dataset["charts_per_organization"]
                         * ce.TARGET_PROFILE["organizations"],
                         dataset["total_charts"])
        self.assertEqual(dataset["total_charts"], ce.TARGET_PROFILE["patient_charts"])
        self.assertTrue(dataset["two_organizations_share_local_ids"])

    def test_workload_mix_is_complete_and_balanced(self):
        mix = self.profile["workload_mix"]
        self.assertEqual(mix["read"] + mix["command"], 1.0)
        families = mix["families"]
        self.assertTrue(families)
        self.assertEqual({f["class"] for f in families}, set(ce.ROUTE_CLASSES))

    def test_ramp_keeps_the_measured_window_at_thirty_minutes(self):
        ramp = self.profile["ramp"]
        self.assertEqual(ramp["measured_window_minutes"],
                         ce.TARGET_PROFILE["duration_minutes"])
        self.assertTrue(ramp["warm_up_excluded_from_latency"])

    def test_evidence_route_allowlist_matches_declared_families(self):
        evidence = json.loads((MODULE_PATH.parent / "fixtures"
                               / "synthetic_pilot_evidence.json").read_text(encoding="utf-8"))
        families = {family["name"]: family["class"]
                    for family in self.profile["workload_mix"]["families"]}
        self.assertEqual(evidence["measurement"]["route_class"], families)

    def test_fixture_text_contains_no_phi_shaped_values(self):
        import re
        text = json.dumps(self.profile)
        self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", text))
        self.assertIsNone(re.search(r"\b\d{3}-\d{2}-\d{4}\b", text))


if __name__ == "__main__":
    unittest.main()
