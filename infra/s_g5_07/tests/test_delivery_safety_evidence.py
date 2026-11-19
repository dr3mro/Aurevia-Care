"""Executable acceptance tests for the OPS-06 delivery-safety evaluator.

All fixtures are synthetic. A conforming artifact must pass; every OPS-06
control (separate environments, synthetic fixtures, CI checks, signed builds,
migration checks, progressive rollout, audited production access) must fail
closed when omitted, malformed or weakened. Tests enumerate both outcomes of
every evaluator branch so the contract is specified without a real deployment.
"""
import contextlib
import importlib.util
import io
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "delivery_safety_evidence.py"
SPEC = importlib.util.spec_from_file_location("delivery_safety_evidence", MODULE_PATH)
dse = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(dse)


def _digest():
    """Return a synthetic but structurally valid sha256 digest string."""
    return "sha256:" + "ab12cd34" * 8


def synthetic_artifact(**overrides):
    """Build a synthetic, conforming OPS-06 delivery-safety artifact."""
    artifact = {
        "schema_version": "1.0",
        "kind": "synthetic_rehearsal",
        "source": "synthetic-delivery-fixture",
        "environments": {
            "development": {"account": "dev-acct-111", "resource": "dev-rs-111"},
            "test": {"account": "test-acct-222", "resource": "test-rs-222"},
            "staging": {"account": "stg-acct-333", "resource": "stg-rs-333"},
            "production": {"account": "prod-acct-444", "resource": "prod-rs-444"},
        },
        "fixtures": {
            "synthetic_only": True,
            "no_real_patient_data": True,
            "environments": ["development", "test", "staging"],
        },
        "ci_checks": {
            "policy": True,
            "dependency_scan": True,
            "secret_scan": True,
        },
        "signed_builds": {
            "method": "cosign",
            "digest": _digest(),
            "verified": True,
            "signer_identity": "ci-signer-release",
            "promotion_gate": "signature_verified",
        },
        "migration_checks": {
            "gate": "check-migrations --require-baseline",
            "expand_contract": True,
            "forward_fix_required": True,
        },
        "rollout": {
            "strategy": "progressive",
            "stages": ["internal", "canary", "partial", "full"],
            "health_gates": True,
            "monitoring": True,
            "rollback_tested": True,
        },
        "production_access": {
            "approval_required": True,
            "requester": "deployment-bot",
            "approver": "release-manager",
            "audited": True,
            "expiring": True,
        },
        "reversible_deployments": True,
    }
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(artifact.get(key), dict):
            artifact[key].update(value)
        else:
            artifact[key] = value
    return artifact


