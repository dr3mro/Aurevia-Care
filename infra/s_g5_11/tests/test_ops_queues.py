"""Executable acceptance tests for the ADM-02 operational-queues evaluator.

Every fixture is synthetic. A conforming artifact must pass; every queue,
operator, action, audit, export and evidence-class omission must fail closed.
The tests enumerate the evaluator's branches so its behaviour is fully specified
without a deployment, a live queue or a real operator.
"""
import contextlib
import importlib.util
import io
import json
import sys
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

MODULE_PATH = Path(__file__).resolve().parents[1] / "ops_queues.py"
SPEC = importlib.util.spec_from_file_location("ops_queues", MODULE_PATH)
ops_queues = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(ops_queues)

oq = ops_queues

ORG = "org-synthetic-1"
AT = "2026-11-19T09:30:00Z"


def _audit(ident, actor, action, resource, at=AT, correlation="corr-1"):
    """Build one synthetic OPS-02 audit event."""
    return {
        "id": ident,
        "actor": actor,
        "action": action,
        "resource_id": resource,
        "at": at,
        "outcome": "SUCCESS",
        "correlation_id": correlation,
        "organization_id": ORG,
    }


def _action(kind, actor="op-1", reason="synthetic reason", audit_id="audit-1"):
    """Build one synthetic operator action."""
    return {
        "type": kind,
        "actor": actor,
        "reason": reason,
        "at": AT,
        "audit_id": audit_id,
    }


def _item(ident, queue, state, actions=None, owner=None):
    """Build one synthetic work item."""
    item = {
        "id": ident,
        "queue": queue,
        "created_at": "2026-11-19T09:00:00Z",
        "state": state,
        "actions": [] if actions is None else actions,
    }
    if owner is not None:
        item["owner"] = owner
    return item


def synthetic_artifact(**overrides):
    """Build a synthetic, conforming ADM-02 operational-queues artifact."""
    artifact = {
        "schema_version": "1.0",
        "kind": "synthetic_rehearsal",
        "source": "synthetic-ops-queue-fixture",
        "organization_id": ORG,
        "operators": [
            {"subject": "op-1", "role": "tenant_admin", "active": True},
            {"subject": "op-2", "role": "privacy_auditor", "active": True},
            {"subject": "clinician-1", "role": "operator", "active": False},
        ],
        "audit_events": [
            _audit("audit-1", "op-1", "ASSIGN", "u-1"),
            _audit("audit-2", "op-2", "ESCALATE", "c-1"),
            _audit("audit-3", "op-1", "RETRY", "x-1"),
            _audit("audit-4", "op-1", "RESOLVE", "x-1"),
            _audit("audit-5", "op-1", "RETRY", "d-1"),
            _audit("audit-6", "op-2", "ASSIGN", "m-1"),
            _audit("audit-7", "op-2", "RESOLVE", "a-1"),
            _audit("audit-8", "op-1", "EXPORT", "export-1"),
        ],
        "queues": {
            "unsigned_encounters": [
                _item("u-1", "unsigned_encounters", "assigned",
                      [_action("assign", "op-1", audit_id="audit-1")], owner="op-1")
            ],
            "unreconciled_reports": [_item("r-1", "unreconciled_reports", "open")],
            "unacknowledged_critical_findings": [
                _item("c-1", "unacknowledged_critical_findings", "escalated",
                      [_action("escalate", "op-2", audit_id="audit-2")], owner="op-2")
            ],
            "rejected_exchanges": [
                _item("x-1", "rejected_exchanges", "resolved", [
                    _action("retry", "op-1", audit_id="audit-3"),
                    _action("resolve", "op-1", audit_id="audit-4"),
                ])
            ],
            "document_failures": [
                _item("d-1", "document_failures", "open",
                      [_action("retry", "op-1", audit_id="audit-5")])
            ],
            "missing_coverage": [
                _item("m-1", "missing_coverage", "assigned",
                      [_action("assign", "op-2", audit_id="audit-6")], owner="op-2")
            ],
            "revoked_accounts": [
                _item("a-1", "revoked_accounts", "resolved",
                      [_action("resolve", "op-2", audit_id="audit-7")])
            ],
        },
        "exports": [{
            "scope": {"organization_id": ORG,
                      "queues": ["unsigned_encounters", "revoked_accounts"]},
            "created_at": "2026-11-19T10:00:00Z",
            "expires_at": "2026-11-20T10:00:00Z",
            "actor": "op-1",
            "audit_id": "audit-8",
        }],
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
        report = oq.evaluate(synthetic_artifact())
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["failed"], [])
        self.assertEqual(report["classification"], "rehearsal")
        self.assertFalse(report["final_evidence"])
        self.assertTrue(all(c["passed"] for c in report["checks"]))

    def test_operator_attested_kind_is_accepted_as_final_evidence(self):
        report = oq.evaluate(synthetic_artifact(
            kind="operator_attested", source="site-attested-snapshot"))
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["classification"], "operator-attested")
        self.assertTrue(report["final_evidence"])

    def test_unknown_kind_is_never_final_evidence(self):
        report = oq.evaluate(synthetic_artifact(kind="unverified"))
        self.assertEqual(report["classification"], "unknown")
        self.assertFalse(report["final_evidence"])
        self.assertIn("kind", failures(report))

    def test_every_required_queue_is_present_and_validated(self):
        report = oq.evaluate(synthetic_artifact())
        names = {check["name"] for check in report["checks"]}
        for queue in oq.REQUIRED_QUEUES:
            self.assertIn("queues." + queue, names)


