# XCH-01 clause 5 — Sharing an order grants no general chart access

## Clause

Sharing an order grants no general chart access.

## Status

Proven by tests

## Evidence

The dedicated API test is `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/SharingOrderGrantsGeneralChartAccessTest.java` (`@Tag("integration")`, enabled, real PostgreSQL with the repository's Flyway migrations and synthetic organizations A, B and C). It proves the clause at the HTTP entry point:

- `sharingAnOrderGrantsOnlyABoundedWorkItemNotSenderChartAccess` (rule met) — after `POST /api/v1/orders` by `clinician-a`/ORG_A, `GET /api/v1/exchange/work-items` by `clinician-b`/ORG_B returns exactly one projection for that order with only the fifteen `WorkItemView` exchange fields; the sender's `GET /api/v1/orders/{id}` is 404 in the receiver tenant, the sender's chart list is 200 but empty for the receiver while a seeded sender chart entry is visible to the sender and the single-entry read is 404, the sender patient read is 403 for a clinician without `PATIENT_READ` and 404 for a receiver receptionist who has it, the receiver receptionist can still read its own patient (200), and no sender order row exists in the receiver tenant.
- `receiverClinicianCannotVerifyAgainstTheSendersPatient` (negative) — binding the sender patient is 404 (`local patient not found`), a receiver patient with a different MRN is 403 `IDENTITY_MISMATCH`, a checked match succeeds with state `ready`, repeating the bind is 409 `ALREADY_VERIFIED`, and after that successful bind the sender chart is still empty for the receiver with the single entry still 404.
- `unrelatedTenantAndWrongRoleSeeNoReceiverProjection` (negative) — an unrelated tenant sees zero work items and gets 404 on the guarded verify route, and a receiver receptionist gets 403 `FORBIDDEN` on that route.

Production code exercised (unchanged by this test-only story):

- `com.aurevia.smartclinic.exchange.order.OrderService` — class Javadoc states that no sender chart access is granted by sharing an order; `deny()` enforces active membership and the clinician role; `verifyPatient()` checks work-item visibility, local patient existence, issuer-scoped MRN agreement and one-way verification; `get()` is tenant scoped.
- `com.aurevia.smartclinic.exchange.order.WorkItemView` — only exchange fields are projected; `patientRef` is documented as not a receiver chart key.
- `com.aurevia.smartclinic.exchange.order.OrderController` — thin injected routes for orders and work items.
- `com.aurevia.smartclinic.exchange.order.OrderRepository` — tenant-bound reads of the bounded projection.
- `db/migrations/V19__exchange_orders.sql` — `exchange_work_item` has no foreign key into any sender chart table, is under FORCE RLS, and its two-party policy lets only the sender insert and only the verified destination update the projection.
- `com.aurevia.smartclinic.chart.record.ChartService` and `com.aurevia.smartclinic.web.PatientController` — existing forced-RLS surfaces that remain the only routes to chart and patient data.

## Gap

None
