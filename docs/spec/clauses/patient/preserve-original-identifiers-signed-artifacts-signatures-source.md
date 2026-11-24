# IAM-04 clause 5 — Preserve original identifiers, signed artifacts, signatures and source references

## Clause

Preserve original identifiers, signed artifacts, signatures and source references.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.patient.clauses.PreserveOriginalIdentifiersSignedArtifactsSignaturesSourceTest`
(`apps/api/src/test/java/com/aurevia/patient/clauses/PreserveOriginalIdentifiersSignedArtifactsSignaturesSourceTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry points `POST /api/v1/patients`, `POST /api/v1/duplicate-reviews` and
`POST /api/v1/duplicate-reviews/{reviewId}/consolidate` with synthetic records and then
reads the append-only tables under forced row-level security.

- `consolidationSnapshotsTheDuplicatesOriginalIdentifiersOnTheAssociation` (rule met) —
  after a second-reviewer consolidation the `chart_association` row for the merged
  duplicate carries its original issuer-scoped MRN (`preserved_mrn`, `preserved_issuer`)
  and its opaque identifier and consent evidence (`preserved_identifiers`,
  `preserved_consents`) verbatim; the original patient row still holds its own MRN.
- `staleVersionRefusalWritesNoPreservationSnapshot` (negative) — a stale `If-Match`
  precondition (412) appends no association or consolidation row, so no partial or
  stale preservation snapshot can be produced.
- `repeatedConsolidationIsRefusedKeepingOnePreservationSnapshot` (negative) — a second
  approval of the same review is refused (409), leaving exactly one `ACTIVE`
  association and one consolidation event, so a single authoritative preserved
  identity remains.
- `consolidationPreservesSignedArtifactsSignaturesAndSourceReferences` — complete but
  `@Disabled("GAP: ...")`, documenting the uncovered part of the clause (see Gap).

Production classes and files exercised by the request path:

- `com.aurevia.web.PatientController` — registration entry point whose
  `RegisterRequest` accepts the opaque `identifiers` and `consents` evidence text.
- `com.aurevia.patient.consolidation.ConsolidationController` — thin HTTP
  adapter for the duplicate-review worklist, open and consolidate commands.
- `com.aurevia.patient.consolidation.ConsolidationService` — domain
  invariants and the snapshot call `insertAssociation(...)` that passes the duplicate's
  MRN, issuer, identifiers and consents to the store.
- `com.aurevia.patient.consolidation.DuplicateRepository` — the RLS-bounded
  SQL boundary writing the preserved columns on `chart_association`.
- `db/migrations/V9__duplicate_review_consolidation.sql` — the append-only
  `chart_association` table with `preserved_mrn`, `preserved_issuer`,
  `preserved_identifiers`, `preserved_consents`, forced RLS and append-only triggers;
  `db/migrations/V2__rls_roles_policies.sql` — forced row-level security keyed on
  `app.current_org()`.
- Discovery note: the `/api/v1/duplicate-reviews/**` operations are currently baselined
  in `contracts/openapi.yaml` rather than published in `contracts/openapi.yaml`;
  adding them to the contract is outside this test-only story's scope.

Verified locally with the command and final line from the surefire report:

```
OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security \
  -Dtest=PreserveOriginalIdentifiersSignedArtifactsSignaturesSourceTest test
Tests run: 4, Failures: 0, Errors: 0, Skipped: 1
```

(The single skipped test is the intentionally disabled GAP test above.)

## Gap

The merged duplicate's original identifiers are preserved as first-class columns and
its identifier/consent evidence as opaque JSON, but signed artifacts, signatures and
source references are only preserved incidentally: they have no dedicated, typed
`preserved_*` columns on `chart_association`, no service code copying them from the
duplicate's signed content, and no read-path proof that the duplicate's signed notes
or documents and their provenance remain attributable after chart visibility moves to
the survivor. The disabled test would assert `preserved_signed_artifacts`,
`preserved_signatures` and `preserved_source_references` on the association.

Closing the gap needs two separate tasks:

1. A schema and service task: add the three preserved columns to a new Flyway migration
   over `chart_association` (append-only, forced RLS, grants) and extend
   `DuplicateRepository.insertAssociation` / `ConsolidationService` to snapshot the
   signed-artifact, signature and source-reference data from the duplicate.
2. A read-path/test task: expose or resolve those preserved values after consolidation
   and enable the disabled test with real signed synthetic evidence.

Neither task is in this story's test-only scope; no production code, migration or
contract was changed here.
