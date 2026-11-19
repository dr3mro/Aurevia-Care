"""Executable acceptance tests for the OPS-08 onboarding evaluator.

All fixtures are synthetic. A conforming, versioned manifest with a deterministic
mapping, clean dry run, reconciled controlled batches and authorized sign-off must
pass. Every OPS-08 clause and every fail-closed branch of the evaluator is
enumerated here so no real onboarding run or clinical approval is needed to prove
the contract.

The expected hashes are computed independently in this file with the standard
library, so the tests verify the evaluator's hashing rather than mirroring its
own helper.
"""
import contextlib
import hashlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "onboarding.py"
SPEC = importlib.util.spec_from_file_location("onboarding", MODULE_PATH)
onboarding = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(onboarding)

ob = onboarding


def _canonical(obj):
    """Independent canonical byte encoding used to build expected hashes."""
    return json.dumps(obj, sort_keys=True, separators=(",", ":"),
                      ensure_ascii=False).encode("utf-8")


def _hex(data):
    return hashlib.sha256(data).hexdigest()


def _record(source_id, mrn, issuer, given="Syn", family="Patient",
            dob="1990-01-01", attachment_present=True, signature=True):
    """Build one synthetic onboarding record."""
    return {
        "source_id": source_id,
        "fields": {"mrn": mrn, "issuer": issuer, "given": given,
                   "family": family, "dob": dob},
        "attachments": [{
            "key": "doc-" + source_id,
            "sha256": _hex(b"synthetic-attachment-bytes"),
            "present": attachment_present,
            "type": "pdf",
        }],
        "historical_signature": (
            {"issuer": "legacy-synthetic-emr",
             "signed_at": "2019-01-01T00:00:00Z",
             "checksum": _hex(b"synthetic-legacy-signature")}
            if signature else None),
    }


def _batches(records, size=2):
    """Compute conforming controlled batches with independently built hashes."""
    ordered = sorted(records, key=lambda r: r["source_id"])
    result = []
    for start in range(0, len(ordered), size):
        chunk = ordered[start:start + size]
        ids = [r["source_id"] for r in chunk]
        result.append({
            "sequence": len(result) + 1,
            "record_ids": ids,
            "expected_count": len(ids),
            "sha256": _hex(b"".join(_canonical(r) for r in chunk)),
        })
    return result


def _content_sha256(batches, manifest_version):
    """Compute the sign-off binding independently of the evaluator."""
    usable = batches if isinstance(batches, list) else []
    return _hex(_canonical({
        "manifest_version": manifest_version,
        "batch_sha256": [b.get("sha256") for b in usable
                         if isinstance(b, dict)],
    }))


def synthetic_manifest(**overrides):
    """Build a synthetic, conforming OPS-08 onboarding manifest."""
    records = [
        _record("SYN-0001", "M-001", "ORG-A"),
        _record("SYN-0002", "M-002", "ORG-A"),
        _record("SYN-0003", "M-003", "ORG-A"),
    ]
    batches = _batches(records)
    manifest = {
        "schema_version": "1.0",
        "requirement": "OPS-08",
        "manifest_version": 3,
        "source": "synthetic-onboarding-fixture",
        "synthetic_only": True,
        "dataset": {"name": "synthetic-clinic-a", "records": records},
        "mapping": {
            "strategy": "canonical_sha256",
            "deterministic": True,
            "source_to_target": {
                "mrn": "mrn", "issuer": "mrnIssuer", "given": "givenName",
                "family": "familyName", "dob": "birthDate",
            },
            "identity_fields": ["mrn", "mrnIssuer"],
            "ignored_fields": [],
        },
        "provenance": {
            "source_system": "legacy-synthetic-emr",
            "source_version": "2019.4",
            "preserve_historical_signatures": True,
            "creates_new_attestation": False,
        },
        "batches": batches,
        "sign_off": {
            "approver": "ops-lead",
            "role": "tenant_admin",
            "decision": "approved",
            "content_sha256": _content_sha256(batches, 3),
            "manifest_version": 3,
            "batches": [b["sequence"] for b in batches],
            "approved_at": "2026-11-19T12:00:00Z",
        },
    }
    for key, value in overrides.items():
        if isinstance(value, dict) and isinstance(manifest.get(key), dict):
            manifest[key].update(value)
        else:
            manifest[key] = value
    # Bind the approval to the final batches and version after all overrides,
    # unless a test explicitly supplies a stale digest to prove tamper detection.
    sign_off_override = overrides.get("sign_off")
    explicit_content = (isinstance(sign_off_override, dict)
                        and "content_sha256" in sign_off_override)
    if isinstance(manifest.get("sign_off"), dict) and not explicit_content:
        manifest["sign_off"]["content_sha256"] = _content_sha256(
            manifest["batches"], manifest["manifest_version"])
    return manifest


