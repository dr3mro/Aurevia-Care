"""Executable acceptance tests for the OPS-04/OPS-05 recovery evidence evaluator.

All fixtures are synthetic. A conforming artifact must pass; every OPS-04 target,
restore scope, reconciliation gate, verification cadence and OPS-05 monitoring,
ownership, runbook and duplicate-prevention omission must fail closed. The tests
enumerate the evaluator's branches so its behaviour is fully specified without a
real deployment or a real restore drill.
"""
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "recovery_evidence.py"
SPEC = importlib.util.spec_from_file_location("recovery_evidence", MODULE_PATH)
recovery_evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(recovery_evidence)

re = recovery_evidence


def synthetic_artifact(**overrides):
    """Build a synthetic, conforming OPS-04/OPS-05 recovery evidence artifact."""
    artifact = {
        "schema_version": "1.0",
        "kind": "synthetic_rehearsal",
        "source": "synthetic-restore-fixture",
        "targets": {"rpo_minutes": 12, "rto_hours": 3.5},
        "scope": ["core_chart", "order_data", "documents", "document_associations"],
        "restore": {
            "targets": ["database", "objects", "idp_dependencies"],
            "clean_environment": True,
        },
        "reconciliation": {
            "hashes": True,
            "source_versions": True,
            "in_flight_deliveries": True,
            "audit_coverage": True,
            "completed_before_writes_reopened": True,
        },
        "verification": {"before_pilot": True, "quarterly_cadence": True},
        "monitoring": {
            "signals": [
                "error_rates", "saturation", "queue_age", "partner_delivery",
                "unmatched_reports", "critical_acknowledgment_time",
                "missing_coverage", "unassigned_patient_messages",
                "response_breaches", "failed_instruction_notifications",
                "overdue_instruction_acknowledgments", "rendering_failures",
                "backup_status",
            ]
        },
        "ownership": {
            "operational_on_call_owner": "platform-oncall",
            "site_clinical_coverage_owner": "site-medical-director",
        },
        "runbooks": [
            "paper_manual_capture", "patient_identification",
            "critical_contacts", "supervised_back_entry",
        ],
        "duplicate_prevention": True,
    }
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(artifact.get(key), dict):
            artifact[key].update(value)
        else:
            artifact[key] = value
    return artifact


def failures(report):
    """Return the set of failed check names in a report."""
    return set(report["failed"])


class ConformingArtifactTest(unittest.TestCase):
    def test_synthetic_rehearsal_passes_but_is_not_final_evidence(self):
        report = re.evaluate(synthetic_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertEqual(report["classification"], "rehearsal")
        self.assertFalse(report["final_evidence"])
        self.assertTrue(all(c["passed"] for c in report["checks"]))

    def test_conducted_drill_kind_is_accepted_as_final_evidence(self):
        report = re.evaluate(synthetic_artifact(
            kind="conducted_drill", source="restore-drill-2026-11-19"))
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["classification"], "conducted-drill")
        self.assertTrue(report["final_evidence"])

    def test_unknown_kind_is_never_final_evidence(self):
        report = re.evaluate(synthetic_artifact(kind="unverified"))
        self.assertEqual(report["classification"], "unknown")
        self.assertFalse(report["final_evidence"])
        self.assertIn("kind", failures(report))

    def test_wrong_typed_kind_is_reported_not_raised(self):
        for kind in ([], {}, 7, None):
            with self.subTest(kind=kind):
                report = re.evaluate(synthetic_artifact(kind=kind))
                self.assertIn("kind", failures(report))
                self.assertEqual(report["classification"], "unknown")
                self.assertFalse(report["final_evidence"])


