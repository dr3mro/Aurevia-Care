# MSG-07 clause 2 — apply configured retention, export and legal holds

## Clause

Apply configured retention, export and legal holds.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.ApplyConfiguredRetentionExportLegalHoldsTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/ApplyConfiguredRetentionExportLegalHoldsTest.java`).
It uses the Spring MVC pipeline with a standalone MockMvc setup of the real controller, service,
action matrix and tenant-scoped test store, the real `TenantContextInterceptor` under the
`synthetic-auth` profile and the real `ApiErrorHandler`, with a fixed clock and synthetic
identifiers. A 30-day policy is seeded with resources created 40, 31 and 10 days before the fixed
instant; no database setup is needed because the test uses the test-only in-memory store.

- `retentionHoldAndExportAreAppliedThroughTheGovernanceApi` (rule met, HTTP) — as a
  privacy-auditor in the active organization, `POST /api/v1/messaging-governance/legal-holds`
  returns 201 with a `Location`; `GET /api/v1/messaging-governance/retention/{id}` then reports
  `LEGAL_HOLD` for the held resource, `ELIGIBLE` for the past-window unheld resource and
  `RETENTION_WINDOW` for the recent resource; `POST /api/v1/messaging-governance/exports` returns
  201 with a `Location` and the validated source, revision and purpose; `GET /api/v1/messaging-governance/audit`
  contains the `hold_place`, `audit_read` and `export` facts.
- `patientCannotPlaceHoldOrCreateExport` (negative, HTTP) — a patient role is refused 403
  `FORBIDDEN` on both commands, no hold is created and every audit fact recorded for the denial
  has the `denied` outcome.
- `retentionDecisionForAnotherOrganizationsResourceIsConcealed` (negative, HTTP) — a principal
  with an active privacy-auditor membership in a different organization receives 404 `NOT_FOUND`
  for a resource owned by the first organization. The owning organization's audit trail is
  untouched; the other organization records a single `not_found` fact.
- `exportWithoutProvenanceIsRefused` (negative, HTTP) — an export without provenance is refused
  422 `INVALID_PROVENANCE` and no audit fact is fabricated.
- `heldResourceSurvivesRetention` (forbidden state) — after placing the hold over HTTP, the real
  `MessagingGovernanceService.applyRetention` is invoked with the tenant context rebound and
  returns only the past-window unheld resource; the held and in-window resources are excluded and
  a single `retention_apply` fact names the eligible resource. This last assertion is made at the
  service boundary because no HTTP route publishes retention application (see Gap).

Production classes and files exercised by these requests:

- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceController` — thin adapter for
  the governance routes under `/api/v1/messaging-governance`; only hold placement, retention
  decisions and export creation are reached over HTTP by this clause.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceService` — `placeHold`,
  `retentionDecision`, `applyRetention`, `createExport`, `validatedProvenance`, `isHeld`,
  `authorize`, `deny` and `append`; derives actor, organization, outcome and timestamps
  server-side.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceActions` — the role matrix that
  reserves holds, exports and retention decisions for privacy and administrative roles.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceInputs` — allowlisted scope,
  purpose, revision and UUID validation before any durable record is written.
- `com.aurevia.smartclinic.messaging.governance.MessagingAuditContract` — the `RetentionPolicy`,
  `MessagingResource`, `LegalHold`, `RetentionDecision`, `ExportProvenance`, `ExportRecord`,
  `AuditAction` and `Outcome` records and enums; all non-sensitive governance facts.
- `com.aurevia.smartclinic.messaging.governance.MessagingGovernanceStore` and its test-only
  `InMemoryMessagingGovernanceStore` implementation; production uses
  `JdbcMessagingGovernanceStore` over the forced-RLS tables in
  `db/migrations/V62__messaging_governance.sql`.
- `com.aurevia.smartclinic.web.TenantContextInterceptor` and `com.aurevia.smartclinic.api.ApiErrorHandler`
  — tenant derivation and the uniform error contract through which the negative cases are
  observed.

## Gap

The decision and command surface of the clause is built and proven, but retention *execution* is
not end to end. `MessagingGovernanceService.applyRetention` computes eligible identifiers and
appends a `retention_apply` audit fact; it performs no removal, has no HTTP route and has no
scheduled or worker caller anywhere in `apps/api/src/main/java`. This is already recorded as
`GAP: Implement and test a scheduled retention job that respects legal holds and records deletion
evidence` (OPS-07) in `docs/security/threat-model.md`. A held resource is therefore protected from
the eligibility decision, but this repository cannot yet prove that a job actually leaves it in
place while deleting an eligible resource and recording deletion evidence.

Closing that gap would change these files: a scheduled executor (new class under
`apps/api/src/main/java/com/aurevia/smartclinic/messaging/governance` with scheduling enabled in
the application configuration), a tenant-scoped removal operation in
`MessagingGovernanceStore` and `JdbcMessagingGovernanceStore` with a narrow `DELETE` grant and an
append-only deletion-evidence audit fact in a new Flyway migration following V62, a restricted
trigger or route in `MessagingGovernanceController` for operator verification, the matching
entries in `contracts/openapi.yaml` with a regenerated client, and an HTTP/integration test for
the held-versus-eligible deletion outcome. That is one cross-boundary backend/database/contract
task plus one operations evidence task: **two separate tasks**. The OpenAPI gap affects all nine
governance routes (already listed in `contracts/openapi/missing-routes.txt`) and is adjacent
context, not a new ask of this evidence task.

This evidence task added only the test class and this document. It changed no production code, no
existing test, no migration and no contract. No skipped test was added: the missing execution path
is an implementation gap outside this task's no-production-code scope, already tracked as OPS-07.