def with_records(records, **overrides):
    """Build a manifest whose batches/sign-off are rebuilt for new records."""
    batches = _batches(records)
    kwargs = {"dataset": {"records": records}, "batches": batches,
              "sign_off": {"batches": [b["sequence"] for b in batches]}}
    kwargs.update(overrides)
    return synthetic_manifest(**kwargs)


def failures(report):
    return set(report["failed"])


class ConformingManifestTest(unittest.TestCase):
    def test_conforming_manifest_passes_and_allows_committed_batches(self):
        report = ob.evaluate(synthetic_manifest())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertTrue(report["commit_allowed"])
        self.assertEqual(report["source_of_truth_action"], "commit_in_batches")
        self.assertEqual(report["new_attestations_created"], 0)
        self.assertTrue(all(c["passed"] for c in report["checks"]))

    def test_dry_run_reports_all_required_categories(self):
        dry = ob.evaluate(synthetic_manifest())["dry_run"]
        for key in ("counts", "unsupported_fields", "duplicate_risk",
                    "missing_attachments", "errors"):
            self.assertIn(key, dry)
        self.assertEqual(dry["counts"]["total_records"], 3)
        self.assertEqual(dry["counts"]["batch_count"], 2)
        self.assertEqual(dry["unsupported_fields"], [])
        self.assertEqual(dry["duplicate_risk"], [])
        self.assertEqual(dry["missing_attachments"], [])
        self.assertEqual(dry["errors"], [])

    def test_batch_plan_is_ordered_and_reconciles(self):
        report = ob.evaluate(synthetic_manifest())
        plan = report["batch_plan"]
        self.assertEqual([b["sequence"] for b in plan], [1, 2])
        self.assertEqual([b["count"] for b in plan], [2, 1])
        self.assertEqual(sum(b["count"] for b in plan), 3)


class EnvelopeTest(unittest.TestCase):
    def test_manifest_must_be_an_object(self):
        report = ob.evaluate([])
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("manifest", failures(report))

    def test_schema_version_must_match(self):
        self.assertIn("schema_version",
                      failures(ob.evaluate(synthetic_manifest(schema_version="2.0"))))

    def test_requirement_must_be_ops08(self):
        self.assertIn("requirement",
                      failures(ob.evaluate(synthetic_manifest(requirement="OPS-07"))))

    def test_manifest_version_must_be_a_positive_integer(self):
        for value in (0, -1, "3", True, None):
            with self.subTest(value=value):
                self.assertIn("manifest_version",
                              failures(ob.evaluate(synthetic_manifest(
                                  manifest_version=value))))

    def test_source_and_synthetic_only_are_enforced(self):
        self.assertIn("source", failures(ob.evaluate(
            synthetic_manifest(source=""))))
        self.assertIn("synthetic_only", failures(ob.evaluate(
            synthetic_manifest(synthetic_only=False))))


