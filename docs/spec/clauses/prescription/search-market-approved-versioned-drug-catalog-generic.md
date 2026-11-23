# RX-01 clause 2 — API test evidence

## Clause

Search a market-approved versioned drug catalog by generic/trade name and formulation.

## Status

Proven by tests

## Evidence

Scope: this status covers the catalog-search rule named in the clause. Publishing the two search routes in the OpenAPI contract is a separate tracked concern (see `## Gap`) and is not claimed here.

The clause test is [SearchMarketApprovedVersionedDrugCatalogGenericTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/prescription/clauses/SearchMarketApprovedVersionedDrugCatalogGenericTest.java). All four tests are active (none disabled) and pass with synthetic data only. Fixtures mirror the neighbouring `VersionedPrescriptionCatalogTest`: mocked `PrescriptionRepository` and `AuditAppender`, an inline `TransactionTemplate`, a fixed `Clock`, and a `TenantContext` set per test and cleared afterwards.

- `searchFindsApprovedEffectiveEntryByGenericOrTradeNameAndFormulation` — positive proof. An active clinician searches "amox" with formulation "tablet" and receives exactly one approved, effective, newest-version tablet entry; an unapproved entry, a retired entry, a future entry and a superseded older version are all excluded. The hit carries a tagged `SUGGESTION` alternative discovered through shared ingredient identity, marked as requiring clinician selection. The same search through the versioned endpoint reports the published version, market and provenance plus one route-bearing item; the alternative (different name and formulation) is not a hit. Both reads append a `SUCCESS` audit row.
- `searchDoesNotReturnAnEntryWithADifferentFormulation` — negative proof (formulation boundary). With both a tablet and a capsule in the catalog, "tablet" returns only the tablet, "capsule" only the capsule, and "syrup" returns nothing; the tablet hit also tags the capsule as a same-generic-name alternative, so alternatives are review suggestions rather than extra hits.
- `searchDeniesASubjectWithoutActiveMembership` — negative proof (authorization). A subject without active membership gets 403 `FORBIDDEN`, the catalog repository is never read and a `DENIED` audit row is appended before any search.
- `searchRejectsAnOverlongQueryBeforeTouchingTheRepository` — negative proof (input boundary). A 129-character query gets 422 `INVALID_QUERY` before authorization; neither membership nor the catalog is read.

Production classes exercised through their normal entry points:

- [PrescriptionService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/PrescriptionService.java) — `searchCatalog` and `searchCatalogWithVersion` validate input before the tenant-guarded transaction, re-derive membership and roles on every request, append `DENIED`/`SUCCESS` audit rows and return hits with tagged alternatives or the published version.
- [DrugCatalogController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/DrugCatalogController.java) — thin HTTP adapter exposing `GET /api/v1/drug-catalog/search` and `GET /api/v1/drug-catalog/search-with-version`; the test exercises both delegated service methods.
- [DrugCatalog.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/DrugCatalog.java) and [MedicationCatalog.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/MedicationCatalog.java) — the effective-dated folds: newest approved version per entry, case-insensitive generic/trade name match, exact formulation match, effective-window filter, tagged review-only alternatives and route-bearing items.
- [PrescriptionContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/PrescriptionContract.java), [MedicationCatalogVersion.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/MedicationCatalogVersion.java) and [VersionedCatalogSearch.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/catalog/VersionedCatalogSearch.java) — the drug entry and its approval/effective window, the tagged alternative and catalog hit, the published version and the immutable versioned result.
- [PrescriptionActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/prescription/PrescriptionActions.java) — the bounded action matrix that admits clinical and governance roles to the catalog search action.

Neighbouring implementations observed in the source survey (not changed by this task): [VersionedCatalogIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/prescription/VersionedCatalogIntegrationTest.java) proves the versioned endpoint over real PostgreSQL with RLS-scoped roles across multiple synthetic organizations, but this test-only story does not claim cross-tenant negative proof; `DrugCatalogTest` and `MedicationCatalogTest` cover the domain folds; the catalog version and entry tables live in the Flyway migrations under `db/migrations`.

## Gap

None for the search rule this clause names: approval, effective-window, newest-version, name/formulation, version metadata and tagged-alternative behaviour are all implemented and proven by active tests. Separately, both GET routes are listed in `contracts/openapi.yaml` (lines 40–41) rather than published in `contracts/openapi.yaml`; that publication is already tracked by `FIX-OPENAPI-02` and is a contract-publication concern, not a missing part of this clause's behaviour. No production file, existing test, contract or migration is changed by this test-only story.
