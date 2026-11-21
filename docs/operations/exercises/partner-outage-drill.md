# Exercise sheet — partner outage drill (results stop arriving, orders pile up)

Back to the [exercise index](./index.md). This is a rehearsal script, **not**
evidence, not a real outage and not an approval. Use synthetic partners, orders
and results only. No drill was run to write this sheet. The symptoms under test
are the signals and queues declared in
[operational runbooks](../s_g5_06/operational-runbooks.md) and the
[queue catalog](../s_g5_11/queue-catalog.md).

## Goal

A partner interface silently stops delivering results while new orders keep
arriving. Detect the loss from declared signals, protect unacknowledged critical
findings, drain the piled-up work without creating duplicates or a second
clinical task, and prove the recovery declaration with the offline evaluator.
Recovery must not satisfy a clinician acknowledgment (XCH-05).

## Who takes part

| Role | Responsibility in this drill |
|---|---|
| Facilitator | Injects the outage, duplicate and out-of-order events; times the script; asks the questions |
| Operational on-call owner | Declares the incident, watches error rate, saturation, queue age and partner delivery |
| Integration lead | Confirms partner status before any retry; owns outbox/dead-letter reconciliation (ARC-05) |
| Site clinical coverage owner | Owns unmatched reports, critical acknowledgment and coverage gaps |
| Unit supervisor | Owns unassigned patient messages, response breaches and overdue acknowledgments |
| Privacy / accountable operations owner | Signs any decision that reopens writes after a data-restoring step |

`NOT YET IN PLACE:` a published on-call rota, escalation contacts and a partner
status channel; the facilitator writes named roles and timestamps into the drill
log instead of inventing contacts.

## Targets and declared envelope taken from the specification

| Concern | Target | Source |
|---|---|---|
| Monitoring signals | All 13 declared: `error_rates`, `saturation`, `queue_age`, `partner_delivery`, `unmatched_reports`, `critical_acknowledgment_time`, `missing_coverage`, `unassigned_patient_messages`, `response_breaches`, `failed_instruction_notifications`, `overdue_instruction_acknowledgments`, `rendering_failures`, `backup_status` | OPS-05; [operational runbooks](../s_g5_06/operational-runbooks.md) |
| Ownership | `operational_on_call_owner` and `site_clinical_coverage_owner` are non-blank named roles | OPS-05 |
| Routine API latency | p95 ≤ 500 ms reads and ≤ 900 ms commands, 99.9% monthly availability | OPS-03 |
| Delivery and dedup | Immutable event IDs, per-aggregate version, one downstream effect and one clinical task per revision; retry with bounded backoff and a restricted dead-letter queue | ARC-05; [restore procedure](../s_g5_06/restore-procedure.md) |
| Work-item discipline | Seven named queues; states `open → assigned → resolved` with `escalated`; non-open items justified by `assign`, `resolve`, `retry` or `escalate` with reason, owner, audit id and instant | ADM-02; [queue catalog](../s_g5_11/queue-catalog.md) |
| Critical findings | Verification, responsible owner, due time and escalation; human acknowledgment with timestamp and read-back; delivery and release do not satisfy it | XCH-05 |
| Duplicate prevention on recovery | Idempotency keys scoped to tenant/caller/command with a request digest; durable business uniqueness for external clinical actions | ARC-03, OPS-05 |

## Preconditions and synthetic setup

1. The facilitator declares a synthetic start time in UTC and confirms the
   partner interface is simulated in the drill log only.
2. Use invented tenants, facilities, staff subjects, order identifiers and
   accession values. Never use a real patient value, credential, endpoint host or
   domain.
3. `NOT YET IN PLACE:` no in-repository command injects a partner outage, stops a
   result feed or generates a live queue snapshot. The injects below are narrated
   by the facilitator and represented by the synthetic queue artifact in
   `infra/s_g5_11/fixtures/`. Do not claim an observed alert or operator action
   from a fixture.