class DatasetTest(unittest.TestCase):
    def test_dataset_must_be_an_object(self):
        self.assertIn("dataset", failures(ob.evaluate(synthetic_manifest(dataset=[]))))

    def test_records_must_be_a_non_empty_list_of_objects(self):
        for value in (None, "records", [], [object()], [{"source_id": "X"}]):
            with self.subTest(value=value):
                self.assertIn("dataset.records", failures(ob.evaluate(
                    synthetic_manifest(dataset={"records": value}))))

    def test_source_ids_must_be_unique_within_the_dataset(self):
        # The documented record shape requires a unique source id. Two records
        # sharing one id must fail a named check, not be deduplicated silently
        # by the batch coverage fold.
        records = [_record("SYN-0001", "M-001", "ORG-A"),
                   _record("SYN-0001", "M-002", "ORG-A")]
        report = ob.evaluate(with_records(records))
        self.assertIn("dataset.source_ids", failures(report))
        self.assertFalse(report["commit_allowed"])

    def test_blank_or_non_string_source_id_is_rejected(self):
        for value in ("", "   ", None, 123):
            with self.subTest(value=value):
                manifest = synthetic_manifest()
                manifest["dataset"]["records"][1]["source_id"] = value
                report = ob.evaluate(manifest)
                self.assertIn("dataset.source_ids", failures(report))


class MappingTest(unittest.TestCase):
    def test_mapping_must_be_an_object(self):
        self.assertIn("mapping", failures(ob.evaluate(synthetic_manifest(mapping=None))))

    def test_mapping_must_be_deterministic(self):
        for value in (False, "yes", None):
            with self.subTest(value=value):
                self.assertIn("mapping.deterministic", failures(ob.evaluate(
                    synthetic_manifest(mapping={"deterministic": value}))))

    def test_mapping_strategy_must_be_the_declared_deterministic_strategy(self):
        self.assertIn("mapping.strategy", failures(ob.evaluate(
            synthetic_manifest(mapping={"strategy": "random"}))))

    def test_all_required_target_fields_must_be_mapped(self):
        self.assertIn("mapping.required_fields", failures(ob.evaluate(
            synthetic_manifest(mapping={
                "source_to_target": {"mrn": "mrn"}}))))

    def test_source_to_target_must_be_an_object(self):
        self.assertIn("mapping.required_fields", failures(ob.evaluate(
            synthetic_manifest(mapping={"source_to_target": []}))))

    def test_identity_fields_must_reference_mapped_targets(self):
        self.assertIn("mapping.identity_fields", failures(ob.evaluate(
            synthetic_manifest(mapping={"identity_fields": ["notMapped"]}))))

    def test_identity_fields_must_include_issuer_scoped_mrn(self):
        # A non-MRN identity would hide duplicate issuer-scoped identifiers.
        self.assertIn("mapping.identity_fields", failures(ob.evaluate(
            synthetic_manifest(mapping={"identity_fields": ["givenName"]}))))

    def test_duplicate_target_values_are_rejected(self):
        mapping = {"source_to_target": {
            "mrn": "mrn", "issuer": "mrnIssuer", "given": "mrn",
            "family": "familyName", "dob": "birthDate",
        }}
        self.assertIn("mapping.required_fields", failures(ob.evaluate(
            synthetic_manifest(mapping=mapping))))

    def test_non_string_target_values_are_rejected(self):
        mapping = {"source_to_target": {
            "mrn": ["mrn"], "issuer": "mrnIssuer", "given": "givenName",
            "family": "familyName", "dob": "birthDate",
        }}
        self.assertIn("mapping.required_fields", failures(ob.evaluate(
            synthetic_manifest(mapping=mapping))))

    def test_identity_fields_must_be_a_non_empty_list(self):
        for value in (None, [], "mrn"):
            with self.subTest(value=value):
                self.assertIn("mapping.identity_fields", failures(ob.evaluate(
                    synthetic_manifest(mapping={"identity_fields": value}))))

    def test_ignored_fields_must_be_a_list(self):
        self.assertIn("mapping.ignored_fields", failures(ob.evaluate(
            synthetic_manifest(mapping={"ignored_fields": "note"}))))


