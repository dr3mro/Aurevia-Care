# Bad deploy

This is the procedure for a release that makes the service worse: health fails,
errors or latency rise, or a partner flow breaks. It covers how to tell, how to
roll back, how to roll forward and what to tell users. It does not by itself
authorize a production change. See the
[runbook index](index.md) for common rules.

## When to use it

Use this runbook when symptoms begin inside a deploy or migration window, or
when a release record (image tag, digest, schema version) correlates with:

- `GET /health/ready` returning HTTP 503 while `GET /health/live` still returns
  HTTP 200, suggesting the database or migration state, not the process.
- A rise in 4xx/5xx or latency on the structured request logs beyond the OPS-03
  targets (server-side p95 ≤ 500 ms for reads and ≤ 900 ms for commands), or
  error-budget burn toward the 99.9% monthly availability target.
- Failed partner delivery, unmatched reports, rendering failures or a broken
  protected sign-in after a configuration change.

If patient safety or a critical result is affected, also open
[incident response](incident-response.md) with the higher severity.

## Who may act

| Role | Responsibility |
|---|---|
| Operational on-call owner | Detects and records the bad deploy, coordinates checks |
| Infrastructure owner | Decides and performs rollback or roll-forward on the deployed environment |
| Engineering owner | Diagnoses the failing change and prepares a tested forward-fix |
| Site clinical coverage owner | Confirms clinical integrity before affected workflows resume |
| Named release approvers | Approve the production change (OPS-06) |

`NOT YET IN PLACE:` the production deployment command, the named release
approvers, the health-gated deploy and the rollback trigger. This repository
publishes images and records digests; it does not contain a deploy, rollback or
progressive-rollout workflow. The launch checklist's rollout and SBOM rows are
open. Do not claim a rollback happened unless the environment operator records
it.

## How to tell

1. **Establish the timeline.** Record the UTC deploy instant, the image tag and
   digest, the schema version and the first observed symptom. The version comes
   from the release tag (a branch build uses `sha-<SHA>`) and the digest is
   written into the GitHub release notes by the Delivery workflow.
2. **Check the probes.** `GET /health/live` is process-local; `GET /health/ready`
   does one database round trip and answers HTTP 503 on failure with a
   status-only body
   ([HealthController](../../../apps/api/src/main/java/com/aurevia/web/HealthController.java)).
   Note which one fails. The bootstrap image smoke test uses `GET /health.txt`
   and is a different, one-off probe, not a monitoring signal.
3. **Read structured logs, not bodies.** Each request line carries
   `correlationId`, `method`, `route`, `status` and `latencyMs`, and the route is
   the matched handler pattern
   ([RequestLogFormatter](../../../apps/api/src/main/java/com/aurevia/observability/RequestLogFormatter.java)).
   Compare the affected route class with the OPS-03 targets in
   [capacity targets](../capacity/capacity-targets.md).
4. **Check the gate that should have caught it.** Delivery runs policy, script,
   frontend, Java/DB, docs and per-architecture smoke jobs and requires them on
   the exact SHA (`Delivery gate`). A failed or skipped gate means the artifact
   was not verified for that commit.
5. **Check configuration drift.** Blank `AUREVIA_OIDC_ISSUER_URI` fails
   startup outside the synthetic profile; a wrong `AUREVIA_DB_PASSWORD`
   makes readiness fail. These are runtime inputs, not code changes.

`NOT YET IN PLACE:` a metrics exporter, dashboard, alert or external uptime
probe. WEB-06 requires request-rate, error-rate, duration, saturation and queue
age metrics and alerting on error-budget burn; none is implemented here.

## How to roll back

1. **Freeze.** Stop further deploys and configuration approvals. Open the
   [incident response](incident-response.md) record if symptoms are user-facing.
2. **Identify the last known-good artifact.** Use the release record: the
   promoted tag and its smoke-tested digest. Promote by digest, not by a floating
   tag; the repository records the digest in release notes and states that tags
   are immutable ([README-SETUP.md](../../../README-SETUP.md)).
