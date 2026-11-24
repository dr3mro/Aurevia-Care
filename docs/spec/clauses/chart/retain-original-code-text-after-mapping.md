# CLN-03 clause 5 — retain original code and text after mapping

## Clause

Retain original code and text after mapping.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.chart.clauses.RetainOriginalCodeTextAfterMappingTest`
(`apps/api/src/test/java/com/aurevia/chart/clauses/RetainOriginalCodeTextAfterMappingTest.java`),
an HTTP-level integration test on real PostgreSQL with Flyway production
migrations and two synthetic tenants. It proves only this clause:

- `mappingRetainsSubmittedOriginalCodeAndTextVerbatim` — an approved diagnosis
  catalog entry maps local code `LOC-77` to a different display label. Recording
  a PROBLEM with that code and a distinctive free text returns and persists the
  original code and text verbatim, while the mapped code, mapped text and catalog
  version are held in separate snapshot fields. The original free text is
  asserted to differ from the catalog label so a replacement cannot pass. The
  retained values are confirmed on the write response, the `GET /entries/{id}`
  read response and the `chart_entry` row.
- `verificationOfMappedEntryRetainsOriginalCodeAndText` — a mapped entry that is
  verified (creating version 2) still carries the originally submitted code and
  text verbatim on the new version; the catalog snapshot is unchanged.
- `recordingProblemWithOriginalCodeButNoCodeSystemIsRefused` — an original code
  without a code system cannot be reliably mapped or retained; the entry point
  refuses it (422 `INVALID_CODE`) instead of storing a half-specified original.
- `recordingEntryWithBlankOriginalTextIsRefused` — a blank original free text is
  refused (422 `INVALID_TEXT`): there is no original text to retain.
- `anotherTenantCannotReadRetainedOriginalCodeAndText` — a cross-tenant read of
  a retained entry is concealed (404); the retained text does not leak.

Production classes and files exercised by the entry point:

- `com.aurevia.chart.record.ChartController` — the `POST
  /api/v1/chart/records/entries`, `POST /entries/{id}/verify` and `GET
  /entries/{id}` entry points.
- `com.aurevia.chart.record.ChartService` — performs the optional
  catalog mapping inside the tenant transaction and constructs the entry with
  `command.originalText()`/`command.originalCode()` retained verbatim and the
  mapping result in separate fields; `verified(...)` carries both originals to
  the next version; `validate(...)` refuses missing text or unpaired code/system.
- `com.aurevia.chart.record.ChartEntry` and
  `com.aurevia.chart.record.RecordEntryCommand` — the domain types
  whose Javadoc states that submitted code/text are retained on every version.
- `com.aurevia.chart.record.ChartRepository` — `lookupCatalog(...)`,
  `insert(...)` and `mapEntry(...)`/read-back of the retained columns.
- `com.aurevia.web.CatalogController` and
  `com.aurevia.catalog.CatalogService` — seed the effective diagnosis
  catalog code that the mapping resolves.
- `com.aurevia.tenant.TenantContext` and the synthetic-auth headers —
  bind the active organization and actor for each request.
- `db/migrations/V13__longitudinal_chart.sql` — defines `original_code`,
  `original_text`, `code_system`, `mapped_code`, `mapped_text` and
  `catalog_version`, with RLS and FORCE RLS; earlier tenant, catalog and audit
  migrations supply the surrounding schema.

Discovery: the clause is implemented in the `chart.record` package; no missing
behaviour was found. This is a test-only story, so there is no production red
phase to report — the suite is regression evidence for already-implemented
behaviour.

Verified locally (tool evidence): `mvn -q -B -f apps/api/pom.xml -Pno-security
-Dtest=RetainOriginalCodeTextAfterMappingTest test` — Surefire report: `Tests
run: 5, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
