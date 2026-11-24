"""Evaluate an OPS-07 tenant lifecycle evidence artifact.

The specification fixes the clauses (PROJECT_V1_SPEC.md, OPS-07): a tenant
export must carry a manifest, schema version, checksums, authorized documents,
provenance and audit history; retention jobs must respect legal holds, track
deletion of active data and expire backups only with approval; representative
record requests, exports, offboarding and disputed access must be tested. This
module turns those clauses into a fail-closed check: an artifact that omits a
clause, misstates a checksum or deletes held data is reported as a failure
rather than silently accepted.

Two honesty rules are enforced beyond the fields:

* A synthetic rehearsal proves the check works; it is never final evidence.
* A ``measured_operation`` artifact that names a synthetic source is a conflict.

The evaluator judges an evidence artifact, not a running system. It can prove
that supplied, synthetic facts meet OPS-07 and that the declared envelope is
complete; it cannot prove an export, deletion or restore actually happened.
Real operational acceptance is a separate, human-gated step.

Usage: ``python3 -I infra/tenant-lifecycle/tenant_lifecycle_evidence.py <artifact.json>``
Exit codes: 0 = artifact passes the OPS-07 checks, 1 = it fails a check,
2 = unreadable input.
"""
import json
import re
import sys
from pathlib import Path

SCHEMA_VERSION = "1.0"
KINDS = ("synthetic_rehearsal", "measured_operation")
CHECKSUM = re.compile(r"\A[0-9a-f]{64}\Z")
EMAIL = re.compile(r"[\w.+-]+@[\w-]+\.[\w.]+")
SSN = re.compile(r"(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)")
REQUIRED_MANIFEST_STRINGS = ("schema_version", "policy_version",
                             "organization", "exported_at")
DOCUMENT_AUTHORIZATIONS = ("release_policy", "record_access_request",
                           "approved_instruction")
OFFBOARDING_STEPS = ("suspend_access", "export", "delete_active_data",
                     "expire_backups", "audit")
SCENARIOS = ("record_request", "export", "offboarding", "disputed_access")
DISPUTED_OUTCOMES = ("suspended", "restored_after_verification", "closed")


def _positive_int(value):
    """Return true for an integer greater than zero, excluding booleans."""
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _nonempty_str(value):
    """Return true for a canonical, non-blank string.

    Surrounding whitespace is rejected rather than trimmed: an identifier like
    ``" rec-1 "`` is not a valid scope and must not slip past scope matching.
    """
    return (isinstance(value, str) and bool(value.strip())
            and value == value.strip())


def _valid_checksum(value):
    """Return true for a lowercase sha256 hex digest."""
    return isinstance(value, str) and CHECKSUM.fullmatch(value) is not None


def _record(checks, name, passed, detail):
    """Append one named check result to the report."""
    checks.append({"name": name, "passed": bool(passed), "detail": detail})


def _verdict(checks, kind):
    """Build the report from the checks and the declared evidence class."""
    failed = [check["name"] for check in checks if not check["passed"]]
    # A non-string kind is unhashable or unexpected: never let it raise here.
    classification = ({
        "synthetic_rehearsal": "rehearsal",
        "measured_operation": "measured-operation",
    }.get(kind, "unknown") if isinstance(kind, str) else "unknown")
    return {
        "verdict": "fail" if failed else "pass",
        "classification": classification,
        # A rehearsal proves the check works; it is never final evidence.
        "final_evidence": classification == "measured-operation" and not failed,
        "checks": checks,
        "failed": failed,
    }


def _valid_provenance(record):
    """Return true when a record names its source system, source id and version."""
    provenance = record.get("provenance") if isinstance(record, dict) else None
    return (isinstance(provenance, dict)
            and _nonempty_str(provenance.get("source_system"))
            and _nonempty_str(provenance.get("source_id"))
            and _positive_int(provenance.get("version")))


