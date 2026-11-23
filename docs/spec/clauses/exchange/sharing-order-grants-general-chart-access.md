# XCH-01 clause 5 — sharing an order grants no general chart access

## Clause

Sharing an order grants no general chart access.

## Status

Proven by tests

## Evidence

New API-level test (this task): `com.aurevia.smartclinic.exchange.clauses.SharingOrderGrantsGeneralChartAccessTest`
under `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/`. It drives the real
`OrderService` through `OrderController` and the request interceptor with synthetic headers.

- `receiverListsOnlyBoundedWorkItemProjection` — `GET /api/v1/exchange/work-items` as a receiver
  clinician returns 200 with exactly the fifteen `WorkItemView` fields: `destinationOrg`, `orderRef`,
  opaque `patientRef`, issuer-scoped `patientMrn`/`mrnIssuer` for local checking only, `localPatientId`
  null, `patientVerified=false`, the released `fingerprint` and `state=awaiting_local_patient`. The
  exact field count fails if a sender chart column is ever added to the projection.
- `receiverWithoutClinicianRoleCannotListWorkItems` — a receiver subject with active membership but
  only the `reception` role receives 403 `FORBIDDEN` and no work item is read. Membership is stubbed
  true, so the refusal comes from the role branch of `OrderService.deny`, not the earlier guard.
- `sharingDoesNotExposeSenderOrderToReceiver` — a receiver clinician's
  `GET /api/v1/orders/{senderOrderId}` receives 404 `NOT_FOUND` when the order is not visible in the
  active tenant. The empty repository result models the row concealment of the V19 RLS policy.
- `receiverCannotUseSenderPatientRefAsChartKey` — binding a local patient with an MRN that does not
  match the projected issuer-scoped MRN receives 403 `IDENTITY_MISMATCH` before any projection update.

Production classes exercised (read-only; none changed by this task):
`com.aurevia.smartclinic.exchange.order.OrderController` (work-items and verify-patient HTTP entries),
`OrderService` (`workItems`, `get`, `verifyPatient`, `deny`), `OrderRepository` (`workItems`, `find`,
`workItem`, `verifyPatient`), `WorkItemView`, `VerifyPatientRequest`, `OrderInputs`,
`com.aurevia.smartclinic.api.ApiErrorHandler`, `com.aurevia.smartclinic.web.TenantContextInterceptor`
and `com.aurevia.smartclinic.tenant.TenantContext`.

Existing evidence cited, not modified:
`com.aurevia.smartclinic.it.exchangeorder.OrderExchangeIntegrationTest.verifiesTheLocalPatientAndNeverGrantsGeneralChartAccess`
drives the real endpoints against PostgreSQL and proves that the sender order is 404 to the receiver,
a direct chart read of the sender patient is refused (403/404), a mismatched local patient cannot be
bound, and an unrelated tenant sees no projected work items. The enforcement lives in
`db/migrations/V19__exchange_orders.sql`: FORCE RLS on all five exchange tables (lines 136-146), the
sender-only `tenant_isolation` policy (lines 148-160), and the deliberate two-party
`exchange_work_item_read` policy that is the receiver's sole surface (lines 162-171). The work item
has no foreign key into any sender chart table (lines 100-129).

Contract note: `GET /api/v1/exchange/work-items` and
`POST /api/v1/exchange/work-items/{}/verify-patient` are absent from `contracts/openapi.yaml`; both are
baselined in `contracts/openapi.yaml` (lines 45 and 207). That documentation gap is
tracked separately and was deliberately left unchanged.

## Gap

None for the clause itself. The standalone MockMvc harness cannot exercise PostgreSQL FORCE RLS, so
a mocked empty repository result models row concealment; the real RLS and chart-refusal proof is the
existing DB-backed integration test named above. Wiring `ChartController` here with a mocked
`ChartService` would only exercise the stub rather than the tenant-scoped chart query, so it was not
done. The missing OpenAPI entries are a separate route-parity concern (one task, touching the contract
and regenerated client); they do not affect whether sharing grants chart access.
