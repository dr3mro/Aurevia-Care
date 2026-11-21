# On-call handover

This is the shift-change procedure for the operational on-call role. Its purpose
is that the incoming person can continue an incident or an open alert without
asking what is on fire. It records roles and state; it never records a person's
credentials or patient data. See the [runbook index](index.md) for common rules.

## When to use it

- At the end of an on-call shift or before the named owner goes offline.
- When the primary owner hands an active incident to a secondary owner.
- Before a planned absence that overlaps a deploy, migration, restore drill or
  rotation.

## Who may act

| Role | Responsibility |
|---|---|
| Outgoing operational on-call owner | Writes the handover and names open items and their state |
| Incoming operational on-call owner | Reads it, asks questions and acknowledges acceptance |
| Site clinical coverage owner | Receives any open patient-safety or critical-result item |
| Privacy / accountable operations owner | Receives any open disclosure or hold item |

`NOT YET IN PLACE:` the named rota, the contact list and the handover log. The
launch checklist's on-call row is open; the rota and escalation contacts live in
the organization's approved policy pack (ADM-01), not in this repository. Record
the gap and use the approved out-of-band channel instead of inventing a contact.

## What the outgoing person writes

1. **Header.** UTC handover instant, outgoing and incoming roles and names,
   shift window, and the environment(s) in scope.
2. **What is on fire now.** Every active incident: severity from
   [incident response](incident-response.md), declaration time, current owner,
   last action, next action and the correlation IDs that identify it. Mark a
   Sev 1 or Sev 2 item as blocked if no receiving owner was found.
3. **Open alerts.** Walk the 13 declared signals in
   [operational monitoring and runbooks](../recovery/operational-runbooks.md):
   `error_rates`, `saturation`, `queue_age`, `partner_delivery`,
   `unmatched_reports`, `critical_acknowledgment_time`, `missing_coverage`,
   `unassigned_patient_messages`, `response_breaches`,
   `failed_instruction_notifications`, `overdue_instruction_acknowledgments`,
   `rendering_failures` and `backup_status`. For each open item give the role
   that owns it, its deadline and its status.
   `NOT YET IN PLACE:` a live alert feed or dashboard. These are declarations in
   an evidence evaluator; take the current values from logs, partner reports and
   the incident record, and say so in the handover.
4. **Patient-safety queue.** Unacknowledged critical results, their original
   deadlines, backup-contact attempts and read-backs, and any open disputed
   patient link. Route these to the site clinical coverage owner by name in the
   record. A critical result needs a recorded human acknowledgment (XCH-05); a
   delivery or a patient release does not satisfy it.
5. **Recent changes.** Deploys and image tags/digests, Flyway migrations,
   configuration approvals and any key rotation in the shift. Link the relevant
   runbook record. An incomplete or reverted migration is called out explicitly.
6. **Recovery and backup state.** Backup job status and age, last restore drill
   date and its next scheduled date (OPS-04 requires verification before pilot
   and quarterly), and any open reconciliation gate from the
   [recovery drill runbook](../recovery/runbook.md).
7. **Keys and certificates.** Rotations pending or half-finished, the
   configuration key involved (never the value), and certificate expiry
   concerns. `NOT YET IN PLACE:` a 21-day expiry alert (WEB-01) and a rotation
   record store.
8. **Evidence and logs.** Where the shift's records live, which correlation IDs
   matter and which checks were run with their final lines. No raw logs, tokens
   or patient fields.
9. **Outstanding decisions.** Anything waiting on a named owner, with the
   expected decision time and what happens if it is not made.
10. **Acceptance.** The incoming owner writes an acknowledgement with a UTC
    instant. If there is no incoming owner, the shift stays with the outgoing
    owner and the gap is recorded as `NOT YET IN PLACE:` rather than assumed.

## How to check it worked

- Every open incident and alert in the handover has an owner, a deadline and a
  next action; none is left as "someone is looking at it".
- The incoming owner can name what is on fire and the next step without asking
  the outgoing owner.
- `GET /health/live` and `GET /health/ready` are recorded as UP, or any failure
  is listed as an open item with an owner.
- The patient-safety queue has been acknowledged by the site clinical coverage
  owner, and the handover contains no patient data, credential, real domain or
  PHI.

## What to record

UTC handover instant, roles and names, shift window, the ten items above with
their current state, links to incident and change records, and the incoming
owner's acknowledgement instant. A rehearsal handover using synthetic fixtures
is labelled as a rehearsal, never as a real rota handover.

## Related material

[Runbook index](index.md) · [Incident response](incident-response.md) ·
[Bad deploy](bad-deploy.md) ·
[Operational monitoring and runbooks](../recovery/operational-runbooks.md)
