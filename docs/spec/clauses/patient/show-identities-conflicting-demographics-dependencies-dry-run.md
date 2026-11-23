# IAM-04 clause 2 — Show both identities, conflicting demographics, dependencies, and a dry run

## Clause

Show both identities, conflicting demographics, dependencies, and a dry run.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.patient.clauses.ShowIdentitiesConflictingDemographicsDependenciesDryRunTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/patient/clauses/ShowIdentitiesConflictingDemographicsDependenciesDryRunTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives
the HTTP entry point `GET /api/v1/duplicate-reviews/{survivorId}/{duplicateId}`
against two synthetic same-tenant patients with the same normalized name and date
of birth but different MRN, issuer, display spelling and language, plus one
ambiguous unresolved order dependency.

- `reviewShowsBothIdentitiesConflictsDependenciesAndDryRun` — the response carries
  both identities, the conflicting demographics as side-by-side fields (`mrn`,
  `mrnIssuer`, `displayName`, `language`), the dependency (`ORDER`, `order-9`), a
  textual dry run naming the move and preserved MRN with `blockers=1`, and the
  unchanged `chartVisibilityId`. The read does not mutate visibility.
- `reviewRefusesCallerWithoutReviewPermission` (negative) — a `reception` subject
  is refused with HTTP 403; identity review is a privacy/tenant-admin capability.
- `reviewRefusesPeerTenant` (negative) — a subject with no membership in the
  selected organization is refused with HTTP 403, and an active peer-tenant
  subject gets HTTP 404 because the other tenant's patients are invisible under
  forced row-level security.
- `reviewRefusesInvalidPairAndMalformedIdentifier` (negative) — a patient paired
  with itself and a malformed identifier are refused with HTTP 422 before any
  query runs.

Production classes and files exercised by the request path:

- `com.aurevia.smartclinic.patient.consolidation.ConsolidationController` — the
  thin HTTP adapter binding `GET /api/v1/duplicate-reviews/{survivorId}/{duplicateId}`.
- `com.aurevia.smartclinic.patient.consolidation.ConsolidationService.review` —
  authorizes the read, loads both patients, folds the conflicts, dependencies and
  dry run, and appends the audit event; it does not change patient data.
- `com.aurevia.smartclinic.patient.consolidation.ReviewDetail` — the immutable
  view carrying both patients, `Conflict` and `Dependency` lists, `dryRun` and
  `chartVisibilityId`.
- `com.aurevia.smartclinic.patient.consolidation.DuplicatePolicy` — bounds the
  review action to `tenant_admin` and `privacy_auditor`.
- `com.aurevia.smartclinic.patient.consolidation.DuplicateRepository` — the
  RLS-bounded SQL boundary for patients, dependencies and effective visibility.
- `com.aurevia.smartclinic.patient.Patient` — the identity record whose original
  spelling, MRN and issuer are preserved in the conflicts and dry run.
- `db/migrations/V9__duplicate_review_consolidation.sql` — the forced-RLS tables
  and append-only folds backing the review. The
  `GET /api/v1/duplicate-reviews/{}/{}` route is currently baselined in
  `contracts/openapi.yaml` rather than published in
  `contracts/openapi.yaml`; that is a contract-wiring follow-up, not part of the
  clause behaviour proved here.

## Gap

None.