def _valid_documents(record, policy_version):
    """Return true when every attached document is checksummed and policy-bound.

    Document ids must also be unique within the record: a duplicate id with
    different bytes would leave the manifest ambiguous about which authorized
    copy was exported. The uniqueness fold runs only after every entry has been
    structurally validated, so an unhashable id can never reach ``set``.
    """
    documents = record.get("documents") if isinstance(record, dict) else None
    if not (isinstance(documents, list) and bool(documents)):
        return False
    if not all(isinstance(document, dict)
               and _nonempty_str(document.get("id"))
               and _valid_checksum(document.get("checksum"))
               and document.get("policy_version") == policy_version
               for document in documents):
        return False
    ids = [document["id"] for document in documents]
    return len(ids) == len(set(ids))


def _valid_authorizations(record):
    """Return true when every document names a policy-allowed authorization."""
    documents = record.get("documents") if isinstance(record, dict) else None
    return (isinstance(documents, list) and bool(documents)
            and all(isinstance(document, dict)
                    and document.get("authorization") in DOCUMENT_AUTHORIZATIONS
                    for document in documents))


def _valid_audit(record):
    """Return true for a well-formed audit history with strictly increasing seq.

    Audit is append-only: a duplicate or out-of-order sequence defeats tamper
    evidence, so the sequence numbers must be strictly increasing. Gaps are not
    checked here — an append-only log may legitimately have no row for every
    integer — so this is ordering, not contiguity, evidence.
    """
    audit = record.get("audit") if isinstance(record, dict) else None
    if not (isinstance(audit, list) and bool(audit)):
        return False
    if not all(isinstance(entry, dict)
               and _positive_int(entry.get("seq"))
               and _nonempty_str(entry.get("action"))
               and _nonempty_str(entry.get("outcome"))
               for entry in audit):
        return False
    seqs = [entry["seq"] for entry in audit]
    return all(left < right for left, right in zip(seqs, seqs[1:]))


def _check_manifest(manifest, org, checks):
    """Check the export manifest envelope and its declared record count."""
    for field in REQUIRED_MANIFEST_STRINGS:
        _record(checks, "export.manifest." + field,
                _nonempty_str(manifest.get(field)),
                "must be a non-blank string")
    _record(checks, "export.manifest.organization_scope",
            _nonempty_str(org) and manifest.get("organization") == org,
            f"organization={manifest.get('organization')!r}; expected {org!r}")
    count = manifest.get("record_count")
    _record(checks, "export.manifest.record_count", _positive_int(count),
            "record_count must be a positive integer")


def _check_export(export, org, checks):
    """Check the export manifest, every record and its policy-bound documents."""
    if not isinstance(export, dict):
        _record(checks, "export", False, "export must be a JSON object")
        return
    manifest = export.get("manifest")
    if not isinstance(manifest, dict):
        _record(checks, "export.manifest", False, "manifest must be a JSON object")
        manifest = {}
    _check_manifest(manifest, org, checks)

    records = export.get("records")
    record_list = records if isinstance(records, list) else []
    _record(checks, "export.records", isinstance(records, list) and bool(records),
            "records must be a non-empty list")
    count = manifest.get("record_count")
    _record(checks, "export.record_count",
            _positive_int(count) and count == len(record_list),
            f"declared {count!r}, found {len(record_list)}")
    _record(checks, "export.record_ids",
            bool(record_list) and all(isinstance(record, dict)
                                      and _nonempty_str(record.get("id"))
                                      for record in record_list),
            "every record needs a non-blank id")
    ids = [record.get("id") for record in record_list if isinstance(record, dict)]
    _record(checks, "export.record_ids_unique",
            bool(record_list) and len(ids) == len(record_list)
            and all(map(_nonempty_str, ids)) and len(ids) == len(set(ids)),
            "exported record ids must be unique")
    _record(checks, "export.record_checksums",
            bool(record_list) and all(isinstance(record, dict)
                                      and _valid_checksum(record.get("checksum"))
                                      for record in record_list),
            "every record needs a lowercase sha256 checksum")
    _record(checks, "export.record_provenance",
            bool(record_list) and all(_valid_provenance(record) for record in record_list),
            "every record needs source system, source id and version")
    policy_version = manifest.get("policy_version")
    _record(checks, "export.documents",
            bool(record_list) and all(_valid_documents(record, policy_version)
                                      for record in record_list),
            "every record needs authorized, checksummed, policy-versioned documents")
    _record(checks, "export.document_authorizations",
            bool(record_list) and all(_valid_authorizations(record)
                                      for record in record_list),
            "every document needs a policy-allowed authorization")
    _record(checks, "export.audit_history",
            bool(record_list) and all(_valid_audit(record) for record in record_list),
            "every record needs a well-formed audit history")


