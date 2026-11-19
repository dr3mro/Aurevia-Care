"""Evaluate an ADM-02 operational-queues evidence artifact.

The specification fixes the required queues, the permitted operator actions, the
authorization and reason requirements, and the export envelope. This module turns
those clauses into a fail-closed check: an artifact that omits a queue, lets an
inactive operator act, records an action without a reason or exports without an
explicit scope, bounded expiry and audit is reported as a failure rather than
silently accepted.

Two honesty rules are enforced beyond the fields:

* A synthetic rehearsal proves the check works; it is never final evidence.
* An ``operator_attested`` artifact cannot name a synthetic source.

The evaluator judges an artifact, not a running system. It can prove that a
supplied snapshot meets ADM-02 and that its declared envelope is complete; it
cannot prove an operator really acted or that a queue really existed at a site.
Human acceptance of live operational evidence is a separate G5 gate.

Usage: ``python3 -I infra/s_g5_11/ops_queues.py <artifact.json>``
Exit codes: 0 = artifact passes the ADM-02 checks, 1 = it fails a check,
2 = unreadable input.
"""
import json
import sys
from datetime import datetime
from pathlib import Path

SCHEMA_VERSION = "1.0"
KINDS = ("synthetic_rehearsal", "operator_attested")

# ADM-02 names these seven operational queues explicitly. The vocabulary is
# exact: adding or omitting a queue changes the declared operational contract.
REQUIRED_QUEUES = (
    "unsigned_encounters",
    "unreconciled_reports",
    "unacknowledged_critical_findings",
    "rejected_exchanges",
    "document_failures",
    "missing_coverage",
    "revoked_accounts",
)
ITEM_STATES = ("open", "assigned", "resolved", "escalated")
ACTION_TYPES = ("assign", "resolve", "retry", "escalate")
AUTHORIZED_OPERATOR_ROLES = ("tenant_admin", "privacy_auditor", "operator")
RETRYABLE_QUEUES = ("rejected_exchanges", "document_failures")

# A state that is no longer open must be justified by its matching action.
REQUIRED_ACTION = {
    "assigned": "assign",
    "resolved": "resolve",
    "escalated": "escalate",
}
MAX_EXPORT_TTL_SECONDS = 86400
# The audit verb that must corroborate each declared operator action. A bare
# audit-id reference is not enough: an unrelated event could stand in for it.
ACTION_AUDIT_NAMES = {
    "assign": "ASSIGN",
    "resolve": "RESOLVE",
    "retry": "RETRY",
    "escalate": "ESCALATE",
}
EXPORT_AUDIT_NAME = "EXPORT"
AUDIT_FIELDS = ("id", "actor", "action", "resource_id", "at", "outcome",
                "correlation_id", "organization_id")
AUDIT_TEXT_FIELDS = ("id", "actor", "action", "resource_id", "outcome",
                     "correlation_id")


def _is_nonempty_string(value):
    """Return true for a non-blank string, excluding booleans and numbers."""
    return isinstance(value, str) and bool(value.strip())


def _parse_instant(value):
    """Parse a timezone-aware ISO instant, or return None when invalid.

    Naive dates are rejected: the specification requires UTC instants with
    time-zone context, so a local-time string cannot be scored as an instant.
    """
    if not isinstance(value, str) or not value:
        return None
    try:
        text = value[:-1] + "+00:00" if value.endswith("Z") else value
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    return parsed if parsed.tzinfo is not None else None


def _is_instant(value):
    """Return true when the value is a timezone-aware ISO instant."""
    return _parse_instant(value) is not None


def _same_instant(first, second):
    """Compare two instants by value, not by their ISO string spelling."""
    left = _parse_instant(first)
    right = _parse_instant(second)
    return left is not None and right is not None and left == right


def _audit_corroborates(event, actor, action_name, at, resource_id):
    """Check that an audit event actually describes a cited action.

    ``resource_id`` is optional because an export envelope has no separate
    identifier; its audit event is matched on actor, verb and time alone.
    """
    return (isinstance(event, dict)
            and event.get("actor") == actor
            and event.get("action") == action_name
            and (resource_id is None or event.get("resource_id") == resource_id)
            and _same_instant(event.get("at"), at))


