# MSG-05 clause 3 — API test evidence

## Clause

Track overdue requested acknowledgments and route them to the owning unit for follow-up.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.TrackOverdueRequestedAcknowledgmentsRouteThemOwningTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/TrackOverdueRequestedAcknowledgmentsRouteThemOwningTest.java`).
All seven tests are active (none disabled). The suite recreates its own PostgreSQL
database (`smartclinic_overdue_followups_it`) from the authoritative Flyway migrations,
seeds two isolated tenants with synthetic memberships, roles, a verified patient chart,
an active patient-unit connection and owning-unit staff assignments, and drives the real
HTTP entry point under the `synthetic-auth` profile with `X-Synthetic-Subject` and
`X-Active-Organization` headers. All fixtures are synthetic; no patient data or
credentials are used.

- `routesAnOverdueRequestedAcknowledgmentToTheOwningUnit` — positive proof. Publishing one
  revision with `requiresAcknowledgment=true` and a past `acknowledgmentDueAt` makes
  `GET /api/v1/instructions/overdue-follow-ups?unitId=...` return exactly one `FollowUp`
  with the expected `instructionId`, `revision`, `unitId` and wire state `routed`. A second
  read is idempotent. An explicit patient acknowledgment removes it from the open queue.
- `doesNotRouteWhenAcknowledgmentWasNotRequested` — negative proof of the request condition.
  An overdue instruction that did not request acknowledgment is never routed; a follow-up row
  seeded directly for that revision is still hidden by the service's defensive
  `requiresAcknowledgment` re-check.
- `doesNotListFollowUpForAnotherUnit` — negative proof of unit scoping. Two owning-unit staff
  in the same tenant each publish an overdue instruction. Each sees only its own unit's
  follow-up; the other unit's queue is shown to be non-empty, so the exclusion is the unit
  filter and not an empty store.
- `doesNotExposePeerTenantInstruction` — negative proof of tenant isolation. The same local
  instruction id and unit id are published in both tenants at different revisions; each
  tenant sees only its own revision under forced row-level security. A unit owned only by the
  peer tenant is refused to ORG_A staff with HTTP 403 and code `FORBIDDEN`.
- `refusesFollowUpListToWrongRoleOrUnassignedUnit` — negative proof of authorization. A
  `reception` role without `FOLLOW_UP_LIST` and a `clinician` not assigned to the requested
  unit are each refused with HTTP 403 and code `FORBIDDEN`.
- `refusesMissingOrInvalidUnitId` — negative proof of input validation. An empty or malformed
  `unitId` is refused before any tenant access with HTTP 422 and code `INVALID_UNITID`.
- `hidesFollowUpOnceAcknowledgedResolvedOrSuperseded` — negative proof of the open-state
  filters. A routed follow-up is hidden once the patient acknowledges it, once an authorized
  reviewer resolves it with a reason, and once a newer revision supersedes it.

Production classes exercised through the entry point and its tenant-guarded transaction:

- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactController` — thin adapter
  binding `POST /api/v1/instructions/publish`, `POST /api/v1/instructions/acknowledge`,
  `POST /api/v1/instructions/follow-up/resolve` and `GET /api/v1/instructions/overdue-follow-ups`.
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactService` — `overdueFollowUps`
  authorizes the reader, creates one `ROUTED` follow-up per overdue candidate and filters the
  open queue by unit, state, acknowledgment, request flag and latest revision; `publish`,
  `acknowledge` and `resolveFollowUp` supply the state transitions the queue observes.
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFactActions` and
  `InstructionFactInputs` — bound the `FOLLOW_UP_LIST` action to owning-unit staff roles and
  validate the `unitId` at the trust boundary.
- `com.aurevia.smartclinic.messaging.acknowledgment.InstructionFacts` — immutable `FollowUp`,
  `PublishCommand`, `AcknowledgeCommand` and `ResolveCommand` records and the `FollowUpState`
  wire values (`routed` on the wire for `ROUTED`).
- `com.aurevia.smartclinic.messaging.acknowledgment.JdbcInstructionFactStore` — reads and writes
  the V63 tables inside the guarded transaction; `overdueCandidates` selects only latest,
  unacknowledged, unrouted revisions whose requested due instant is in the past.
- `com.aurevia.smartclinic.api.ApiErrorHandler` and
  `com.aurevia.smartclinic.web.TenantContextInterceptor` — stable error codes and the
  synthetic subject/organization binding used by every request.
- `db/migrations/V63__instruction_facts.sql` — owns `instruction_fact_instruction`,
  `instruction_fact_acknowledgment`, `instruction_fact_follow_up` and
  `instruction_fact_unit_assignment` with row-level security, FORCE ROW LEVEL SECURITY,
  tenant-composite keys and append-only triggers. The database is rebuilt from all
  migrations before the suite, so the proof also covers the migration shape.

The existing unit and HTTP suites `InstructionFactServiceTest`,
`InstructionFactControllerHttpTest` and `JdbcInstructionFactStoreIntegrationTest` cover the
same behavior at their own layers; this task adds a focused, clause-only HTTP proof and
changes neither.

## Gap

None for the routing rule: it is built and proven by active HTTP tests through the normal
entry point. This task added proof only; it did not change production code, an existing
test, the OpenAPI contract or a migration.

One adjacent, non-blocking debt is recorded for completeness:
`GET /api/v1/instructions/overdue-follow-ups` is listed in
`contracts/openapi.yaml` as a baselined route, so the follow-up view is not yet
published in the authoritative OpenAPI contract. Publishing that route (and regenerating the
staff client) is a separate cross-cutting contract task under WEB-05, outside this clause's
test-only scope and not required to prove the routing rule.
