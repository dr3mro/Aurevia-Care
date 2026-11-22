# CLN-03 clause 4 — support local procedure codes and a versioned market diagnosis catalog

## Clause

Support local procedure codes and a versioned market diagnosis catalog.

## Status

Proven by tests

## Evidence

Test class
`com.aurevia.smartclinic.chart.clauses.SupportLocalProcedureCodesVersionedMarketDiagnosisTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/SupportLocalProcedureCodesVersionedMarketDiagnosisTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
synthetic catalog codes only. Catalog drafts and approvals are written through
`POST /api/v1/catalogs/{family}/entries`, a chart PROBLEM is recorded through
`POST /api/v1/chart/records/entries`, and the resulting mapped snapshot is read
from that HTTP response:

- `mapsTheLatestApprovedDiagnosisCatalogVersionAndRetainsTheLocalCodeAndText` —
  a diagnosis catalog local code `R51` is defined as version 1 and approved with
  market text, then redefined as version 2 and approved; a chart PROBLEM carrying
  the local code maps to the effective approved version 2 (`mappedText`,
  `mappedCode`, `catalogVersion = 2`) while `originalCode` and `originalText` stay
  unchanged (201).
- `doesNotMapAnUnapprovedDraftCatalogVersion` — with an approved version 1 and an
  unapproved draft version 2, the chart entry maps only the approved version 1;
  the draft market text is refused as a mapping source (201).
- `retainsALocalProcedureCodeVerbatimWithoutSupplyingAMarketMapping` — a chart
  PROBLEM carrying the tenant-local procedure code `PROC-42` in code system
  `local-procedure` keeps the code and system verbatim and supplies no market
  mapping (`mappedCode`, `mappedText`, `catalogVersion` all absent), showing local
  procedure codes are supported without a market entry (201).
- `doesNotMapACatalogEntryOwnedByAnotherTenant` — a diagnosis catalog entry
  approved in one tenant is invisible to a second tenant recording the same local
  code; no market mapping leaks across the forced-RLS tenant boundary (201).

Production classes exercised by these entry points:

- `com.aurevia.smartclinic.web.CatalogController` and
  `com.aurevia.smartclinic.catalog.CatalogService` — bounded catalog families and
  tenant-local codes (`com.aurevia.smartclinic.api.contract.CatalogCode`),
  append-only drafts and named approvals through
  `com.aurevia.smartclinic.configuration.ConfigurationService`.
- `com.aurevia.smartclinic.catalog.CatalogRepository`,
  `com.aurevia.smartclinic.chart.record.ChartRepository` — read the single
  effective approved version of `catalog.<family>.<code>` through the
  `public.effective_config` / `public.effective_catalog_family` folds in
  `db/migrations/V5__tenant_configuration.sql` and
  `db/migrations/V6__catalog_fold.sql`, under forced tenant RLS.
- `com.aurevia.smartclinic.chart.record.ChartController`,
  `com.aurevia.smartclinic.chart.record.ChartService`,
  `com.aurevia.smartclinic.chart.record.RecordEntryCommand`,
  `com.aurevia.smartclinic.chart.record.ChartEntry`,
  `com.aurevia.smartclinic.chart.record.ChartActionPolicy` — authorize recording,
  snapshot the effective catalog mapping into `mappedCode` / `mappedText` /
  `catalogVersion`, and retain `originalCode` / `originalText` / `codeSystem`
  verbatim.
- `com.aurevia.smartclinic.catalog.dictionary.DiagnosisCatalogService`,
  `com.aurevia.smartclinic.catalog.dictionary.DiagnosisCode` and
  `com.aurevia.smartclinic.catalog.dictionary.ClinicalCatalogController` — the
  versioned diagnosis/procedure catalogue domain and its HTTP adapter, whose
  mapping keeps original code and text unchanged. Related evidence is in
  `apps/api/src/test/java/com/aurevia/smartclinic/catalog/dictionary/CatalogCodeValidationTest.java`,
  `apps/api/src/test/java/com/aurevia/smartclinic/it/catalog/dictionary/ClinicalCatalogAcceptanceTest.java`
  and `ClinicalCatalogHttpIntegrationTest.java` in the same directory.
- Persistence and isolation are supplied by the production Flyway migrations in
  `db/migrations` (V1–V95), including `V13__longitudinal_chart.sql` with
  `ENABLE`/`FORCE ROW LEVEL SECURITY` on `chart_entry`.

Discovery survey (files read while locating today's implementation): the chart
package `apps/api/src/main/java/com/aurevia/smartclinic/chart/record`, its
controllers under `apps/api/src/main/java/com/aurevia/smartclinic/web` and
`.../catalog/dictionary`, the catalog tables/folds in `db/migrations/V5` and
`V6` plus `V13__longitudinal_chart.sql`, and the catalog routes in
`contracts/openapi.yaml`. The clause is implemented across `ChartService` /
`ChartRepository`, `CatalogService` / `CatalogRepository` /
`ConfigurationService` and `DiagnosisCatalogService` / `DiagnosisCode`; no
previously written clause test existed under `chart/clauses`.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=SupportLocalProcedureCodesVersionedMarketDiagnosisTest test`
— `Tests run: 4, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None. The versioned market diagnosis catalog, its governance fold and the
verbatim local-code capture are built and proven through their HTTP entry
points. A dedicated `PROCEDURE` chart entry type is not present; local procedure
codes are carried by the shared `originalCode` / `codeSystem` fields and the
bounded `CatalogCode` rule, which is sufficient to support and retain them as
local codes. Real market publication and site clinical governance of a specific
diagnosis catalog remain a separate policy-pack / G5 concern and are not claimed
by this synthetic test.
