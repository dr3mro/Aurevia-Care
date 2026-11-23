# ARC-02 clause 6 — Cross-tenant exchange with explicit, verified relationships

## Clause

Cross-tenant exchange uses explicit, verified sender/recipient relationships and bounded projections.

## Status

Proven by tests

## Evidence

The clause test is [CrossTenantExchangeUsesExplicitVerifiedSenderTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/tenancy/clauses/CrossTenantExchangeUsesExplicitVerifiedSenderTest.java), a real-PostgreSQL integration test (`@Tag("integration")`, `synthetic-auth` profile) that drives the existing HTTP entry points over three synthetic organizations, two reused issuer-scoped MRNs and distinct sender/recipient subjects. It is enabled (no `@Disabled`) and green: 4 tests, 0 failures, 0 errors, 0 skipped.

- `onlyDisclosesThroughVerifiedGrantToNamedRecipient` (rule met) — an `admin-a` grant naming organization B, order code and purpose lets `clinician-a` release an order; `clinician-b` then receives only the bounded work item carrying the same snapshot fingerprint, cannot read the sender's order, binds its own verified patient through the issuer-scoped MRN match, and the correlation-linked audit rows carry no clinical payload.
- `refusesOrderWhenGrantRecipientOrPurposeDoesNotMatch` (negative) — releases with a different purpose or a different recipient than the one named in the grant return HTTP 403 `DISCLOSURE_NOT_AUTHORIZED`, and no order or disclosure row is written.
- `refusesCrossTenantGrantAndConcealsPeerOrder` (negative) — organization B cannot reuse organization A's grant (forced RLS makes it invisible), a peer order id returns HTTP 404, and the owner's row is unchanged and still readable.
- `failsClosedWithoutSubjectOrActiveOrganizationOnExchange` (negative) — work-item reads and order/grant writes without `X-Synthetic-Subject` return HTTP 401, and with no `X-Active-Organization` return HTTP 422, before any exchange row is written.

Production classes and files exercised through those HTTP entry points:

- [OrderService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/order/OrderService.java) — `authorize`, `create`, `workItems` and `verifyPatient`; `checkGrant` re-verifies the grant's active flag, validity window, order code, named recipient, purpose and duration, returning 403 `DISCLOSURE_NOT_AUTHORIZED` on any mismatch, and stamps one immutable snapshot fingerprint on the disclosure and the work item.
- [OrderController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/order/OrderController.java) — the thin HTTP adapter for `POST /api/v1/exchange/authorizations`, `POST /api/v1/orders`, `GET /api/v1/orders/{id}`, `GET /api/v1/exchange/work-items` and `POST /api/v1/exchange/work-items/{id}/verify-patient`.
- [OrderView.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/order/OrderView.java) and [WorkItemView.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/order/WorkItemView.java) — the sender view and the bounded receiver projection; the work item carries only exchange fields plus the snapshot fingerprint and has no sender-chart key.
- [OrderRepository.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/exchange/order/OrderRepository.java) — the RLS-bounded store: `authorization` and `find` are scoped by the active organization, `workItems` and `workItem` are scoped to the named destination, and `verifyPatient` is a guard-clause update on the receiver side.
- [TenantContextInterceptor.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/web/TenantContextInterceptor.java) and [TenantContext.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/tenant/TenantContext.java) — derive subject and active organization from authenticated headers, fail closed with 401/422 and clear the thread-local after each request.
- [ApiErrorHandler.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/api/ApiErrorHandler.java) — renders the machine-readable `code` and correlation id asserted by the negatives.
- [V19__exchange_orders.sql](../../../../db/migrations/V19__exchange_orders.sql) — `exchange_authorization`, `exchange_order`, `exchange_order_snapshot`, `exchange_disclosure` and the two-party `exchange_work_item` projection with forced RLS, the composite grant/recipient foreign keys, the `organization_id <> destination_org` check, fingerprint columns and projection-immutability triggers.

Scope note: this status proves the clause's exchange behavior. Publishing every exchange route in the canonical contract is a separate WEB-05 concern; the routes are baselined in `contracts/openapi/missing-routes.txt` and are not claimed here. The recipient-side XCH-03 response ledger in `V21__xch03_order_response.sql` is exercised by other exchange stories, not by this bounded proof.

## Gap

None.
