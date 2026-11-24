# XCH-01 clause 1 — order includes local patient, encounter, order code, priority, clinical question, destination, source actor and responsible clinician/work queue

## Clause

An order includes local patient and encounter, order code, priority, clinical question, destination, source actor and responsible clinician/work queue.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.exchange.clauses.OrderIncludesLocalPatientEncounterOrderCodeTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/OrderIncludesLocalPatientEncounterOrderCodeTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic organizations A (sender) and B
(destination), synthetic members and patients, and one approved exchange
authorization:

- `releasedOrderCarriesEveryClauseField` — a `clinician` releases an order with
  `POST /api/v1/orders`; the 201 response, a follow-up `GET /api/v1/orders/{id}`
  (200) and the persisted `exchange_order` row all carry the local `patientId`,
  `encounterRef`, `orderCode`, `priority`, `clinicalQuestion`, `destinationOrg`,
  `sourceActor`, `responsibleClinician` and `workQueue`. `sourceActor` is the
  authenticated subject `clinician-a` and is distinct from the command's
  `responsibleClinician` (`dr-a`), because it is derived from the tenant context
  and stamped by the migration's insert trigger rather than taken from the body.
  The approved destination then reads `GET /api/v1/exchange/work-items` (200) and
  sees the bounded work-item projection carrying the clause fields it is
  permitted to hold — `priority`, `clinicalQuestion`, `workQueue`,
  `destinationOrg`, `orderRef`, `patientRef`, `fingerprint` and `state` — with the
  sender's encounter, order code, source actor, responsible clinician and chart
  deliberately not projected; the sender-only fields are asserted absent.
- `sourceActorCannotBeForgedFromTheRequestBody` — a request that adds an extra
  `sourceActor` JSON property with a forged value still yields `sourceActor =
  clinician-a` in both the 201 response and the persisted row, proving the actor
  comes from the authenticated subject rather than the payload.
- `wrongRoleCannotReleaseAnOrder` — a `reception` actor of the sending tenant is
  denied (403 `FORBIDDEN`) after a control release with the same grant proves the
  payload is otherwise acceptable; no order row is written.
- `crossTenantPatientIsConcealed` — a patient of organization B used from
  organization A is concealed (404 `NOT_FOUND`) while a control release succeeds
  and a tenant-bound read proves the foreign patient exists; no order row is
  written.
- `unapprovedPriorityAndBlankQuestionAreRefused` — an unapproved `ASAP` priority
  is refused (422 `INVALID_PRIORITY`) and a blank clinical question is refused
  (422 `INVALID_INPUT`); neither writes an order row.
- `malformedPatientOrDestinationIsRefused` — a non-UUID patient and a non-UUID
  destination are each refused (422 `INVALID_INPUT`, with the offending field in
  the message) after a control release succeeds; neither writes an order row.

Production classes exercised through `POST /api/v1/orders`,
`GET /api/v1/orders/{id}` and `GET /api/v1/exchange/work-items`:
`com.aurevia.exchange.order.OrderController` (thin HTTP adapter),
`com.aurevia.exchange.order.OrderService` (validates the command,
derives `sourceActor` from the tenant context and commits the order, snapshot,
disclosure and work item in one tenant-guarded transaction),
`com.aurevia.exchange.order.OrderRequest`,
`com.aurevia.exchange.order.OrderView`,
`com.aurevia.exchange.order.WorkItemView`,
`com.aurevia.exchange.order.OrderInputs` (priority, UUID and text
validation), `com.aurevia.exchange.order.OrderRepository` (the
`Jdbc*` store behind the exchange boundary) and the tenant RLS/FORCE RLS plus
actor-stamping triggers supplied by `db/migrations/V19__exchange_orders.sql`.

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.it.exchangeorder.OrderServiceTest` (service-level
assertion of every `OrderView` field and the full `insertOrder`/`insertWorkItem`
arguments), `com.aurevia.it.exchangeorder.OrderExchangeIntegrationTest`
(real-PostgreSQL release, snapshot, disclosure and work-item flow), and
`com.aurevia.it.exchangeorder.OrderRecordsTest` (record value
semantics).

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml
-Pno-security -Dtest=OrderIncludesLocalPatientEncounterOrderCodeTest test` —
`tests="6" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the order routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing:
`POST /api/v1/orders`, `GET /api/v1/orders/{id}` and
`GET /api/v1/exchange/work-items` are listed in
`contracts/openapi.yaml`; publishing them would change
`contracts/openapi.yaml`, the generated client and the contract tests — a
separate contract-publication task.
