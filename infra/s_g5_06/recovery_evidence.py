"""Evaluate an OPS-04/OPS-05 recovery and operations evidence artifact.

The specification fixes the recovery targets, the restore scope and the set of
operational controls. This module turns those clauses into a fail-closed check:
an artifact that omits a required scope, misses a target or misstates an
operational control is reported as a failure rather than silently accepted.

Two honesty rules are enforced beyond the numbers:

* A passing synthetic artifact is a rehearsal of the check, never final pilot
  evidence; ``final_evidence`` is true only for a conducted drill artifact that
  is not itself labelled synthetic.
* The evaluator judges an evidence artifact, not a running system. It can prove
  that supplied facts meet OPS-04/OPS-05 and that the declared envelope is
  complete; it cannot prove a restore really happened or that a human owner is
  actually on call. Real pilot acceptance is a separate, human-gated step.

Usage: ``python3 -I infra/s_g5_06/recovery_evidence.py <artifact.json>``
Exit codes: 0 = artifact passes the OPS-04/OPS-05 checks, 1 = it fails a check,
2 = unreadable input.
"""
import json
import sys
from pathlib import Path

SCHEMA_VERSION = "1.0"
KINDS = ("synthetic_rehearsal", "conducted_drill")
RPO_MAX_MINUTES = 15
RTO_MAX_HOURS = 4
# A conducted drill cannot be attested from an artifact whose own source is
# labelled as a synthetic fixture. The enum (kind) is the authority for the
# evidence class; this denylist only prevents a contradictory label.
SYNTHETIC_MARKERS = ("synthetic", "fixture", "seed", "mock")

# The scope OPS-04 requires a restore to cover. "documents and their
# associations" is represented as two distinct, independently checked members.
REQUIRED_SCOPE = ("core_chart", "order_data", "documents", "document_associations")
REQUIRED_RESTORE_TARGETS = ("database", "objects", "idp_dependencies")
REQUIRED_RECONCILIATION = (
    "hashes", "source_versions", "in_flight_deliveries", "audit_coverage",
)
REQUIRED_MONITORING_SIGNALS = (
    "error_rates", "saturation", "queue_age", "partner_delivery",
    "unmatched_reports", "critical_acknowledgment_time", "missing_coverage",
    "unassigned_patient_messages", "response_breaches",
    "failed_instruction_notifications", "overdue_instruction_acknowledgments",
    "rendering_failures", "backup_status",
)
REQUIRED_RUNBOOKS = (
    "paper_manual_capture", "patient_identification",
    "critical_contacts", "supervised_back_entry",
)
REQUIRED_VERIFICATION_FLAGS = ("before_pilot", "quarterly_cadence")
REQUIRED_OWNERSHIP_ROLES = (
    "operational_on_call_owner", "site_clinical_coverage_owner",
)
# An owner name that is a placeholder records no accountable person.
OWNER_PLACEHOLDERS = ("tbd", "n/a", "na", "unknown", "none", "-")


def _is_number(value):
    """Return true for a real numeric value, excluding booleans."""
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def _is_nonempty_string(value):
    """Return true for a non-blank string (owner names must be meaningful)."""
    return isinstance(value, str) and bool(value.strip())


def _record(checks, name, passed, detail):
    """Append one named check result to the report."""
    checks.append({"name": name, "passed": bool(passed), "detail": detail})


def _verdict(checks, kind):
    """Build the report from the checks and the declared evidence class."""
    failed = [check["name"] for check in checks if not check["passed"]]
    # A wrong-typed kind is already a failed check; never let it raise here.
    lookup = kind if isinstance(kind, str) else None
    classification = {
        "synthetic_rehearsal": "rehearsal",
        "conducted_drill": "conducted-drill",
    }.get(lookup, "unknown")
    return {
        "verdict": "fail" if failed else "pass",
        "classification": classification,
        # A rehearsal proves the check works; it is never final pilot evidence.
        "final_evidence": classification == "conducted-drill" and not failed,
        "checks": checks,
        "failed": failed,
    }


def _check_required_members(value, required, checks, name):
    """Check that a list names every required member exactly once.

    Extra members are allowed (recording more scope or signals cannot hide a
    required one), but a missing or duplicated required member fails closed.
    """
    if not isinstance(value, list):
        _record(checks, name, False, "must be a JSON array")
        return
    missing = [item for item in required if item not in value]
    duplicates = [item for item in required if value.count(item) > 1]
    passed = not missing and not duplicates
    detail = ("all required members present exactly once" if passed
              else "missing: " + ", ".join(missing) + "; duplicated: "
              + ", ".join(duplicates))
    _record(checks, name, passed, detail)


def _check_targets(targets, checks):
    """Check the RPO and RTO literals from OPS-04.

    The field names pin the unit (minutes for RPO, hours for RTO), so a value
    cannot be silently reinterpreted in another unit. RPO = 0 is allowed: a
    zero-data-loss design satisfies "≤ 15 minutes". RTO must be positive.
    """
    if not isinstance(targets, dict):
        _record(checks, "targets", False, "targets must be a JSON object")
        return
    for key, limit, unit, allow_zero in (
            ("rpo_minutes", RPO_MAX_MINUTES, "minutes", True),
            ("rto_hours", RTO_MAX_HOURS, "hours", False)):
        value = targets.get(key)
        name = "targets." + key
        if _is_number(value) and (value >= 0 if allow_zero else value > 0):
            _record(checks, name, value <= limit,
                    f"{value} {unit}; target <= {limit} {unit}")
        else:
            _record(checks, name, False,
                    f"must be a {'non-negative' if allow_zero else 'positive'}"
                    f" number of {unit}")


