# MSG-05 clause 6 — Use generic push notifications and retrieve full content only after authentication

## Clause

Use generic push notifications and retrieve full content only after authentication.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.messaging.clauses.UseGenericPushNotificationsRetrieveFullContentTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/messaging/clauses/UseGenericPushNotificationsRetrieveFullContentTest.java`),
an active MockMvc plus synthetic-port test. No test is skipped and no production
code, contract or migration was changed.

- `genericAppointmentUpdateCarriesNoClinicalDetail` (rule met) — captures the
  `GenericPushNotice` actually handed to `PushGateway.send` by both
  `PatientAppointmentService.confirm` and `PatientAppointmentService.notifyStatusChange`,
  then asserts it equals `GenericPushNotice.appointmentUpdate()` and that
  `GenericPushNotice.reveals` is false for a synthetic patient, facility,
  clinician, start time and status. It also asserts the body has no digits and
  the deep link carries no appointment identifier.
- `unauthenticatedContentRetrievalIsRejectedBeforeAnyRead` (negative) — a
  `GET /api/v1/patient/appointments` and a `POST /api/v1/patient/appointments/{id}/notify`
  without `X-Synthetic-Subject` both answer HTTP 401 `UNAUTHENTICATED`, raised by
  `TenantContextInterceptor.preHandle` before the controller or any store runs.
- `unlinkedSubjectIsDeniedFullContent` (negative) — an authenticated but
  unlinked `X-Synthetic-Subject` gets HTTP 403 `FORBIDDEN`; the service-level
  companion asserts an `AuditSink.append(..., "DENIED", ...)` fact and that the
  `PatientAppointmentStore` and `PushGateway` boundaries are never touched.

Production classes exercised, all in `com.aurevia.smartclinic.messaging.appointments`
(the PAT-01/OPS-02 patient appointment slice, which owns the only generic push
payload in the repository): `GenericPushNotice`, `PatientAppointmentService`
(`authorize`, `dispatch`, `confirm`, `notifyStatusChange`, `listAppointments`),
`PatientAppointmentController` (its `subject` guard and list route are reached
for the 403 unlinked case; the 401 cases are rejected by the interceptor before
any controller route, and `confirm`/`notifyStatusChange` are proven at the
service layer only), and the ports `PushGateway`, `DeviceTokenStore`, `VerifiedPatientLink`,
`PatientAppointmentStore` and `AuditSink`. Cross-boundary classes:
`com.aurevia.smartclinic.web.TenantContextInterceptor`,
`com.aurevia.smartclinic.api.ApiErrorHandler` and
`com.aurevia.smartclinic.tenant.TenantContext`.

Scope of the claim: the generic-push-and-authenticated-retrieval pattern is
proven for those appointment classes only. MSG-05's own secure patient-to-unit
communication flow (`messaging.instructions`, `messaging.conversations`,
`messaging.acknowledgment`) has no `PushGateway` call and no generic-notice
dispatch, so this document does not claim the whole MSG-05 workflow is proven.

Verified locally (tool evidence, final line of the Surefire report):

```
mvn -q -B -f apps/api/pom.xml -Pno-security \
  -Dtest=UseGenericPushNotificationsRetrieveFullContentTest test
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

## Gap

Generic push and content-after-authentication exist only in the patient
appointment slice. MSG-05's message and instruction flow records a notification
as a ledger fact (`InstructionContract.FactType.DISPATCHED` — "notification
dispatch was requested; delivery is a separate fact") and
`db/migrations/V33__conversation_lifecycle.sql` explicitly excludes delivery,
notification and first view from its slice. There is no generic notice factory,
no `PushGateway` dispatch and no contract entry on the instruction, message or
acknowledgment routes, so a published instruction or message does not emit a
push and the authenticated full-content read for that flow is not proven here.

Closing the gap needs two implementation tasks plus one test-enablement task:

1. Add a generic notice factory and a push dispatch boundary to instruction and
   message publication, with a Flyway migration for the dispatch/outbox fact,
   forced row-level security, OpenAPI entries and a regenerated client. Keep the
   payload free of clinical detail, as `GenericPushNotice` does.
2. Gate instruction and message full-content reads on authentication and a
   verified patient-unit connection in the service and controller, with the
   matching HTTP negative tests (401 without a subject, 403 for an unlinked
   subject).
3. Enable clause-level tests that capture the dispatched notice for the
   message/instruction flow and re-run this document's evidence against them.

This test-only story does not change production code, migrations or the
contract; the gap is recorded here in prose and no test is left skipped.
