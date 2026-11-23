# XCH-05 clause 4 — delivery and patient release do not satisfy clinician acknowledgment

## Clause

Delivery and patient release do not satisfy clinician acknowledgment.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.DeliveryPatientReleaseDoSatisfyClinicianAcknowledgmentTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/DeliveryPatientReleaseDoSatisfyClinicianAcknowledgmentTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants.
Each test registers a synthetic critical result and drives the real entry points
`POST /api/v1/critical-results/{id}/escalate`, `.../release`, `.../read-back`,
`.../acknowledge` and `GET /api/v1/critical-results/{id}`, carrying the
`If-Match` precondition as a plain integer string, then reads the append-only
event trail through the same API.

- `deliveryAndReleaseLeaveResultAwaitingUntilClinicianAcknowledges` — the rule
  being met. After a delivered escalation attempt the trail contains an
  `escalation_attempt` with outcome `delivered` at revision 3 and zero
  `acknowledged` events; after the patient release it contains a
  `patient_released` event at revision 3 and still zero `acknowledged` events,
  with the result at `awaiting_ack`, version 3. Only a clinician read-back plus
  acknowledgment at revision 3 then returns 200, moves the result to
  `acknowledged` at version 4, and appends exactly one `acknowledged` event at
  revision 3 with the clinician's actor id and one `CRITICAL_ACKNOWLEDGE` audit
  event.
- `deliveryWithoutReadBackCannotAcknowledge` — negative: delivery without the
  required verbatim read-back is refused with 409 `READ_BACK_REQUIRED`; state
  stays `awaiting_ack` with no acknowledgment event.
- `patientReleaseWithoutDeliveryCannotAcknowledge` — negative: a release with no
  delivered attempt leaves the result `awaiting_ack`, and acknowledgment is then
  refused with 409 `NO_DELIVERED_ATTEMPT`.
- `clinicianAcknowledgmentRequiresClinicianRole` — negative: a tenant_admin's
  acknowledgment is refused with 403 and a clinician's release is refused with
  403; no acknowledgment event is appended.
- `anotherTenantCannotAcknowledge` — negative: a clinician of the other active
  organization is refused with 404 (the result is concealed by forced RLS) and
  the original tenant's result is unchanged.
- `staleVersionAcknowledgmentIsRefused` — negative: a stale `If-Match` (1 against
  version 3) is refused with 412 `STALE_VERSION` and nothing is mutated.

Production classes exercised by these entry points:

- `com.aurevia.smartclinic.exchange.critical.CriticalResultController` — binds the
  critical-result routes and parses the `If-Match` precondition through
  `com.aurevia.smartclinic.api.IfMatch`.
- `com.aurevia.smartclinic.exchange.critical.CriticalResultService` —
  `acknowledge` requires a delivered attempt, then a verbatim read-back where
  required, and binds `ACKNOWLEDGED` to the exact revision; `release` appends
  `PATIENT_RELEASED` and returns the result unchanged.
- `com.aurevia.smartclinic.exchange.critical.CriticalActions` — `ACKNOWLEDGE` is
  clinician-only and `RELEASE` is tenant_admin-only.
- `com.aurevia.smartclinic.exchange.critical.CriticalResultRepository` — the
  RLS-bounded SQL ledger; `hasDeliveredAttempt` and `appendEvent` keep delivery,
  read-back, acknowledgment and release as separate facts.
- `db/migrations/V23__critical_results.sql` — `critical_result` and
  `critical_result_event` with RLS, FORCE RLS and tenant policies; the append-only
  event trigger and the state-transition guard permit `awaiting_ack` to
  `acknowledged` only through the clinical path.

This class proves clause 4 only and adds an explicit no-`acknowledged`-event
assertion after each fact, event revision and actor bindings, and the negative
matrix to the existing proof in
`com.aurevia.smartclinic.it.exchangeresult.CriticalResultFlowTest.deliveryAndPatientReleaseNeverAcknowledge`.
The other XCH-05 clauses have their own acceptance suites and are not re-tested
here.

## Gap

None.
