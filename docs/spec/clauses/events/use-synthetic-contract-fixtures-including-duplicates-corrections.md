# ARC-05 clause 8 — API test evidence

## Clause

Use synthetic contract fixtures, including duplicates, corrections and partial partner outages.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.events.clauses.UseSyntheticContractFixturesIncludingDuplicatesCorrectionsTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/events/clauses/UseSyntheticContractFixturesIncludingDuplicatesCorrectionsTest.java`).
All seven tests are active (none disabled) and pass against a real PostgreSQL database with synthetic
fixtures only. The suite recreates its own database from the authoritative Flyway migrations, seeds two
tenants with bounded roles and a confirmed synthetic order and patient, and drives every scenario of
`contracts/events/fixtures/partner-events.json` through the normal HTTP entry point under the
`synthetic-auth` profile. The fixture's single-character digest placeholders are mapped to distinct
synthetic 64-hex digests, and each test uses a fresh aggregate id and a scoped external event id so
neither the version watermark nor the deduplication key can leak between tests.

- `duplicateDeliveryReturnsOneReceipt` — the `duplicate-delivery` scenario is consumed twice through
  `POST /api/v1/delivery/consumer-events`. The first result is `accepted`; the second returns the same
  `receiptId` and original outcome, exactly one receipt is stored for the aggregate, and the append-only
  audit records a `CONSUME` fact with a `DUPLICATE` outcome for the repeated external event.
- `laterCorrectionAcceptedEarlierVersionHeld` — the `correction-after-final` scenarios at versions 4 and
  5 are both `accepted`, a later version 8 is `accepted`, and the delayed version 6 replay is
  `held_out_of_order`; the four fixture receipts are counted. The higher revision never overwrites the
  lower one.
- `partialOutageRetriesBackoffDeadLettersThenReviewRequeues` — the `partial-partner-outage` event is
  consumed as `accepted`; an outbox event is published and driven through `sent`, `failed`, three bounded
  retries with increasing `nextAttemptAt`, a fourth retry that dead-letters at three attempts with reason
  `retry budget exhausted`, and a separate authorized review that requeues the event with a fresh budget.
  The dead-letter `attempt`, `finalState`, `reviewRequired` and `resolution` are asserted from the fixture.
- `rejectsEventWithUnconfirmedOrderOrPatientAndRefusesLink` — the `unconfirmed-identifiers` event is
  `rejected` with detail `order_not_found`, its receipt is recorded, and a linking attempt by an authorized
  reviewer is refused with HTTP 404 and produces no link.
- `otherTenantCannotReadOrReplayDeliveryFacts` — an ORG_A event and receipt are invisible to ORG_B through
  direct store reads under enforced row-level security; a `sent` transition as the ORG_B worker and a
  replay as the ORG_B integration administrator both return HTTP 404, concealing the other tenant's facts.
- `roleWithoutConsumeOrReplayIsDenied` — a `privacy_auditor` with active ORG_A membership is refused both
  `CONSUME` and `REPLAY` with HTTP 403 and receives no receipt or replay data.
- `staleIfMatchIsRejected` — a second `sent` transition with the already-used `If-Match` version is
  refused with HTTP 412 and the event remains at its single advanced state.

Production classes exercised through the entry point and its transaction:

- `com.aurevia.smartclinic.events.DeliveryController` — thin adapter binding the delivery routes and
  delegating to the domain service.
- `com.aurevia.smartclinic.events.DurableEventService` — consume deduplication and outcome recording,
  correction/out-of-order version gating, bounded retry and dead-letter review, replay, and identifier
  confirmation before linking.
- `com.aurevia.smartclinic.events.DeliveryActions` — role matrix used for the wrong-role refusals.
- `com.aurevia.smartclinic.events.DeliveryInputs` — trust-boundary validation, including the 64-hex digest
  rule that makes the fixture placeholders safe to send.
- `com.aurevia.smartclinic.events.DurableEventContract` — immutable records returned on the wire.
- `com.aurevia.smartclinic.events.JdbcDeliveryStore` — tenant-guarded persistence behind `DeliveryStore`.
- `db/migrations/V55__durable_event_outbox.sql` — forced tenant row-level security and immutable fact
  ledgers that enforce the tenant concealment in the evidence above.
- `contracts/events/README.md` (X-02) and `contracts/events/fixtures/partner-events.json` — the authoritative
  synthetic contract and the scenarios loaded by the test.

The existing unit and integration suites (`DurableEventServiceTest`, `JdbcDeliveryStoreIntegrationTest`)
cover the same behaviour; this task adds a focused, clause-only proof and changes neither.

## Gap

None for this clause: it is built and proven by active tests through the HTTP entry point. This task added
proof only; it did not change production code, an existing test, the OpenAPI contract or a migration. The
delivery routes remain baselined in `contracts/openapi.yaml`, which is a separate contract
publication task outside this test-only scope and is not required to prove the rule.