def _check_restore(restore, checks):
    """Check the restore targets and the clean-environment prerequisite."""
    if not isinstance(restore, dict):
        _record(checks, "restore", False, "restore must be a JSON object")
        return
    _check_required_members(restore.get("targets"), REQUIRED_RESTORE_TARGETS,
                            checks, "restore.targets")
    clean = restore.get("clean_environment")
    _record(checks, "restore.clean_environment", clean is True,
            "restore must run into a clean, isolated environment")


def _check_reconciliation(reconciliation, checks):
    """Check that every reconciliation gate closed before writes reopened."""
    if not isinstance(reconciliation, dict):
        _record(checks, "reconciliation", False,
                "reconciliation must be a JSON object")
        return
    for gate in REQUIRED_RECONCILIATION:
        value = reconciliation.get(gate)
        _record(checks, "reconciliation." + gate, value is True,
                f"{gate} must be reconciled before writes reopen")
    ordered = reconciliation.get("completed_before_writes_reopened")
    _record(checks, "reconciliation.completed_before_writes_reopened",
            ordered is True,
            "reconciliation must complete before writes are reopened")


def _check_verification(verification, checks):
    """Check the OPS-04 verification cadence (before pilot and quarterly)."""
    if not isinstance(verification, dict):
        _record(checks, "verification", False,
                "verification must be a JSON object")
        return
    for flag in REQUIRED_VERIFICATION_FLAGS:
        value = verification.get(flag)
        _record(checks, "verification." + flag, value is True,
                f"{flag} verification must be recorded")


def _check_monitoring(monitoring, checks):
    """Check that all OPS-05 monitoring signals are declared."""
    if not isinstance(monitoring, dict):
        _record(checks, "monitoring", False, "monitoring must be a JSON object")
        return
    _check_required_members(monitoring.get("signals"),
                            REQUIRED_MONITORING_SIGNALS,
                            checks, "monitoring.signals")


def _check_ownership(ownership, checks):
    """Check that both OPS-05 owner roles are named."""
    if not isinstance(ownership, dict):
        _record(checks, "ownership", False, "ownership must be a JSON object")
        return
    for key in REQUIRED_OWNERSHIP_ROLES:
        value = ownership.get(key)
        named = (_is_nonempty_string(value)
                 and value.strip().lower() not in OWNER_PLACEHOLDERS)
        _record(checks, "ownership." + key, named,
                f"{key} must name an owner, not a blank or placeholder")


def _check_duplicate_prevention(value, checks):
    """Check the OPS-05 rule that recovery must prevent duplicates."""
    _record(checks, "duplicate_prevention", value is True,
            "recovery must declare duplicate prevention")


def evaluate(artifact):
    """Return a fail-closed OPS-04/OPS-05 verdict for one evidence artifact.

    Content problems are recorded as failed checks rather than raised, so a
    reviewer sees every breach in one report.
    """
    checks = []
    if not isinstance(artifact, dict):
        _record(checks, "artifact", False, "artifact must be a JSON object")
        return _verdict(checks, None)
    kind = artifact.get("kind")
    source = artifact.get("source")
    _record(checks, "schema_version",
            artifact.get("schema_version") == SCHEMA_VERSION,
            f"expected {SCHEMA_VERSION}")
    _record(checks, "kind", kind in KINDS,
            "kind must be " + " or ".join(KINDS))
    _record(checks, "source", _is_nonempty_string(source),
            "source must be a non-blank drill or fixture identifier")
    if (kind == "conducted_drill" and _is_nonempty_string(source)
            and any(marker in source.lower() for marker in SYNTHETIC_MARKERS)):
        _record(checks, "evidence_conflict", False,
                "a conducted drill artifact cannot name a synthetic source")
    _check_targets(artifact.get("targets"), checks)
    _check_required_members(artifact.get("scope"), REQUIRED_SCOPE,
                            checks, "scope")
    _check_restore(artifact.get("restore"), checks)
    _check_reconciliation(artifact.get("reconciliation"), checks)
    _check_verification(artifact.get("verification"), checks)
    _check_monitoring(artifact.get("monitoring"), checks)
    _check_ownership(artifact.get("ownership"), checks)
    _check_required_members(artifact.get("runbooks"), REQUIRED_RUNBOOKS,
                            checks, "runbooks")
    _check_duplicate_prevention(artifact.get("duplicate_prevention"), checks)
    return _verdict(checks, kind)


def format_report(report):
    """Render a verdict, its evidence class and every named check as text."""
    lines = [f"OPS-04/OPS-05 recovery evidence: {report['verdict'].upper()}"]
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
        print("usage: recovery_evidence.py <artifact.json>", file=sys.stderr)
        return 2
    try:
        artifact = json.loads(Path(arguments[0]).read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as error:
        print(f"cannot read evidence artifact: {error}", file=sys.stderr)
        return 2
    report = evaluate(artifact)
    print(format_report(report))
    return 0 if report["verdict"] == "pass" else 1


if __name__ == "__main__":
    sys.exit(main())  #pragma NO COVER - CLI entry point, exercised via main() in tests