def measured_artifact(**overrides):
    """Build a synthetic, conforming measured-deployment artifact with provenance."""
    artifact = synthetic_artifact(kind="measured_deployment",
                                  source="authorized-rollout-run")
    artifact["provenance"] = {
        "tool": "delivery-pipeline",
        "tool_version": "1.4.2",
        "command": "deploy --env production",
        "utc_timestamp": "2026-11-19T10:00:00Z",
        "environment": "production",
        "source_sha": "abcdef0123456789abcdef0123456789abcdef01",
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
        report = dse.evaluate(synthetic_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertEqual(report["classification"], "rehearsal")
        self.assertFalse(report["final_evidence"])
        self.assertTrue(all(c["passed"] for c in report["checks"]))

    def test_measured_deployment_kind_is_accepted_as_final_evidence(self):
        report = dse.evaluate(measured_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["classification"], "measured-deployment")
        self.assertTrue(report["final_evidence"])

    def test_unknown_kind_is_never_final_evidence(self):
        report = dse.evaluate(synthetic_artifact(kind="unverified"))
        self.assertEqual(report["classification"], "unknown")
        self.assertFalse(report["final_evidence"])
        self.assertIn("kind", failures(report))

    def test_unhashable_kind_is_unknown_without_raising(self):
        report = dse.evaluate(synthetic_artifact(kind=["measured_deployment"]))
        self.assertEqual(report["classification"], "unknown")
        self.assertIn("kind", failures(report))


class StructureTest(unittest.TestCase):
    def test_artifact_must_be_an_object(self):
        report = dse.evaluate([])
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("artifact", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_schema_version_must_match(self):
        report = dse.evaluate(synthetic_artifact(schema_version="2.0"))
        self.assertIn("schema_version", failures(report))

    def test_measured_deployment_cannot_declare_a_synthetic_source(self):
        report = dse.evaluate(measured_artifact(source="SYNTHETIC-fixture"))
        self.assertIn("evidence_conflict", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_measured_deployment_with_neutral_source_has_no_conflict(self):
        report = dse.evaluate(measured_artifact())
        self.assertNotIn("evidence_conflict", failures(report))


class SourceTest(unittest.TestCase):
    def test_source_must_be_a_non_blank_string(self):
        for value in ("", "   ", 7, None, []):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(source=value))
                self.assertIn("source", failures(report))

    def test_blank_source_never_yields_final_evidence(self):
        report = dse.evaluate(synthetic_artifact(
            kind="measured_deployment", source=""))
        self.assertFalse(report["final_evidence"])
        self.assertIn("source", failures(report))


class EnvironmentTest(unittest.TestCase):
    def test_environments_must_be_an_object(self):
        report = dse.evaluate(synthetic_artifact(environments=[]))
        self.assertIn("environments", failures(report))

    def test_exactly_the_four_environments_are_required(self):
        artifact = synthetic_artifact()
        artifact["environments"]["extra"] = {"account": "x", "resource": "x"}
        report = dse.evaluate(artifact)
        self.assertIn("environments.names", failures(report))

    def test_each_environment_entry_must_be_an_object(self):
        artifact = synthetic_artifact()
        artifact["environments"]["test"] = "test-acct-222"
        report = dse.evaluate(artifact)
        self.assertIn("environments.test", failures(report))
        self.assertIn("environments.distinct_accounts", failures(report))

    def test_account_and_resource_must_be_non_empty_strings(self):
        for bad in ("", "   ", None, 7, []):
            with self.subTest(bad=bad):
                artifact = synthetic_artifact()
                artifact["environments"]["test"]["account"] = bad
                report = dse.evaluate(artifact)
                self.assertIn("environments.test", failures(report))

    def test_resources_must_also_be_valid(self):
        artifact = synthetic_artifact()
        artifact["environments"]["staging"]["resource"] = None
        report = dse.evaluate(artifact)
        self.assertIn("environments.staging", failures(report))

    def test_accounts_must_be_distinct(self):
        artifact = synthetic_artifact()
        artifact["environments"]["test"]["account"] = \
            artifact["environments"]["development"]["account"]
        report = dse.evaluate(artifact)
        self.assertIn("environments.distinct_accounts", failures(report))

    def test_resources_must_be_distinct(self):
        artifact = synthetic_artifact()
        artifact["environments"]["test"]["resource"] = \
            artifact["environments"]["development"]["resource"]
        report = dse.evaluate(artifact)
        self.assertIn("environments.distinct_resources", failures(report))

    def test_whitespace_variant_account_is_treated_as_a_duplicate(self):
        # Identifiers are stripped before the distinctness comparison, so an
        # account padded with whitespace cannot alias another environment.
        artifact = synthetic_artifact()
        artifact["environments"]["test"]["account"] = \
            " " + artifact["environments"]["development"]["account"] + " "
        report = dse.evaluate(artifact)
        self.assertIn("environments.distinct_accounts", failures(report))

    def test_whitespace_variant_resource_is_treated_as_a_duplicate(self):
        artifact = synthetic_artifact()
        artifact["environments"]["test"]["resource"] = \
            "\t" + artifact["environments"]["development"]["resource"] + "\n"
        report = dse.evaluate(artifact)
        self.assertIn("environments.distinct_resources", failures(report))


class FixtureTest(unittest.TestCase):
    def test_fixtures_must_be_an_object(self):
        report = dse.evaluate(synthetic_artifact(fixtures="synthetic"))
        self.assertIn("fixtures", failures(report))

    def test_synthetic_only_must_be_strictly_true(self):
        for value in (False, 1, "yes", None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    fixtures={"synthetic_only": value}))
                self.assertIn("fixtures.synthetic_only", failures(report))

    def test_no_real_patient_data_must_be_strictly_true(self):
        for value in (False, 1, None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    fixtures={"no_real_patient_data": value}))
                self.assertIn("fixtures.no_real_patient_data", failures(report))

    def test_fixture_environments_must_be_the_non_production_set(self):
        for declared in (
            ["development", "test"],
            ["development", "test", "production"],
            ["development", "test", "test"],
            ["development", "test", None],
            "development",
            None,
        ):
            with self.subTest(declared=declared):
                report = dse.evaluate(synthetic_artifact(
                    fixtures={"environments": declared}))
                self.assertIn("fixtures.environments", failures(report))


class CiChecksTest(unittest.TestCase):
    def test_ci_checks_must_be_an_object(self):
        report = dse.evaluate(synthetic_artifact(ci_checks=[]))
        self.assertIn("ci_checks", failures(report))

    def test_every_required_ci_check_must_be_strictly_true(self):
        for key in ("policy", "dependency_scan", "secret_scan"):
            for value in (False, 1, "true", None):
                with self.subTest(key=key, value=value):
                    report = dse.evaluate(synthetic_artifact(
                        ci_checks={key: value}))
                    self.assertIn("ci_checks." + key, failures(report))


class SignedBuildsTest(unittest.TestCase):
    def test_signed_builds_must_be_an_object(self):
        report = dse.evaluate(synthetic_artifact(signed_builds=None))
        self.assertIn("signed_builds", failures(report))

    def test_method_must_be_an_allowed_value(self):
        for value in ("unsigned", "md5", 7, None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    signed_builds={"method": value}))
                self.assertIn("signed_builds.method", failures(report))

    def test_digest_must_be_a_full_sha256_hex_string(self):
        for value in (
            "sha256:abc",
            "sha1:" + "ab12cd34" * 8,
            "sha256:" + "zz" * 32,
            "sha256:" + "ab12cd34" * 7,
            "sha256:" + "ab12cd34" * 9,
            None,
            7,
        ):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    signed_builds={"digest": value}))
                self.assertIn("signed_builds.digest", failures(report))

    def test_uppercase_hex_digest_is_accepted(self):
        digest = "sha256:" + "AB12CD34" * 8
        report = dse.evaluate(synthetic_artifact(
            signed_builds={"digest": digest}))
        self.assertNotIn("signed_builds.digest", failures(report))

    def test_verified_must_be_strictly_true(self):
        for value in (False, 1, None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    signed_builds={"verified": value}))
                self.assertIn("signed_builds.verified", failures(report))

    def test_signer_identity_must_be_a_non_empty_string(self):
        for value in ("", "   ", 7, None, []):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    signed_builds={"signer_identity": value}))
                self.assertIn("signed_builds.signer_identity", failures(report))

    def test_promotion_gate_must_require_verification(self):
        for value in ("none", "manual", 7, None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    signed_builds={"promotion_gate": value}))
                self.assertIn("signed_builds.promotion_gate", failures(report))


