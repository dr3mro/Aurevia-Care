# Queue catalog and operator actions (ADM-02)

- Requirement: **ADM-02**
- Machine source of truth: [ops_queues.py](../../../infra/s_g5_11/ops_queues.py).
  The constants there are the single enforced copy of this contract; this page
  explains them and must be kept in step with that module.
- Back to the [S-G5-11 handoff](README.md).

## Required queues

All seven queues must be present, and no unlisted queue may be declared. A work
item names the queue it belongs to; a mismatched tag fails the check.

| Queue | Originating requirement | Work it carries |
|---|---|---|
| `unsigned_encounters` | CLN-05 | Encounters with a draft that has not been signed |
| `unreconciled_reports` | XCH-04 | Reports whose patient, source or accession could not be matched |
| `unacknowledged_critical_findings` | XCH-05 | Critical findings without a recorded human acknowledgment |
| `rejected_exchanges` | XCH-03 | Rejected or clarified order exchanges awaiting action |
| `document_failures` | ARC-06 | Rendering, scan or attachment failures |
| `missing_coverage` | XCH-05, ADM-01 | Findings or units without a named owner or backup coverage |
| `revoked_accounts` | IAM-02 | Accounts whose membership or invitation was revoked |

The originating requirement explains where an item comes from; ADM-02 is the
requirement that the item is visible in an operational queue and actionable.

## Work-item states

`open → assigned → resolved`, with `escalated` as a branch from `assigned` or
`open`. `assigned` and `escalated` items must name a non-empty owner (a person or
named backup), so no item is silently left to an unstaffed queue. A non-open
item must carry at least one operator action, and its state must be justified by
a matching action:

| State | Required action |
|---|---|
| `open` | none required |
| `assigned` | `assign` |
| `resolved` | `resolve` |
| `escalated` | `escalate` |

## Operator actions

ADM-02 names four operator actions. The evaluator enforces the same shape for
each one, regardless of the queue:

| Action | Meaning | Constraint |
|---|---|---|
| `assign` | Give the item a named owner | Owner must be non-empty |
| `resolve` | Close the item with an outcome | Requires a non-empty reason |
| `retry` | Re-attempt a failed delivery or rendering | Only on `rejected_exchanges` or `document_failures` |
| `escalate` | Hand the item to a named backup owner | Owner must be non-empty, reason required |

Every action records a non-empty `reason`, a timezone-aware instant, the acting
`actor` and an `audit_id` that references an existing audit event. An actor must
be an `active` operator whose role is in the allowlist. Revocation is effective
on the next action: an operator whose `active` flag is false, or who is absent
from the roster, fails the check even if an earlier action by the same subject
was accepted.

`retry` scope is a **declared assumption**, not a specification literal. The
specification requires a retry action but does not enumerate the queues that
permit one; retrying clinical review, coverage or revocation work would discard
the evidence that the queue exists to preserve. Site alignment is an open item.

## Authorized operators

The operator roster is a non-empty list of subjects with a role and an `active`
boolean; subjects are unique, so a stale `active` entry cannot re-authorize a
revoked person. Accepted roles are `tenant_admin`, `privacy_auditor` and `operator`.
This is a **declared assumption**: site role names must be mapped to these three
before live enablement. Audit actors are not required to appear in the operator
roster — a system principal may write an audit event — but every *operator
action* and *export request* must be an active, authorized human operator.

## Audit facts

Every operator action and export references an audit event by id; audit ids are
unique, so a forged duplicate cannot stand in for a real event. Each referenced
event must carry the OPS-02 fields: `id`, `actor`, `action`, `resource_id`, `at`
(timezone-aware instant), `outcome`, `correlation_id` and `organization_id`. An
event from another tenant fails the check, so a queue snapshot cannot borrow
another organization's audit trail. The evaluator checks the *declaration* of
these facts, not their truth; append-only storage and tamper detection remain
the responsibility of the audit service.

## Export envelope

An export declares an explicit `scope` with the active `organization_id` and a
non-empty list of known queue names. It declares a `created_at` and an
`expires_at` whose difference is a positive number of seconds no greater than
86,400 (`MAX_EXPORT_TTL_SECONDS`). It names an authorized `actor` and an
`audit_id`. A missing scope, an unknown queue, an unbounded or expired window,
an unauthorized actor or a missing audit reference each fail closed.

## What the catalog does not decide

Queue staffing levels, response targets, escalation deadlines and which site
role maps to `operator` are configuration decisions, not evaluator literals.
The [handoff](README.md) lists them as open items for the accountable owners.
