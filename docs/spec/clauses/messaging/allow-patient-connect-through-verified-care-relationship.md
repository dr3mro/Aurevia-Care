# MSG-01 clause 2 — API test evidence

## Clause

Allow a patient to connect through a verified care relationship, an order, referral, prescription or a unit-issued invitation tied to one of these relationships.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.messaging.clauses.AllowPatientConnectThroughVerifiedCareRelationshipTest`
(path: `apps/api/src/test/java/com/aurevia/messaging/clauses/AllowPatientConnectThroughVerifiedCareRelationshipTest.java`).
All 16 invocations are active (none disabled) and use synthetic data only. The class drives the
real `POST /api/v1/patient-unit-connections` route over HTTP with
`@SpringBootTest(RANDOM_PORT)` and the `synthetic-auth` profile against a dedicated
Flyway-migrated PostgreSQL database. It uses the production `JdbcMessagingStore` — there is no
`@Primary InMemoryMessagingStore` override — so forced row-level security and the tenant fold
functions are part of the proof.

- `connectsPatientThroughEachVerifiedRelationshipContext` — parameterized over all five
  `ConnectionContext` wire values (`care_relationship`, `order`, `referral`, `prescription`,
  `invitation`). Each returns HTTP 201 with an `active` connection whose organization, patient,
  unit, context, reference, relationship id and verifier come from the server, never from the
  request body; the `Location` header matches the created id; and exactly one connection row
  exists for the reference. The `invitation` invocation additionally asserts that the
  single-use relationship is consumed.
- `replayOfTheSameVerifiedReferenceReturnsTheOriginalConnection` — an identical second POST
  returns the original connection id and leaves one row (idempotent retry, no parallel
  conversation).
- `connectWithAConflictingOrInactiveReferenceIsRefused` — a prior connection with a different
  context, or one already revoked, makes the same reference answer HTTP 409 `CONFLICT`.
- `connectWithAnUnusableRelationshipIsRefused` — parameterized over inactive, consumed and
  expired relationship fixtures; each answers HTTP 422 `NO_ELIGIBLE_RELATIONSHIP`.
- `connectWithoutAnEligibleVerifiedRelationshipIsRefused` — an unverified reference answers
  HTTP 422 `NO_ELIGIBLE_RELATIONSHIP`.
- `connectForAnotherTenantsOrganizationIsRefused` — a request bound to a foreign organization
  answers HTTP 404 `NOT_FOUND`, and a tenant-bound SQL read confirms no connection row was
  written in either tenant.
- `connectWithAWrongRoleIsRefused` — an active member without the patient role answers HTTP 403
  `FORBIDDEN`.
- `connectForAnUnverifiedChartIsRefused` — an active patient account with no verified chart link
  answers HTTP 403 `NO_VERIFIED_CHART`.
- `connectForAnotherVerifiedChartIsRefused` — a patient that exists but is not linked to the
  caller answers HTTP 403 `FORBIDDEN`.
- `productionMessagingStoreIsTheJdbcAdapter` — asserts the HTTP route is wired to the real
  `JdbcMessagingStore`, not an in-memory fake.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.messaging.units.CareUnitController` — the thin adapter that owns
  `POST /api/v1/patient-unit-connections` and maps the created connection to `Location`.
- `com.aurevia.messaging.units.CareUnitDirectoryService` — `connect`, `authorize`,
  `verifiedCharts`, `isUsable` and `deny`: server-derived identities, the five-context rule,
  single-use invitation consumption, idempotent replay, conflict and concealment decisions.
- `com.aurevia.messaging.units.MessagingActions` and `MessagingInputs` — the action
  matrix (patient only for `CONNECTION_CREATE`) and the trust-boundary field validation.
- `com.aurevia.messaging.units.MessagingContract` — `ConnectionContext`,
  `ConnectRequest`, `EligibleRelationship` and `PatientUnitConnection`.
- `com.aurevia.messaging.units.JdbcMessagingStore` — the tenant-guarded PostgreSQL
  adapter for units, relationships, connections and audit facts.
- `com.aurevia.web.TenantContextInterceptor` — derives tenant and subject from the
  synthetic headers, so every read and write carries the caller's organization.
- `db/migrations/V60__care_units.sql` — the tenant-owned `care_unit`, `eligible_relationship`,
  `patient_unit_connection` and `messaging_audit` tables with row-level security and forced
  row-level security.

## Gap

None. All five contexts and every refusal boundary named in the clause are implemented and
proven at the HTTP entry point. An invitation is modeled as an independently verified, single-use
`eligible_relationship` row whose `context` is `invitation`; it is not a foreign key to a separate
parent relationship. That modelling nuance is not a missing behavior at this route and needs no
production change for this test-only story. The pre-existing OpenAPI missing-routes entry for the
patient-unit-connection routes is unrelated legacy debt and outside this task's scope.