4. The only evaluator commands are `bash infra/s_g5_11/check.sh` and
   `python3 -I infra/s_g5_11/ops_queues.py <artifact.json>`; the recovery envelope
   can also be checked with
   `python3 -I infra/s_g5_06/recovery_evidence.py <artifact.json>`.

## Timed script with injects

`T+` is minutes from the synthetic incident declaration.

| T+ | Inject / trigger | Operator action (real command where one exists) | Expected observation |
|---:|---|---|---|
| 0 | Facilitator: "The partner result feed has gone quiet; new orders are still arriving." | On-call owner declares the incident in the drill log and starts the clock | Incident, named owner and UTC time recorded |
| 5 | No new synthetic result events are injected | Owner checks the declared signals `partner_delivery`, `queue_age`, `unmatched_reports`, `error_rates` and `saturation`. `NOT YET IN PLACE:` no in-repo clinical monitoring dashboard or alert feed; `scripts/monitor.sh` is a GitHub Actions swarm dashboard and is **not** used as evidence | Signal names and the owner who would receive each are recorded as declarations, not as observed alerts |
| 10 | Synthetic orders accumulate unresolved work items | Owner maps them to the real queues `rejected_exchanges` and `unreconciled_reports` in the [queue catalog](../s_g5_11/queue-catalog.md). The synthetic snapshot is evaluated with `python3 -I infra/s_g5_11/ops_queues.py <artifact.json>` | Unknown queues, mismatched tags or unowned non-open items fail closed |
| 15 | A synthetic **critical finding** is injected with no clinician acknowledgment | Site clinical coverage owner assigns a named owner and due time; if the named owner is unavailable, route to a staffed backup queue and record the escalation. Delivery or patient release is **not** accepted as acknowledgment (XCH-05) | `critical_acknowledgment_time` has a named owner; read-back and receipt recorded; no silent reassignment to an unstaffed queue |
| 20 | Inject a coverage gap and an unassigned patient message | Unit supervisor records `missing_coverage` and `unassigned_patient_messages`; each item gets a named owner (`assign`) or an `escalate` with reason | No item left in a non-open state without a matching action and reason |
| 25 | Inject a duplicate event and an out-of-order revision by immutable ID | Integration lead reconciles by immutable event ID inside the sender/type namespace, holds the out-of-order change by per-aggregate version (ARC-05) and confirms order and patient identifiers before any external link is re-created | One downstream effect and one clinical task for the intended revision; duplicate does not create a second order or result |
| 30 | Inject a transient send failure | Owner proposes `retry`. Per the queue catalog, `retry` is permitted only on `rejected_exchanges` or `document_failures`; integration lead confirms partner status before retrying | Retry uses bounded backoff; failed attempts recorded; no blind retry into an unknown partner state |
| 35 | Facilitator: "Partner delivery resumes." | Owner drains the queues with `assign`, `escalate`, `resolve` and permitted `retry`; every action carries reason, timezone-aware instant, actor and an `audit_id` that references an existing same-tenant audit event. Re-run `bash infra/s_g5_11/check.sh` | Evaluator passes; audit ids corroborate actor, verb, work item and instant |
| 45 | Facilitator asks whether writes need reopening after a data-restoring step | If any data was restored, switch to the [database restore drill](./database-restore-drill.md) reconciliation gate before writes resume; otherwise state that no restore was involved | Reconciliation-before-writes rule honoured or explicitly out of scope |
| 50 | Observation window | Owner watches error rate, queue age and partner delivery; if the loss was data-bearing, evaluate the recovery envelope with `python3 -I infra/s_g5_06/recovery_evidence.py <artifact.json>` | Any breach is a failed step or corrective action, not suppressed |

## Questions the facilitator asks

1. Which of the 13 OPS-05 signals first showed the failure, and who owns it?
2. Was the critical finding acknowledged by an authorized human with a timestamp
   and read-back, or merely delivered or released to the patient?
