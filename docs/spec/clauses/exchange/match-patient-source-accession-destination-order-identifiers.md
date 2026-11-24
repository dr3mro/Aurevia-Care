# XCH-04 clause 2 — match patient, source, accession, destination and order identifiers before import

## Clause

Match patient, source, accession, destination and order identifiers before import.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.exchange.clauses.MatchPatientSourceAccessionDestinationOrderIdentifiersTest`
(`apps/api/src/test/java/com/aurevia/exchange/clauses/MatchPatientSourceAccessionDestinationOrderIdentifiersTest.java`),
an HTTP-level test driven through `POST /api/v1/xch04/results` with standalone
MockMvc, the real `ResultController`, the real `ResultRoutingService` and the real
`ResultRules` domain decision. The repository and audit boundaries are mocked
with synthetic identifiers only; a synchronous transaction template and a fixed
clock make every case deterministic, and the tenant context is cleared after each
case. Every negative case verifies that the report and review ledgers stay
untouched.

- `allFiveIdentifiersMatchImportsTheReport` — a registered expectation whose
  patient, source, accession, destination and order identifiers all equal the
  inbound claim produces 201 with a routed `ResultReport` (version 1, the
  expectation's owner and coverage queue); `insertReport` and `insertReview` are
  called and `insertReconciliation` is never called.
- `mismatchedPatientIdIsDivertedToReconciliationWithoutImport` — 202 with
  `reason=conflict`, `status=open`, `mismatches=[patientId]` and the exact detail
  `identifier mismatch: patientId`; no report or review is written.
- `mismatchedSourceIdIsDivertedToReconciliationWithoutImport` — the same refusal
  naming `sourceId`.
- `mismatchedAccessionIdIsDivertedToReconciliationWithoutImport` — the same
  refusal naming `accessionId`.
- `mismatchedDestinationIdIsDivertedToReconciliationWithoutImport` — the same
  refusal naming `destinationId`.
- `unregisteredOrderIsDivertedToReconciliationAsUnmatched` — an order reference
  with no local expectation is refused as 202 with `reason=unmatched`, an empty
  mismatch list and no report write. The order identifier is the lookup key, so
  a wrong reference cannot resolve an expectation; the field-level `orderId`
  comparison branch is proved by
  `com.aurevia.it.exchangeresult.ResultRulesTest.eachMismatchedIdentifierIsReportedByName`
  (which asserts `orderId` alongside the other four names) and the repository
  always derives `ExpectedOrder.orderId` from the queried order reference.
- `callerWithoutResultImportRoleIsForbiddenBeforeMatching` — a caller with only
  the `reception` role is refused with 403 `FORBIDDEN`, a `DENIED` audit event and
  no order lookup or write.
- `callerWithoutActiveMembershipIsForbiddenBeforeMatching` — a caller with no
  active membership is refused with 403 `FORBIDDEN` and the same no-write proof.
- `orderNotVisibleInActiveTenantIsDivertedWithoutImport` — with an order lookup
  that yields no expectation visible in the active tenant, the request is refused
  as 202/unmatched with no report write, and the audit event is bound to the
  active tenant context. The mocked repository models the RLS outcome; it does
  not itself prove PostgreSQL isolation. That evidence is
  `com.aurevia.it.exchangeresult.ResultRoutingIntegrationTest.authorizationAndTenantIsolationFailClosed`
  (`apps/api/src/test/java/com/aurevia/it/exchangeresult/ResultRoutingIntegrationTest.java`),
  which imports the same order reference in two tenants and shows a cross-tenant
  read returns 404.

Entry-point-to-test trace:
`POST /api/v1/xch04/results` → `ResultController.importResult` →
`ResultRoutingService.importResult` → `ResultRoutingService.importGuarded` →
`ResultRules.match` → HTTP status, JSON body and repository-verification assertions.

Production classes exercised: `com.aurevia.exchange.result.ResultController`
(thin HTTP adapter), `com.aurevia.exchange.result.ResultRoutingService`
(validation, authorization and match-before-import transaction),
`com.aurevia.exchange.result.ResultRules` (five-facet matching and
routing), `com.aurevia.exchange.result.ResultModel` (`ResultIdentifiers`,
`ExpectedOrder`, `MatchStatus`, `ReconciliationEntry`, `ImportOutcome`),
`com.aurevia.exchange.result.ResultCommands` (`ImportResultRequest`),
`com.aurevia.exchange.result.ResultActionPolicy` (real role rules),
`com.aurevia.api.ApiErrorHandler` (403 rendering) and
`com.aurevia.tenant.TenantContext` (actor and organization boundary).

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=MatchPatientSourceAccessionDestinationOrderIdentifiersTest
test` — `tests="9" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim that
the xch04 routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the committed
OpenAPI contract publishes none of the seven `/api/v1/xch04/*` routes; all are
listed in `contracts/openapi.yaml` (lines 122-124 and 350-353).
Publishing them, adding the required operation tags and request headers, and
regenerating the client would change `contracts/openapi.yaml`, the generated
client and the contract tests — one separate ARC-04 contract task. No migration,
RLS policy, contract, dependency or production code change is needed for this
clause: the matching rule and the reconciliation queue are already implemented
and exercised by the suites cited above.
