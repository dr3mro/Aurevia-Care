# XCH-05 clause 4 — delivery and patient release do not satisfy clinician acknowledgment

## Clause

Delivery and patient release do not satisfy clinician acknowledgment.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.exchange.clauses.DeliveryPatientReleaseDoSatisfyClinicianAcknowledgmentTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/DeliveryPatientReleaseDoSatisfyClinicianAcknowledgmentTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants:

- `patientReleaseAndDeliveryLeaveTheResultAwaitingClinicianAcknowledgment` — after a
  delivered escalation and a patient release the result is still `awaiting_ack` at
  version 3, with `patient_released` but no `acknowledged` event and no
  acknowledgment audit; an attempted acknowledgment before read-back is refused
  `READ_BACK_REQUIRED`. Only a verbatim read-back plus an authorized clinician's
  acknowledgment advances the state to `acknowledged` at version 4, with the
  `acknowledged` event bound to version 3 and a `CRITICAL_ACKNOWLEDGE` audit.
- `acknowledgeWithoutADeliveredEscalationIsRejected` — acknowledgment without any
  escalation attempt is refused `409 NO_DELIVERED_ATTEMPT` (checked before the
  read-back guard) and leaves the result `awaiting_ack` at version 3 with no
  acknowledgment event or audit.
- `anAdministratorWhoReleasesCannotAcknowledge` — the tenant administrator may
  release (`200`, unchanged state/version and a `patient_released` event) but may
  not acknowledge (`403 FORBIDDEN`); the responsible clinician's acknowledgment
  still succeeds and is attributed to the clinician.
- `aClinicianOfAnotherTenantCannotAcknowledgeTheReleasedAlert` — a clinician of
  the second tenant is refused at `403 FORBIDDEN` when naming the first tenant as
  active scope (no active membership) and at `404 NOT_FOUND` when using its own
  scope (forced RLS conceals the row); the first tenant's result stays
  `awaiting_ack` at version 3 and remains acknowledgeable by its own clinician.

Production classes exercised through the entry point
`POST /api/v1/critical-results/...` and `GET /api/v1/critical-results/{id}`:
`com.aurevia.exchange.critical.CriticalResultController` (thin HTTP
adapter with `If-Match` version preconditions),
`com.aurevia.exchange.critical.CriticalResultService` (`acknowledge`
requires a delivered attempt and a separate read-back before the state change;
`release` appends `patient_released` and returns the unchanged result),
`com.aurevia.exchange.critical.CriticalActions` (`RELEASE` is limited
to `tenant_admin`, `ACKNOWLEDGE` to `clinician`),
`com.aurevia.exchange.critical.CriticalResultRepository`,
`com.aurevia.exchange.critical.CriticalContract` (distinct `State` and
append-only `EventType` facts), `com.aurevia.web.TenantContextInterceptor`
and `com.aurevia.tenant.TenantContext`, with tenant RLS and FORCE RLS
supplied by the Flyway migrations under `db/migrations`. These routes are listed
in `contracts/openapi.yaml` (pre-existing, out of this story's scope).

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=DeliveryPatientReleaseDoSatisfyClinicianAcknowledgmentTest test`
— `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
