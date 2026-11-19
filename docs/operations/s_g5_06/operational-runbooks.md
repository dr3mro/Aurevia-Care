# Operational monitoring and runbooks (OPS-05)

This document specifies the monitoring envelope, the named owners and the four
downtime and recovery runbook topics OPS-05 requires. A declared signal or owner
in an evidence artifact is a claim; it is not proof that an alert fired or that a
person is on call. The evaluator checks that the declaration is complete and
fail-closed, not that monitoring is live.

## Monitoring signals

`monitoring.signals` must name all 13 signals below at least once, with no
duplicates. Extra signals are allowed. Membership is exact, not substring.

| Signal (`monitoring.signals` member) | What it surfaces | Primary owner role |
|---|---|---|
| `error_rates` | API and worker error rate by route class | Operational on-call |
| `saturation` | CPU, memory, connection-pool and worker saturation | Operational on-call |
| `queue_age` | Oldest pending work item and dead-letter age | Operational on-call |
| `partner_delivery` | Outbound order/result delivery status and failed attempts | Operational on-call with integration lead |
| `unmatched_reports` | Reports in the reconciliation queue awaiting review | Site clinical coverage |
| `critical_acknowledgment_time` | Time from critical alert to human acknowledgment and overdue alerts | Site clinical coverage |
| `missing_coverage` | Gaps in the critical-result coverage roster | Site clinical coverage |
| `unassigned_patient_messages` | Care-unit messages with no assignee | Operational on-call with unit supervisor |
| `response_breaches` | Care-unit response target breaches | Unit supervisor |
| `failed_instruction_notifications` | Instruction notification dispatch failures | Operational on-call |
| `overdue_instruction_acknowledgments` | Requested patient acknowledgments past due | Unit supervisor |
| `rendering_failures` | PDF render and document artifact failures | Operational on-call |
| `backup_status` | Backup job success, age and restore-test status | Operational on-call |

Generic push text is used for patient notifications and full content is retrieved
only after authentication (PAT-01, MSG-05). Notification content must never carry
clinical detail, a patient identifier or a download URL.

## Named owners

`ownership.operational_on_call_owner` and
`ownership.site_clinical_coverage_owner` must each be a non-blank string naming a
role or rota, not a placeholder. The artifact records the name; the rota and
escalation contacts live in the organization's approved policy pack (ADM-01).
A critical alert always has an owner, a deadline and an escalation trail; delivery
and patient release never satisfy the clinician acknowledgment required by XCH-05.

## Required runbook topics

`runbooks` must name all four topics below at least once, with no duplicates.

### `paper_manual_capture`

When the system is unavailable, capture required care on approved paper or local
manual forms. On restoration, enter records under supervision, preserve the
original clinical timestamps and the entering actor, and reconcile against
entries already committed so a re-entry cannot create a duplicate chart note,
order or result. Follow the reconciliation and duplicate-prevention steps in
[Restore procedure](restore-procedure.md).

### `patient_identification`

Confirm two identifiers at high-risk transitions when available (IAM-03), using
issuer-scoped MRN and verification state. A shared phone number, or a name and
date of birth alone, must not link records or patient accounts. Where identifiers
are unavailable, use the documented approved alternate process and record it.
Apply the same rule to supervised back-entry after an outage.

### `critical_contacts`

Use the site-approved backup contact channel when an alert is overdue or the
system is down. Record each attempt, the recipient, the original deadline, any
required read-back and the confirmed receipt as separate events. Route a critical
result to a staffed backup queue when the named owner is unavailable; never
silently reassign it to an unstaffed queue. Escalate to the site clinical coverage
owner and keep the escalation trail with the alert.

### `supervised_back_entry`

When the system returns from an outage, re-enter paper or manually captured care
under a qualified supervisor. Preserve the original clinical timestamps and the
entering actor, and reconcile each re-entry against entries already committed so
a supervised re-entry cannot create a duplicate chart note, order, result or
release. Apply patient identification and the reconciliation and
duplicate-prevention gates in [Restore procedure](restore-procedure.md) before
the re-entered record becomes authoritative.

Downtime drills are a separate obligation: OPS-04 requires the restore path to be
verified before pilot and quarterly thereafter, and the G5 gate requires each
site's downtime drill. The `downtime_drills` member is therefore an allowed extra
runbook, not one of the four topics OPS-05 enumerates. Rehearse paper/manual
capture, patient identification, critical contacts and supervised back-entry in
the same drill, record the scenario, owners, timestamps, observed RPO and RTO,
failed steps and corrective actions, and produce the evidence artifact described
in [Recovery evidence](recovery-evidence.md). A synthetic-labelled artifact is a
rehearsal of the check, never proof that the drill happened.

## Escalation and handoff

Every required signal has a named owner and a response target. Breaches and
failed notifications surface to the unit supervisor or operational on-call, are
assigned with a reason, and remain visible until resolved. Coverage rosters and
critical escalation rules are approved configuration with effective dates and
version history (ADM-01); the actual approval belongs to the named site owners,
not to this repository.

## Boundaries

- This is a declared envelope, not operational telemetry. No alert was observed
  and no person was contacted while producing these documents.
- No clinical, privacy or operations sign-off is implied. Those approvals remain
  G5 human gates.