class ProvenanceTest(unittest.TestCase):
    def test_provenance_must_be_an_object(self):
        self.assertIn("provenance", failures(ob.evaluate(
            synthetic_manifest(provenance=None))))

    def test_source_system_and_version_are_required(self):
        self.assertIn("provenance.source_system", failures(ob.evaluate(
            synthetic_manifest(provenance={"source_system": ""}))))
        self.assertIn("provenance.source_version", failures(ob.evaluate(
            synthetic_manifest(provenance={"source_version": None}))))

    def test_import_must_preserve_historical_signatures(self):
        self.assertIn("provenance.preserve_historical_signatures", failures(
            ob.evaluate(synthetic_manifest(provenance={
                "preserve_historical_signatures": False}))))

    def test_import_must_not_create_a_new_attestation(self):
        self.assertIn("provenance.creates_new_attestation", failures(
            ob.evaluate(synthetic_manifest(provenance={
                "creates_new_attestation": True}))))

    def test_each_record_must_carry_historical_signature_evidence(self):
        records = [_record("SYN-0001", "M-001", "ORG-A"),
                   _record("SYN-0002", "M-002", "ORG-A", signature=False)]
        self.assertIn("provenance.historical_signature_evidence", failures(
            ob.evaluate(with_records(records))))

    def test_historical_signature_fields_must_be_valid(self):
        records = [_record("SYN-0001", "M-001", "ORG-A")]
        records[0]["historical_signature"]["checksum"] = "not-a-sha256"
        self.assertIn("provenance.historical_signature_evidence", failures(
            ob.evaluate(with_records(records))))


class DryRunTest(unittest.TestCase):
    def test_unsupported_fields_are_reported(self):
        records = [_record("SYN-0001", "M-001", "ORG-A"),
                   _record("SYN-0002", "M-002", "ORG-A")]
        records[0]["fields"]["note"] = "free text"
        report = ob.evaluate(with_records(records))
        self.assertIn("dry_run.unsupported_fields", failures(report))
        self.assertIn("note", report["dry_run"]["unsupported_fields"])

    def test_declared_ignored_fields_are_not_unsupported(self):
        records = [_record("SYN-0001", "M-001", "ORG-A"),
                   _record("SYN-0002", "M-002", "ORG-A")]
        records[0]["fields"]["note"] = "free text"
        report = ob.evaluate(with_records(records, mapping={"ignored_fields": ["note"]}))
        self.assertNotIn("dry_run.unsupported_fields", failures(report))
        self.assertEqual(report["verdict"], "pass")

    def test_duplicate_risk_is_reported(self):
        records = [_record("SYN-0001", "M-001", "ORG-A"),
                   _record("SYN-0002", "M-002", "ORG-A"),
                   _record("SYN-0003", "M-003", "ORG-A"),
                   _record("SYN-0004", "M-001", "ORG-A")]
        report = ob.evaluate(with_records(records))
        self.assertIn("dry_run.duplicate_risk", failures(report))
        self.assertEqual(len(report["dry_run"]["duplicate_risk"]), 2)

    def test_missing_attachments_are_reported(self):
        records = [_record("SYN-0001", "M-001", "ORG-A"),
                   _record("SYN-0002", "M-002", "ORG-A", attachment_present=False)]
        report = ob.evaluate(with_records(records))
        self.assertIn("dry_run.missing_attachments", failures(report))
        self.assertEqual(len(report["dry_run"]["missing_attachments"]), 1)

    def test_record_validation_errors_are_reported(self):
        records = [_record("SYN-0001", "M-001", "ORG-A"),
                   _record("SYN-0002", "M-002", "ORG-A")]
        records[0]["fields"] = []
        report = ob.evaluate(with_records(records))
        self.assertIn("dry_run.errors", failures(report))
        self.assertTrue(report["dry_run"]["errors"])

    def test_invalid_attachment_is_a_record_error(self):
        records = [_record("SYN-0001", "M-001", "ORG-A")]
        records[0]["attachments"][0]["sha256"] = "short"
        self.assertIn("dry_run.errors", failures(
            ob.evaluate(with_records(records))))

    def test_invalid_source_id_is_a_record_error(self):
        records = [_record("SYN-0001", "M-001", "ORG-A")]
        records[0]["source_id"] = ""
        self.assertIn("dry_run.errors", failures(
            ob.evaluate(with_records(records))))

    def test_record_missing_or_blank_required_field_is_an_error(self):
        for value in (None, ""):
            with self.subTest(value=value):
                records = [_record("SYN-0001", "M-001", "ORG-A"),
                           _record("SYN-0002", "M-002", "ORG-A")]
                if value is None:
                    del records[1]["fields"]["mrn"]
                else:
                    records[1]["fields"]["mrn"] = value
                report = ob.evaluate(with_records(records))
                self.assertIn("dry_run.errors", failures(report))
                self.assertIn("SYN-0002: missing required field mrn",
                              report["dry_run"]["errors"])

    def test_non_object_attachment_is_a_record_error(self):
        records = [_record("SYN-0001", "M-001", "ORG-A")]
        records[0]["attachments"] = ["not-an-object"]
        self.assertIn("dry_run.errors", failures(
            ob.evaluate(with_records(records))))

    def test_counts_report_every_category(self):
        counts = ob.evaluate(synthetic_manifest())["dry_run"]["counts"]
        self.assertEqual(counts, {
            "total_records": 3, "batch_count": 2,
            "unsupported_fields": 0, "duplicate_risk": 0,
            "missing_attachments": 0, "errors": 0,
        })


