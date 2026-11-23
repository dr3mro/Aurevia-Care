# XCH-01 clause 1 — API test evidence

## Clause

An order includes local patient and encounter, order code, priority, clinical question, destination, source actor and responsible clinician/work queue.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.OrderIncludesLocalPatientEncounterOrderCodeTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/OrderIncludesLocalPatientEncounterOrderCodeTest.java`).
All four tests are active (none disabled) and pass with synthetic data only. Fixtures mirror the
neighbouring `it.exchangeorder.OrderServiceTest`: mocked `OrderRepository` and `AuditAppender`, an
inline `TransactionTemplate`, a fixed `Clock`, and a `TenantContext` set per test and cleared
afterwards. The tests prove the nine clause fields at the real service entry point; end-to-end
delivery over HTTP is carried by the existing integration test named below.

- `releasedOrderCarriesEveryClauseOneField` — positive proof. `OrderService.create` returns an
  `OrderView` carrying patient, encounter reference, order code, priority, clinical question,
  destination, source actor, responsible clinician and work queue. `sourceActor` is asserted equal
  to the `TenantContext` subject even though `OrderRequest` has no source-actor field and the
  responsible clinician holds a deliberately different value, so the value can only have been
  derived from the authenticated context. The captured snapshot content is asserted to carry the
  patient, encounter, order code, priority, question, destination, responsible clinician and work
  queue; the disclosure and work-item writes are asserted with exact arguments (issuer-scoped MRN,
  work queue and snapshot fingerprint) and the audit outcome is `SUCCESS`.
- `priorityOutsideApprovedSetIsRefusedBeforeAnyWrite` — negative proof (missing/invalid input).
  An unapproved priority yields HTTP 422 `INVALID_PRIORITY` from `OrderInputs.priority`; no
  repository boundary and no audit appender is touched at all.
- `orderingRoleIsRequiredAndDenialIsAudited` — negative proof (wrong role). A `reception` member
  with an otherwise valid authorization is refused with HTTP 403 `FORBIDDEN` by `OrderService.deny`,
  a `DENIED` audit row is appended for the `ORDER_DISCLOSURE` action, and none of the order,
  snapshot, disclosure or work-item writes happen.
- `unknownLocalPatientIsRefused` — negative proof (missing local patient). With a valid grant but
  an empty `OrderRepository.patientIdentity`, the release is concealed as HTTP 404 `NOT_FOUND`
  before any of the four writes.

Production classes exercised through their normal entry points:

- `com.aurevia.smartclinic.exchange.order.OrderService` — `create` (lines 86-145) validates the
  command, re-derives the actor and roles inside the tenant-guarded transaction, checks the
  authorization, resolves the local patient and commits the order, snapshot, disclosure and work
  item together; `deny` (lines 377-386) audits and refuses an unauthorized role.
- `com.aurevia.smartclinic.exchange.order.OrderInputs` — boundary validation; `priority`
  (lines 69-78) admits only `routine`, `urgent` and `stat`.
- `com.aurevia.smartclinic.exchange.order.OrderView`, `OrderRequest` and `WorkItemView` — the
  immutable released-order view, release command and bounded receiver projection records.
- `com.aurevia.smartclinic.exchange.order.OrderRepository` — the SQL boundary whose writes are
  verified above; its `insertOrder` deliberately omits `source_actor`, which the database fills
  from the transaction actor.

Entry point to test trace (whole clause, end to end, unchanged by this task):

- HTTP entry point `OrderController.create` at `apps/api/src/main/java/com/aurevia/smartclinic/exchange/order/OrderController.java:43-47`
  receives `POST /api/v1/orders` and delegates by injection to `OrderService.create`; the controller
  holds no business rules.
- The existing `com.aurevia.smartclinic.it.exchangeorder.OrderExchangeIntegrationTest.releasesAnAuthorizedOrderWithSnapshotDisclosureAndReceiverWorkItem`
  (path `apps/api/src/test/java/com/aurevia/smartclinic/it/exchangeorder/OrderExchangeIntegrationTest.java:313-356`)
  drives that route against a real PostgreSQL with synthetic tenants A, B and C. It asserts HTTP
  201, `sourceActor == "clinician-a"` after the database round trip (line 320), the snapshot and
  disclosure rows, the audit row, the receiver work item and its projected clinical question,
  state and fingerprint (lines 342-355). This test was not modified by this task.
- `db/migrations/V19__exchange_orders.sql` defines the forced-RLS `exchange_order`,
  `exchange_order_snapshot`, `exchange_disclosure`, `exchange_work_item` and
  `exchange_authorization` tables (lines 36-60 and following). Its
  `set_exchange_source_actor_from_actor` trigger (lines 221-231, wired at 253-255) stamps
  `source_actor` from `app.actor`, so the derived actor survives persistence and cannot be
  supplied by the caller.
- The contract currently publishes `GET /api/v1/orders` at `contracts/openapi.yaml:401-419`.
  The `POST /api/v1/orders` operation of the clause's entry point is not published; this is
  pre-existing debt recorded in `contracts/openapi/missing-routes.txt:251` and is not changed here.

Neighbouring implementations observed in the source survey (not changed by this task): the
authorization command `OrderService.authorize` and `AuthorizationView`, the receiver-side
`OrderService.verifyPatient` patient binding, and the exchange task/response/result workflows under
`apps/api/src/main/java/com/aurevia/smartclinic/exchange/`.

## Gap

None.

The clause is built and proven by active tests at both the service and HTTP levels. This task added
proof only: no production code, existing test, migration or contract was changed. Publication of
`POST /api/v1/orders` in the OpenAPI contract is separately tracked debt, not a gap in clause 1
implementation.
