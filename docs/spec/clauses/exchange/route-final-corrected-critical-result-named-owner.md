# XCH-04 clause 1 — API test evidence

## Clause

Route every final, corrected and critical result to a named owner and coverage queue.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.exchange.clauses.RouteFinalCorrectedCriticalResultNamedOwnerTest`
(path: `apps/api/src/test/java/com/aurevia/exchange/clauses/RouteFinalCorrectedCriticalResultNamedOwnerTest.java`).
All three tests are active (none disabled) and pass against a real PostgreSQL database with synthetic fixtures only.

- `everyFinalCorrectedAndCriticalResultIsRoutedToTheRegisteredOwnerAndCoverageQueue` — positive proof.
  One expected order is registered with the distinctive values `owner-named-route` / `queue-named-route`;
  a FINAL (version 1), a CORRECTED (`supersedesVersion = 1`, the current tip, version 2) and a CRITICAL
  (version 3) are imported through `POST /api/v1/xch04/results`. Every import response and every
  `GET /api/v1/xch04/results/{id}` detail (`/current/owner`, `/current/coverageQueue`, and the lineage
  entries) carries exactly those two values; three persisted `xch04_report_revision` rows carry them too.
  The critical marker is evidenced by `kind = "critical"` and exactly one `XCH04_CRITICAL_ROUTE`
  `SUCCESS` audit for that revision, with zero such audits for the final and corrected revisions;
  `ResultReport` and the revision table have no `critical` column, so no such field is asserted.
- `aReceptionActorCannotImportOrRouteAResult` — negative proof for the authorization boundary. The
  order is valid and registered by `admin-a`, but `reception-a` lacks `XCH04_RESULT_IMPORT`: the import
  returns 403 `FORBIDDEN`, writes no report and no reconciliation row, and leaves one `DENIED` audit.
- `aResultForAnotherTenantsOrderIsNotRoutedToThatTenantsOwner` — negative proof for tenant isolation.
  The order is registered only in ORG_B with a distinct peer owner and queue and references ORG_B's
  patient. An ORG_A caller necessarily carries an ORG_A patient, so the peer order is invisible under
  forced row level security and its identifiers differ; the result is diverted to reconciliation
  (202 `unmatched`, open). No ORG_A report for that order, and no report anywhere visible to ORG_A
  carrying the peer owner or queue, is created; the peer order remains in ORG_B. This proves the
  security outcome, not which single matching facet refused the import.

Production classes exercised through the normal HTTP entry points:

- `com.aurevia.exchange.result.ResultController` — `POST /api/v1/xch04/orders`,
  `POST /api/v1/xch04/results` (201 report / 202 reconciliation) and `GET /api/v1/xch04/results/{id}`;
  a thin adapter that injects the domain service and makes no routing decision.
- `com.aurevia.exchange.result.ResultRoutingService` — `registerOrder`, `importResult` and
  `importAuthorization`: re-derives membership and roles inside the tenant-guarded transaction, matches
  the five identifiers, routes the matched order, persists `routing.owner()` and `routing.coverageQueue()`
  on the new `ResultReport`, and writes the critical-route audit. Unmatched results go to reconciliation
  instead of creating a revision.
- `com.aurevia.exchange.result.ResultRules` — `match` (all five identifiers must agree) and
  `route` (switch over FINAL, CORRECTED and CRITICAL yielding the named owner and coverage queue).
- `com.aurevia.exchange.result.ResultActionPolicy`, `ResultRepository` — the
  `XCH04_RESULT_IMPORT` role set and the RLS-bounded SQL boundary.
- `db/migrations/V22__result_routing.sql` — `xch04_expected_order` and `xch04_report_revision` carry
  `owner_subject` and `coverage_queue`; both tables enable and force row level security.

This evidence proves the routing behavior only. No OpenAPI or generated-client coverage is claimed by
this test or this document; the route contract gap is recorded below.

## Gap

Clause behavior: none. Two documentation boundaries remain outside this story's scope:

1. Public contract coverage: `POST /api/v1/xch04/orders`, `POST /api/v1/xch04/results` and
   `GET /api/v1/xch04/results/{id}` are still listed as undocumented in
   `contracts/openapi.yaml` (lines 350, 352 and 124), and `contracts/openapi.yaml` has
   no xch04 paths. Closing it would change `contracts/openapi.yaml`, regenerate
   `contracts/openapi/generated/openapi.yaml` and remove the corresponding `missing-routes.txt` lines.
   That is one separate contract task; this story forbids contract changes.
2. Critical-route marker: the named owner and coverage queue are persisted and asserted, but the
   in-memory `Routing.critical` flag has no column on `xch04_report_revision` and no field on
   `ResultReport`. The tests observe it through `kind` and the `XCH04_CRITICAL_ROUTE` audit instead.
   Persisting a dedicated column would need a migration, repository, DTO and OpenAPI change — a separate
   task, and not required by this clause's sentence. Due-time escalation and clinician acknowledgment
   remain XCH-05 work.