class BatchesTest(unittest.TestCase):
    def test_batches_must_be_a_non_empty_list(self):
        for value in (None, [], "batch"):
            with self.subTest(value=value):
                self.assertIn("batches", failures(ob.evaluate(
                    synthetic_manifest(batches=value))))

    def test_batch_sequences_must_be_integers_not_booleans(self):
        # True == 1 in Python, so an explicit type guard is required.
        batches = _batches([_record("SYN-0001", "M-001", "ORG-A"),
                            _record("SYN-0002", "M-002", "ORG-A")], size=1)
        batches[0]["sequence"] = True
        self.assertIn("batches.sequence", failures(
            ob.evaluate(synthetic_manifest(batches=batches))))

    def test_batch_sequences_must_be_contiguous_from_one(self):
        batches = _batches([_record("SYN-0001", "M-001", "ORG-A"),
                            _record("SYN-0002", "M-002", "ORG-A")], size=1)
        batches[1]["sequence"] = 3
        self.assertIn("batches.sequence", failures(
            ob.evaluate(synthetic_manifest(batches=batches))))

    def test_batch_record_ids_must_be_sorted_and_unique(self):
        batches = _batches([_record("SYN-0001", "M-001", "ORG-A"),
                            _record("SYN-0002", "M-002", "ORG-A")])
        batches[0]["record_ids"] = ["SYN-0002", "SYN-0001"]
        self.assertIn("batches.order", failures(
            ob.evaluate(synthetic_manifest(batches=batches))))

    def test_batch_coverage_must_match_all_records_exactly_once(self):
        batches = _batches([_record("SYN-0001", "M-001", "ORG-A"),
                            _record("SYN-0002", "M-002", "ORG-A")])
        batches[0]["record_ids"] = ["SYN-0009", "SYN-0002"]
        self.assertIn("batches.coverage", failures(
            ob.evaluate(synthetic_manifest(batches=batches))))

    def test_batch_count_must_be_within_the_controlled_limit(self):
        records = [_record(f"SYN-{n:04d}", f"M-{n:03d}", "ORG-A")
                   for n in range(1, ob.MAX_BATCH_SIZE + 2)]
        # One oversized batch holding every record.
        batches = [{"sequence": 1,
                    "record_ids": [r["source_id"] for r in records],
                    "expected_count": len(records),
                    "sha256": _hex(b"".join(_canonical(r) for r in records))}]
        self.assertIn("batches.size", failures(
            ob.evaluate(with_records(records, batches=batches,
                                     sign_off={"batches": [1]}))))

    def test_batch_expected_count_must_equal_record_ids_length(self):
        batches = _batches([_record("SYN-0001", "M-001", "ORG-A")])
        batches[0]["expected_count"] = 2
        self.assertIn("batches.size", failures(
            ob.evaluate(synthetic_manifest(batches=batches))))

    def test_batch_hash_must_reconcile(self):
        batches = _batches([_record("SYN-0001", "M-001", "ORG-A"),
                            _record("SYN-0002", "M-002", "ORG-A")])
        batches[0]["sha256"] = _hex(b"tampered")
        self.assertIn("batches.sha256", failures(
            ob.evaluate(synthetic_manifest(batches=batches))))

    def test_non_string_batch_id_is_rejected(self):
        batches = _batches([_record("SYN-0001", "M-001", "ORG-A")])
        batches[0]["record_ids"] = [{"bad": "id"}]
        report = ob.evaluate(synthetic_manifest(batches=batches))
        self.assertIn("batches.order", failures(report))
        self.assertIn("batches.sha256", failures(report))

    def test_empty_and_duplicate_batch_ids_are_rejected(self):
        for ids in ([], ["SYN-0001", "SYN-0001"]):
            with self.subTest(ids=ids):
                batches = _batches([_record("SYN-0001", "M-001", "ORG-A")])
                batches[0]["record_ids"] = ids
                report = ob.evaluate(synthetic_manifest(batches=batches))
                self.assertIn("batches.order", failures(report))
                self.assertIn("batches.size", failures(report))


