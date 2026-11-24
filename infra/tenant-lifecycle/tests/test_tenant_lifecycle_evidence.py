"""Executable acceptance tests for the OPS-07 tenant lifecycle evidence evaluator.

Every fixture is synthetic. A conforming artifact must pass; every OPS-07 clause
(export manifest, checksums, authorized documents, provenance, audit history,
legal holds, tracked deletion, approved backup expiration, offboarding and the
four representative scenarios) must fail closed when breached. The tests
enumerate the evaluator's branches so its behaviour is fully specified without a
real deployment or real patient data.
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

MODULE_PATH = Path(__file__).resolve().parents[1] / "tenant_lifecycle_evidence.py"
SPEC = importlib.util.spec_from_file_location("tenant_lifecycle_evidence", MODULE_PATH)
tlev = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(tlev)

CHECKSUM_A = "a" * 64
CHECKSUM_B = "b" * 64
CHECKSUM_C = "c" * 64


def synthetic_record(record_id="rec-synthetic-1", checksum=CHECKSUM_A,
                     policy_version="release-policy-v2", with_documents=True,
                     with_provenance=True, with_audit=True):
    """Build one synthetic exported chart record."""
    record = {
        "id": record_id,
        "checksum": checksum,
        "provenance": ({"source_system": "orevia", "source_id": "src-synthetic-1",
                        "version": 3} if with_provenance else None),
        "documents": ([{"id": "doc-synthetic-1", "checksum": CHECKSUM_B,
                        "authorization": "release_policy",
                        "policy_version": policy_version}]
                      if with_documents else None),
        "audit": ([{"seq": 1, "action": "RELEASE", "outcome": "SUCCESS"}]
                  if with_audit else None),
    }
    return record


def synthetic_artifact(**overrides):
    """Build a synthetic, conforming OPS-07 lifecycle evidence artifact."""
    artifact = {
        "schema_version": "1.0",
        "kind": "synthetic_rehearsal",
        "source": "synthetic-lifecycle-fixture",
        "organization": "org-synthetic-1",
        "export": {
            "manifest": {
                "schema_version": "chart-v3",
                "policy_version": "release-policy-v2",
                "organization": "org-synthetic-1",
                "exported_at": "2026-11-19T00:00:00Z",
                "record_count": 1,
            },
            "records": [synthetic_record()],
        },
        "retention": {
            "policy_version": "retention-policy-v1",
            "legal_holds": [{"id": "hold-synthetic-1", "reason": "disputed_access",
                             "scope": "rec-synthetic-held", "placed_by": "privacy-officer",
                             "active": True}],
            "deletions": [{"record_id": "rec-synthetic-expired",
                           "reason": "retention_expiry", "approved_by": "privacy-officer",
                           "active_data": True}],
            "backup_expirations": [{"backup_set": "bk-synthetic-1",
                                    "scope": "org-synthetic-1",
                                    "approved_by": "ops-lead", "approved": True}],
        },
        "offboarding": {
            "organization": "org-synthetic-1",
            "authorized_by": "tenant-admin-synthetic",
            "steps": ["suspend_access", "export", "delete_active_data",
                      "expire_backups", "audit"],
            "audit_reference": "audit-synthetic-offboard-1",
        },
        "disputed_access": {
            "link": "chart-synthetic-1",
            "suspended_at": "2026-11-19T01:00:00Z",
            "suspended_by": "privacy-officer",
            "rereviewed_by": "privacy-officer-2",
            "rereviewed_at": "2026-11-19T02:00:00Z",
            "outcome": "suspended",
        },
        "scenarios": ["record_request", "export", "offboarding", "disputed_access"],
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


def with_retention(**overrides):
    """Return a full retention section with the given overrides."""
    section = synthetic_artifact()["retention"]
    section.update(overrides)
    return section


class ConformingArtifactTest(unittest.TestCase):
    def test_synthetic_rehearsal_passes_but_is_not_final_evidence(self):
        report = tlev.evaluate(synthetic_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertEqual(report["classification"], "rehearsal")
        self.assertFalse(report["final_evidence"])
        self.assertTrue(all(c["passed"] for c in report["checks"]))

    def test_measured_operation_kind_is_accepted_as_final_evidence(self):
        report = tlev.evaluate(synthetic_artifact(
            kind="measured_operation", source="staging-lifecycle-run"))
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["classification"], "measured-operation")
        self.assertTrue(report["final_evidence"])

    def test_unknown_kind_is_never_final_evidence(self):
        report = tlev.evaluate(synthetic_artifact(kind="unverified"))
        self.assertEqual(report["classification"], "unknown")
        self.assertFalse(report["final_evidence"])


class StructureTest(unittest.TestCase):
    def test_artifact_must_be_an_object(self):
        report = tlev.evaluate([])
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("artifact", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_schema_version_must_match(self):
        report = tlev.evaluate(synthetic_artifact(schema_version="2.0"))
        self.assertIn("schema_version", failures(report))

    def test_kind_must_be_known(self):
        report = tlev.evaluate(synthetic_artifact(kind="unverified"))
        self.assertIn("kind", failures(report))

    def test_measured_operation_cannot_declare_a_synthetic_source(self):
        report = tlev.evaluate(synthetic_artifact(
            kind="measured_operation", source="SYNTHETIC-fixture"))
        self.assertIn("evidence_conflict", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_every_artifact_must_name_a_nonblank_source(self):
        for value in (None, "", "   ", 7):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(source=value))
                self.assertIn("evidence.source", failures(report))

    def test_measured_operation_with_neutral_source_has_no_conflict(self):
        report = tlev.evaluate(synthetic_artifact(
            kind="measured_operation", source="staging-lifecycle-run"))
        self.assertNotIn("evidence_conflict", failures(report))

    def test_organization_must_be_a_nonempty_string(self):
        for value in (None, "", "   ", 7):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(organization=value))
                self.assertIn("organization", failures(report))

    def test_unhashable_kind_fails_closed_without_raising(self):
        report = tlev.evaluate(synthetic_artifact(kind=["synthetic_rehearsal"]))
        self.assertEqual(report["classification"], "unknown")
        self.assertIn("kind", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_export_retention_offboarding_dispute_and_scenarios_must_exist(self):
        for key in ("export", "retention", "offboarding", "disputed_access", "scenarios"):
            with self.subTest(key=key):
                report = tlev.evaluate(synthetic_artifact(**{key: None}))
                self.assertIn(key if key != "scenarios" else "scenarios",
                              failures(report))


class ExportManifestTest(unittest.TestCase):
    def test_export_must_be_an_object(self):
        report = tlev.evaluate(synthetic_artifact(export=[]))
        self.assertIn("export", failures(report))

    def test_manifest_must_be_an_object(self):
        report = tlev.evaluate(synthetic_artifact(export={"manifest": "chart-v3"}))
        self.assertIn("export.manifest", failures(report))

    def test_manifest_string_fields_must_be_nonempty(self):
        for key in ("schema_version", "policy_version", "organization", "exported_at"):
            for value in (None, "", "  ", 7):
                with self.subTest(key=key, value=value):
                    manifest = synthetic_artifact()["export"]["manifest"]
                    manifest[key] = value
                    report = tlev.evaluate(synthetic_artifact(export={"manifest": manifest}))
                    self.assertIn("export.manifest." + key, failures(report))

    def test_manifest_organization_must_match_artifact_scope(self):
        manifest = synthetic_artifact()["export"]["manifest"]
        manifest["organization"] = "org-synthetic-other"
        report = tlev.evaluate(synthetic_artifact(export={"manifest": manifest}))
        self.assertIn("export.manifest.organization_scope", failures(report))

    def test_manifest_record_count_must_be_a_positive_integer(self):
        for value in (0, -1, True, "1", None):
            with self.subTest(value=value):
                manifest = synthetic_artifact()["export"]["manifest"]
                manifest["record_count"] = value
                report = tlev.evaluate(synthetic_artifact(export={"manifest": manifest}))
                self.assertIn("export.manifest.record_count", failures(report))


class ExportRecordsTest(unittest.TestCase):
    def test_records_must_be_a_nonempty_list(self):
        for value in (None, [], "rec", {}):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(export={"records": value}))
                self.assertIn("export.records", failures(report))

    def test_record_count_must_match_the_records_list(self):
        for count in (0, 2):
            with self.subTest(count=count):
                manifest = synthetic_artifact()["export"]["manifest"]
                manifest["record_count"] = count
                report = tlev.evaluate(synthetic_artifact(export={"manifest": manifest}))
                self.assertIn("export.record_count", failures(report))

    def test_when_records_are_not_a_list_derived_checks_also_fail(self):
        report = tlev.evaluate(synthetic_artifact(export={"records": "not-a-list"}))
        for name in ("export.record_ids", "export.record_checksums",
                     "export.record_provenance", "export.documents",
                     "export.document_authorizations", "export.audit_history"):
            self.assertIn(name, failures(report))

    def test_non_dict_record_fails_every_record_check(self):
        report = tlev.evaluate(synthetic_artifact(export={"records": ["not-a-record"]}))
        for name in ("export.record_ids", "export.record_ids_unique",
                     "export.record_checksums", "export.record_provenance",
                     "export.documents", "export.document_authorizations",
                     "export.audit_history"):
            self.assertIn(name, failures(report))

    def test_record_id_must_be_a_nonempty_string(self):
        for value in (None, "", "  ", 7):
            with self.subTest(value=value):
                record = synthetic_record()
                record["id"] = value
                report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
                self.assertIn("export.record_ids", failures(report))

    def test_record_checksum_must_be_lowercase_sha256_hex(self):
        for value in (CHECKSUM_A.upper(), "abc", 7, None, "g" * 64):
            with self.subTest(value=value):
                record = synthetic_record(checksum=value)
                report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
                self.assertIn("export.record_checksums", failures(report))

    def test_record_provenance_must_be_complete(self):
        for value in (None, {}, {"source_system": "orevia"},
                      {"source_system": "orevia", "source_id": "src", "version": 0},
                      {"source_system": "orevia", "source_id": "src", "version": True}):
            with self.subTest(value=value):
                record = synthetic_record()
                record["provenance"] = value
                report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
                self.assertIn("export.record_provenance", failures(report))

    def test_record_provenance_helper_rejects_non_dict(self):
        self.assertFalse(tlev._valid_provenance("not-a-dict"))

    def test_documents_must_be_a_nonempty_list_of_valid_entries(self):
        for value in (None, [], "doc", [None],
                      [{"id": "doc", "checksum": "short",
                        "authorization": "release_policy",
                        "policy_version": "release-policy-v2"}]):
            with self.subTest(value=value):
                record = synthetic_record()
                record["documents"] = value
                report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
                self.assertIn("export.documents", failures(report))

    def test_document_authorization_must_be_policy_allowed(self):
        for value in ("arbitrary", None, "", 7):
            with self.subTest(value=value):
                record = synthetic_record()
                record["documents"][0]["authorization"] = value
                report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
                self.assertIn("export.document_authorizations", failures(report))

    def test_document_policy_version_must_match_the_manifest(self):
        record = synthetic_record(policy_version="release-policy-OLD")
        report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
        self.assertIn("export.documents", failures(report))

    def test_audit_history_must_be_nonempty_and_well_formed(self):
        for value in (None, [], "audit", [None],
                      [{"seq": 0, "action": "RELEASE", "outcome": "SUCCESS"}],
                      [{"seq": 1, "action": "  ", "outcome": "SUCCESS"}],
                      [{"seq": 1, "action": "RELEASE", "outcome": None}]):
            with self.subTest(value=value):
                record = synthetic_record()
                record["audit"] = value
                report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
                self.assertIn("export.audit_history", failures(report))


class AuditHistoryOrderTest(unittest.TestCase):
    def test_audit_seq_must_be_strictly_increasing(self):
        for seqs in ([2, 1], [1, 1], [3, 1, 2]):
            with self.subTest(seqs=seqs):
                record = synthetic_record()
                record["audit"] = [{"seq": seq, "action": "RELEASE", "outcome": "SUCCESS"}
                                   for seq in seqs]
                report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
                self.assertIn("export.audit_history", failures(report))

    def test_ordered_distinct_audit_seq_is_accepted(self):
        record = synthetic_record()
        record["audit"] = [{"seq": seq, "action": "RELEASE", "outcome": "SUCCESS"}
                           for seq in (1, 2, 3)]
        report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
        self.assertNotIn("export.audit_history", failures(report))
        self.assertEqual(report["verdict"], "pass")


class RetentionTest(unittest.TestCase):
    def test_retention_must_be_an_object(self):
        report = tlev.evaluate(synthetic_artifact(retention=[]))
        self.assertIn("retention", failures(report))

    def test_retention_policy_version_is_required(self):
        for value in (None, "", 7):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(
                    retention=with_retention(policy_version=value)))
                self.assertIn("retention.policy_version", failures(report))

    def test_legal_holds_must_be_a_list_of_valid_entries(self):
        for value in (None, "hold", [None],
                      [{"id": "h", "reason": "dispute", "scope": "rec",
                        "placed_by": "privacy-officer", "active": "yes"}]):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(
                    retention=with_retention(legal_holds=value)))
                self.assertIn("retention.legal_hold_entries", failures(report))

    def test_empty_legal_holds_list_is_allowed(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(legal_holds=[])))
        self.assertEqual(report["verdict"], "pass")
        self.assertNotIn("retention.legal_hold_entries", failures(report))

    def test_deletions_must_be_a_list_of_valid_entries(self):
        for value in (None, "del", [None],
                      [{"record_id": "r", "reason": "", "approved_by": "a", "active_data": True}]):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(
                    retention=with_retention(deletions=value)))
                self.assertIn("retention.deletion_entries", failures(report))

    def test_deletion_under_an_active_legal_hold_is_rejected(self):
        # The held record must never appear in the deletion tracking list.
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h1", "reason": "disputed_access",
                          "scope": "rec-synthetic-held", "placed_by": "privacy-officer",
                          "active": True}],
            deletions=[{"record_id": "rec-synthetic-held", "reason": "retention_expiry",
                        "approved_by": "privacy-officer", "active_data": True}])))
        self.assertIn("retention.legal_hold_respected", failures(report))

    def test_deletion_under_an_authorized_lifted_hold_is_allowed(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h1", "reason": "closed_dispute",
                          "scope": "rec-synthetic-held", "placed_by": "privacy-officer",
                          "active": False, "lifted_by": "privacy-officer-2",
                          "lifted_at": "2026-11-19T06:00:00Z",
                          "lift_reason": "dispute_closed"}],
            deletions=[{"record_id": "rec-synthetic-held", "reason": "retention_expiry",
                        "approved_by": "privacy-officer", "active_data": True}])))
        self.assertNotIn("retention.legal_hold_respected", failures(report))
        self.assertEqual(report["verdict"], "pass")

    def test_inactive_hold_without_a_recorded_lift_is_rejected(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h1", "reason": "closed_dispute",
                          "scope": "rec-synthetic-held", "placed_by": "privacy-officer",
                          "active": False}])))
        self.assertIn("retention.legal_hold_entries", failures(report))
        # It still covers the record for fail-closed deletion matching.
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h1", "reason": "closed_dispute",
                          "scope": "rec-synthetic-held", "placed_by": "privacy-officer",
                          "active": False}],
            deletions=[{"record_id": "rec-synthetic-held", "reason": "retention_expiry",
                        "approved_by": "privacy-officer", "active_data": True}])))
        self.assertIn("retention.legal_hold_respected", failures(report))

    def test_active_data_deletion_requires_reason_and_approval(self):
        for deletion in (
            {"record_id": "r", "reason": "", "approved_by": "privacy-officer", "active_data": True},
            {"record_id": "r", "reason": "expiry", "approved_by": "", "active_data": True},
            {"record_id": "r", "reason": "expiry", "approved_by": "privacy-officer", "active_data": False},
        ):
            with self.subTest(deletion=deletion):
                report = tlev.evaluate(synthetic_artifact(
                    retention=with_retention(deletions=[deletion])))
                self.assertIn("retention.deletion_approval", failures(report))

    def test_backup_expirations_must_be_a_list_of_approved_entries(self):
        for value in (None, "bk", [None],
                      [{"backup_set": "bk", "scope": "org", "approved_by": "ops", "approved": False}]):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(
                    retention=with_retention(backup_expirations=value)))
                self.assertIn("retention.backup_expiration_entries", failures(report))

    def test_empty_backup_expirations_list_is_allowed(self):
        report = tlev.evaluate(synthetic_artifact(
            retention=with_retention(backup_expirations=[])))
        self.assertEqual(report["verdict"], "pass")


class OffboardingTest(unittest.TestCase):
    def test_offboarding_must_be_an_object(self):
        report = tlev.evaluate(synthetic_artifact(offboarding=[]))
        self.assertIn("offboarding", failures(report))

    def test_offboarding_requires_authorization_and_audit_reference(self):
        for key in ("authorized_by", "audit_reference"):
            for value in (None, "", "  ", 7):
                with self.subTest(key=key, value=value):
                    offboarding = synthetic_artifact()["offboarding"]
                    offboarding[key] = value
                    report = tlev.evaluate(synthetic_artifact(offboarding=offboarding))
                    self.assertIn("offboarding." + key, failures(report))

    def test_offboarding_organization_must_match_scope(self):
        offboarding = synthetic_artifact()["offboarding"]
        offboarding["organization"] = "org-synthetic-other"
        report = tlev.evaluate(synthetic_artifact(offboarding=offboarding))
        self.assertIn("offboarding.organization", failures(report))

    def test_offboarding_requires_every_governed_step(self):
        for missing in tlev.OFFBOARDING_STEPS:
            with self.subTest(missing=missing):
                offboarding = synthetic_artifact()["offboarding"]
                offboarding["steps"] = [s for s in offboarding["steps"] if s != missing]
                report = tlev.evaluate(synthetic_artifact(offboarding=offboarding))
                self.assertIn("offboarding.steps", failures(report))

    def test_offboarding_steps_must_be_a_list(self):
        offboarding = synthetic_artifact()["offboarding"]
        offboarding["steps"] = "suspend_access"
        report = tlev.evaluate(synthetic_artifact(offboarding=offboarding))
        self.assertIn("offboarding.steps", failures(report))


class ScenariosTest(unittest.TestCase):
    def test_all_four_representative_scenarios_are_required(self):
        for missing in tlev.SCENARIOS:
            with self.subTest(missing=missing):
                scenarios = [s for s in synthetic_artifact()["scenarios"] if s != missing]
                report = tlev.evaluate(synthetic_artifact(scenarios=scenarios))
                self.assertIn("scenarios", failures(report))

    def test_scenarios_must_be_unique_strings(self):
        for value in (["record_request", "export", "offboarding", "disputed_access",
                       "export"], [1, 2, 3, 4]):
            with self.subTest(value=value):
                report = tlev.evaluate(synthetic_artifact(scenarios=value))
                self.assertIn("scenarios.no_duplicates", failures(report))


class ExportIntegrityTest(unittest.TestCase):
    def test_duplicate_record_ids_are_rejected(self):
        first = synthetic_record()
        second = synthetic_record(record_id=first["id"], checksum=CHECKSUM_C)
        export = synthetic_artifact()["export"]
        export["records"] = [first, second]
        export["manifest"]["record_count"] = 2
        report = tlev.evaluate(synthetic_artifact(export=export))
        self.assertIn("export.record_ids_unique", failures(report))

    def test_unhashable_record_id_fails_closed_without_raising(self):
        record = synthetic_record()
        record["id"] = ["not-a-string"]
        report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
        self.assertIn("export.record_ids_unique", failures(report))

    def test_duplicate_document_ids_are_rejected(self):
        # A record cannot list the same document id twice with different bytes:
        # the manifest would be ambiguous about which authorized copy was exported.
        record = synthetic_record()
        record["documents"].append({"id": record["documents"][0]["id"],
                                    "checksum": CHECKSUM_C,
                                    "authorization": "record_access_request",
                                    "policy_version": "release-policy-v2"})
        report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
        self.assertIn("export.documents", failures(report))

    def test_distinct_document_ids_are_accepted(self):
        record = synthetic_record()
        record["documents"].append({"id": "doc-synthetic-2", "checksum": CHECKSUM_C,
                                    "authorization": "record_access_request",
                                    "policy_version": "release-policy-v2"})
        report = tlev.evaluate(synthetic_artifact(export={"records": [record]}))
        self.assertNotIn("export.documents", failures(report))
        self.assertEqual(report["verdict"], "pass")


class ScopeMatchingTest(unittest.TestCase):
    def test_exact_and_wildcard_scopes_cover_the_target(self):
        self.assertTrue(tlev._scope_covers("rec-1", "rec-1"))
        self.assertTrue(tlev._scope_covers("*", "rec-1"))
        self.assertTrue(tlev._scope_covers("org-1", "org-1/rec-1"))

    def test_unrelated_scope_and_invalid_values_do_not_cover(self):
        for scope, target in (("rec-2", "rec-1"), (None, "rec-1"),
                              ("rec-1", None), ("  ", "rec-1"), ("rec-1", "  ")):
            with self.subTest(scope=scope, target=target):
                self.assertFalse(tlev._scope_covers(scope, target))

    def test_backup_expiration_under_an_active_hold_is_rejected(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            backup_expirations=[{"backup_set": "bk-1", "scope": "rec-synthetic-held",
                                 "approved_by": "ops-lead", "approved": True}])))
        self.assertIn("retention.backup_hold_respected", failures(report))

    def test_broad_backup_scope_covering_a_narrow_hold_is_blocked(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h", "reason": "held_record", "scope": "org-1/rec-5",
                          "placed_by": "privacy-officer", "active": True}],
            backup_expirations=[{"backup_set": "bk-1", "scope": "org-1",
                                 "approved_by": "ops-lead", "approved": True}])))
        self.assertIn("retention.backup_hold_respected", failures(report))

    def test_deleting_an_ancestor_of_a_held_record_is_blocked(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h", "reason": "held_record", "scope": "org-1/rec-5",
                          "placed_by": "privacy-officer", "active": True}],
            deletions=[{"record_id": "org-1", "reason": "offboarding",
                        "approved_by": "privacy-officer", "active_data": True}])))
        self.assertIn("retention.legal_hold_respected", failures(report))

    def test_whitespace_padded_scope_does_not_bypass_a_hold(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h", "reason": "held_record", "scope": "rec-1",
                          "placed_by": "privacy-officer", "active": True}],
            deletions=[{"record_id": " rec-1 ", "reason": "expiry",
                        "approved_by": "privacy-officer", "active_data": True}])))
        self.assertIn("retention.deletion_entries", failures(report))
        self.assertIn("retention.legal_hold_respected", failures(report))

    def test_wildcard_hold_blocks_backup_expiration(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h", "reason": "hold_all", "scope": "*",
                          "placed_by": "privacy-officer", "active": True}])))
        self.assertIn("retention.backup_hold_respected", failures(report))
        self.assertIn("retention.legal_hold_respected", failures(report))

    def test_hold_on_a_backup_set_blocks_its_expiration(self):
        report = tlev.evaluate(synthetic_artifact(retention=with_retention(
            legal_holds=[{"id": "h", "reason": "hold_backup", "scope": "bk-synthetic-1",
                          "placed_by": "privacy-officer", "active": True}])))
        self.assertIn("retention.backup_hold_respected", failures(report))

    def test_backup_expiration_outside_holds_is_allowed(self):
        report = tlev.evaluate(synthetic_artifact())
        self.assertNotIn("retention.backup_hold_respected", failures(report))

    def test_malformed_ledgers_fail_the_cross_checks(self):
        expectations = {
            "legal_holds": ("retention.legal_hold_respected",
                            "retention.backup_hold_respected"),
            "deletions": ("retention.legal_hold_respected",),
            "backup_expirations": ("retention.backup_hold_respected",),
        }
        for key, checks in expectations.items():
            with self.subTest(key=key):
                report = tlev.evaluate(synthetic_artifact(
                    retention=with_retention(**{key: None})))
                for name in checks:
                    self.assertIn(name, failures(report))


class OffboardingOrderTest(unittest.TestCase):
    def test_steps_must_follow_the_governed_order(self):
        offboarding = synthetic_artifact()["offboarding"]
        offboarding["steps"] = ["audit", "suspend_access", "export",
                                "delete_active_data", "expire_backups"]
        report = tlev.evaluate(synthetic_artifact(offboarding=offboarding))
        self.assertIn("offboarding.steps", failures(report))

    def test_extra_steps_between_governed_steps_are_allowed(self):
        offboarding = synthetic_artifact()["offboarding"]
        offboarding["steps"] = ["suspend_access", "notify_owner", "export",
                                "delete_active_data", "expire_backups", "audit"]
        report = tlev.evaluate(synthetic_artifact(offboarding=offboarding))
        self.assertNotIn("offboarding.steps", failures(report))


class DisputedAccessTest(unittest.TestCase):
    def test_disputed_access_must_be_an_object(self):
        report = tlev.evaluate(synthetic_artifact(disputed_access=[]))
        self.assertIn("disputed_access", failures(report))

    def test_link_is_required(self):
        for value in (None, "", 7):
            with self.subTest(value=value):
                disputed = synthetic_artifact()["disputed_access"]
                disputed["link"] = value
                report = tlev.evaluate(synthetic_artifact(disputed_access=disputed))
                self.assertIn("disputed_access.link", failures(report))

    def test_suspension_requires_timestamp_and_actor(self):
        for key in ("suspended_at", "suspended_by"):
            with self.subTest(key=key):
                disputed = synthetic_artifact()["disputed_access"]
                disputed[key] = None
                report = tlev.evaluate(synthetic_artifact(disputed_access=disputed))
                self.assertIn("disputed_access.suspends", failures(report))

    def test_rereview_requires_reviewer_and_timestamp(self):
        for key in ("rereviewed_by", "rereviewed_at"):
            with self.subTest(key=key):
                disputed = synthetic_artifact()["disputed_access"]
                disputed[key] = ""
                report = tlev.evaluate(synthetic_artifact(disputed_access=disputed))
                self.assertIn("disputed_access.rereview_required", failures(report))

    def test_outcome_must_be_a_known_value(self):
        for value in ("granted", None, 7):
            with self.subTest(value=value):
                disputed = synthetic_artifact()["disputed_access"]
                disputed["outcome"] = value
                report = tlev.evaluate(synthetic_artifact(disputed_access=disputed))
                self.assertIn("disputed_access.outcome", failures(report))

    def test_restoration_without_identity_reverification_is_rejected(self):
        disputed = synthetic_artifact()["disputed_access"]
        disputed["outcome"] = "restored_after_verification"
        report = tlev.evaluate(synthetic_artifact(disputed_access=disputed))
        self.assertIn("disputed_access.reverification", failures(report))

    def test_restoration_with_identity_reverification_is_allowed(self):
        disputed = synthetic_artifact()["disputed_access"]
        disputed["outcome"] = "restored_after_verification"
        disputed["identity_reverified"] = True
        report = tlev.evaluate(synthetic_artifact(disputed_access=disputed))
        self.assertNotIn("disputed_access.reverification", failures(report))


class RobustnessTest(unittest.TestCase):
    def test_unhashable_scenario_member_fails_closed(self):
        scenarios = ["record_request", "export", "offboarding",
                     ["disputed_access"]]
        report = tlev.evaluate(synthetic_artifact(scenarios=scenarios))
        self.assertIn("scenarios", failures(report))
        self.assertIn("scenarios.no_duplicates", failures(report))

    def test_nonserializable_artifact_fails_the_phi_scan(self):
        artifact = synthetic_artifact()
        artifact["extra"] = {(1, 2): "tuple-key"}
        report = tlev.evaluate(artifact)
        self.assertIn("phi.free", failures(report))

    def test_main_rejects_an_undecodable_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_bytes(b"\xff\xfe\x00invalid")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = tlev.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())


class PhiTest(unittest.TestCase):
    def test_email_shaped_value_fails_closed(self):
        report = tlev.evaluate(synthetic_artifact(source="synthetic@example.invalid"))
        self.assertIn("phi.free", failures(report))

    def test_ssn_shaped_value_fails_closed(self):
        ssn = "123" + "-45-" + "6789"
        report = tlev.evaluate(synthetic_artifact(source=ssn))
        self.assertIn("phi.free", failures(report))

    def test_embedded_ssn_shaped_value_fails_closed(self):
        ssn = "ssn" + "123" + "-45-" + "6789"
        report = tlev.evaluate(synthetic_artifact(source=ssn))
        self.assertIn("phi.free", failures(report))

    def test_ssn_shaped_value_in_a_longer_digit_run_is_not_a_false_positive(self):
        value = "id" + "12345" + "-45-" + "6789"
        report = tlev.evaluate(synthetic_artifact(source=value))
        self.assertNotIn("phi.free", failures(report))


class ReportAndCliTest(unittest.TestCase):
    def test_format_report_shows_each_check_and_verdict(self):
        passed = tlev.format_report(tlev.evaluate(synthetic_artifact()))
        self.assertIn("[PASS]", passed)
        self.assertIn("export.record_checksums", passed)
        self.assertIn("OPS-07 lifecycle evidence: PASS", passed)
        self.assertIn("evidence class: rehearsal (final: False)", passed)
        failing = tlev.format_report(tlev.evaluate(synthetic_artifact(kind="unverified")))
        self.assertIn("[FAIL]", failing)
        self.assertIn("evidence class: unknown (final: False)", failing)
        final = tlev.format_report(tlev.evaluate(synthetic_artifact(
            kind="measured_operation", source="staging-lifecycle-run")))
        self.assertIn("final: True", final)

    def test_main_evaluates_a_conforming_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = tlev.main([str(path)])
            self.assertEqual(code, 0)
            self.assertIn("PASS", stdout.getvalue())

    def test_main_returns_one_for_a_failing_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact(
                kind="unverified")), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = tlev.main([str(path)])
            self.assertEqual(code, 1)

    def test_main_reads_sys_argv_when_no_arguments_are_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with mock.patch.object(sys, "argv", ["tenant_lifecycle_evidence.py", str(path)]):
                with contextlib.redirect_stdout(stdout):
                    code = tlev.main()
            self.assertEqual(code, 0)

    def test_main_rejects_bad_usage(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = tlev.main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_extra_arguments(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = tlev.main(["a.json", "b.json"])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_a_missing_file(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = tlev.main(["/definitely/not/here/evidence.json"])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "evidence.json"
            path.write_text("{not json", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = tlev.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())


class CheckedInFixtureTest(unittest.TestCase):
    """The shipped fixtures must exercise both the pass and fail paths."""

    @classmethod
    def setUpClass(cls):
        fixtures = MODULE_PATH.parent / "fixtures"
        cls.conforming = json.loads(
            (fixtures / "synthetic_lifecycle_evidence.json").read_text(encoding="utf-8"))
        cls.measured = json.loads(
            (fixtures / "measured_lifecycle_evidence.json").read_text(encoding="utf-8"))
        cls.violation = json.loads(
            (fixtures / "violation_lifecycle_evidence.json").read_text(encoding="utf-8"))

    def test_synthetic_fixture_passes_as_a_rehearsal(self):
        report = tlev.evaluate(self.conforming)
        self.assertEqual(report["verdict"], "pass")
        self.assertFalse(report["final_evidence"])

    def test_measured_fixture_passes_as_final_evidence(self):
        report = tlev.evaluate(self.measured)
        self.assertEqual(report["verdict"], "pass")
        self.assertTrue(report["final_evidence"])

    def test_violation_fixture_fails_on_every_safety_invariant(self):
        report = tlev.evaluate(self.violation)
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({
            "export.record_checksums", "export.document_authorizations",
            "retention.legal_hold_respected", "retention.backup_hold_respected",
            "retention.backup_expiration_entries", "disputed_access.reverification",
            "scenarios", "evidence_conflict",
        }.issubset(failures(report)))


if __name__ == "__main__":
    unittest.main()
