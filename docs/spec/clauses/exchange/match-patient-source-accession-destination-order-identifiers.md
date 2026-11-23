# XCH-04 clause 2 — API test evidence

## Clause

Match patient, source, accession, destination and order identifiers before import.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.exchange.clauses.MatchPatientSourceAccessionDestinationOrderIdentifiersTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/exchange/clauses/MatchPatientSourceAccessionDestinationOrderIdentifiersTest.java`).
All nine tests are active (none disabled) and pass against a real PostgreSQL database with synthetic
fixtures only. The suite recreates its own database from the authoritative Flyway migrations
(`filesystem:../../db/migrations`), seeds two isolated tenants with bounded roles and local patient
records, and drives the rule through the normal HTTP entry points under the `synthetic-auth` profile
using the `X-Synthetic-Subject` and `X-Active-Organization` headers. `POST /api/v1/xch04/orders` registers
the expectation as `tenant_admin`; `POST /api/v1/xch04/results` imports as `lab_verifier` and returns
201 for an import or 202 for a diversion; `GET /api/v1/xch04/reconciliations` exposes the queue.

- `importsWhenAllFiveIdentifiersMatch` — positive proof. With every identifier equal to the registered
  expectation, the import returns 201, version 1, the owner and coverage queue from the expectation,
  an immutable report revision and a review task are persisted, and no reconciliation entry is created.
- `divertsToReconciliationWhenPatientIdentifierDiffers` — negative proof of the patient facet. A second
  local patient identifier yields 202, `reason=conflict`, `mismatches=["patientId"]`, no report revision
  and one local reconciliation entry.
- `divertsToReconciliationWhenSourceIdentifierDiffers` — negative proof of the source facet (202,
  `mismatches=["sourceId"]`).
- `divertsToReconciliationWhenAccessionIdentifierDiffers` — negative proof of the accession facet (202,
  `mismatches=["accessionId"]`).
- `divertsToReconciliationWhenDestinationIdentifierDiffers` — negative proof of the destination facet
  (202, `mismatches=["destinationId"]`).
- `divertsToReconciliationWhenOrderIdentifierHasNoRegisteredExpectation` — negative proof of the order
  facet at the HTTP boundary. The inbound order identifier is the repository lookup key, so an order
  that was never registered resolves to no expectation: the result is diverted with 202,
  `reason=unmatched` and an empty mismatch list, and no report revision is created.
- `deniesImportToRoleWithoutResultImport` — negative proof of authorization. A `privacy_auditor` with
  active membership but no result-import permission receives 403; neither a report nor a reconciliation
  entry is created because authorization runs before matching.
- `doesNotImportAnotherTenantsMatchingOrder` — negative proof of tenant isolation. An order registered
  in the peer tenant cannot authorize an import in the local tenant (202 `unmatched`, no report in
  either tenant, queue entry only in the local tenant), and a local actor under the peer organization
  is refused with 403 for lack of active membership.
- `rejectsMissingOrMalformedIdentifier` — negative proof of boundary validation. A blank order
  identifier and a non-UUID patient identifier are both rejected with 422 and persisted nowhere.

Production classes exercised through the entry points and their transaction:

- `com.aurevia.smartclinic.exchange.result.ResultController` — thin adapter binding the three routes;
  it distinguishes a 201 imported report from a 202 reconciliation entry by the outcome's shape.
- `com.aurevia.smartclinic.exchange.result.ResultRoutingService` — `importResult` validates the five
  identifiers at the trust boundary, re-derives authorization and calls `importGuarded`, which matches
  before any report, review or notification insert and diverts non-MATCHED outcomes to reconciliation.
- `com.aurevia.smartclinic.exchange.result.ResultRules` — pure five-facet `match`: an absent expectation
  is `UNMATCHED`, any disagreeing facet is `CONFLICT` with the disagreeing field names in stable order,
  and all five agreeing is `MATCHED`.
- `com.aurevia.smartclinic.exchange.result.ResultRepository` — tenant-bounded lookup of the expectation
  by order reference and inserts into the append-only ledger; every statement runs under forced RLS.
- `com.aurevia.smartclinic.exchange.result.ResultActionPolicy` — limits order registration to
  `tenant_admin` and import to `lab_verifier`, which is why the auditor and peer-actor denials occur.
- `db/migrations/V22__result_routing.sql` — `xch04_expected_order`, `xch04_report_revision`,
  `xch04_reconciliation` and `xch04_review` with forced RLS and tenant-and-patient foreign keys.

The existing unit suite `com.aurevia.smartclinic.it.exchangeresult.ResultRulesTest` and HTTP acceptance
suite `com.aurevia.smartclinic.it.exchangeresult.ResultRoutingIntegrationTest` cover the same behavior;
this task adds a focused, clause-only proof and changes neither.

## Gap

None for the matching rule: it is built and proven by active tests through the HTTP entry point. No
production code, existing test, contract or migration was changed.

One observable nuance is recorded rather than hidden. `ResultRules.match` compares all five identifiers,
including `orderId`, and the pure unit test `ResultRulesTest.eachMismatchedIdentifierIsReportedByName`
proves that a differing order identifier is named as a `CONFLICT`. Through HTTP the order identifier is
also the repository lookup key (`ResultRepository.findOrder(identifiers.orderId())`), so an inbound
order identifier that differs from every registered expectation necessarily resolves to no expectation
and is correctly reported as `UNMATCHED`, not as a `CONFLICT` naming `orderId`. Both are refusals to
import; this difference is a consequence of the lookup design, not an untested fifth facet.

One adjacent, non-blocking debt is recorded for completeness: `POST /api/v1/xch04/orders` and
`POST /api/v1/xch04/results` are listed in `contracts/openapi/missing-routes.txt` as baselined routes,
so these operations are not yet published in the authoritative OpenAPI contract. Publishing them (and
regenerating the staff client) is a separate contract task under WEB-05, outside this clause's
test-only scope and not required to prove the matching rule.
