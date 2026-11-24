# Incident response

This is the first-response procedure for a suspected patient-safety, availability,
tenant-isolation or security event. It tells a tired person what to do in the
first 15 minutes and what evidence to keep. It does not declare an incident
closed and it does not replace the approvals in the organization's policy pack
(ADM-01). See the [runbook index](index.md) for the rules common to all runbooks.

## When to use it

Use this runbook when any of the following is observed or credibly reported:

- A patient-safety event: a wrong-patient association, a critical result without
  a recorded human acknowledgment (XCH-05), a withdrawn release still granting
  access, or a signed record that appears altered.
- A tenant-isolation or disclosure concern: data from one organization visible
  to another, or an unexpected export or download.
- An availability or performance breach against OPS-03 (99.9% monthly core
  availability; server-side p95 ≤ 500 ms for routine reads and ≤ 900 ms for
  routine commands, excluding document transfer, rendering and external calls).
  The enforced targets and the ~43.2-minute monthly error budget are in
  [capacity targets](../capacity/capacity-targets.md).
- A security signal: suspected credential exposure, an unexpected dependency
  or secret-scan finding, or a failed external uptime probe.

If the event began within one deploy or migration window, also open the
[bad deploy runbook](bad-deploy.md). If it may require a restored environment,
also open the [recovery drill runbook](../recovery/runbook.md).

## Who may act

| Role | Acts when |
|---|---|
| Operational on-call owner | Always: declares the incident, sets severity, coordinates and records |
| Site clinical coverage owner | Any patient-safety or critical-result event; owns clinical escalation |
| Privacy / accountable operations owner | Suspected disclosure, export, download or data-exposure event |
| Integration lead | Partner delivery or unmatched-report events |
| Security owner | Suspected credential, tenant-isolation or dependency compromise |
| G5 acceptance owners | Only for final acceptance of a conducted-drill record, never for first response |

`NOT YET IN PLACE:` the named rota, the on-call contact list and the approved
incident-notification rules. The launch checklist's on-call row is open and the
policy pack (ADM-01) is not in this repository. Do not invent a phone number or
a person; record that the escalation channel is missing.

## Severity and who is called

| Severity | Examples | Call immediately |
|---|---|---|
| Sev 1 — patient safety or integrity | Wrong-patient data, unacknowledged critical result, tenant-isolation breach, loss or alteration of a signed record | Operational on-call + site clinical coverage; add privacy owner for disclosure and security owner for compromise |
| Sev 2 — service or partner breach | OPS-03 target breached or error budget burning, `/health/ready` failing, partner delivery stopped, overdue critical acknowledgment | Operational on-call; add integration lead or site clinical coverage as the signal requires |
| Sev 3 — degraded, within target | Single rendering failure, one unassigned message, a non-blocking scan warning | Operational on-call; track in the handover record |

The signal names the on-call role: the 13 declared monitoring signals and their
primary owners are in
[operational monitoring and runbooks](../recovery/operational-runbooks.md).

`NOT YET IN PLACE:` a pager, alert manager, notification sender or external
uptime probe. These signals are declarations in an evidence evaluator, not live
telemetry; a passing evaluation is not proof an alert fired.

## The first 15 minutes

1. **Declare and timestamp.** In a new UTC record, write the incident id, the
   declaration instant (`YYYY-MM-DDTHH:MM:SSZ`), your role and the suspected
   scope. Start the RTO clock here if recovery may be needed.
2. **Set severity** from the table above. When in doubt, use the higher severity
   for a patient-safety event.
3. **Call the roles** listed for that severity. Record who was contacted and
   when. `NOT YET IN PLACE:` the rota and contact channel; record the gap and
   use the site-approved backup channel if one exists outside this repository.
4. **Check liveness and readiness.** Call `GET /health/live` and
   `GET /health/ready`. Liveness is process-local; readiness does one database
   round trip and answers HTTP 503 on failure with a status-only body. See
   [HealthController](../../../apps/api/src/main/java/com/aurevia/web/HealthController.java).
   Do not paste connection strings or exception text anywhere.
