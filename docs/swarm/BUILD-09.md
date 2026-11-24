# BUILD-09 · Versioned drug catalog search with market approval

PR #304, tested branch tip 9708b6402db02cfb659966c6cea9d00d785deb6b.

Task: BUILD-09

Swarm worker 1. Local checks: passed. Agent result: ok.

<!-- PROJECT_V1_SPEC.md section 11: every implementation pull request records the items below. Use synthetic data only. -->

## Summary

Adds a published, market-approved medication catalog version to RX-01. A versioned search returns the catalog version it used together with route-bearing approved items; a prescription draft binds that version when a medication is selected and keeps it across later publications. Unapproved, future and retired items are rejected on selection. V76 adds the registry and columns; no existing record signature, test, migration or dependency was changed.

## Affected requirements

RX-01 (primary). ARC-01/ARC-02/OPS-02 for the tenant-owned, forced-RLS append-only registry in V76.

## Risk class

Safety-critical (prescribing) with cross-boundary DB/API changes. Clinical review was requested from the physician specialist and is advisory/simulated only; no authorized human clinical acceptance is claimed.

## Review owner

Senior Backend, Senior Database, Test Engineer and Physician Reviewer (bounded advisory reviews, run in parallel). Primary agent owns the edits and all executed evidence.

## Data changes

`db/migrations/V76__build_09.sql` (reserved version V76, `Change: expand`):
- `drug_catalog_entry.route text NOT NULL DEFAULT 'unspecified'` (additive metadata; the sentinel is deliberate, ALTER TABLE does not fire the V43 row triggers).
- `prescription_draft.catalog_version integer` (nullable; a draft with no medication has no catalog version).
- New append-only `public.medication_catalog_version` registry: `(organization_id, version, market, published_at, provenance)`, PK `(organization_id, version)`, ENABLE+FORCE RLS, tenant policy, GRANT SELECT/INSERT, REVOKE UPDATE/DELETE/TRUNCATE to `orevia_app`, new immutability function and triggers. V43 functions were not touched.
- No seed rows: organizations do not exist at Flyway time under forced RLS. Readers fall back to documented baseline version 1 until a version is published.
- Rollback/forward-fix: additive expand only; no destructive DDL. Removing the columns is a separate contract migration. Lock/volume impact negligible (two metadata columns and one small append-only registry).

## Authorization boundaries and audit events

Unchanged. Both new endpoints reuse existing action keys (`RX_SEARCH_CATALOG`, `RX_READ_DRAFT`) and the same tenant membership/role fold and append-only audit path as the existing prescription endpoints. No new audit purpose, no caller-supplied author, no patient data in URLs or logs.

## Migration and rollout considerations

Migration applies as V76 by Flyway and by `db/check.sh` (`sort -V`); verified by Flyway in the integration test (33 migrations applied, now v76) and by `db/check.sh` in the worker gate. Forced RLS on the new table was verified by the worker gate's database policy checks.

## Test evidence

Entry-point-to-test trace:
- `GET /api/v1/drug-catalog/search-with-version` → `DrugCatalogController.searchWithVersion` → `PrescriptionService.searchCatalogWithVersion` → `MedicationCatalog.search` (reuses `DrugCatalog`) → `PrescriptionRepository.currentCatalogVersion/loadMedicationCatalog` → `VersionedCatalogIntegrationTest.versionedSearchReturnsThePublishedVersionAndApprovedRoute` and `baselineVersionIsReturnedWhenNoVersionWasPublished` (HTTP + PostgreSQL).
- `POST /api/v1/prescription-drafts/{id}/select-medication` → `PrescriptionService.selectMedication` → `PrescriptionRepository.selectMedication` (COALESCE) → `GET /api/v1/prescription-drafts/{id}/catalog-version` → `PrescriptionController.catalogVersion` → `VersionedCatalogIntegrationTest.aDraftKeepsItsCatalogVersionAfterANewPublication`.
- Rejection of unapproved/retired items → `VersionedCatalogIntegrationTest.unapprovedAndRetiredItemsCannotBeAddedToANewDraft` and `VersionedPrescriptionCatalogTest.{anUnapprovedItemCannotBeAddedToADraft,aRetiredItemCannotBeAddedToADraft,aFutureItemCannotBeAddedToADraft}`.