3. **Deploy the known-good digest through the approved path.**
   `NOT YET IN PLACE:` a deploy or rollback command in this repository.
   `infra/DEPLOYMENT.md` is a design handoff that asks for health-based routing,
   connection draining and promotion of the same digest, but supplies no
   executable rollback. The environment operator must perform the step and
   record it; do not invent a command or claim automation that does not exist.
4. **Handle migrations carefully.** A rollback of application code does not
   undo an applied Flyway migration. Schema changes follow expand/contract and
   the change-safety rules in
   [decision 0002](../../decisions/0002-ops06-migration-change-safety.md) and
   `scripts/check-migrations.py`. If a contract step already ran, do not blindly
   roll back the schema; use roll-forward below or the
   [restore procedure](../recovery/restore-procedure.md) under authorization.
5. **Verify the rollback.** Repeat the probes and log checks in "How to tell":
   `GET /health/live` and `GET /health/ready` UP, error rate and p95 back within
   OPS-03, protected sign-in and the affected clinical flow working with
   synthetic verification. Confirm the running digest is the known-good one.
6. **Reconcile data.** A rollback can replay work. Reconcile in-flight deliveries
   by immutable event id and per-aggregate version, and prevent a second order,
   result, release or payment entry (ARC-05). Apply the duplicate-prevention and
   revocation gates in the
   [recovery drill runbook](../recovery/runbook.md) before writes resume if a
   restore was involved.
7. **Confirm clinical integrity.** The site clinical coverage owner confirms the
   affected chart, order and result data before clinical use resumes.

## How to roll forward

Use roll-forward when rollback is unsafe, for example after a contract migration
or when the old artifact is no longer promotable.

1. Diagnose the cause with the recorded correlation IDs and the failing gate.
2. Write a failing test for the defect, apply the smallest fix and run the
   scoped checks (`scripts/check.sh`, the module `check.sh`, and the relevant
   coverage gate at 100%). Never lower a threshold or skip a test.
3. Produce a new versioned artifact from a green commit and the exact SHA's
   Delivery gate; do not rebuild or retag an existing artifact.
4. Deploy through the approved path and verify with the same checks as a
   rollback.
5. `NOT YET IN PLACE:` the 5% / 25% / 100% progressive rollout and automatic
   rollback on error-rate or latency breach (WEB-11). Until that tooling exists,
   record the staged exposure manually and treat the lack of automation as an
   open risk, not as a completed gate.

## What to tell users

- Use the site-approved status or maintenance channel; do not send clinical
  detail, a patient identifier or a download URL in a notification. Patient
  notifications carry generic text and details are retrieved only after
  authentication (PAT-01, MSG-05).
- Say what is affected and what users should do (for example, use the approved
  paper/manual capture process and expect supervised back-entry), without
  exposing patient data. The four downtime topics are in
  [operational monitoring and runbooks](../recovery/operational-runbooks.md).
- Record each message, its UTC time, the channel and who approved it.
- `NOT YET IN PLACE:` a hosted status/maintenance page and support page (WEB-13);
  the launch checklist row is open. Use the approved out-of-band channel and
  record the gap.

## How to check it worked

- Probes UP, logs within OPS-03 targets, sign-in and the affected flow working,
  running digest matches the promoted artifact, no duplicate work created.

## What to record

- Record the UTC detection, decision and recovery instants, symptoms and
  correlation IDs, tag and digest rolled back from and to, migration state,
  commands with final lines, who approved and who performed each action, user
  communications, failed steps and follow-ups. Mark a rehearsal as a rehearsal.

## Related material

[Runbook index](index.md) · [Incident response](incident-response.md) ·
[Secret and key rotation](key-rotation.md) ·
[Deployment design handoff](../../../infra/DEPLOYMENT.md) ·
[Release and delivery setup](../../../README-SETUP.md) ·
[Production readiness checklist](../production-readiness/launch-checklist.md)
