# RX-01 clause 3 — API test evidence

## Clause

Show tagged catalog alternatives for prescriber review.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.prescription.clauses.ShowTaggedCatalogAlternativesPrescriberReviewTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/ShowTaggedCatalogAlternativesPrescriberReviewTest.java`).
All six tests are active (none are disabled) and use synthetic catalog keys, subjects and
provenance strings only; no patient data or credentials are involved. Five tests drive
`PrescriptionService.searchCatalog`, the entry point that `DrugCatalogController.search`
delegates to, and assert on the returned `CatalogHit`/`Alternative` values and the audit row;
the sixth asserts the null-selected fold directly on `DrugCatalog`.

- `searchCatalogShowsTaggedReviewOnlyAlternativesForAPrescriber` — positive proof. An active
  clinician searches the catalog; each alternative on the selected hit is tagged
  `AlternativeKind.SUGGESTION` (wire key `suggestion`), carries a non-blank reason and its own
  `approvalProvenance` as `sourceCatalogVersion`, requires clinician selection, and excludes the
  selected entry. The generic-name and shared-substance/shared-group relations are distinguished
  by their reasons, suggestions are sorted by entry id, only the newest effective version is
  suggested, and alternatives are computed per hit (an unrelated hit has none). The search writes
  a `SUCCESS` audit row.
- `searchCatalogRefusesASubjectWithoutCatalogPermission` — negative proof of role. A subject with
  active membership but the `reception` role is refused with HTTP 403 `FORBIDDEN` and the
  role-fold message; no catalog read occurs and the denial is audited.
- `searchCatalogRefusesAnInactiveSubject` — negative proof of membership. An inactive subject is
  refused with HTTP 403 `FORBIDDEN` and the membership-fold message before any role lookup or
  catalog read; the denial is audited.
- `alternativesHideUnapprovedExpiredAndFutureVersions` — negative proof of effective dating.
  Identity-linked but unapproved, expired (`effectiveTo == AT`) and future
  (`effectiveFrom > AT`) versions are neither hits nor alternatives; only the approved, in-window
  entry is returned, with no alternatives.
- `emptyCatalogShowsNoAlternatives` — fail-closed proof. An empty catalog yields no hits, with a
  `SUCCESS` audit row.
- `missingSelectionFailsClosedWithoutInventingAlternatives` — fail-closed proof at the fold
  boundary. A null selected entry returns no alternatives. The service always passes an effective
  entry, so this path is asserted by calling `DrugCatalog` directly.

Production classes exercised through the normal command entry point
(`PrescriptionService.searchCatalog`):

- `com.aurevia.smartclinic.prescription.PrescriptionService` — owns the tenant-guarded
  authorization, the catalog fold, per-hit alternative attachment and the `SUCCESS`/`DENIED`
  audit pair for the search command.
- `com.aurevia.smartclinic.prescription.DrugCatalog` — the effective-dated fold: `search`,
  `alternativesFor`, `effectiveEntries` and `sharesIdentity` decide which entries and tagged
  suggestions are visible to the prescriber.
- `com.aurevia.smartclinic.prescription.PrescriptionContract` — `Alternative`, `AlternativeKind`,
  `CatalogHit`, `DrugEntry` and `Ingredient` carry the suggestion tag, reason, provenance and
  structured identity that make a review-only alternative safe to display.
- `com.aurevia.smartclinic.prescription.PrescriptionActions` — `SEARCH_CATALOG` is granted to
  clinician, tenant admin and privacy auditor, and withheld from other roles such as reception.
- `com.aurevia.smartclinic.prescription.PrescriptionRepository.loadCatalog` — the tenant-guarded
  SQL boundary; mocked here. The real RLS/FORCE RLS behaviour is proven by the existing HTTP-level
  integration tests, not by this mocked-repository test.
- `com.aurevia.smartclinic.audit.AuditAppender` — the transactional audit sink asserted above.

Neighbouring implementations observed in the source survey (not changed by this task): the
thin HTTP adapter `com.aurevia.smartclinic.prescription.DrugCatalogController` for
`GET /api/v1/drug-catalog/search`, which delegates to the service but is not directly exercised
by this service-level proof; the existing unit proof in `it.prescription.DrugCatalogTest`, the action-matrix proof in
`it.prescription.PrescriptionDomainTest.actionMatrixGrantsWritesOnlyToClinicians`, the real
HTTP/PostgreSQL proof in `it.prescription.PrescriptionFlowTest` (`fullWorkflowKeepsValidationOverrideAndAuditSeparate`,
`searchHidesUnapprovedAndFutureVersionsAndForcesRLS`, `authorizationAndTenantIsolationHoldOnEveryCommand`)
and `it.prescription.PrescriptionHttpSecurityTest`, and the versioned-search proof in
`prescription.VersionedPrescriptionCatalogTest`. The route remains baselined as missing from
OpenAPI in `contracts/openapi.yaml`; that contract debt is outside this task's scope.

## Gap

None.

The clause is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the contract or a migration.