class SignOffTest(unittest.TestCase):
    def test_sign_off_must_be_an_object(self):
        self.assertIn("sign_off", failures(ob.evaluate(
            synthetic_manifest(sign_off=None))))

    def test_approver_is_required(self):
        self.assertIn("sign_off.approver", failures(ob.evaluate(
            synthetic_manifest(sign_off={"approver": ""}))))

    def test_role_must_be_an_authorized_role(self):
        self.assertIn("sign_off.role", failures(ob.evaluate(
            synthetic_manifest(sign_off={"role": "patient"}))))

    def test_decision_must_be_approved(self):
        self.assertIn("sign_off.decision", failures(ob.evaluate(
            synthetic_manifest(sign_off={"decision": "pending"}))))

    def test_scope_must_cover_manifest_version_and_all_batches(self):
        self.assertIn("sign_off.scope", failures(ob.evaluate(
            synthetic_manifest(sign_off={"manifest_version": 2}))))
        self.assertIn("sign_off.scope", failures(ob.evaluate(
            synthetic_manifest(sign_off={"batches": [1]}))))

    def test_sign_off_batches_must_be_integer_sequences(self):
        for value in ("all", ["1"], [True]):
            with self.subTest(value=value):
                self.assertIn("sign_off.scope", failures(ob.evaluate(
                    synthetic_manifest(sign_off={"batches": value}))))

    def test_sign_off_version_must_be_an_integer_not_a_boolean(self):
        # Without the type guard True == 1 would satisfy a version of 1.
        self.assertIn("sign_off.scope", failures(ob.evaluate(
            synthetic_manifest(manifest_version=1,
                               sign_off={"manifest_version": True}))))

    def test_content_change_under_unchanged_sign_off_is_rejected(self):
        original = synthetic_manifest()
        signed_content = original["sign_off"]["content_sha256"]
        records = original["dataset"]["records"]
        records[0]["fields"]["mrn"] = "M-TAMPERED"
        # The importer recomputes the batch digests, but the approval does not
        # change: the content binding must still reject the substitution.
        tampered = with_records(records, sign_off={"content_sha256": signed_content})
        report = ob.evaluate(tampered)
        self.assertIn("sign_off.content", failures(report))
        self.assertNotIn("batches.sha256", failures(report))
        self.assertFalse(report["commit_allowed"])

    def test_new_attestation_claim_is_not_made_for_a_non_conforming_manifest(self):
        report = ob.evaluate(synthetic_manifest(provenance={
            "creates_new_attestation": True}))
        self.assertIsNone(report["new_attestations_created"])

    def test_approval_time_is_required(self):
        self.assertIn("sign_off.approved_at", failures(ob.evaluate(
            synthetic_manifest(sign_off={"approved_at": ""}))))

    def test_without_sign_off_the_source_of_truth_is_unchanged(self):
        report = ob.evaluate(synthetic_manifest(sign_off={"decision": "pending"}))
        self.assertFalse(report["commit_allowed"])
        self.assertEqual(report["source_of_truth_action"], "unchanged")


