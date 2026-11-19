# Progressive rollout and production access (OPS-06)

## Progressive rollout

The `rollout` object must declare a monitored, reversible promotion:

| Field | Required value | Check name |
|---|---|---|
| `strategy` | exactly `progressive` | `rollout.strategy` |
| `stages` | two or more distinct non-blank names, last one `full` | `rollout.stages` |
| `health_gates` | literal `true` | `rollout.health_gates` |
| `monitoring` | literal `true` | `rollout.monitoring` |
| `rollback_tested` | literal `true` | `rollout.rollback_tested` |

The stage list models traffic promotion from a limited cohort to full
availability — for example `internal`, `canary`, `partial`, `full`. The minimum
of two distinct stages prevents a direct big-bang release from being relabelled
as progressive. The final stage must be `full`; a list that stops at `canary`
or `partial` fails. Duplicate names and blank or non-string members fail, and
the element type guard runs before the uniqueness comparison so an unhashable
member fails closed instead of raising. Names are stripped before the
distinctness and final-stage checks, so `["full ", "full"]` is a single stage
and is rejected rather than counting as two distinct stages.

Health gates, monitoring and a tested rollback are separate controls: a stage
that is monitored but has no health gate, or a rollout that cannot be rolled
back, is not acceptable under OPS-06.

## Production access

The `production_access` object encodes approval discipline:

| Field | Required value | Check name |
|---|---|---|
| `approval_required` | literal `true` | `production_access.approval_required` |
| `requester` | non-blank string, distinct from `approver` | `production_access.separation_of_duties` |
| `approver` | non-blank string, distinct from `requester` | `production_access.separation_of_duties` |
| `audited` | literal `true` | `production_access.audited` |
| `expiring` | literal `true` | `production_access.expiring` |

The single `separation_of_duties` check requires both identities to be non-blank
strings and different after stripping, so whitespace padding cannot turn a
self-approval into two distinct parties. A missing, blank, non-string or
self-approved identity fails. Together with `approval_required`, `audited` and `expiring`, this encodes
the OPS-06 rule that production access is approved, attributable, recorded and
time-bounded rather than permanent.

## What this does not prove

The evaluator cannot observe live health signals, confirm that a rollback was
rehearsed, verify the approver's identity or authority, or check that an access
grant actually expired. It judges the declared contract only. Real monitoring
dashboards, rollback drill records and audit-log extracts must be reviewed by
operations and the privacy owner; those are open items in the
[handoff](README.md).