class MigrationChecksTest(unittest.TestCase):
    def test_migration_checks_must_be_an_object(self):
        report = dse.evaluate(synthetic_artifact(migration_checks="ok"))
        self.assertIn("migration_checks", failures(report))

    def test_migration_gate_must_be_the_baseline_gate(self):
        for value in ("check-migrations", "make migrate", None, 7):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    migration_checks={"gate": value}))
                self.assertIn("migrations.gate", failures(report))

    def test_expand_contract_and_forward_fix_must_be_true(self):
        for key in ("expand_contract", "forward_fix_required"):
            for value in (False, 1, None):
                with self.subTest(key=key, value=value):
                    report = dse.evaluate(synthetic_artifact(
                        migration_checks={key: value}))
                    self.assertIn("migrations." + key, failures(report))


class RolloutTest(unittest.TestCase):
    def test_rollout_must_be_an_object(self):
        report = dse.evaluate(synthetic_artifact(rollout="progressive"))
        self.assertIn("rollout", failures(report))

    def test_strategy_must_be_progressive(self):
        for value in ("big-bang", "direct", None, 7):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    rollout={"strategy": value}))
                self.assertIn("rollout.strategy", failures(report))

    def test_stages_must_be_an_ordered_distinct_list_ending_in_full(self):
        for value in (
            [],
            ["full"],
            ["internal", "canary"],
            ["internal", "canary", "internal", "full"],
            ["internal", "", "full"],
            ["internal", 7, "full"],
            ["internal", "canary", "partial"],
            "internal,canary,full",
            None,
        ):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    rollout={"stages": value}))
                self.assertIn("rollout.stages", failures(report))

    def test_two_minimal_distinct_stages_are_accepted(self):
        report = dse.evaluate(synthetic_artifact(
            rollout={"stages": ["canary", "full"]}))
        self.assertNotIn("rollout.stages", failures(report))

    def test_whitespace_padded_duplicate_stages_are_rejected(self):
        # Normalized comparison stops ["full ", "full"] from relabelling a
        # single-stage big-bang release as a two-stage progressive rollout.
        for stages in (["full ", "full"], [" full", "full "]):
            with self.subTest(stages=stages):
                report = dse.evaluate(synthetic_artifact(
                    rollout={"stages": stages}))
                self.assertIn("rollout.stages", failures(report))

    def test_health_monitoring_and_rollback_must_be_true(self):
        for key in ("health_gates", "monitoring", "rollback_tested"):
            for value in (False, 1, None):
                with self.subTest(key=key, value=value):
                    report = dse.evaluate(synthetic_artifact(
                        rollout={key: value}))
                    self.assertIn("rollout." + key, failures(report))