Commands actually run (all from repo root; tool evidence, simulated test fixtures only):
1. `mvn -q -B -Pno-security -f apps/api/pom.xml -Dtest=MedicationCatalogTest,VersionedPrescriptionCatalogTest,VersionedCatalogIntegrationTest test` → exit 0; Surefire: `MedicationCatalogTest` 7/7, `VersionedPrescriptionCatalogTest` 10/10, `VersionedCatalogIntegrationTest` 4/4, 0 failures/errors.
2. Targeted coverage (see Coverage) → exit 0.
3. `bash scripts/worker-check.sh` → exit 0; final line: `worker check: quick gate, frontend standards, integration tests and database policy passed` (includes quick-check policy + unit tests, frontend standards, SpotBugs, all integration tests and `db/check.sh`).
4. `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests ... OK`.
5. `python3 -I scripts/check-migrations.py`, `check-names.py`, `check-wiring.py`, `check-docs.py`, `check-secrets.py` → all PASS. `git diff --check` → PASS.
6. Flyway log in the integration test: `Successfully applied 33 migrations ... now at version v76`.

## Coverage

Command: `mvn -q -B -Pno-security -f apps/api/pom.xml -Dtest=MedicationCatalogTest,VersionedPrescriptionCatalogTest,VersionedCatalogIntegrationTest,AllergyCheckerTest,DrugCatalogTest,PrescriptionDomainTest,PrescriptionFlowTest,PrescriptionHttpSecurityTest,RxContractTest,RxTransmissionFlowTest,RxHttpSecurityTest,ContractValidationTest test jacoco:report` → exit 0. Report read: `apps/api/target/site/jacoco/jacoco.xml`. Every touched class is 100% for INSTRUCTION, LINE, BRANCH and METHOD:

| Class | I / L / B / M |
|---|---|
| catalog/MedicationCatalog | 84/84, 16/16, 4/4, 6/6 |
| catalog/MedicationCatalogVersion | 67/67, 11/11, 12/12, 2/2 |
| catalog/MedicationCatalogItem | 30/30, 6/6, 6/6, 1/1 |
| catalog/VersionedCatalogSearch | 34/34, 8/8, 4/4, 2/2 |
| prescription/DraftCatalogVersion | 21/21, 4/4, 4/4, 1/1 |
| prescription/PrescriptionService | 705/705, 123/123, 22/22, 28/28 |
| prescription/PrescriptionRepository | 513/513, 96/96, 10/10, 26/26 |
| prescription/DrugCatalogController | 18/18, 5/5, 0/0, 3/3 |
| prescription/PrescriptionController | 43/43, 10/10, 0/0, 6/6 |

New branches and their tests:
- `MedicationCatalogVersion`: version<1, market null, market blank, publishedAt null, provenance null, provenance blank → `catalogVersionValidatesItsIdentityAndProvenance`; all-valid path → same test and `versionedSearchReturnsThePublishedVersionAndApprovedRoute`.
- `MedicationCatalogItem`: drug null, route null, route blank → `medicationItemValidatesItsDrugAndRoute`; valid → same test.
- `VersionedCatalogSearch`: version null, items null → `searchResultFreezesItsItemsAgainstCallerMutation`; defensive-copy accessor + non-null path → same test (`UnsupportedOperationException` on clear).
- `DraftCatalogVersion`: draftId null, draftId blank → `draftCatalogVersionValidatesItsIdentifier`; valid → same test and `draftCatalogVersionIsReadThroughTheDomainService`.
- `MedicationCatalog`: items null, version null → `catalogRejectsMissingItemsAndVersion`; effective/unapproved/future/retired filtering, blank and exact filters, newest-version fold, equal-version merge and empty result → `searchReturnsOnlyNewestApprovedEffectiveItemsWithTheirRoute`, `equalVersionKeepsTheFirstFoldedItem`, `emptyCatalogStillReturnsTheVersionItWasBuiltFrom`.
- `PrescriptionService.searchCatalogWithVersion`: input validation (422) → `searchValidatesInputBeforeTouchingTheRepository`; denial (403) → `searchDeniesAnInactiveSubject`; success/authorization success → `searchReturnsTheCatalogVersionUsedAndRouteAwareItems`.
- `PrescriptionService.catalogVersion`: uuid validation → `draftCatalogVersionValidatesItsIdentifier`; not found (404) → `draftCatalogVersionConcealsAnInvisibleDraft`; success → `draftCatalogVersionIsReadThroughTheDomainService`.
- `PrescriptionService.selectMedication` new version binding → `selectionStoresTheCatalogVersionItWasBuiltFrom`; unapproved/retired/future re