class StructureTest(unittest.TestCase):
    def test_artifact_must_be_an_object(self):
        report = oq.evaluate([])
        self.assertEqual(report["verdict"], "fail")
        self.assertIn("artifact", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_schema_version_must_match(self):
        report = oq.evaluate(synthetic_artifact(schema_version="2.0"))
        self.assertIn("schema_version", failures(report))

    def test_kind_must_be_known(self):
        report = oq.evaluate(synthetic_artifact(kind="unverified"))
        self.assertIn("kind", failures(report))

    def test_unhashable_kind_fails_closed(self):
        report = oq.evaluate(synthetic_artifact(kind=[]))
        self.assertIn("kind", failures(report))
        self.assertEqual(report["classification"], "unknown")
        self.assertFalse(report["final_evidence"])

    def test_operator_attested_cannot_declare_a_synthetic_source(self):
        report = oq.evaluate(synthetic_artifact(
            kind="operator_attested", source="SYNTHETIC-ops-fixture"))
        self.assertIn("evidence_conflict", failures(report))
        self.assertFalse(report["final_evidence"])

    def test_operator_attested_with_neutral_source_has_no_conflict(self):
        report = oq.evaluate(synthetic_artifact(
            kind="operator_attested", source="site-attested-snapshot"))
        self.assertNotIn("evidence_conflict", failures(report))

    def test_synthetic_rehearsal_with_neutral_source_has_no_conflict(self):
        report = oq.evaluate(synthetic_artifact(source="rehearsal-run"))
        self.assertNotIn("evidence_conflict", failures(report))

    def test_organization_id_must_be_a_nonempty_string(self):
        for value in (None, "", "  ", 17):
            with self.subTest(value=value):
                report = oq.evaluate(synthetic_artifact(organization_id=value))
                self.assertIn("organization_id", failures(report))

    def test_queues_must_be_an_object(self):
        report = oq.evaluate(synthetic_artifact(queues=[]))
        self.assertIn("queues", failures(report))

    def test_non_object_queues_still_reports_item_and_export_checks(self):
        report = oq.evaluate(synthetic_artifact(queues=None))
        names = failures(report)
        self.assertIn("queues", names)
        self.assertIn("items.structure", {c["name"] for c in report["checks"]})

    def test_operators_must_be_a_list(self):
        report = oq.evaluate(synthetic_artifact(operators={"op-1": "tenant_admin"}))
        self.assertIn("operators", failures(report))

    def test_audit_events_must_be_a_list(self):
        report = oq.evaluate(synthetic_artifact(audit_events={}))
        self.assertIn("audit_events", failures(report))


class QueueCatalogTest(unittest.TestCase):
    def test_every_required_queue_is_enforced(self):
        for queue in oq.REQUIRED_QUEUES:
            with self.subTest(queue=queue):
                artifact = synthetic_artifact()
                del artifact["queues"][queue]
                self.assertIn("queues", failures(oq.evaluate(artifact)))

    def test_an_unlisted_queue_is_rejected(self):
        artifact = synthetic_artifact()
        artifact["queues"]["speculative_new_queue"] = []
        self.assertIn("queues", failures(oq.evaluate(artifact)))

    def test_each_queue_must_be_a_list(self):
        for queue in oq.REQUIRED_QUEUES:
            with self.subTest(queue=queue):
                artifact = synthetic_artifact()
                artifact["queues"][queue] = {"id": "oops"}
                self.assertIn("queues." + queue, failures(oq.evaluate(artifact)))

    def test_required_queue_names_are_exactly_the_spec_set(self):
        self.assertEqual(set(oq.REQUIRED_QUEUES), {
            "unsigned_encounters", "unreconciled_reports",
            "unacknowledged_critical_findings", "rejected_exchanges",
            "document_failures", "missing_coverage", "revoked_accounts",
        })


class OperatorTest(unittest.TestCase):
    def test_operators_must_not_be_empty(self):
        self.assertIn("operators", failures(oq.evaluate(synthetic_artifact(operators=[]))))

    def test_each_operator_needs_a_subject(self):
        artifact = synthetic_artifact()
        artifact["operators"][0] = {"role": "tenant_admin", "active": True}
        self.assertIn("operators", failures(oq.evaluate(artifact)))

    def test_operator_role_must_be_authorized(self):
        artifact = synthetic_artifact()
        artifact["operators"][0] = {"subject": "op-1", "role": "superuser", "active": True}
        self.assertIn("operators", failures(oq.evaluate(artifact)))

    def test_operator_active_flag_must_be_a_boolean(self):
        artifact = synthetic_artifact()
        artifact["operators"][0] = {"subject": "op-1", "role": "tenant_admin", "active": "yes"}
        self.assertIn("operators", failures(oq.evaluate(artifact)))

    def test_all_authorized_roles_are_accepted(self):
        for role in oq.AUTHORIZED_OPERATOR_ROLES:
            with self.subTest(role=role):
                artifact = synthetic_artifact(
                    operators=[{"subject": "op-1", "role": role, "active": True}])
                self.assertNotIn("operators", failures(oq.evaluate(artifact)))

    def test_duplicate_operator_subjects_are_rejected(self):
        artifact = synthetic_artifact()
        artifact["operators"].append(
            {"subject": "op-1", "role": "tenant_admin", "active": True})
        self.assertIn("operators", failures(oq.evaluate(artifact)))

    def test_inactive_operator_cannot_perform_an_action(self):
        artifact = synthetic_artifact()
        artifact["queues"]["unsigned_encounters"][0]["actions"][0]["actor"] = "clinician-1"
        self.assertIn("items.actor", failures(oq.evaluate(artifact)))

    def test_unlisted_operator_cannot_perform_an_action(self):
        artifact = synthetic_artifact()
        artifact["queues"]["unsigned_encounters"][0]["actions"][0]["actor"] = "intruder"
        self.assertIn("items.actor", failures(oq.evaluate(artifact)))

    def test_unhashable_actor_fails_closed(self):
        artifact = synthetic_artifact()
        artifact["queues"]["unsigned_encounters"][0]["actions"][0]["actor"] = ["op-1"]
        self.assertIn("items.actor", failures(oq.evaluate(artifact)))


class AuditTest(unittest.TestCase):
    def test_audit_events_must_not_be_empty(self):
        self.assertIn("audit_events", failures(oq.evaluate(synthetic_artifact(audit_events=[]))))

    def test_audit_event_must_be_an_object(self):
        artifact = synthetic_artifact()
        artifact["audit_events"].append("not-an-event")
        self.assertIn("audit_events", failures(oq.evaluate(artifact)))

    def test_audit_event_must_carry_every_required_field(self):
        artifact = synthetic_artifact()
        del artifact["audit_events"][0]["correlation_id"]
        self.assertIn("audit_events", failures(oq.evaluate(artifact)))

    def test_audit_event_must_belong_to_active_tenant(self):
        artifact = synthetic_artifact()
        artifact["audit_events"][0]["organization_id"] = "org-other"
        self.assertIn("audit_events", failures(oq.evaluate(artifact)))

    def test_audit_event_time_must_be_a_timezone_aware_instant(self):
        artifact = synthetic_artifact()
        artifact["audit_events"][0]["at"] = "2026-11-19T09:30:00"
        self.assertIn("audit_events", failures(oq.evaluate(artifact)))

    def test_audit_event_id_must_be_nonempty(self):
        artifact = synthetic_artifact()
        artifact["audit_events"][0]["id"] = "  "
        self.assertIn("audit_events", failures(oq.evaluate(artifact)))

    def test_duplicate_audit_event_ids_are_rejected(self):
        artifact = synthetic_artifact()
        artifact["audit_events"][1]["id"] = "audit-1"
        self.assertIn("audit_events", failures(oq.evaluate(artifact)))

    def test_audit_event_actor_and_action_must_be_nonempty(self):
        artifact = synthetic_artifact()
        artifact["audit_events"][0]["actor"] = ""
        artifact["audit_events"][1]["action"] = ""
        self.assertIn("audit_events", failures(oq.evaluate(artifact)))

    def test_unknown_action_audit_id_is_rejected(self):
        artifact = synthetic_artifact()
        artifact["queues"]["unsigned_encounters"][0]["actions"][0]["audit_id"] = "audit-999"
        self.assertIn("items.audit", failures(oq.evaluate(artifact)))

    def test_unhashable_audit_id_fails_closed(self):
        artifact = synthetic_artifact()
        artifact["queues"]["unsigned_encounters"][0]["actions"][0]["audit_id"] = ["audit-1"]
        self.assertIn("items.audit", failures(oq.evaluate(artifact)))


class ItemTest(unittest.TestCase):
    def _queue(self, artifact, name="unsigned_encounters"):
        return artifact["queues"][name]

    def test_item_must_be_an_object(self):
        artifact = synthetic_artifact()
        self._queue(artifact).append("bad")
        self.assertIn("items.structure", failures(oq.evaluate(artifact)))

    def test_item_id_must_be_nonempty(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["id"] = ""
        self.assertIn("items.structure", failures(oq.evaluate(artifact)))

    def test_item_queue_tag_must_match_its_queue(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["queue"] = "revoked_accounts"
        self.assertIn("items.structure", failures(oq.evaluate(artifact)))

    def test_item_created_at_must_be_an_instant(self):
        for value in ("yesterday", None, "2026-11-19T09:00:00"):
            with self.subTest(value=value):
                artifact = synthetic_artifact()
                self._queue(artifact)[0]["created_at"] = value
                self.assertIn("items.structure", failures(oq.evaluate(artifact)))

    def test_item_state_must_be_known(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["state"] = "zombie"
        self.assertIn("items.state", failures(oq.evaluate(artifact)))

    def test_unhashable_item_state_fails_closed(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["state"] = {"unexpected": "object"}
        report = oq.evaluate(artifact)
        self.assertIn("items.state", failures(report))

    def test_assigned_and_escalated_items_need_an_owner(self):
        for queue, state in (("unsigned_encounters", "assigned"),
                             ("unacknowledged_critical_findings", "escalated")):
            with self.subTest(queue=queue):
                artifact = synthetic_artifact()
                item = self._queue(artifact, queue)[0]
                del item["owner"]
                self.assertIn("items.owner", failures(oq.evaluate(artifact)))

    def test_owner_is_not_required_for_open_or_resolved_items(self):
        for queue in ("unreconciled_reports", "revoked_accounts"):
            with self.subTest(queue=queue):
                self.assertNotIn("items.owner", failures(oq.evaluate(synthetic_artifact())))

    def test_actions_must_be_a_list(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["actions"] = {"type": "assign"}
        self.assertIn("items.actions", failures(oq.evaluate(artifact)))

    def test_non_open_item_must_have_an_action(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["actions"] = []
        self.assertIn("items.actions", failures(oq.evaluate(artifact)))

    def test_open_item_may_have_no_actions(self):
        self.assertNotIn("items.actions",
                         failures(oq.evaluate(synthetic_artifact())))

    def test_action_must_be_an_object(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["actions"].append("bad")
        self.assertIn("items.actions", failures(oq.evaluate(artifact)))

    def test_action_type_must_be_known(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["actions"][0]["type"] = "delete"
        self.assertIn("items.actions", failures(oq.evaluate(artifact)))

    def test_action_time_must_be_an_instant(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["actions"][0]["at"] = "not-a-time"
        self.assertIn("items.actions", failures(oq.evaluate(artifact)))

    def test_retry_is_only_valid_on_retryable_queues(self):
        artifact = synthetic_artifact()
        item = self._queue(artifact)[0]
        item["actions"][0]["type"] = "retry"
        self.assertIn("items.actions", failures(oq.evaluate(artifact)))

    def test_retry_is_valid_on_retryable_queues(self):
        for queue in oq.RETRYABLE_QUEUES:
            with self.subTest(queue=queue):
                self.assertNotIn("items.actions",
                                 failures(oq.evaluate(synthetic_artifact())))

    def test_every_action_requires_a_reason(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["actions"][0]["reason"] = "  "
        self.assertIn("items.reason", failures(oq.evaluate(artifact)))

    def test_assigned_state_requires_an_assign_action(self):
        artifact = synthetic_artifact()
        self._queue(artifact)[0]["actions"][0]["type"] = "resolve"
        self.assertIn("items.state", failures(oq.evaluate(artifact)))

    def test_resolved_state_requires_a_resolve_action(self):
        artifact = synthetic_artifact()
        self._queue(artifact, "revoked_accounts")[0]["actions"][0]["type"] = "escalate"
        self.assertIn("items.state", failures(oq.evaluate(artifact)))

    def test_escalated_state_requires_an_escalate_action(self):
        artifact = synthetic_artifact()
        queue = self._queue(artifact, "unacknowledged_critical_findings")
        queue[0]["actions"][0]["type"] = "assign"
        self.assertIn("items.state", failures(oq.evaluate(artifact)))

    def test_escalated_owner_may_be_a_backup(self):
        artifact = synthetic_artifact()
        self._queue(artifact, "unacknowledged_critical_findings")[0]["owner"] = "op-1"
        self.assertNotIn("items.owner", failures(oq.evaluate(artifact)))


class ExportTest(unittest.TestCase):
    def test_exports_must_be_a_list(self):
        report = oq.evaluate(synthetic_artifact(exports={}))
        self.assertTrue({"exports.scope", "exports.expiry", "exports.audit",
                         "exports.actor"}.issubset(failures(report)))

    def test_an_empty_export_list_is_valid(self):
        self.assertEqual(oq.evaluate(synthetic_artifact(exports=[]))["verdict"], "pass")

    def test_export_must_be_an_object(self):
        artifact = synthetic_artifact()
        artifact["exports"].append("bad")
        report = failures(oq.evaluate(artifact))
        self.assertTrue({"exports.scope", "exports.expiry", "exports.audit",
                         "exports.actor"}.issubset(report))

    def test_scope_must_be_an_object(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["scope"] = ["unsigned_encounters"]
        self.assertIn("exports.scope", failures(oq.evaluate(artifact)))

    def test_scope_must_name_the_active_organization(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["scope"]["organization_id"] = "org-other"
        self.assertIn("exports.scope", failures(oq.evaluate(artifact)))

    def test_scope_must_list_at_least_one_queue(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["scope"]["queues"] = []
        self.assertIn("exports.scope", failures(oq.evaluate(artifact)))

    def test_scope_queues_must_be_known(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["scope"]["queues"] = ["not_a_queue"]
        self.assertIn("exports.scope", failures(oq.evaluate(artifact)))

    def test_scope_queues_must_be_strings(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["scope"]["queues"] = [["unsigned_encounters"]]
        self.assertIn("exports.scope", failures(oq.evaluate(artifact)))

    def test_export_times_must_be_instants(self):
        for field in ("created_at", "expires_at"):
            with self.subTest(field=field):
                artifact = synthetic_artifact()
                artifact["exports"][0][field] = "not-a-time"
                self.assertIn("exports.expiry", failures(oq.evaluate(artifact)))

    def test_export_ttl_must_be_positive(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["expires_at"] = artifact["exports"][0]["created_at"]
        self.assertIn("exports.expiry", failures(oq.evaluate(artifact)))

    def test_export_ttl_must_be_bounded(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["expires_at"] = "2026-11-21T10:00:01Z"
        self.assertIn("exports.expiry", failures(oq.evaluate(artifact)))

    def test_export_ttl_boundary_is_accepted(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["expires_at"] = "2026-11-20T10:00:00Z"
        self.assertNotIn("exports.expiry", failures(oq.evaluate(artifact)))

    def test_export_requires_an_audit_reference(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["audit_id"] = "not-an-audit"
        self.assertIn("exports.audit", failures(oq.evaluate(artifact)))

    def test_export_actor_must_be_authorized(self):
        artifact = synthetic_artifact()
        artifact["exports"][0]["actor"] = "clinician-1"
        self.assertIn("exports.actor", failures(oq.evaluate(artifact)))


class HelperTest(unittest.TestCase):
    def test_nonempty_string_rejects_non_strings_and_blank_values(self):
        for value in (None, 1, True, "", "  "):
            with self.subTest(value=value):
                self.assertFalse(oq._is_nonempty_string(value))
        self.assertTrue(oq._is_nonempty_string(" value "))

    def test_instant_parser_accepts_zulu_and_offset(self):
        self.assertIsInstance(oq._parse_instant("2026-11-19T09:30:00Z"), datetime)
        self.assertIsInstance(oq._parse_instant("2026-11-19T09:30:00+00:00"), datetime)

    def test_instant_parser_rejects_naive_invalid_and_non_string(self):
        for value in ("2026-11-19T09:30:00", "not-a-time", None, 9):
            with self.subTest(value=value):
                self.assertIsNone(oq._parse_instant(value))

    def test_ttl_returns_none_for_unparseable_times(self):
        self.assertIsNone(oq._ttl_seconds("not-a-time", "2026-11-20T10:00:00Z"))

    def test_ttl_returns_seconds_for_valid_times(self):
        self.assertEqual(
            oq._ttl_seconds("2026-11-19T10:00:00Z", "2026-11-19T10:00:30Z"), 30.0)


class AllBreachesTest(unittest.TestCase):
    def test_all_breaches_are_reported_together(self):
        report = oq.evaluate(synthetic_artifact(
            schema_version="2.0",
            organization_id="",
            operators=[],
            audit_events=[],
            exports=[],
        ))
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({
            "schema_version", "organization_id", "operators", "audit_events",
        }.issubset(failures(report)))


class ReportAndCliTest(unittest.TestCase):
    def test_format_report_shows_each_check_and_verdict(self):
        passed = oq.format_report(oq.evaluate(synthetic_artifact()))
        self.assertIn("[PASS]", passed)
        self.assertIn("items.reason", passed)
        self.assertIn("ADM-02 operational queues: PASS", passed)
        self.assertIn("evidence class: rehearsal (final: False)", passed)
        failing = oq.format_report(oq.evaluate(synthetic_artifact(kind="unverified")))
        self.assertIn("[FAIL]", failing)
        self.assertIn("evidence class: unknown (final: False)", failing)
        final = oq.format_report(oq.evaluate(synthetic_artifact(
            kind="operator_attested", source="site-attested-snapshot")))
        self.assertIn("final: True", final)

    def test_main_evaluates_a_conforming_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queues.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with contextlib.redirect_stdout(stdout):
                code = oq.main([str(path)])
            self.assertEqual(code, 0)
            self.assertIn("PASS", stdout.getvalue())

    def test_main_returns_one_for_a_failing_artifact(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queues.json"
            path.write_text(json.dumps(synthetic_artifact(kind="unverified")), encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
                code = oq.main([str(path)])
            self.assertEqual(code, 1)

    def test_main_reads_sys_argv_when_no_arguments_are_passed(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queues.json"
            path.write_text(json.dumps(synthetic_artifact()), encoding="utf-8")
            stdout = io.StringIO()
            with mock.patch.object(sys, "argv", ["ops_queues.py", str(path)]):
                with contextlib.redirect_stdout(stdout):
                    code = oq.main()
            self.assertEqual(code, 0)

    def test_main_rejects_bad_usage(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = oq.main([])
        self.assertEqual(code, 2)
        self.assertIn("usage", stderr.getvalue())

    def test_main_rejects_too_many_arguments(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = oq.main(["a", "b"])
        self.assertEqual(code, 2)

    def test_main_rejects_a_missing_file(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            code = oq.main(["/definitely/not/here/queues.json"])
        self.assertEqual(code, 2)
        self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_json(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queues.json"
            path.write_text("{not json", encoding="utf-8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = oq.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())

    def test_main_rejects_invalid_utf8(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "queues.json"
            path.write_bytes(b"\xff\xfe{not utf8")
            stderr = io.StringIO()
            with contextlib.redirect_stderr(stderr):
                code = oq.main([str(path)])
            self.assertEqual(code, 2)
            self.assertIn("cannot read", stderr.getvalue())


class FixtureIntegrityTest(unittest.TestCase):
    """The committed fixtures must exercise the evaluator as documented."""

    @classmethod
    def setUpClass(cls):
        base = MODULE_PATH.parent
        cls.conforming_path = base / "fixtures" / "synthetic_ops_queues_evidence.json"
        cls.violation_path = base / "fixtures" / "violation_ops_queues_evidence.json"
        cls.conforming = json.loads(cls.conforming_path.read_text(encoding="utf-8"))
        cls.violation = json.loads(cls.violation_path.read_text(encoding="utf-8"))

    def test_conforming_fixture_passes_and_is_a_rehearsal(self):
        report = oq.evaluate(self.conforming)
        self.assertEqual(report["verdict"], "pass")
        self.assertEqual(report["classification"], "rehearsal")
        self.assertFalse(report["final_evidence"])

    def test_violation_fixture_fails_with_named_breaches(self):
        report = oq.evaluate(self.violation)
        self.assertEqual(report["verdict"], "fail")
        self.assertTrue({"queues", "items.state", "items.reason",
                         "items.actor", "exports.expiry"}.issubset(failures(report)))

    def test_fixtures_cover_all_required_queues(self):
        self.assertEqual(set(self.conforming["queues"]), set(oq.REQUIRED_QUEUES))

    def test_fixture_text_contains_no_phi_shaped_values(self):
        import re
        text = (self.conforming_path.read_text(encoding="utf-8")
                + self.violation_path.read_text(encoding="utf-8"))
        self.assertIsNone(re.search(r"[\w.+-]+@[\w-]+\.[\w.]+", text))
        self.assertIsNone(re.search(r"\b\d{3}-\d{2}-\d{4}\b", text))


if __name__ == "__main__":
    unittest.main()
