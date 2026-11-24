# XCH-01 clause 4 — API test evidence

## Clause

The receiver sees an exchange-specific work item, with a locally verified patient association where needed.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.exchange.clauses.ReceiverSeesExchangeSpecificWorkItemLocallyTest`
(path: `apps/api/src/test/java/com/aurevia/exchange/clauses/ReceiverSeesExchangeSpecificWorkItemLocallyTest.java`).
All nine tests are active (none disabled) and pass with synthetic identifiers, MRNs, issuers and in
a fixed clock. Fixtures mirror the neighbouring exchange service tests: a mocked `OrderRepository`
and `AuditAppender`, an inline `TransactionTemplate`, a fixed `Clock` and a tenant context set per
test and cleared afterwards. An embedded standalone `MockMvc` with the synthetic-auth
`TenantContextInterceptor` drives the real `OrderService` (repository, audit appender and transaction
template remain mocked at the persistence boundary) so the HTTP assertions follow the full
controller-to-service path rather than echoing stubs.

- `receiverListsOnlyItsOwnWorkItems` — positive proof. An active receiving `clinician` calls
  `workItems()`, receives only the destination's bounded projection (sender organization, order
  reference, sender patient reference, MRN/issuer, priority, question, queue and snapshot
  fingerprint) with no local patient bound yet, and a `WORK_ITEM_LIST` `SUCCESS` audit row is
  appended.
- `receiverBindsLocallyVerifiedPatientAndItemBecomesReady` — positive proof. A site-verified
  issuer-scoped MRN plus attestation binds a local patient: `OrderRepository.verifyPatient`
  returns one, the returned view carries the local patient id, `patientVerified = true` and state
  `ready`, and a `WORK_ITEM_VERIFY` `SUCCESS` audit row is appended.
- `receptionRoleCannotListOrVerify` — negative proof (wrong role). An active `reception` member is
  refused 403 `FORBIDDEN` on both commands, two `DENIED` audit rows are appended, and neither the
  list query nor the bind write is reached.
- `otherTenantsWorkItemCannotBeVerified` — negative proof (other tenant). A work item not projected
  to the active destination is concealed: the repository returns no row, verification fails 404
  `NOT_FOUND`, and no write is attempted.
- `mismatchedMrnOrIssuerCannotBind` — negative proof (identity mismatch). A wrong MRN, a wrong
  issuer and a local patient whose own MRN/issuer does not match the projection are each refused
  403 `IDENTITY_MISMATCH`; `OrderRepository.verifyPatient` is never called.
- `repeatVerificationIsRejected` — negative proof (forbidden state). A zero-row conditional update
  is mapped to 409 `ALREADY_VERIFIED`, so a second bind cannot overwrite an existing association.
- `blankLocalPatientFieldsAreRejected` — negative proof (missing input). Blank patient id, MRN,
  issuer or attestation is refused 422 `INVALID_INPUT` at the trust boundary before any transaction
  or repository call.
- `inactiveMembershipCannotListOrVerify` — negative proof (no active membership). A subject without
  an active membership is refused 403 `FORBIDDEN` on both commands with two `DENIED` audit rows,
  and role lookup is never reached.
- `httpWorkItemRoutesReachTheReceiverProjection` — entry-point trace through the real service.
  `GET /api/v1/exchange/work-items` returns 200 with the projected fields,
  `POST /api/v1/exchange/work-items/{id}/verify-patient` returns 200 with state `ready`, and an
  unauthenticated list request is refused 401 `UNAUTHENTICATED`. The list and bind calls are
  verified on the mocked repository, proving the HTTP request reached the domain command.

Production classes exercised through their normal entry points:

- `com.aurevia.exchange.order.OrderService` — `workItems()` re-derives membership and the
  exchange role inside the tenant-guarded transaction and audits the projection; `verifyPatient()`
  validates the command at the boundary, conceals an invisible item with 404, requires an exact
  MRN/issuer match plus a real receiver-side identifier match before binding, maps a zero-row update
  to 409, and audits success; `deny()` writes a `DENIED` audit row on every refusal.
- `com.aurevia.exchange.order.OrderController` — the thin HTTP adapter injects the
  service and delegates both exchange work-item routes; it contains no business rules.
- `com.aurevia.exchange.order.OrderRepository` — `workItems()`, `workItem()` and
  `verifyPatient()` scope every read and write by `destination_org = public.app_org()`, so neither a
  list nor a bind can cross a tenant boundary.
- `com.aurevia.exchange.order.WorkItemView` and `VerifyPatientRequest` — the bounded
  exchange projection and the receiver identity command. The sender patient reference is not a
  receiver chart key; no general chart access is conveyed with the item.
- `db/migrations/V19__exchange_orders.sql` — `exchange_work_item` (lines 100-129) with RLS and FORCE
  RLS on all exchange tables (136-146), a two-party read policy for sender or destination, a
  sender-only insert policy and a destination-only update policy (165-171), plus the check that a
  verified item has a local patient, verifier and verification time.
- `contracts/openapi.yaml` — both work-item routes are already baselined (lines 45 and
  207), so this proof task adds no route and changes no contract.

The service-layer tests above prove delegation, audit and refusal at the domain boundary only;
tenant isolation and the absence of general chart access are enforced by the V19 RLS/FORCE RLS and
two-party policies listed above, not by the mocked repository. The real-PostgreSQL entry-point proof
is
`com.aurevia.it.exchangeorder.OrderExchangeIntegrationTest.verifiesTheLocalPatientAndNeverGrantsGeneralChartAccess`
(path: `apps/api/src/test/java/com/aurevia/it/exchangeorder/OrderExchangeIntegrationTest.java`),
which shows over HTTP that the receiving tenant sees its own item, cannot read the sender's order or
chart with the projected identifiers, cannot verify another tenant's patient, and binds only its own
locally verified patient. This task is the unit-level rule proof and did not run or change that
suite.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the contract or a migration.