def _valid_hold_lift(hold):
    """Return true when an inactive hold carries an authorized, reasoned lift.

    A hold cannot simply be switched off: lifting it requires a named actor,
    time and reason, so the artifact records who released held evidence.
    """
    return (isinstance(hold, dict)
            and hold.get("active") is False
            and _nonempty_str(hold.get("lifted_by"))
            and _nonempty_str(hold.get("lifted_at"))
            and _nonempty_str(hold.get("lift_reason")))


def _valid_hold(hold):
    """Return true for an identified, reasoned hold with an actor and a valid lift."""
    return (isinstance(hold, dict)
            and _nonempty_str(hold.get("id"))
            and _nonempty_str(hold.get("reason"))
            and _nonempty_str(hold.get("scope"))
            and _nonempty_str(hold.get("placed_by"))
            and isinstance(hold.get("active"), bool)
            and (hold.get("active") is True or _valid_hold_lift(hold)))


def _valid_deletion(deletion):
    """Return true for a tracked deletion with a reason and an approver."""
    return (isinstance(deletion, dict)
            and _nonempty_str(deletion.get("record_id"))
            and _nonempty_str(deletion.get("reason"))
            and _nonempty_str(deletion.get("approved_by"))
            and isinstance(deletion.get("active_data"), bool))


def _approved_active_deletion(deletion):
    """Return true when an entry records an approved deletion of active data."""
    return (isinstance(deletion, dict)
            and deletion.get("active_data") is True
            and _nonempty_str(deletion.get("reason"))
            and _nonempty_str(deletion.get("approved_by")))


def _valid_backup_expiration(backup):
    """Return true only for an explicitly approved backup expiration."""
    return (isinstance(backup, dict)
            and _nonempty_str(backup.get("backup_set"))
            and _nonempty_str(backup.get("scope"))
            and _nonempty_str(backup.get("approved_by"))
            and backup.get("approved") is True)


def _scope_covers(hold_scope, target):
    """Return true when an active hold's scope covers a deletion or backup target.

    A ``*`` scope covers everything; a scoped hold covers an exact id or a
    descendant separated by ``/``. Blank or non-string values match nothing, so
    callers still fail closed through their own entry checks.
    """
    # Matching strips whitespace even though entry validation rejects padding:
    # a padded identifier must fail structurally AND stay under its hold.
    if not isinstance(hold_scope, str) or not isinstance(target, str):
        return False
    scope, target_id = hold_scope.strip(), target.strip()
    if not scope or not target_id:
        return False
    return (scope == "*" or scope == target_id
            or target_id.startswith(scope.rstrip("/") + "/"))


def _covered(target, held):
    """Return true when any effective hold scope overlaps the target.

    Coverage is bidirectional: a hold on a descendant still blocks expiring an
    ancestor backup or deleting a parent scope, because those actions can
    destroy the held data even though the scopes are not identical.
    """
    return any(_scope_covers(scope, target) or _scope_covers(target, scope)
               for scope in held)


