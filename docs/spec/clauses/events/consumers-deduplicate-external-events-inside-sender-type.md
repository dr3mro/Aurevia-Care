# ARC-05 clause 3 — API test evidence

## Clause

Consumers deduplicate external events inside sender/type namespace, reject or hold out-of-order changes, and record processing outcome.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.events.clauses.ConsumersDeduplicateExternalEventsInsideSenderTypeTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/events/clauses/ConsumersDeduplicateExternalEventsInsideSenderTypeTest.java`).
All five tests are active (none disabled) and pass against real PostgreSQL with synthetic fixtures only. Every command
travels through the HTTP entry point `POST /api/v1/delivery/consumer-events`; the tenant interceptor binds
`app.current_org` and `app.actor`, and receipts and audit rows are read back with the same tenant context.

- `aRepeatedExternalEventInTheSameSenderTypeNamespaceReturnsTheOriginalReceipt` — positive proof of deduplication and
  its key. Two identical POSTs as `worker-a`/ORG_A return 200 with the same `receiptId` and the original `accepted`
  outcome; the repeat is visible only as a `duplicate` audit outcome, and exactly one `delivery_receipt` row exists.
  The same external id then returns a distinct accepted receipt from a different sender, from a different namespace
  and from a different event type, proving all four fields scope deduplication.
- `anExternalEventOlderThanTheHighestProcessedVersionIsHeld` — order proof. Version 5 is accepted, version 3 for the
  same aggregate/sender/type/namespace returns `held_out_of_order` with detail `out_of_order` and a stored
  `HELD_OUT_OF_ORDER` receipt plus a `held` audit outcome; version 6 is accepted and becomes the new watermark.
- `anExternalEventWithUnconfirmedOrderOrPatientIsRejectedAndRecorded` — identifier-confirmation proof. An unseeded
  `orderId` yields `rejected`/`order_not_found`, and an unseeded `patientId` yields `rejected`/`patient_not_found`.
  Both return HTTP 200 with a persisted `REJECTED` receipt and a `not_found` audit outcome, so a rejected event is
  recorded rather than silently dropped.
- `aSubjectWithoutTheConsumerRoleIsForbidden` — authorization negative. `operator-a` has active membership in ORG_A
  but the `operator` role is not a service role for `CONSUME`; the POST returns 403 `FORBIDDEN`, a `denied` audit
  outcome is written, and no receipt is stored.
- `receiptsAreIsolatedAcrossTenantsAndDeduplicationIsTenantScoped` — tenant negative. The same external id consumed
  as `worker-a`/ORG_A and `worker-b`/ORG_B yields two independent receipts owned by their respective tenants. A
  further receipt is created only in ORG_A and read successfully there, then the same tenant-bound read from ORG_B
  returns nothing, proving that forced RLS conceals an existing cross-tenant row rather than an absent one. The
  migration and runtime roles are both asserted to lack `BYPASSRLS`.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.events.DeliveryController` — thin adapter that binds the request body and returns the
  `ConsumerReceipt`; the service is injected, never constructed in the controller.
- `com.aurevia.smartclinic.events.DurableEventService` — `consume` validates inputs, derives the tenant, authorizes
  `CONSUME`, checks the sender/type receipt before the version watermark, confirms optional order and patient ids,
  and returns the recorded outcome; `recordReceipt` persists the receipt and then appends its audit fact.
- `com.aurevia.smartclinic.events.DeliveryActions` — `allows(CONSUME)` grants only `worker` and
  `integration_admin`, so `operator` is refused.
- `com.aurevia.smartclinic.events.JdbcDeliveryStore` — tenant-guarded reads and writes for `delivery_receipt` and
  `delivery_audit`, including the dedup key, `maxVersion`, `orderExists` and `patientExists`.
- `db/migrations/V55__durable_event_outbox.sql` — `delivery_receipt` and `delivery_audit` with row level security
  enabled and forced.

The route `POST /api/v1/delivery/consumer-events` is already reachable and is baselined (not yet documented) in
`contracts/openapi.yaml`; publishing it in the OpenAPI contract is tracked as a separate contract task
and was deliberately not changed by this test-only story.

## Gap

None.
