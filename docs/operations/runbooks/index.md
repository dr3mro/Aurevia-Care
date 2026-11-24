# Operator runbooks

These are short procedures for the few operational events this repository can
actually describe. Each one follows the same shape: when to use it, who may act,
numbered steps, how to check it worked, and what to record.

A runbook is a **procedure**, not evidence. Writing one does not mean an alert
fired, a person was called, a key was rotated or a deploy was rolled back. The
named owners, rota, escalation contacts, jurisdiction rules and approvals belong
to the organization's approved policy pack (ADM-01); a flag or a passing check
in this repository is never an approval. Read the
[production readiness checklist](../production-readiness/launch-checklist.md)
for the rows that still need an owner and real evidence.

| Runbook | Use when | Requirements |
|---|---|---|
| [Incident response](incident-response.md) | A patient-safety, availability, tenant-isolation or security event is suspected | OPS-01, OPS-03, OPS-04, OPS-05, OPS-07, WEB-06 |
| [Secret and key rotation](key-rotation.md) | A credential or key is due for rotation, or may be exposed | OPS-01, OPS-02, WEB-12 |
| [On-call handover](on-call-handover.md) | The operational on-call role changes hands | OPS-05 |
| [Bad deploy](bad-deploy.md) | Health, error rate or latency worsens after a release | OPS-06, WEB-05, WEB-11 |

## Rules that apply to every runbook

1. **Roles, not names.** Act as the named role. Record the person's identity on
   the incident or handover record, not in these pages. A named rota and contact
   list are `NOT YET IN PLACE:` in this repository (the launch checklist's
   on-call row is open).
2. **UTC timestamps.** Write every time as an ISO-8601 UTC instant, for example
   `2026-11-21T15:10:24Z`. The RTO clock in the
   [recovery runbook](../recovery/runbook.md) also starts at the UTC incident
   declaration.
3. **Correlate, do not copy.** Use the `X-Correlation-Id` value to find a request
   in the structured JSON logs. Never paste a request body, query string, token,
   patient identifier, real domain or PHI into a log, a link, a tracker or these
   notes. The API's log line is fixed to `correlationId`, `method`, `route`,
   `status` and `latencyMs`
   ([RequestLogFormatter](../../../apps/api/src/main/java/com/aurevia/observability/RequestLogFormatter.java)).
4. **Synthetic data only in rehearsal.** A rehearsal with synthetic fixtures
   tests the procedure and the evidence envelope; it is never presented as a
   conducted incident, drill, rotation or restore.
5. **Say the gap.** Where a step needs a tool, rota, policy decision or contact
   that does not exist in this repository, the step says `NOT YET IN PLACE:` and
   names the gap. Do not invent a command or an owner to fill it.
6. **Leave a record.** Every runbook points at the same evidence discipline as
   the [drill evidence template](../recovery/drill-evidence.md): what happened,
   when, by which role, with what outcome and what is still open.

## What the repository really provides today

- **Health probes:** unauthenticated `GET /health/live` (process up) and
  `GET /health/ready` (one database round trip, HTTP 503 on failure, status-only
  body). See [HealthController](../../../apps/api/src/main/java/com/aurevia/web/HealthController.java)
  and the [API README](../../../apps/api/README.md).
- **Structured logs ECS on stdout:** `logging.structured.format.console: ecs` and
  one line per request with a valid-or-generated correlation id
  ([application.yml](../../../apps/api/src/main/resources/application.yml),
  [CorrelationIdFilter](../../../apps/api/src/main/java/com/aurevia/observability/CorrelationIdFilter.java)).
- **Fail-closed checks:** `scripts/check.sh`, the module `check.sh` entry points,
  OWASP dependency analysis and SpotBugs inside `mvn verify`, and the append-only
  audit hash chain verified by `db/check.sh`
  ([V3 migration](../../../db/migrations/V3__audit_chain.sql)).
- **Targets and declared signals:** [capacity targets](../capacity/capacity-targets.md)
  for OPS-03 and the 13 declared monitoring signals in
  [operational monitoring and runbooks](../recovery/operational-runbooks.md).

`NOT YET IN PLACE:` a log pipeline or dashboard, any configured alert or pager,
an external uptime probe, a secret manager or rotation tool, production
deployment and rollback commands, a status page, and a signed policy pack.
These are recorded as open rows in the
[production readiness checklist](../production-readiness/launch-checklist.md);
the runbooks below say `NOT YET IN PLACE:` at the exact step that needs them.

## Related material

- Product specification: [PROJECT_V1_SPEC.md](../../../.opencode/config/PROJECT_V1_SPEC.md).
- [Recovery drill runbook](../recovery/runbook.md) and
  [drill evidence template](../recovery/drill-evidence.md).
- [Deployment design handoff](../../../infra/DEPLOYMENT.md) and
  [project setup and delivery](../../../README-SETUP.md).