def _check_retention(retention, checks):
    """Check holds, deletion tracking and approved backup expiration."""
    if not isinstance(retention, dict):
        _record(checks, "retention", False, "retention must be a JSON object")
        return
    _record(checks, "retention.policy_version",
            _nonempty_str(retention.get("policy_version")),
            "retention policy version must be a non-blank string")

    # A malformed or missing ledger is never read as "nothing happened".
    holds = retention.get("legal_holds")
    holds_ok = isinstance(holds, list)
    _record(checks, "retention.legal_holds", holds_ok, "legal_holds must be a list")
    hold_list = holds if holds_ok else []
    _record(checks, "retention.legal_hold_entries",
            holds_ok and all(_valid_hold(hold) for hold in hold_list),
            "every legal hold needs id, reason, scope, actor and active flag")
    # An inactive hold without a valid, authorized lift still covers its scope:
    # a crafted artifact must not release held data by flipping "active" to false.
    held = {hold.get("scope") for hold in hold_list
            if isinstance(hold, dict)
            and (hold.get("active") is True or not _valid_hold_lift(hold))
            and _nonempty_str(hold.get("scope"))}

    deletions = retention.get("deletions")
    deletions_ok = isinstance(deletions, list)
    _record(checks, "retention.deletions", deletions_ok, "deletions must be a list")
    deletion_list = deletions if deletions_ok else []
    _record(checks, "retention.deletion_entries",
            deletions_ok and all(_valid_deletion(deletion) for deletion in deletion_list),
            "every deletion needs record id, reason, approver and active-data flag")
    _record(checks, "retention.legal_hold_respected",
            holds_ok and deletions_ok
            and all(isinstance(deletion, dict)
                    and not _covered(deletion.get("record_id"), held)
                    for deletion in deletion_list),
            "no record under an active legal hold may be deleted")
    _record(checks, "retention.deletion_approval",
            deletions_ok and all(_approved_active_deletion(deletion)
                                 for deletion in deletion_list),
            "every tracked deletion must be an approved deletion of active data")

    backups = retention.get("backup_expirations")
    backups_ok = isinstance(backups, list)
    _record(checks, "retention.backup_expirations", backups_ok,
            "backup_expirations must be a list")
    backup_list = backups if backups_ok else []
    _record(checks, "retention.backup_expiration_entries",
            backups_ok and all(_valid_backup_expiration(backup) for backup in backup_list),
            "every backup expiration needs scope, approver and approved flag")
    _record(checks, "retention.backup_hold_respected",
            holds_ok and backups_ok
            and all(isinstance(backup, dict)
                    and not _covered(backup.get("backup_set"), held)
                    and not _covered(backup.get("scope"), held)
                    for backup in backup_list),
            "no backup set or scope under an effective legal hold may be expired")


def _check_offboarding(offboarding, org, checks):
    """Check that tenant offboarding is authorized, complete and audited."""
    if not isinstance(offboarding, dict):
        _record(checks, "offboarding", False, "offboarding must be a JSON object")
        return
    _record(checks, "offboarding.authorized_by",
            _nonempty_str(offboarding.get("authorized_by")),
            "offboarding needs a named authorizer")
    _record(checks, "offboarding.audit_reference",
            _nonempty_str(offboarding.get("audit_reference")),
            "offboarding needs an audit reference")
    _record(checks, "offboarding.organization",
            _nonempty_str(org) and offboarding.get("organization") == org,
            f"organization={offboarding.get('organization')!r}; expected {org!r}")
    steps = offboarding.get("steps")
    all_strings = isinstance(steps, list) and all(isinstance(step, str) for step in steps)
    present = all_strings and set(OFFBOARDING_STEPS).issubset(steps)
    ordered = present and [step for step in steps if step in OFFBOARDING_STEPS] == list(
        OFFBOARDING_STEPS)
    _record(checks, "offboarding.steps", ordered,
            "offboarding must list every governed step in the required order")


def _check_scenarios(scenarios, checks):
    """Check that all four representative OPS-07 scenarios were exercised."""
    if not isinstance(scenarios, list):
        _record(checks, "scenarios", False, "scenarios must be a list")
        _record(checks, "scenarios.no_duplicates", False, "scenarios must be a list")
        return
    all_strings = all(isinstance(scenario, str) for scenario in scenarios)
    _record(checks, "scenarios",
            all_strings and set(SCENARIOS).issubset(scenarios),
            "record_request, export, offboarding and disputed_access are required")
    _record(checks, "scenarios.no_duplicates",
            all_strings and len(set(scenarios)) == len(scenarios),
            "scenarios must be unique strings")