5. **Scope from the structured logs.** Take the request lines for the affected
   route and time window. Each line has exactly `correlationId`, `method`,
   `route`, `status` and `latencyMs`
   ([RequestLogFormatter](../../../apps/api/src/main/java/com/aurevia/observability/RequestLogFormatter.java))
   and the route is the matched handler pattern, never a raw patient-bearing
   path. Compare p95 with the OPS-03 targets.
   `NOT YET IN PLACE:` the log pipeline and dashboards that would make this a
   query instead of reading stdout (the API emits ECS JSON to the console per
   [application.yml](../../../apps/api/src/main/resources/application.yml)).
6. **Attach correlation IDs to the audit trail.** Audit events carry
   `correlationId`, actor, purpose, action, resource id and outcome (OPS-02).
   The append-only hash chain and its verifier are in
   [V3](../../../db/migrations/V3__audit_chain.sql) and `db/check.sh` asserts
   the chain. `NOT YET IN PLACE:` an incident-time chain-verification command
   for a live tenant; ask engineering to run a read-only check by tenant id.
7. **Stop further change.** Pause deploys and configuration approvals. If a
   deploy or migration is a plausible cause, switch to
   [bad deploy](bad-deploy.md) before continuing.
8. **Escalate patient safety.** Confirm the site clinical coverage owner has
   the affected patient/result scope and the original deadline. A critical
   result needs a recorded human acknowledgment; route to a staffed backup
   queue, never an unstaffed one. Record each contact attempt, recipient,
   read-back and receipt as separate events.
9. **Preserve evidence.** Keep the UTC timeline, correlation IDs, route class,
   HTTP statuses, latency figures, image tag/digest, health responses and the
   audit references. Never include request bodies, tokens, patient identifiers,
   real domains or PHI.
10. **Place a hold if needed.** If records may be subject to retention or legal
    hold (OPS-01, OPS-07), flag the privacy owner at once.
    `NOT YET IN PLACE:` an approved legal-hold placement tool or policy pack.
    A real `POST /api/v1/messaging-governance/legal-holds` endpoint exists, but
    no approved policy pack is bound to it in this repository and no named hold
    authority is configured here; it is not an approved hold system for incident
    use. The [tenant lifecycle README](../tenant-lifecycle/README.md) states that
    legal-hold authority belongs to qualified site, privacy and clinical owners,
    not to a tool or flag, and the hold rules are documented in
    [retention and offboarding](../tenant-lifecycle/retention-and-offboarding.md).
11. **Hand over if the shift ends.** Use
    [on-call handover](on-call-handover.md); do not leave an open Sev 1 or Sev 2
    without a named receiving owner.

## How to check it worked

- `GET /health/live` returns HTTP 200 and `GET /health/ready` returns HTTP 200
  `UP`; a 503 means the database probe still fails.
- Structured request logs for the affected route show status and p95 back
  within the OPS-03 targets over a sustained window, not one lucky request.
- Every patient-safety item has a site clinical coverage owner and, where
  required, a recorded human acknowledgment.
- The audit chain check reports no bad rows and no evidence link contains PHI,
   credentials or a real domain.
- The record names the roles, the UTC declaration time and the outcome. A
  rehearsal with synthetic data is labelled as a rehearsal.

## What to record

Use the discipline of the
[drill evidence template](../recovery/drill-evidence.md): declaration and
resolution instants (UTC), severity, roles called and when, correlation IDs,
affected route class and tenant identifier only, commands run with their final
lines, image tag/digest, decisions and their approvers, failed steps, corrective
actions and open follow-ups. Record who wrote the entry and who reviewed it.
Never record patient data, a credential, a raw token or a raw log dump.

## Related material

[Runbook index](index.md) · [Bad deploy](bad-deploy.md) ·
[On-call handover](on-call-handover.md) ·
[Recovery drill runbook](../recovery/runbook.md) ·
[Production readiness checklist](../production-readiness/launch-checklist.md)
