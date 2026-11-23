# MSG-06 clause 4 — API test evidence

## Clause

Outside hours, show when the unit next handles messages.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.OutsideHoursShowUnitNextHandlesMessagesTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/OutsideHoursShowUnitNextHandlesMessagesTest.java`).
All five tests are active (none are disabled) and use synthetic data only.

- `outsideHoursReturnsTheNextHandlingInstantForAConnectedPatient` — positive proof.
  With an approved FRIDAY 09–17 UTC profile and a fixed Thursday clock, a connected
  patient reads `GET /api/v1/care-units/{unitId}/availability` through
  `ServiceProfileService.availability`. The test asserts `open=false`,
  `nextHandlingAt=2026-11-20T09:00:00Z`, `timeZone=UTC`, and a `SUCCESS`
  `AVAILABILITY_VIEW` audit row.
- `availabilityRefusesARoleWithoutTheAvailabilityViewAction` — negative proof of role.
  An active `reception` member lacks the `AVAILABILITY_VIEW` action and is refused
  with HTTP 403 `FORBIDDEN`; the denial is audited as `DENIED` with no availability
  projection returned.
- `availabilityConcealsAUnitForAnotherTenant` — negative proof of tenant isolation.
  A second tenant has an active member with a connected chart to the same local
  unit identifier, so authorization and unit access pass; the read is still
  refused with HTTP 404 `NOT_FOUND` because no approved profile is owned by that
  tenant. The denial is audited as `NOT_FOUND` in the other tenant, proving a
  cross-tenant reader cannot borrow the originating tenant's profile.
- `availabilityRefusesAPatientWithoutAnActiveUnitConnection` — negative proof of the
  connection guard. A patient whose connection to the unit has been revoked is
  refused with HTTP 404 `NOT_FOUND`; the denial is audited as `NOT_FOUND`.
- `availabilityIsConcealedWhenNoApprovedEffectiveProfileExists` — negative proof of
  the effective-profile guard. A connected unit with no approved effective profile
  is refused with HTTP 404 `NOT_FOUND`; the denial is audited as `NOT_FOUND`.

This class is clause-focused service-level evidence. The end-to-end HTTP proof that
the availability route is served through the real controller and durable store is
the existing `com.aurevia.smartclinic.it.messaging.ServiceProfileHttpIntegrationTest`,
which asserts `200` on `GET /api/v1/care-units/{unitId}/availability`. The thin
controller delegation is exercised by that integration test and by the route in
`ServiceProfileController`.

Production classes exercised through the normal read entry point
(`ServiceProfileService.availability`):

- `com.aurevia.smartclinic.messaging.service.ServiceProfileService` — re-checks
  membership, the `AVAILABILITY_VIEW` action, unit access, the effective named-approved
  profile and the audited denial/success pair on every read.
- `com.aurevia.smartclinic.messaging.service.ServiceHoursCalculator` — computes the
  zone-aware `ServiceAvailability(false, nextHandlingAt, timeZone)` from the published
  service hours at the current instant.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileActions` — grants
  `AVAILABILITY_VIEW` to `patient`, `tenant_admin`, `privacy_auditor` and
  `unit_supervisor`, and withholds it from `reception`.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileContract.ServiceAvailability`
  — the immutable availability projection; `nextHandlingAt` is the absolute instant
  the unit next handles messages.
- `com.aurevia.smartclinic.messaging.service.ServiceProfileController` — the thin HTTP
  adapter exposing `GET /api/v1/care-units/{unitId}/availability`; its real HTTP
  persistence proof is `ServiceProfileHttpIntegrationTest`.

Neighbouring implementations observed in the source survey (not changed by this task):
the existing unit proof in `ServiceProfileServiceTest` and `ServiceHoursCalculatorTest`,
the real-PostgreSQL HTTP proof in `ServiceProfileHttpIntegrationTest`, the Jdbc store and
the Flyway migrations under `db/migrations`. The route is served by `ServiceProfileController`,
but the full published OpenAPI contract does not yet carry it: it is listed in
`contracts/openapi/missing-routes.txt`. Contract publication is out of scope for this
test-only task.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not
change production code, an existing test, the contract or a migration.