def _check_disputed_access(disputed, checks):
    """Check that a disputed link is suspended and re-reviewed before restoration."""
    if not isinstance(disputed, dict):
        _record(checks, "disputed_access", False,
                "disputed_access must be a JSON object")
        return
    _record(checks, "disputed_access.link", _nonempty_str(disputed.get("link")),
            "a disputed access must name the affected link")
    _record(checks, "disputed_access.suspends",
            _nonempty_str(disputed.get("suspended_at"))
            and _nonempty_str(disputed.get("suspended_by")),
            "access must be suspended with a timestamp and actor")
    _record(checks, "disputed_access.rereview_required",
            _nonempty_str(disputed.get("rereviewed_by"))
            and _nonempty_str(disputed.get("rereviewed_at")),
            "a named reviewer and review time are required")
    outcome = disputed.get("outcome")
    _record(checks, "disputed_access.outcome",
            isinstance(outcome, str) and outcome in DISPUTED_OUTCOMES,
            "outcome must be " + " or ".join(DISPUTED_OUTCOMES))
    _record(checks, "disputed_access.reverification",
            outcome != "restored_after_verification"
            or disputed.get("identity_reverified") is True,
            "restoring access requires recorded identity re-verification")


def _check_phi(artifact, checks):
    """Fail closed when a fixture carries a real-looking identifier."""
    try:
        text = json.dumps(artifact, sort_keys=True, default=str)
    except (TypeError, ValueError):
        _record(checks, "phi.free", False, "artifact is not serializable for scanning")
        return
    _record(checks, "phi.free",
            EMAIL.search(text) is None and SSN.search(text) is None,
            "no email- or SSN-shaped values in a synthetic artifact")


def evaluate(artifact):
    """Return a fail-closed OPS-07 verdict for one evidence artifact.

    Content problems are recorded as failed checks rather than raised, so a
    reviewer sees every breach in one report.
    """
    checks = []
    if not isinstance(artifact, dict):
        _record(checks, "artifact", False, "artifact must be a JSON object")
        return _verdict(checks, None)
    org = artifact.get("organization")
    _record(checks, "organization", _nonempty_str(org),
            "organization must be a non-blank string")
    kind = artifact.get("kind")
    source = artifact.get("source")
    _record(checks, "schema_version",
            artifact.get("schema_version") == SCHEMA_VERSION,
            f"expected {SCHEMA_VERSION}")
    _record(checks, "kind", kind in KINDS,
            "kind must be " + " or ".join(KINDS))
    # An artifact without a named source cannot claim a measured run, and a
    # measured run cannot name a synthetic source. This is a best-effort
    # declaration check, not independent proof that the run occurred.
    _record(checks, "evidence.source", _nonempty_str(source),
            "every evidence artifact must name a non-blank source")
    _record(checks, "evidence_conflict",
            not (kind == "measured_operation"
                 and "synthetic" in str(source).lower()),
            "a measured operation cannot name a synthetic source")
    _check_export(artifact.get("export"), org if _nonempty_str(org) else None, checks)
    _check_retention(artifact.get("retention"), checks)
    _check_offboarding(artifact.get("offboarding"),
                       org if _nonempty_str(org) else None, checks)
    _check_disputed_access(artifact.get("disputed_access"), checks)
    _check_scenarios(artifact.get("scenarios"), checks)
    _check_phi(artifact, checks)
    return _verdict(checks, kind)


def format_report(report):
    """Render a verdict, its evidence class and every named check as text."""
    lines = [f"OPS-07 lifecycle evidence: {report['verdict'].upper()}"]
    lines.append(f"  evidence class: {report['classification']}"
                 f" (final: {report['final_evidence']})")
    for check in report["checks"]:
        status = "PASS" if check["passed"] else "FAIL"
        lines.append(f"  [{status}] {check['name']}: {check['detail']}")
    return "\n".join(lines)


def main(argv=None):
    """Evaluate a JSON artifact path from the command line."""
    arguments = sys.argv[1:] if argv is None else list(argv)
    if len(arguments) != 1:
        print("usage: tenant_lifecycle_evidence.py <artifact.json>", file=sys.stderr)
        return 2
    try:
        artifact = json.loads(Path(arguments[0]).read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as error:
        print(f"cannot read evidence artifact: {error}", file=sys.stderr)
        return 2
    report = evaluate(artifact)
    print(format_report(report))
    return 0 if report["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())
