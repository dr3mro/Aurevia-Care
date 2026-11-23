# ARC-05 clause 7 — API test evidence

## Clause

Confirm order and patient identifiers before linking external data.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.events.clauses.ConfirmOrderPatientIdentifiersBeforeLinkingExternalTest`
(path: [ConfirmOrderPatientIdentifiersBeforeLinkingExternalTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/events/clauses/ConfirmOrderPatientIdentifiersBeforeLinkingExternalTest.java)).
All twelve tests are active, use synthetic UUID fixtures and a fixed clock, and share the synthetic
[InMemoryDeliveryStore.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/events/InMemoryDeliveryStore.java).
No production code, existing test, contract or migration was changed by this note.

- `clinicianLinksExternalDataAfterAnAcceptedReceiptConfirmsBothIdentifiers` — positive proof. A clinician
  consumes an accepted event for (ORDER, PATIENT) and links it; the returned `ExternalLink` carries the
  event id, order id, patient id, target aggregate, author and fixed instant, and the append-only
  `LINK_EXTERNAL` audit fact records `SUCCESS` and the request correlation id.
- `integrationAdminMayAlsoLinkAfterConfirmation` — proves the second permitted role of the link action.
- `controllerLinkExternalBindsTheCommandAndEnforcesTheSameConfirmation` — direct controller-to-service
  trace: `DeliveryController.linkExternal` returns the confirmed link for a clinician and still refuses a
  worker with HTTP 403, so the thin adapter neither re-implements nor bypasses the gate.
- `externalLinksRouteLinksOnlyAnAuthorizedClinicianAfterConfirmation` — HTTP entry-point proof through
  MockMvc with the real controller, `ApiErrorHandler` and `TenantContextInterceptor`: `POST /api/v1/delivery/external-links`
  returns 200 with the confirmed link for a clinician and 403 with error code `FORBIDDEN` for a worker.
- `workerIsDeniedBeforeAnyReceiptLookup` — negative proof of role and of ordering. A worker is refused with
  403 `FORBIDDEN` and a `DENIED` audit even when a perfectly linkable receipt exists, and also when the
  external event does not exist, because authorization runs before the receipt lookup.
- `linkExternalConcealsAnUnprocessedExternalEvent` — negative proof of the accepted-receipt guard: an
  unknown external id is concealed as 404 `NOT_FOUND`.
- `linkExternalRejectsAnOrderIdThatDiffersFromTheReceipt` — negative proof of order-identifier equality.
- `linkExternalRejectsAPatientIdThatDiffersFromTheReceipt` — negative proof of patient-identifier equality.
- `linkExternalRejectsAReceiptWithRejectedOutcome` — a `REJECTED` receipt is not linkable: both the
  consume-produced case (missing order) and a seeded receipt whose order and patient both exist are
  refused, so the outcome guard is the only reason the second link fails.
- `linkExternalConcealsAReceiptOwnedByAnotherTenant` — negative proof of tenant isolation in both
  directions: a receipt consumed in the active tenant is invisible from another tenant's clinician, and a
  receipt seeded in another tenant is invisible from the active tenant's clinician; both refusals are
  audited in the caller's tenant with `NOT_FOUND`.
- `linkExternalRejectsReceiptWhenTheConfirmedOrderNoLongerExists` — an `ACCEPTED` receipt naming an order
  that is absent in the active tenant is refused; the existence check is re-run at link time.
- `linkExternalRejectsReceiptWhenTheConfirmedPatientNoLongerExists` — the same re-check for the patient.

The requirement and its boundary are defined in [SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md),
section 7, row ARC-05.

Production classes exercised through the normal command entry point:

- [DurableEventService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/events/DurableEventService.java)
  — `linkExternal` (receipt lookup, `ACCEPTED` outcome, order and patient equality, tenant-scoped existence
  re-check, 404 concealment, link write and audit), `consume` (produces the confirmed or rejected receipt).
- [DeliveryActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/events/DeliveryActions.java)
  — grants `LINK_EXTERNAL` only to `clinician` and `integration_admin`.
- [DeliveryStore.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/events/DeliveryStore.java)
  and [JdbcDeliveryStore.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/events/JdbcDeliveryStore.java)
  — `receiptById`, `orderExists`, `patientExists` and `putLink`; the JDBC adapter runs inside a
  tenant-guarded transaction under forced row-level security.
- [DeliveryController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/events/DeliveryController.java)
  — thin HTTP adapter for `POST /api/v1/delivery/external-links`.
- [DurableEventContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/events/DurableEventContract.java)
  — the immutable `ExternalLink`, `ConsumerReceipt`, `LinkRequest` and audit records.
- [V55__durable_event_outbox.sql](../../../../db/migrations/V55__durable_event_outbox.sql) — creates
  `delivery_receipt` and `delivery_link` with forced row-level security.
- [TenantContextInterceptor.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/web/TenantContextInterceptor.java)
  and [ApiErrorHandler.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/api/ApiErrorHandler.java)
  — bind the synthetic subject and tenant to the HTTP request and map failures to machine-readable errors.

Complementary existing evidence, not duplicated here: [DurableEventServiceTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/events/DurableEventServiceTest.java)
already covers the service-level happy path, concealment and existence re-check; [JdbcDeliveryStoreIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/JdbcDeliveryStoreIntegrationTest.java)
exercises receipt persistence, cross-tenant row-level security and link insertion against real PostgreSQL.

Contract status observed during the survey (not changed by this task): the route is described in
[contracts/events/README.md](../../../../contracts/events/README.md), but
[missing-routes.txt](../../../../contracts/openapi/missing-routes.txt) still lists it as absent from
`contracts/openapi.yaml`. That is a separate contract-publication concern tracked outside this test-only
task; no contract file was edited here.

Verification performed for this note: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='ConfirmOrderPatientIdentifiersBeforeLinkingExternalTest,DurableEventServiceTest' test`
ended `EXIT:0`, with 12/12 tests in the new class and 25/25 in the existing class, zero failures and zero
skips. Evidence is from executed tests, not from a clinical or human reviewer.

## Gap

None.

The rule is implemented and proven by active, ungated tests at service, controller and HTTP-route level.
The unpublished OpenAPI entry above is an existing, separately tracked contract concern and does not make
this clause's confirmation rule missing or partly proven.