class ProductionAccessTest(unittest.TestCase):
    def test_production_access_must_be_an_object(self):
        report = dse.evaluate(synthetic_artifact(production_access="approved"))
        self.assertIn("production_access", failures(report))

    def test_approval_must_be_strictly_true(self):
        for value in (False, 1, None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    production_access={"approval_required": value}))
                self.assertIn("production_access.approval_required", failures(report))

    def test_approver_must_be_a_non_empty_string(self):
        for value in ("", "  ", 7, None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    production_access={"approver": value}))
                self.assertIn("production_access.separation_of_duties", failures(report))

    def test_requester_and_approver_must_be_distinct(self):
        artifact = synthetic_artifact()
        artifact["production_access"]["requester"] = \
            artifact["production_access"]["approver"]
        report = dse.evaluate(artifact)
        self.assertIn("production_access.separation_of_duties", failures(report))

    def test_whitespace_padded_self_approval_is_rejected(self):
        artifact = synthetic_artifact()
        artifact["production_access"]["requester"] = \
            artifact["production_access"]["approver"] + " "
        report = dse.evaluate(artifact)
        self.assertIn("production_access.separation_of_duties", failures(report))

    def test_requester_must_be_a_non_empty_string(self):
        for value in ("", "  ", 7, None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    production_access={"requester": value}))
                self.assertIn("production_access.separation_of_duties", failures(report))

    def test_audit_and_expiry_must_be_true(self):
        for key in ("audited", "expiring"):
            for value in (False, 1, None):
                with self.subTest(key=key, value=value):
                    report = dse.evaluate(synthetic_artifact(
                        production_access={key: value}))
                    self.assertIn("production_access." + key, failures(report))


class ReversibleDeploymentTest(unittest.TestCase):
    def test_reversible_deployments_must_be_strictly_true(self):
        for value in (False, 1, "yes", None):
            with self.subTest(value=value):
                report = dse.evaluate(synthetic_artifact(
                    reversible_deployments=value))
                self.assertIn("reversible_deployments", failures(report))


class AllBreachesTest(unittest.TestCase):
    def test_all_breaches_are_reported_together(self):
        artifact = synthetic_artifact(
            schema_version="2.0",
            ci_checks={"secret_scan": False},
            signed_builds={"verified": False},
            rollout={"strategy": "direct"},
            production_access={"audited": False},
            reversible_deployments=False,
        )
        report = dse.evaluate(artifact)
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({
            "schema_version", "ci_checks.secret_scan", "signed_builds.verified",
            "rollout.strategy", "production_access.audited",
            "reversible_deployments",
        }.issubset(failures(report)))


