# XCH-04 clause 3 — API test evidence

## Clause

Unmatched or conflicting reports enter a reconciliation queue.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.UnmatchedConflictingReportsEnterReconciliationQueueTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/UnmatchedConflictingReportsEnterReconciliationQueueTest.java`).
It is an active two-tenant HTTP integration test against a dedicated synthetic PostgreSQL database
(`smartclinic_xch04_clause3_it`) migrated with the production Flyway chain; none of its six tests is disabled.

- `unmatchedResultIsDivertedToOpenReconciliationQueue` — positive proof of the diversion. Importing a
  result with no registered order returns HTTP 202 with reason `unmatched` and status `open`, persists one
  OPEN reconciliation row, writes no report or review row, and records the `XCH04_RESULT_RECONCILED` audit.
- `conflictingIdentifiersAreDivertedToOpenReconciliationQueue` — positive proof of the conflict branch.
  With an order registered, an issuer identifier that disagrees returns 202 with reason `conflict` and
  mismatch `sourceId`, persists one OPEN conflict row and writes no report row.
- `divertedResultsAppearInTheOpenQueue` — proof that both diversions are visible: a privileged queue read
  returns the exact entry ids created by the two imports, with reason `unmatched` and `conflict`, status `open`.
- `receptionRoleCannotImportOrDivert` — refusal proof. A `reception` role with active membership but without
  `RESULT_IMPORT` is denied with HTTP 403 `FORBIDDEN` and no reconciliation or report row is written.
- `otherTenantCannotSeeTheQueueEntry` — tenant-isolation refusal proof. A peer organization sharing the same
  local identifier space reads an empty queue under forced RLS and sees no row of the owning tenant, while the
  owning tenant still sees its own entry (positive control).
- `invalidQueueStatusIsRejected` — queue-endpoint validation regression: an unknown status is refused with
  HTTP 422 `INVALID_STATUS`.

Production classes exercised through the normal HTTP entry points:

- `com.aurevia.smartclinic.exchange.result.ResultRoutingService` — `importResult` and `importGuarded` divert a
  non-matching result to an OPEN reconciliation entry (audit `XCH04_RESULT_RECONCILED`) instead of importing a
  revision; `listReconciliation` validates the status, re-authorizes and reads the tenant-scoped queue;
  `authorize` denies a missing role. The sibling `reconcile` path for incomplete correction lineage is
  exercised by the existing correction suites, not by this clause's six tests.
- `com.aurevia.smartclinic.exchange.result.ResultRules` — `match` distinguishes `UNMATCHED` (no expectation)
  from `CONFLICT` (identifier disagreement, naming each mismatching facet in stable order).
- `com.aurevia.smartclinic.exchange.result.ResultController` — maps a diverted outcome to HTTP 202 and exposes
  `GET /api/v1/xch04/reconciliations`.
- `com.aurevia.smartclinic.exchange.result.ResultModel` — `ReconciliationEntry`, `ReconciliationReason`,
  `ReconciliationStatus`, `MatchStatus` and `ImportOutcome` carry the diversion contract.
- `com.aurevia.smartclinic.exchange.result.ResultActionPolicy` — limits import to `lab_verifier` and queue
  review to `lab_verifier`, `tenant_admin` and `privacy_auditor`; `reception` is refused.
- `com.aurevia.smartclinic.exchange.result.ResultRepository` and `db/migrations/V22__result_routing.sql` —
  persist the queue entry in `xch04_reconciliation` under row-level security (ENABLE and FORCE RLS, tenant
  policy), with the OPEN/conflict status constraints and the queue index.
- `com.aurevia.smartclinic.api.ApiErrorHandler` — renders the `FORBIDDEN` and `INVALID_STATUS` error codes.

The routes `POST /api/v1/xch04/results` and `GET /api/v1/xch04/reconciliations` remain listed in
`contracts/openapi/missing-routes.txt`; this proof-only task did not change the contract or regenerate a client.
Neighbouring tests `UnmatchedReportReviewAcceptanceTest` and `ResultRoutingIntegrationTest` were read as
fixtures and were not modified.

## Gap

None.