def _ttl_seconds(created_at, expires_at):
    """Return an export's lifetime in seconds, or None for invalid times."""
    start = _parse_instant(created_at)
    end = _parse_instant(expires_at)
    if start is None or end is None:
        return None
    return (end - start).total_seconds()


def _record(checks, name, passed, detail):
    """Append one named check result to the report."""
    checks.append({"name": name, "passed": bool(passed), "detail": detail})


def _verdict(checks, kind):
    """Build the report from the checks and the declared evidence class."""
    failed = [check["name"] for check in checks if not check["passed"]]
    classification = {
        "synthetic_rehearsal": "rehearsal",
        "operator_attested": "operator-attested",
    }.get(kind if isinstance(kind, str) else None, "unknown")
    return {
        "verdict": "fail" if failed else "pass",
        "classification": classification,
        # A rehearsal proves the check works; it is never final evidence.
        "final_evidence": classification == "operator-attested" and not failed,
        "checks": checks,
        "failed": failed,
    }


def _check_queues(queues, checks):
    """Check the seven-queue vocabulary and that each queue is a list."""
    if not isinstance(queues, dict):
        _record(checks, "queues", False, "queues must be a JSON object")
        for queue in REQUIRED_QUEUES:
            _record(checks, "queues." + queue, False, "queues must be a JSON object")
        return {}
    complete = set(queues) == set(REQUIRED_QUEUES)
    missing = [q for q in REQUIRED_QUEUES if q not in queues]
    extra = [str(q) for q in queues if q not in REQUIRED_QUEUES]
    _record(checks, "queues", complete,
            "all seven required queues" if complete
            else "missing: " + ", ".join(missing) + "; unexpected: " + ", ".join(extra))
    for queue in REQUIRED_QUEUES:
        is_list = isinstance(queues.get(queue), list)
        _record(checks, "queues." + queue, is_list,
                "list of work items" if is_list else "queue must be a list")
    return queues


def _check_operators(operators, checks):
    """Check the operator roster and return the subjects allowed to act now."""
    if not isinstance(operators, list) or not operators:
        _record(checks, "operators", False, "operators must be a non-empty list")
        return set()
    valid = True
    subjects = []
    for operator in operators:
        if not (isinstance(operator, dict)
                and _is_nonempty_string(operator.get("subject"))
                and operator.get("role") in AUTHORIZED_OPERATOR_ROLES
                and isinstance(operator.get("active"), bool)):
            valid = False
        if isinstance(operator, dict) and _is_nonempty_string(operator.get("subject")):
            subjects.append(operator["subject"])
    # A duplicate subject could carry a stale `active` flag and re-authorize a
    # revoked operator, so identity must be unique in the roster.
    if len(subjects) != len(set(subjects)):
        valid = False
    _record(checks, "operators", valid,
            "every operator has a unique subject, authorized role and active flag" if valid
            else "operator subject, uniqueness, role or active flag is invalid")
    active = set()
    for operator in operators:
        if (isinstance(operator, dict)
                and _is_nonempty_string(operator.get("subject"))
                and operator.get("role") in AUTHORIZED_OPERATOR_ROLES
                and operator.get("active") is True):
            active.add(operator["subject"])
    return active


def _check_audit(events, organization_id, checks):
    """Check OPS-02 audit facts and return valid events indexed by their id."""
    if not isinstance(events, list) or not events:
        _record(checks, "audit_events", False, "audit_events must be a non-empty list")
        return {}
    complete = True
    by_id = {}
    duplicate = False
    for event in events:
        if not isinstance(event, dict):
            complete = False
            continue
        if any(field not in event for field in AUDIT_FIELDS):
            complete = False
        if event.get("organization_id") != organization_id:
            complete = False
        if not _is_instant(event.get("at")):
            complete = False
        if not all(_is_nonempty_string(event.get(field)) for field in AUDIT_TEXT_FIELDS):
            complete = False
        if _is_nonempty_string(event.get("id")):
            ident = event["id"]
            if ident in by_id:
                duplicate = True
            by_id[ident] = event
    complete = complete and not duplicate
    if complete:
        detail = "actor, action, resource, time, outcome, correlation and tenant recorded"
    elif duplicate:
        detail = "an audit event repeats an identifier"
    else:
        detail = "an audit event is missing a required OPS-02 field"
    _record(checks, "audit_events", complete, detail)
    return by_id