class ReportAndCliTest(unittest.TestCase):
    def test_format_report_shows_each_check_and_verdict(self):
        passed = dse.format_report(dse.evaluate(synthetic_artifact()))
        self.assertIn("[PASS]", passed)
        self.assertIn("signed_builds.verified", passed)
        self.assertIn("OPS-06 delivery safety: PASS", passed)
        self.assertIn("evidence class: rehearsal (final: False)", passed)
        failing = dse.format_report(dse.evaluate(synthetic_artifact(kind="unverified")))
        self.assertIn("[FAIL]", failing)
        self.assertIn("evidence class: unknown (final: False)", failing)
        final = dse.format_report(dse.evaluate(measured_artifact()))
        self.assertIn("final: True", final)

    def test_main_evaluates_a_conforming_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = dse.main([str(path)])
            self.assertEqual(code, 0)
            self.assertIn("PASS", stdout.getvalue())

    def test_main_returns_one_for_a_failing_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_text(json.dumps(synthetic_artifact(
                reversible_deployments=False)), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = dse.main([str(path)])
            self.assertEqual(code, 1)

    def test_main_reads_sys_argv_when_no_arguments_are_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with mock.patch.object(sys, "argv", ["delivery_safety_evidence.py", str(path)]):
                with contextlib.redirect_stdout(stdout):
                    code = dse.main()
            self.assertEqual(code, 0)

    def test_main_rejects_bad_usage(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = dse.main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_a_missing_file(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = dse.main(["/definitely/not/here/artifact.json"])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_text("{not json", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = dse.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_a_non_finite_number(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_text('{"p95": NaN}', encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = dse.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_a_non_utf8_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "artifact.json"
            path.write_bytes(b"{\"kind\": \xff}")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = dse.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())


class MeasuredProvenanceTest(unittest.TestCase):
    """A measured claim needs run provenance; relabelling alone is not enough."""

    def test_rehearsal_does_not_require_provenance(self):
        report = dse.evaluate(synthetic_artifact())
        self.assertFalse([n for n in failures(report)
                          if n.startswith("provenance")])
        self.assertFalse(report["final_evidence"])

    def test_conforming_provenance_is_accepted(self):
        report = dse.evaluate(measured_artifact())
        self.assertFalse([n for n in failures(report)
                          if n.startswith("provenance")])
        self.assertTrue(report["final_evidence"])

    def test_missing_empty_and_non_object_provenance_fail(self):
        for value in (None, {}, [], "run-it", 7):
            with self.subTest(value=value):
                artifact = measured_artifact()
                if value is None:
                    del artifact["provenance"]
                else:
                    artifact["provenance"] = value
                report = dse.evaluate(artifact)
                self.assertIn("provenance", failures(report))
                self.assertFalse(report["final_evidence"])

    def test_each_required_field_must_be_a_non_blank_string(self):
        for field in dse.MEASURED_PROVENANCE_FIELDS:
            for bad in ("", "   ", None, 7):
                with self.subTest(field=field, bad=bad):
                    report = dse.evaluate(measured_artifact(
                        provenance={field: bad}))
                    self.assertIn("provenance." + field, failures(report))
                    self.assertFalse(report["final_evidence"])

    def test_a_missing_field_is_reported_by_name(self):
        artifact = measured_artifact()
        del artifact["provenance"]["source_sha"]
        report = dse.evaluate(artifact)
        self.assertIn("provenance.source_sha", failures(report))
        self.assertFalse(report["final_evidence"])


class PolicyManifestTest(unittest.TestCase):
    """The declared policy artifact must agree with the enforced constants."""

    @classmethod
    def setUpClass(cls):
        path = MODULE_PATH.parent / "delivery-safety-policy.json"
        cls.policy = json.loads(path.read_text(encoding="utf-8"))

    def test_policy_matches_the_enforced_constants(self):
        self.assertEqual(self.policy["schema_version"], dse.SCHEMA_VERSION)
        self.assertEqual(self.policy["requirement"], "OPS-06")
        self.assertEqual(set(self.policy["environments"]), set(dse.ENVIRONMENTS))
        self.assertEqual(self.policy["ci_checks"], list(dse.CI_CHECKS))
        self.assertEqual(self.policy["signing_methods"], list(dse.SIGNING_METHODS))
        self.assertEqual(self.policy["migration_gate"], dse.MIGRATION_GATE)
        self.assertEqual(self.policy["rollout_strategy"], dse.ROLLOUT_STRATEGY)
        self.assertEqual(self.policy["rollout_final_stage"], dse.ROLLOUT_FINAL_STAGE)
        self.assertEqual(self.policy["measured_provenance_fields"],
                         list(dse.MEASURED_PROVENANCE_FIELDS))

    def test_synthetic_fixture_declaration_is_consistent(self):
        dataset = self.policy["dataset"]
        self.assertTrue(dataset["synthetic_only"])
        self.assertEqual(set(dataset["non_production_environments"]),
                         set(dse.NON_PRODUCTION_ENVIRONMENTS))
        self.assertNotIn("production", dataset["non_production_environments"])

    def test_policy_text_contains_no_phi_or_credential_shapes(self):
        text = json.dumps(self.policy)
        self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", text))
        self.assertIsNone(re.search(r"\b\d{3}-\d{2}-\d{4}\b", text))
        self.assertNotIn("-----BEGIN", text)

    def test_fixture_files_contain_no_phi_or_credential_shapes(self):
        for name in ("synthetic_evidence.json", "violation_evidence.json"):
            with self.subTest(name=name):
                text = (MODULE_PATH.parent / "fixtures" / name).read_text(
                    encoding="utf-8")
                self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", text))
                self.assertIsNone(re.search(r"\b\d{3}-\d{2}-\d{4}\b", text))
                self.assertNotIn("-----BEGIN", text)


if __name__ == "__main__":
    unittest.main()
