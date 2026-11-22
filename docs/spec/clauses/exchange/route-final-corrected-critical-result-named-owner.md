# XCH-04 clause 1 — API test evidence

## Clause

Route every final, corrected and critical result to a named owner and coverage queue.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.RouteFinalCorrectedCriticalResultNamedOwnerTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/RouteFinalCorrectedCriticalResultNamedOwnerTest.java`).
All four tests are active (none disabled) and pass against a real PostgreSQL database with
synthetic fixtures only. The suite recreates its own database from the authoritative Flyway
migrations, seeds two tenants with bounded roles, and drives the rule through the normal HTTP
entry points `POST /api/v1/xch04/orders` and `POST /api/v1/xch04/results` under the
`synthetic-auth` profile.

- `routesFinalCorrectedAndCriticalResultsToTheRegisteredOwnerAndQueue` — positive proof. One
  order registers `owner-a`/`queue-a`; a final, a corrected (`supersedesVersion=1`) and a
  critical result are imported against it. Each import returns 201 with `/kind` and the
  registered `/owner` and `/coverageQueue`; the versions are 1, 2 and 3 with three distinct
  revision ids and one predecessor link (corrected → final), and only the critical revision carries the `XCH04_CRITICAL_ROUTE` audit
  event. A second order registers `owner-b`/`queue-b` and its final result returns those
  different values, proving the destination comes from the matched order rather than a
  constant.
- `refusesImportForRoleWithoutResultImport` — negative proof of authorization. A `reception`
  member with active membership but no result-import permission receives 403, no report
  fields and no stored revision.
- `doesNotRouteAnotherTenantsOrder` — negative proof of tenant isolation. An order registered
  in the peer tenant is invisible under forced RLS, so an import in the active tenant is
  diverted to reconciliation (202, `reason=unmatched`, no mismatches) with an audit entry and
  no routed report in either tenant.
- `rejectsRegistrationWithoutOwnerOrCoverageQueue` — negative proof of the input boundary. A
  blank owner, a blank coverage queue, and an omitted owner each return 422 naming the
  missing field, so an order without a named destination can never be registered.

Production classes exercised through the entry points and their transaction:

- `com.aurevia.smartclinic.exchange.result.ResultController` — thin adapter binding the two
  POST routes and mapping an imported revision to 201 and a reconciliation entry to 202.
- `com.aurevia.smartclinic.exchange.result.ResultRoutingService` — `registerOrder` validates
  and stores the named owner and queue; `importResult` and `importGuarded` re-derive
  authorization, match identifiers, call `ResultRules.route`, and persist the routed owner
  and queue together with the immutable revision, review task and audit event.
- `com.aurevia.smartclinic.exchange.result.ResultRules` — `route` selects a rational per
  disposition and returns `order.owner()` and `order.coverageQueue()` for FINAL, CORRECTED
  and CRITICAL; `match` returns UNMATCHED for an order absent in the active tenant.
- `com.aurevia.smartclinic.exchange.result.ResultModel` — `ExpectedOrder` carries the named
  destination; `ResultReport` stores the resolved owner and coverage queue; `ResultKind`
  serializes the three dispositions in lower-case wire form.
- `com.aurevia.smartclinic.exchange.result.ResultActionPolicy` — bounds `RESULT_IMPORT` to the
  laboratory verifier role, which is why the reception member is refused.
- `com.aurevia.smartclinic.exchange.result.ResultRepository` — `findOrder` reads the tenant's
  expectations under enforced row-level security; the underlying tables, RLS policies and
  constraints are defined in `db/migrations/V22__result_routing.sql`.

The existing unit suite `com.aurevia.smartclinic.it.exchangeresult.ResultRulesTest` and HTTP
acceptance suite `com.aurevia.smartclinic.it.exchangeresult.ResultRoutingIntegrationTest`
cover the same behaviour; this task adds a focused, clause-only proof and changes neither.

## Gap

None. The rule is built and proven by active tests through the HTTP entry points. This task
added proof only; it did not change production code, an existing test, the OpenAPI contract or
a migration.
