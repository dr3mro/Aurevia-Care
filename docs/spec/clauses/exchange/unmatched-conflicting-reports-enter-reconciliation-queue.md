# XCH-04 clause 3 — unmatched or conflicting reports enter a reconciliation queue

## Clause

Unmatched or conflicting reports enter a reconciliation queue.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.exchange.clauses.UnmatchedConflictingReportsEnterReconciliationQueueTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/UnmatchedConflictingReportsEnterReconciliationQueueTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with two synthetic tenants and one synthetic
patient each. Each diverted report is proved three ways: the 202 response, the
persisted V22 queue row and the audit event; a fourth assertion proves that no
clinical revision was imported.

- `unmatchedReportWithoutRegisteredOrderEntersOpenReconciliationQueue` —
  importing a report for an unregistered order returns 202 with
  `reason=unmatched`, `status=open`, an empty `mismatches` array and the
  `no expected order registered for …` detail. The `xch04_reconciliation` row
  carries `reason='unmatched'`, `status='open'`, `mismatches=''` and the claimed
  order/accession/source; no `xch04_report_revision` row exists; one
  `XCH04_RESULT_RECONCILED` audit event is recorded for the returned entry id
  with `resource_type='RECONCILIATION_ENTRY'`, `outcome='SUCCESS'`,
  `purpose='XCH04_RESULT_ROUTING'` and `scope='EXCHANGE'`.
- `conflictingIdentifiersEnterOpenReconciliationQueueWithMismatches` — after
  registering an order with source `SRC-EXPECTED`, a report claiming
  `SRC-WRONG` returns 202 with `reason=conflict`, `status=open` and a
  `mismatches` array of exactly `["sourceId"]` (asserted as an array element,
  not by substring). The V22 row stores `reason='conflict'`,
  `mismatches='sourceId'`, `source_code='SRC-WRONG'`; no report is imported and
  the same reconciliation audit event is recorded.
- `clinicianCannotImportIntoTheQueue` — a role without `RESULT_IMPORT` receives
  403 `FORBIDDEN` for a fully valid report. No queue or report row is written,
  and the denial is itself audited as `XCH04_RESULT_IMPORT` with
  `outcome='DENIED'` for the order reference.
- `otherTenantCannotReadOrgAQueueEntries` — an authorized peer-tenant privacy
  auditor reads the queue as 200 with an empty page while the owning tenant's
  read returns 200 containing the new entry id (positive control). A JDBC read
  of that id under the peer tenant returns zero under FORCE RLS.
- `blankOrderIdIsRejectedBeforeEnqueue` — a blank order identifier is refused
  422 `INVALID_INPUT` before any matching, with no queue or report row.
- `clinicianCannotReadQueue` — a role without `RECONCILIATION_READ` (result
  queue read) receives 403 `FORBIDDEN`; the denial is audited as
  `XCH04_RECONCILIATION_READ` with `outcome='DENIED'`.
- `invalidStatusFilterIsRejected` — an authorized reader passing
  `?status=closed` receives 422 `INVALID_STATUS` before any queue query.

Production classes exercised through `POST /api/v1/xch04/orders`,
`POST /api/v1/xch04/results` and `GET /api/v1/xch04/reconciliations`:
`com.aurevia.smartclinic.exchange.result.ResultController` (thin HTTP adapter
returning 202 for a diverted outcome),
`com.aurevia.smartclinic.exchange.result.ResultRoutingService`
(`importResult`, `importGuarded`, `listReconciliation`, `authorize`, `writeAudit`),
`com.aurevia.smartclinic.exchange.result.ResultRules` (`match` yielding
`UNMATCHED` or `CONFLICT` with ordered mismatch names),
`com.aurevia.smartclinic.exchange.result.ResultModel` (`ReconciliationReason`,
`ReconciliationStatus`, `ReconciliationEntry`),
`com.aurevia.smartclinic.exchange.result.ResultActionPolicy` (role keys),
`com.aurevia.smartclinic.exchange.result.ResultRepository`
(`insertReconciliation`, `listReconciliation`),
`db/migrations/V22__result_routing.sql` (`xch04_reconciliation` with RLS, FORCE
RLS, tenant policy, immutable triggers and grants), and the append-only
`audit_event` table from `db/migrations/V1__tenant_schema.sql` with its chain
trigger in `db/migrations/V3__audit_chain.sql`.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=UnmatchedConflictingReportsEnterReconciliationQueueTest test`
— `tests="7" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the xch04 routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the xch04 routes
(`POST /api/v1/xch04/orders`, `POST /api/v1/xch04/results`,
`GET /api/v1/xch04/reconciliations` and the result/reconciliation sub-routes)
are already listed in `contracts/openapi/missing-routes.txt`. Publishing them
would change the contract and generated client — a separate ARC-04/WEB-05
contract task, not this test-only change.