class ReportAndCliTest(unittest.TestCase):
    def test_format_report_shows_verdict_and_each_check(self):
        text = ob.format_report(ob.evaluate(synthetic_manifest()))
        self.assertIn("OPS-08 onboarding: PASS", text)
        self.assertIn("[PASS]", text)
        self.assertIn("dry_run.counts", text)
        failing = ob.format_report(ob.evaluate(synthetic_manifest(requirement="X")))
        self.assertIn("OPS-08 onboarding: FAIL", failing)
        self.assertIn("[FAIL]", failing)

    def test_main_evaluates_a_conforming_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(synthetic_manifest()), encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = ob.main([str(path)])
            self.assertEqual(code, 0)
            self.assertIn("PASS", stdout.getvalue())

    def test_main_returns_one_for_a_failing_manifest(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(synthetic_manifest(
                requirement="WRONG")), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), \
                    contextlib.redirect_stderr(io.StringIO()):
                code = ob.main([str(path)])
            self.assertEqual(code, 1)

    def test_main_reads_sys_argv_when_no_arguments_are_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text(json.dumps(synthetic_manifest()), encoding="utf-8")
            stdout = io.StringIO()
            with mock.patch.object(sys, "argv", ["onboarding.py", str(path)]):
                with contextlib.redirect_stdout(stdout):
                    code = ob.main()
            self.assertEqual(code, 0)

    def test_main_rejects_bad_usage(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = ob.main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_a_missing_file(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = ob.main(["/definitely/not/here/manifest.json"])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.json"
            path.write_text("{not json", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = ob.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())


class ManifestFixtureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixtures = MODULE_PATH.parent / "fixtures"
        cls.conforming = json.loads(
            (fixtures / "synthetic_onboarding_manifest.json").read_text(
                encoding="utf-8"))
        cls.violation = json.loads(
            (fixtures / "violation_onboarding_manifest.json").read_text(
                encoding="utf-8"))

    def test_conforming_fixture_passes(self):
        report = ob.evaluate(self.conforming)
        self.assertEqual(report["verdict"], "pass")
        self.assertTrue(report["commit_allowed"])

    def test_violation_fixture_fails_multiple_clauses(self):
        report = ob.evaluate(self.violation)
        self.assertEqual(report["verdict"], "fail")
        self.assertFalse(report["commit_allowed"])
        self.assertEqual(report["source_of_truth_action"], "unchanged")

    def test_fixture_text_contains_no_phi_shaped_values(self):
        import re
        text = json.dumps([self.conforming, self.violation])
        self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", text))
        self.assertIsNone(re.search(r"\b\d{3}-\d{2}-\d{4}\b", text))


if __name__ == "__main__":
    unittest.main()
