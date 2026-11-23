# XCH-04 clause 3 — unmatched or conflicting reports enter a reconciliation queue (API test evidence)

## Clause

Unmatched or conflicting reports enter a reconciliation queue.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.UnmatchedConflictingReportsEnterReconciliationQueueTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/UnmatchedConflictingReportsEnterReconciliationQueueTest.java`).
It is an active two-tenant HTTP integration test against a dedicated synthetic PostgreSQL database
(`smartclinic_xch04_clause_it`) created and dropped by the suite and migrated with the production
Flyway chain (58 migrations, v95); none of its nine tests is disabled. Two synthetic organizations
share the same local order, accession and patient MRN space, so forced RLS concealment is observable
on a real database, and every row-count assertion is scoped to one tenant inside that tenant's
context. Each diverted report is proved several ways: the 202 response, the persisted V22 queue
row, the audit event, and the absence of any clinical revision or review task.

- `unmatchedReportWithoutRegisteredOrderEntersOpenReconciliationQueue` (the positive `unmatched`
  proof) — importing a report for an unregistered order returns 202 with `reason=unmatched`,
  `status=open`, an empty `mismatches` array and the `no expected order registered for …` detail.
  The `xch04_reconciliation` row carries `reason='unmatched'`, `status='open'`, `mismatches=''`
  and the claimed order/accession/source. No `xch04_report_revision` or `xch04_review` row exists,
  and one `XCH04_RESULT_RECONCILED` audit event is recorded for the returned entry id with
  `resource_type='RECONCILIATION_ENTRY'`, `outcome='SUCCESS'`, `purpose='XCH04_RESULT_ROUTING'`
  and `scope='EXCHANGE'`.
- `conflictingIdentifiersEnterOpenReconciliationQueueWithMismatches` (the positive `conflict`
  proof) — after registering an order with source `SRC-EXPECTED`, a report claiming `SRC-WRONG`
  returns 202 with `reason=conflict`, `status=open`, the `identifier mismatch: sourceId` detail and
  a `mismatches` array of exactly `["sourceId"]` (asserted as an array element and JSON pointer,
  not only by substring). The V22 row stores `reason='conflict'`, `mismatches='sourceId'`,
  `source_code='SRC-WRONG'`; no report or review row is written and the same reconciliation audit
  event is recorded.
- `divertedResultsAppearInTheOpenQueue` — proof that both diversions are visible through the
  normal queue endpoint: a privileged queue read returns the exact entry ids created by the two
  imports, with reason `unmatched` and `conflict`, status `open`.
- `clinicianCannotImportIntoTheQueue` — a role without `RESULT_IMPORT` receives 403 `FORBIDDEN`
  for a fully valid report. No queue or report row is written and the denial is audited as
  `XCH04_RESULT_IMPORT` with `outcome='DENIED'` for the order reference.
- `receptionRoleCannotImportOrDivert` — a `reception` role with active membership but without
  `RESULT_IMPORT` is likewise refused with 403 `FORBIDDEN` and writes no reconciliation or
  report row.
- `otherTenantCannotReadOrgAQueueEntries` — an authorized peer-tenant privacy auditor reads the
  queue as 200 with an empty page while the owning tenant's read returns 200 containing the new
  entry id (positive control); a direct JDBC read of that id under the peer tenant returns zero
  under FORCE RLS.
- `blankOrderIdIsRejectedBeforeEnqueue` — a blank order identifier is refused 422 `INVALID_INPUT`
  before any matching, with no queue or report row.
- `clinicianCannotReadQueue` — a role without `RECONCILIATION_READ` receives 403 `FORBIDDEN`; the
  denial is audited as `XCH04_RECONCILIATION_READ` with `outcome='DENIED'`.
- `invalidStatusFilterIsRejected` — an authorized reader passing `?status=closed` receives 422
  `INVALID_STATUS` before any queue query.

Production classes exercised through `POST /api/v1/xch04/orders`,
`POST /api/v1/xch04/results` and `GET /api/v1/xch04/reconciliations`:

- `com.aurevia.smartclinic.exchange.result.ResultController` — thin HTTP adapter mapping a
  diverted outcome to 202 and exposing `GET /api/v1/xch04/reconciliations`; no domain decision
  lives in the controller.
- `com.aurevia.smartclinic.exchange.result.ResultRoutingService` — `importResult` and
  `importGuarded` divert a non-matching result to an OPEN reconciliation entry (audit
  `XCH04_RESULT_RECONCILED`) instead of importing a revision; `listReconciliation` validates the
  status, re-authorizes and reads the tenant-scoped queue; `authorize` denies a missing role and
  audits the denial; `writeAudit` stamps the shared purpose and scope. The sibling `reconcile`
  path for incomplete correction lineage is exercised by the existing correction suites, not by
  this clause's tests.
- `com.aurevia.smartclinic.exchange.result.ResultRules` — `match` distinguishes `UNMATCHED` (no
  expectation) from `CONFLICT` (identifier disagreement, naming each mismatching facet in stable
  order).
- `com.aurevia.smartclinic.exchange.result.ResultModel` — `ReconciliationEntry`,
  `ReconciliationReason`, `ReconciliationStatus`, `MatchStatus` and `ImportOutcome` carry the
  diversion contract.
- `com.aurevia.smartclinic.exchange.result.ResultActionPolicy` — limits import to `lab_verifier`
  and queue review to `lab_verifier`, `tenant_admin` and `privacy_auditor`; `clinician` and
  `reception` are refused.
- `com.aurevia.smartclinic.exchange.result.ResultRepository` and
  `db/migrations/V22__result_routing.sql` — persist the queue entry in `xch04_reconciliation`
  under row-level security (ENABLE and FORCE RLS, tenant policy), with the OPEN/conflict status
  constraints, immutable triggers and the queue index. The append-only `audit_event` table from
  `db/migrations/V1__tenant_schema.sql` and its chain trigger in `db/migrations/V3__audit_chain.sql`
  carry the audit evidence.
- `com.aurevia.smartclinic.api.ApiErrorHandler` — renders the `FORBIDDEN`, `INVALID_INPUT` and
  `INVALID_STATUS` error codes.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=UnmatchedConflictingReportsEnterReconciliationQueueTest test`
— `tests="9" errors="0" skipped="0" failures="0"` in the Surefire XML report. "Proven by tests"
is limited to the clause behaviour above; it does not claim that the xch04 routes are published
in the committed OpenAPI contract. Neighbouring tests `UnmatchedReportReviewAcceptanceTest` and
`ResultRoutingIntegrationTest` were read as fixtures and were not modified.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the xch04 routes
(`POST /api/v1/xch04/orders`, `POST /api/v1/xch04/results`,
`GET /api/v1/xch04/reconciliations` and the result/reconciliation sub-routes)
are already listed in `contracts/openapi/missing-routes.txt`. Publishing them would change the
contract and generated client — a separate ARC-04/WEB-05 contract task, not this test-only
change.
