# XCH-01 clause 4 — API test evidence

## Clause

The receiver sees an exchange-specific work item, with a locally verified patient association where needed.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.ReceiverSeesExchangeSpecificWorkItemLocallyTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/ReceiverSeesExchangeSpecificWorkItemLocallyTest.java`).
All eight tests are active (none are disabled) and use synthetic data only.

- `receiverSeesOnlyTheExchangeScopedWorkItem` — positive proof of the bounded projection.
  A `clinician` in the destination organization calls `OrderService.workItems()` and receives a
  `WorkItemView` carrying the released snapshot fingerprint, the `awaiting_local_patient` state and
  no bound receiver chart key; the `SUCCESS` `WORK_ITEM_LIST` audit row is asserted.
- `receiverBindsLocallyVerifiedPatientAndWorkItemBecomesReady` — positive proof of local
  verification. A matching issuer-scoped MRN and explicit attestation bind a local patient through
  `OrderService.verifyPatient()`; the returned item is `ready`, `patientVerified` is true, the
  `verifyPatient` write happened and the `SUCCESS` `WORK_ITEM_VERIFY` audit row is asserted.
- `workItemsAndVerificationRefuseTheWrongRole` — negative proof of role. A `reception` member
  with active membership is refused on both routes with HTTP 403 `FORBIDDEN`; both denials are
  audited and neither the listing nor the verification write occurs.
- `workItemsAndVerificationRefuseASubjectWithoutActiveMembership` — negative proof of the
  membership guard. A subject without active membership is refused on the listing route with
  HTTP 403 `FORBIDDEN`, the denial is audited and no listing occurs.
- `senderAndUnrelatedTenantCannotReachTheReceiversWorkItem` — negative proof of tenant scope.
  Under a context whose organization is not the destination, the projection is empty and
  verification of the destination work item yields HTTP 404 `NOT_FOUND`; no local-patient lookup
  or verification write occurs.
- `verificationRefusesUnmatchedLocalPatient` — negative proof of identity binding. A wrong MRN,
  a wrong issuer and a false receiver-side identifier match each yield HTTP 403
  `IDENTITY_MISMATCH`; the verification write is never called and no success audit is written.
- `verificationRefusesBlankIdentityFieldsAndInvalidIds` — negative proof of boundary validation.
  Blank MRN, issuer and attestation plus non-UUID identifiers yield HTTP 422 before any repository
  read or write.
- `repeatVerificationIsRejected` — negative proof of the one-way guard. A database update that
  matches zero rows yields HTTP 409 `ALREADY_VERIFIED`, with no success audit written.

This class is clause-focused service-level evidence with a mocked repository. The end-to-end
HTTP proof that both routes reach the real controller and store is the existing, active
`com.aurevia.smartclinic.it.exchangeorder.OrderExchangeIntegrationTest`:
`releasesAnAuthorizedOrderWithSnapshotDisclosureAndReceiverWorkItem` calls
`GET /api/v1/exchange/work-items` and asserts the bounded projection;
`verifiesTheLocalPatientAndNeverGrantsGeneralChartAccess` calls
`POST /api/v1/exchange/work-items/{id}/verify-patient` and proves the tenant concealment and the
absence of general chart access. That integration test also proves the V19 row policies, forced
RLS and the receiver-only update guard against real PostgreSQL. Neither HTTP reachability nor
RLS is claimed by this mocked class on its own.

Production classes exercised through the normal command entry points
(`OrderService.workItems()` and `OrderService.verifyPatient()`):

- `com.aurevia.smartclinic.exchange.order.OrderService` — owns the role and membership check, the
  bounded listing, the MRN/issuer match with attestation, the one-way verification and the
  transactional `SUCCESS`/`DENIED` audit pair.
- `com.aurevia.smartclinic.exchange.order.OrderRepository` — the SQL boundary for the work-item
  projection, local-patient existence and identifier match, and the one-way verification update.
- `com.aurevia.smartclinic.exchange.order.OrderController` — the thin HTTP adapter exposing both
  routes; its delegation is covered by `it.exchangeorder.OrderServiceTest`.
- `com.aurevia.smartclinic.exchange.order.WorkItemView` — the bounded projection; it carries the
  referred patient's issuer-scoped MRN for local verification, never a sender chart key.
- `com.aurevia.smartclinic.exchange.order.VerifyPatientRequest` — the receiver identity command
  carrying the local patient identifier and attestation.
- `db/migrations/V19__exchange_orders.sql` — the `exchange_work_item` table, its row-level
  security policies with `FORCE ROW LEVEL SECURITY` and the one-way verification guard.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the contract or a migration. Publishing the two work-item
routes in OpenAPI is separate contract-parity work, tracked outside this clause.
