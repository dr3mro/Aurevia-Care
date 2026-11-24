# XCH-05 clause 5 — report overdue items, roster gaps and failed notification attempts

## Clause

Report overdue items, roster gaps and failed notification attempts.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.exchange.clauses.ReportOverdueItemsRosterGapsFailedNotificationTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/ReportOverdueItemsRosterGapsFailedNotificationTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants:

- `reportListsOverdueAlertsRosterGapsAndFailedNotifications` — one synthetic alert is
  driven past its deadline (overdue), given an unavailable approved backup
  recipient (roster gap) and a recorded failed escalation attempt (failed
  notification). `GET /api/v1/critical-results/report` returns that exact
  result id in `overdue` and that exact id bound to the `roster_gap` and
  `escalation_attempt`/`failed` events. A control alert with a future deadline
  and a delivered attempt is asserted absent from all three sections.
- `reportIsForbiddenForReceptionRole` — a role without `CRITICAL_READ` is refused
  the report (403).
- `reportFromPeerTenantIsEmpty` — a tenant that owns no critical results gets
  three empty arrays; another tenant is seeded with a reportable fact first so
  the empty result proves the tenant filter rather than an empty database.

Production classes exercised by the entry point:
`com.aurevia.exchange.critical.CriticalResultController` (thin
adapter for `GET /api/v1/critical-results/report`),
`com.aurevia.exchange.critical.CriticalResultService` (`report`,
`execute`, `authorize`),
`com.aurevia.exchange.critical.CriticalActions` (READ action
matrix),
`com.aurevia.exchange.critical.CriticalResultRepository`
(`overdue`, `rosterGaps`, `failedNotifications`, `eventsOfType`),
`com.aurevia.exchange.critical.CriticalContract.Report` and the
RLS/FORCE RLS tables in `db/migrations/V23__critical_results.sql`.

The clause is also proven end to end in
`com.aurevia.it.exchangeresult.CriticalResultFlowTest`
(`reportSurfacesOverdueAlertsAndRosterGaps`,
`overdueAndSystemDownUseApprovedBackupChannel` and
`authorizationAndTenantIsolationHoldOnEveryCommand`); this per-clause suite
adds exact result-id binding on every reported row.

## Gap

None.