def _check_items(queues, active_actors, audit_events, checks):
    """Check work items and their operator actions across every queue."""
    structure_ok = state_ok = owner_ok = True
    actions_ok = reason_ok = actor_ok = audit_ok = True
    inspected = 0
    for queue in REQUIRED_QUEUES:
        items = queues.get(queue)
        if not isinstance(items, list):
            continue
        for item in items:
            inspected += 1
            if not (isinstance(item, dict)
                    and _is_nonempty_string(item.get("id"))
                    and item.get("queue") == queue
                    and _is_instant(item.get("created_at"))):
                structure_ok = False
                continue
            state = item.get("state")
            if state not in ITEM_STATES:
                state_ok = False
            owner = item.get("owner")
            if state in ("assigned", "escalated") and not _is_nonempty_string(owner):
                owner_ok = False
            actions = item.get("actions")
            if not isinstance(actions, list) or (state != "open" and not actions):
                actions_ok = False
                actions = actions if isinstance(actions, list) else []
            required = REQUIRED_ACTION.get(state) if isinstance(state, str) else None
            if required is not None and not any(
                    isinstance(action, dict) and action.get("type") == required
                    for action in actions):
                state_ok = False
            for action in actions:
                if not isinstance(action, dict):
                    actions_ok = False
                    continue
                kind = action.get("type")
                if kind not in ACTION_TYPES:
                    actions_ok = False
                if kind == "retry" and queue not in RETRYABLE_QUEUES:
                    actions_ok = False
                action_at = action.get("at")
                if not _is_instant(action_at):
                    actions_ok = False
                created_at = _parse_instant(item.get("created_at"))
                action_instant = _parse_instant(action_at)
                if (created_at is not None and action_instant is not None
                        and action_instant < created_at):
                    actions_ok = False
                if not _is_nonempty_string(action.get("reason")):
                    reason_ok = False
                if not _is_nonempty_string(action.get("actor")) or action["actor"] not in active_actors:
                    actor_ok = False
                audit_id = action.get("audit_id")
                if not _is_nonempty_string(audit_id) or audit_id not in audit_events:
                    audit_ok = False
                elif not _audit_corroborates(
                        audit_events[audit_id], action.get("actor"),
                        ACTION_AUDIT_NAMES.get(kind) if isinstance(kind, str) else None,
                        action_at, item["id"]):
                    audit_ok = False
    details = f"{inspected} work items inspected"
    _record(checks, "items.structure", structure_ok,
            "id, queue tag and creation instant present" if structure_ok
            else "an item has a missing id, wrong queue tag or invalid instant: " + details)
    _record(checks, "items.state", state_ok,
            "states are known and non-open states are justified" if state_ok
            else "an item has an invalid state or no matching state action: " + details)
    _record(checks, "items.owner", owner_ok,
            "assigned and escalated items have an owner" if owner_ok
            else "an assigned or escalated item has no owner: " + details)
    _record(checks, "items.actions", actions_ok,
            "actions are typed, timed, retry-scoped and never predate their item" if actions_ok
            else "an item has malformed, missing, premature or disallowed actions: " + details)
    _record(checks, "items.reason", reason_ok,
            "every operator action carries a reason" if reason_ok
            else "an operator action has no reason: " + details)
    _record(checks, "items.actor", actor_ok,
            "every action was performed by an active authorized operator" if actor_ok
            else "an action names an inactive or unauthorized actor: " + details)
    _record(checks, "items.audit", audit_ok,
            "every action cites an audit event for the same actor, verb, item and time" if audit_ok
            else "an action cites an unknown or non-corroborating audit event: " + details)