3. Was partner status confirmed before the retry, and was the retry limited to
   the queues that permit it?
4. How did deduplication produce exactly one downstream effect and one clinical
   task when the duplicate event arrived twice?
5. Does every non-open work item name an owner and carry the action that
   justifies its state, with a reason and a same-tenant audit id?
6. Did the error rate or latency breach the OPS-03 p95 targets during the
   backlog drain, and what was done about it?
7. Is the snapshot labelled a synthetic rehearsal, and does it avoid claiming an
   alert fired or a person was contacted?
8. Does any evidence link, URL or log line contain a patient value, a partner
   credential or a real domain?

## Evidence to capture

- The synthetic queue snapshot (synthetic fixture or a rehearsal-labelled
  artifact) and the exact output of `bash infra/s_g5_11/check.sh` and
  `python3 -I infra/s_g5_11/ops_queues.py <artifact.json>`.
- A declaration table: all 13 signals with their named owner role, and the
  required OPS-05 runbook topics (`paper_manual_capture`, `patient_identification`,
  `critical_contacts`, `supervised_back_entry`) from
  [operational runbooks](../s_g5_06/operational-runbooks.md).
- The timeline with UTC times for declaration, detection, escalation, retry,
  drain and observation; the critical acknowledgment record with owner, due
  time, attempts and read-back; duplicate and out-of-order handling decisions.
- The recovery envelope result if a restore was involved, plus the observed
  queue-age and partner-delivery values.
- A corrective-actions section with an owner for each failed step.

Run `python3 -I scripts/check-secrets.py` over the captured artifacts before
sharing them. Evidence links must reference approved internal artifacts, never a
patient record or a signed URL.

## Pass criteria

- All 13 OPS-05 signals and both named owner roles are declared; no duplicate or
  missing signal.
- Each piled-up work item is in a valid queue with a state justified by a
  matching action carrying a non-empty owner, reason, actor, instant and a
  corroborating same-tenant audit id (ADM-02).
- A critical finding has a named owner, due time, escalation path and a recorded
  human acknowledgment; delivery or release never substitutes for it (XCH-05).
- Replay of the duplicate and out-of-order events produces one downstream effect
  and one clinical task per revision, with order and patient identifiers
  confirmed before relinking (ARC-05).
- Partner status is confirmed before retry; retries are bounded and limited to
  the queues that permit them.
- Observed error rate and latency remain within the OPS-03 targets, or a breach
  is recorded with a corrective action.
- If data was restored, the reconciliation gate completed and was signed before
  writes reopened (OPS-04).
- The artifact is labelled as a synthetic rehearsal and contains no patient
  data, credential, real domain or PHI.
- Passing the offline evaluator is necessary but not sufficient for a conducted
  drill; human G5 acceptance stays open.

## Real commands used

| Command | Exit / evidence |
|---|---|
| `bash infra/s_g5_11/check.sh` | 0; ADM-02 unit tests and positive/negative fixture runs |
| `python3 -I infra/s_g5_11/ops_queues.py <artifact.json>` | 0 pass / 1 failed checks / 2 unreadable input |
| `python3 -I infra/s_g5_06/recovery_evidence.py <artifact.json>` | 0 pass / 1 failed checks / 2 unreadable input, when a restore is involved |
| `bash db/check.sh` | 0; proves audit chain and next-request revocation semantics on synthetic PostgreSQL when the drill touches identity or audit data |
| `python3 -I scripts/check-secrets.py` | 0; no credential- or PHI-shaped strings in captured files |

`NOT YET IN PLACE:` a clinical monitoring dashboard and alert routing, a
partner-delivery inject simulator, a live queue snapshot export, a partner status
channel and a named on-call rota. These are gaps for the operations team to own;
do not substitute the swarm CI dashboard or a fixture for real telemetry.