class StructureTest(unittest.TestCase):
    def test_artifact_must_be_an_object(self):
        report = re.evaluate([])
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("artifact", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_schema_version_must_match(self):
        report = re.evaluate(synthetic_artifact(schema_version="2.0"))
        self.assertIn("schema_version", failures(report))

    def test_kind_must_be_known(self):
        report = re.evaluate(synthetic_artifact(kind="unverified"))
        self.assertIn("kind", failures(report))

    def test_conducted_drill_cannot_declare_a_synthetic_source(self):
        report = re.evaluate(synthetic_artifact(
            kind="conducted_drill", source="SYNTHETIC-rehearsal"))
        self.assertIn("evidence_conflict", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_conducted_drill_with_neutral_source_has_no_conflict(self):
        report = re.evaluate(synthetic_artifact(
            kind="conducted_drill", source="restore-drill-2026-11-19"))
        self.assertNotIn("evidence_conflict", failures(report))

    def test_conducted_drill_rejects_other_synthetic_markers(self):
        for marker in ("fixture", "seed", "mock"):
            with self.subTest(marker=marker):
                report = re.evaluate(synthetic_artifact(
                    kind="conducted_drill", source=f"drill-{marker}-data"))
                self.assertIn("evidence_conflict", failures(report))
                self.assertFalse(report["final_evidence"])

    def test_source_must_be_a_non_blank_string(self):
        for source in ("", "   ", None, 7):
            with self.subTest(source=source):
                report = re.evaluate(synthetic_artifact(source=source))
                self.assertIn("source", failures(report))

    def test_empty_object_reports_every_section_check(self):
        report = re.evaluate({})
        expected = {
            "schema_version", "kind", "source", "targets", "scope", "restore",
            "reconciliation", "verification", "monitoring", "ownership",
            "runbooks", "duplicate_prevention",
        }
        self.assertTrue(expected <= failures(report))
        self.assertEqual(report["verdict"], "fail")
        self.assertFalse(report["final_evidence"])


class TargetsTest(unittest.TestCase):
    def test_targets_must_be_an_object(self):
        report = re.evaluate(synthetic_artifact(targets=[]))
        self.assertIn("targets", failures(report))

    def test_rpo_at_limit_passes(self):
        report = re.evaluate(synthetic_artifact(targets={"rpo_minutes": 15}))
        passed = {c["name"] for c in report["checks"] if c["passed"]}
        self.assertIn("targets.rpo_minutes", passed)

    def test_rpo_above_limit_fails(self):
        report = re.evaluate(synthetic_artifact(targets={"rpo_minutes": 16}))
        self.assertIn("targets.rpo_minutes", failures(report))

    def test_rpo_zero_is_allowed_as_zero_data_loss(self):
        report = re.evaluate(synthetic_artifact(targets={"rpo_minutes": 0}))
        self.assertNotIn("targets.rpo_minutes", failures(report))

    def test_rpo_must_be_non_negative(self):
        report = re.evaluate(synthetic_artifact(targets={"rpo_minutes": -1}))
        self.assertIn("targets.rpo_minutes", failures(report))

    def test_rpo_rejects_non_finite_numbers(self):
        for value in (float("nan"), float("inf")):
            with self.subTest(value=value):
                report = re.evaluate(synthetic_artifact(targets={"rpo_minutes": value}))
                self.assertIn("targets.rpo_minutes", failures(report))

    def test_rpo_cannot_be_a_boolean(self):
        report = re.evaluate(synthetic_artifact(targets={"rpo_minutes": True}))
        self.assertIn("targets.rpo_minutes", failures(report))

    def test_rpo_must_be_numeric(self):
        report = re.evaluate(synthetic_artifact(targets={"rpo_minutes": "12"}))
        self.assertIn("targets.rpo_minutes", failures(report))

    def test_rto_at_limit_passes(self):
        report = re.evaluate(synthetic_artifact(targets={"rto_hours": 4}))
        passed = {c["name"] for c in report["checks"] if c["passed"]}
        self.assertIn("targets.rto_hours", passed)

    def test_rto_above_limit_fails(self):
        report = re.evaluate(synthetic_artifact(targets={"rto_hours": 4.5}))
        self.assertIn("targets.rto_hours", failures(report))

    def test_rto_must_be_positive(self):
        report = re.evaluate(synthetic_artifact(targets={"rto_hours": -1}))
        self.assertIn("targets.rto_hours", failures(report))

    def test_rto_cannot_be_a_boolean(self):
        report = re.evaluate(synthetic_artifact(targets={"rto_hours": False}))
        self.assertIn("targets.rto_hours", failures(report))

    def test_rto_must_be_numeric(self):
        report = re.evaluate(synthetic_artifact(targets={"rto_hours": "3"}))
        self.assertIn("targets.rto_hours", failures(report))


class CoverageListTest(unittest.TestCase):
    """Tests for the shared required-members list checker used by several sections."""

    def test_scope_must_be_a_list(self):
        report = re.evaluate(synthetic_artifact(scope={"core_chart": True}))
        self.assertIn("scope", failures(report))

    def test_scope_missing_a_required_member_fails(self):
        report = re.evaluate(synthetic_artifact(scope=["core_chart", "order_data"]))
        self.assertIn("scope", failures(report))

    def test_scope_duplicate_member_fails(self):
        report = re.evaluate(synthetic_artifact(
            scope=["core_chart", "order_data", "documents", "documents",
                   "document_associations"]))
        self.assertIn("scope", failures(report))

    def test_extra_scope_member_is_allowed(self):
        report = re.evaluate(synthetic_artifact(
            scope=["core_chart", "order_data", "documents",
                   "document_associations", "audit_history"]))
        self.assertNotIn("scope", failures(report))

    def test_near_miss_member_does_not_satisfy_required_member(self):
        report = re.evaluate(synthetic_artifact(
            scope=["core_chart", "order_data", "documentation",
                   "document_associations"]))
        self.assertIn("scope", failures(report))

    def test_restore_targets_must_be_an_object(self):
        report = re.evaluate(synthetic_artifact(restore=[]))
        self.assertIn("restore", failures(report))

    def test_restore_targets_missing_member_fails(self):
        report = re.evaluate(synthetic_artifact(
            restore={"targets": ["database", "objects"], "clean_environment": True}))
        self.assertIn("restore.targets", failures(report))

    def test_restore_targets_duplicate_member_fails(self):
        report = re.evaluate(synthetic_artifact(
            restore={"targets": ["database", "objects", "objects",
                                 "idp_dependencies"], "clean_environment": True}))
        self.assertIn("restore.targets", failures(report))

    def test_restore_targets_must_be_a_list(self):
        report = re.evaluate(synthetic_artifact(
            restore={"targets": "database", "clean_environment": True}))
        self.assertIn("restore.targets", failures(report))

    def test_clean_environment_must_be_true(self):
        report = re.evaluate(synthetic_artifact(
            restore={"targets": ["database", "objects", "idp_dependencies"],
                     "clean_environment": False}))
        self.assertIn("restore.clean_environment", failures(report))

    def test_clean_environment_must_be_a_boolean(self):
        report = re.evaluate(synthetic_artifact(
            restore={"targets": ["database", "objects", "idp_dependencies"],
                     "clean_environment": "yes"}))
        self.assertIn("restore.clean_environment", failures(report))


class ReconciliationTest(unittest.TestCase):
    def test_reconciliation_must_be_an_object(self):
        report = re.evaluate(synthetic_artifact(reconciliation=[]))
        self.assertIn("reconciliation", failures(report))

    def test_each_reconciliation_gate_must_be_true(self):
        for gate in re.REQUIRED_RECONCILIATION:
            with self.subTest(gate=gate):
                report = re.evaluate(synthetic_artifact(
                    reconciliation={gate: False}))
                self.assertIn("reconciliation." + gate, failures(report))

    def test_each_reconciliation_gate_must_be_a_boolean(self):
        for gate in re.REQUIRED_RECONCILIATION:
            with self.subTest(gate=gate):
                report = re.evaluate(synthetic_artifact(
                    reconciliation={gate: "true"}))
                self.assertIn("reconciliation." + gate, failures(report))

    def test_completed_before_writes_reopened_must_be_true(self):
        report = re.evaluate(synthetic_artifact(
            reconciliation={"completed_before_writes_reopened": False}))
        self.assertIn("reconciliation.completed_before_writes_reopened",
                      failures(report))

    def test_completed_before_writes_reopened_must_be_a_boolean(self):
        report = re.evaluate(synthetic_artifact(
            reconciliation={"completed_before_writes_reopened": 1}))
        self.assertIn("reconciliation.completed_before_writes_reopened",
                      failures(report))


class VerificationTest(unittest.TestCase):
    def test_verification_must_be_an_object(self):
        report = re.evaluate(synthetic_artifact(verification=[]))
        self.assertIn("verification", failures(report))

    def test_before_pilot_must_be_true(self):
        report = re.evaluate(synthetic_artifact(verification={"before_pilot": False}))
        self.assertIn("verification.before_pilot", failures(report))

    def test_quarterly_cadence_must_be_true(self):
        report = re.evaluate(synthetic_artifact(
            verification={"quarterly_cadence": False}))
        self.assertIn("verification.quarterly_cadence", failures(report))

    def test_verification_flags_must_be_booleans(self):
        for flag in ("before_pilot", "quarterly_cadence"):
            with self.subTest(flag=flag):
                report = re.evaluate(synthetic_artifact(verification={flag: "yes"}))
                self.assertIn("verification." + flag, failures(report))


class MonitoringTest(unittest.TestCase):
    def test_monitoring_must_be_an_object(self):
        report = re.evaluate(synthetic_artifact(monitoring=[]))
        self.assertIn("monitoring", failures(report))

    def test_signals_must_be_a_list(self):
        report = re.evaluate(synthetic_artifact(monitoring={"signals": "error_rates"}))
        self.assertIn("monitoring.signals", failures(report))

    def test_missing_signal_fails(self):
        report = re.evaluate(synthetic_artifact(
            monitoring={"signals": ["error_rates"]}))
        self.assertIn("monitoring.signals", failures(report))

    def test_duplicate_signal_fails(self):
        signals = list(synthetic_artifact()["monitoring"]["signals"])
        signals[1] = signals[0]
        report = re.evaluate(synthetic_artifact(monitoring={"signals": signals}))
        self.assertIn("monitoring.signals", failures(report))

    def test_extra_signal_is_allowed(self):
        signals = list(synthetic_artifact()["monitoring"]["signals"]) + ["custom_gauge"]
        report = re.evaluate(synthetic_artifact(monitoring={"signals": signals}))
        self.assertNotIn("monitoring.signals", failures(report))

    def test_every_required_signal_is_named(self):
        self.assertEqual(len(re.REQUIRED_MONITORING_SIGNALS), 13)


class OwnershipTest(unittest.TestCase):
    def test_ownership_must_be_an_object(self):
        report = re.evaluate(synthetic_artifact(ownership=[]))
        self.assertIn("ownership", failures(report))

    def test_missing_owner_fails(self):
        artifact = synthetic_artifact()
        del artifact["ownership"]["operational_on_call_owner"]
        report = re.evaluate(artifact)
        self.assertIn("ownership.operational_on_call_owner", failures(report))

    def test_blank_owner_fails(self):
        report = re.evaluate(synthetic_artifact(
            ownership={"operational_on_call_owner": "   "}))
        self.assertIn("ownership.operational_on_call_owner", failures(report))

    def test_non_string_owner_fails(self):
        report = re.evaluate(synthetic_artifact(
            ownership={"operational_on_call_owner": 7}))
        self.assertIn("ownership.operational_on_call_owner", failures(report))

    def test_site_clinical_coverage_owner_is_required(self):
        artifact = synthetic_artifact()
        del artifact["ownership"]["site_clinical_coverage_owner"]
        report = re.evaluate(artifact)
        self.assertIn("ownership.site_clinical_coverage_owner", failures(report))

    def test_placeholder_owner_fails(self):
        for placeholder in ("TBD", "n/a", "Unknown", "-"):
            with self.subTest(placeholder=placeholder):
                report = re.evaluate(synthetic_artifact(
                    ownership={"operational_on_call_owner": placeholder}))
                self.assertIn("ownership.operational_on_call_owner",
                              failures(report))


class RunbooksTest(unittest.TestCase):
    def test_required_runbooks_match_the_spec_vocabulary(self):
        self.assertEqual(set(re.REQUIRED_RUNBOOKS), {
            "paper_manual_capture", "patient_identification",
            "critical_contacts", "supervised_back_entry",
        })

    def test_runbooks_must_be_a_list(self):
        report = re.evaluate(synthetic_artifact(runbooks="paper_manual_capture"))
        self.assertIn("runbooks", failures(report))

    def test_missing_runbook_fails(self):
        report = re.evaluate(synthetic_artifact(
            runbooks=["paper_manual_capture", "critical_contacts"]))
        self.assertIn("runbooks", failures(report))

    def test_duplicate_runbook_fails(self):
        report = re.evaluate(synthetic_artifact(
            runbooks=["paper_manual_capture", "patient_identification",
                      "critical_contacts", "supervised_back_entry",
                      "supervised_back_entry"]))
        self.assertIn("runbooks", failures(report))

    def test_downtime_drills_alone_does_not_satisfy_supervised_back_entry(self):
        report = re.evaluate(synthetic_artifact(
            runbooks=["paper_manual_capture", "patient_identification",
                      "critical_contacts", "downtime_drills"]))
        self.assertIn("runbooks", failures(report))

    def test_extra_runbook_is_allowed(self):
        report = re.evaluate(synthetic_artifact(
            runbooks=["paper_manual_capture", "patient_identification",
                      "critical_contacts", "supervised_back_entry",
                      "downtime_drills", "restore_from_backup"]))
        self.assertNotIn("runbooks", failures(report))


class DuplicatePreventionTest(unittest.TestCase):
    def test_duplicate_prevention_must_be_true(self):
        report = re.evaluate(synthetic_artifact(duplicate_prevention=False))
        self.assertIn("duplicate_prevention", failures(report))

    def test_duplicate_prevention_must_be_a_boolean(self):
        for value in ("true", "false", 1, None):
            with self.subTest(value=value):
                report = re.evaluate(synthetic_artifact(duplicate_prevention=value))
                self.assertIn("duplicate_prevention", failures(report))


class ContractConsistencyTest(unittest.TestCase):
    def test_contract_matches_evaluator_constants(self):
        path = MODULE_PATH.parent / "recovery-contract.json"
        contract = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(contract["targets"]["rpo_minutes"]["max"],
                         re.RPO_MAX_MINUTES)
        self.assertEqual(contract["targets"]["rto_hours"]["max"],
                         re.RTO_MAX_HOURS)
        self.assertEqual(set(contract["recovery_scope"]), set(re.REQUIRED_SCOPE))
        self.assertEqual(set(contract["restore_targets"]),
                         set(re.REQUIRED_RESTORE_TARGETS))
        self.assertEqual(
            set(contract["reconciliation"]),
            set(re.REQUIRED_RECONCILIATION) | {"completed_before_writes_reopened"})
        self.assertEqual(set(contract["monitoring_signals"]),
                         set(re.REQUIRED_MONITORING_SIGNALS))
        self.assertEqual(set(contract["runbooks"]), set(re.REQUIRED_RUNBOOKS))
        self.assertEqual(set(contract["evidence_kinds"]), set(re.KINDS))
        self.assertEqual(set(contract["synthetic_markers"]),
                         set(re.SYNTHETIC_MARKERS))


class FormatAndMainTest(unittest.TestCase):
    def test_format_report_renders_all_checks(self):
        report = re.evaluate(synthetic_artifact())
        text = re.format_report(report)
        self.assertIn("OPS-04/OPS-05 recovery evidence: PASS", text)
        self.assertIn("evidence class: rehearsal", text)
        self.assertIn("[PASS] targets.rpo_minutes", text)

    def test_format_report_renders_failed_checks(self):
        report = re.evaluate(synthetic_artifact(duplicate_prevention=False))
        text = re.format_report(report)
        self.assertIn("OPS-04/OPS-05 recovery evidence: FAIL", text)
        self.assertIn("[FAIL] duplicate_prevention", text)

    def test_main_requires_exactly_one_path(self):
        self.assertEqual(re.main([]), 2)
        self.assertEqual(re.main(["a", "b"]), 2)

    def test_main_reads_sys_argv_when_no_arguments_passed(self):
        fixture = MODULE_PATH.parent / "fixtures" / "synthetic_recovery_evidence.json"
        with mock.patch("sys.argv", ["recovery_evidence.py", str(fixture)]), \
                mock.patch("sys.stdout"):
            self.assertEqual(re.main(), 0)

    def test_main_sys_argv_wrong_length(self):
        with mock.patch("sys.argv", ["recovery_evidence.py"]), \
                mock.patch("sys.stderr"):
            self.assertEqual(re.main(), 2)

    def test_main_returns_zero_for_conforming_fixture(self):
        fixture = MODULE_PATH.parent / "fixtures" / "synthetic_recovery_evidence.json"
        self.assertEqual(re.main([str(fixture)]), 0)

    def test_main_returns_one_for_violation_fixture(self):
        fixture = MODULE_PATH.parent / "fixtures" / "violation_recovery_evidence.json"
        self.assertEqual(re.main([str(fixture)]), 1)

    def test_main_returns_two_for_unreadable_input(self):
        self.assertEqual(re.main(["/no/such/artifact.json"]), 2)

    def test_main_returns_two_for_malformed_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            path.write_text("{not json", encoding="utf-8")
            self.assertEqual(re.main([str(path)]), 2)

    def test_main_returns_two_for_invalid_utf8(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.json"
            path.write_bytes(b'{"source": "\xff\xfe"}')
            self.assertEqual(re.main([str(path)]), 2)

    def test_main_returns_two_for_directory_path(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(re.main([directory]), 2)

    def test_main_uses_stdout_and_stderr(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            with mock.patch("sys.stdout") as out:
                self.assertEqual(re.main([str(path)]), 0)
                out.write.assert_called()


if __name__ == "__main__":
    unittest.main()