def _check_exports(exports, organization_id, known_queues, active_actors,
                   audit_events, checks):
    """Check explicit scope, bounded expiry, audit and actor on every export."""
    names = ("exports.scope", "exports.expiry", "exports.audit", "exports.actor")
    if not isinstance(exports, list):
        for name in names:
            _record(checks, name, False, "exports must be a list")
        return
    scope_ok = expiry_ok = audit_ok = actor_ok = True
    for export in exports:
        if not isinstance(export, dict):
            scope_ok = expiry_ok = audit_ok = actor_ok = False
            continue
        scope = export.get("scope")
        if not (isinstance(scope, dict)
                and scope.get("organization_id") == organization_id
                and isinstance(scope.get("queues"), list) and scope["queues"]
                and all(isinstance(q, str) and q in known_queues
                        for q in scope["queues"])):
            scope_ok = False
        ttl = _ttl_seconds(export.get("created_at"), export.get("expires_at"))
        if ttl is None or ttl <= 0 or ttl > MAX_EXPORT_TTL_SECONDS:
            expiry_ok = False
        audit_id = export.get("audit_id")
        if not _is_nonempty_string(audit_id) or audit_id not in audit_events:
            audit_ok = False
        elif not _audit_corroborates(audit_events[audit_id], export.get("actor"),
                                     EXPORT_AUDIT_NAME, export.get("created_at"), None):
            audit_ok = False
        actor = export.get("actor")
        if not _is_nonempty_string(actor) or actor not in active_actors:
            actor_ok = False
    _record(checks, "exports.scope", scope_ok,
            "explicit tenant and queue scope on every export" if scope_ok
            else "an export has no explicit tenant/queue scope")
    _record(checks, "exports.expiry", expiry_ok,
            f"every export expires within {MAX_EXPORT_TTL_SECONDS}s" if expiry_ok
            else "an export has an unbounded, missing or non-positive expiry")
    _record(checks, "exports.audit", audit_ok,
            "every export cites an export audit event by the same actor at creation" if audit_ok
            else "an export has no valid, corroborating audit reference")
    _record(checks, "exports.actor", actor_ok,
            "every export was requested by an active authorized operator" if actor_ok
            else "an export names an inactive or unauthorized actor")


def evaluate(artifact):
    """Return a fail-closed ADM-02 verdict for one evidence artifact.

    Content problems are recorded as failed checks rather than raised, so a
    reviewer sees every breach in one report.
    """
    checks = []
    if not isinstance(artifact, dict):
        _record(checks, "artifact", False, "artifact must be a JSON object")
        return _verdict(checks, None)
    kind = artifact.get("kind")
    _record(checks, "schema_version", artifact.get("schema_version") == SCHEMA_VERSION,
            f"expected {SCHEMA_VERSION}")
    _record(checks, "kind", kind in KINDS, "kind must be " + " or ".join(KINDS))
    if kind == "operator_attested" and "synthetic" in str(artifact.get("source", "")).lower():
        _record(checks, "evidence_conflict", False,
                "an operator-attested artifact cannot name a synthetic source")
    else:
        _record(checks, "evidence_conflict", True, "source matches the evidence class")
    organization_id = artifact.get("organization_id")
    _record(checks, "organization_id", _is_nonempty_string(organization_id),
            "a non-empty active organization is required")
    queues = _check_queues(artifact.get("queues"), checks)
    active_actors = _check_operators(artifact.get("operators"), checks)
    audit_events = _check_audit(artifact.get("audit_events"), organization_id, checks)
    _check_items(queues, active_actors, audit_events, checks)
    _check_exports(artifact.get("exports"), organization_id, set(queues),
                   active_actors, audit_events, checks)
    return _verdict(checks, kind)


def format_report(report):
    """Render a verdict, its evidence class and every named check as text."""
    lines = [f"ADM-02 operational queues: {report['verdict'].upper()}"]
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
        print("usage: ops_queues.py <artifact.json>", file=sys.stderr)
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
    sys.exit(main())
