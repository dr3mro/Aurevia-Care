# CLN-03 clause 5 — retain original code and text after mapping

## Clause

Retain original code and text after mapping.

## Status

Proven by tests

## Evidence

Test class
`com.aurevia.smartclinic.chart.clauses.RetainOriginalCodeTextAfterMappingTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/RetainOriginalCodeTextAfterMappingTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
synthetic catalog codes only. Catalog drafts and approvals are written through
`POST /api/v1/catalogs/{family}/entries`, a chart PROBLEM is recorded through
`POST /api/v1/chart/records/entries`, and the snapshot is read through the
verify, single-entry and patient-chart HTTP routes:

- `retainsSubmittedOriginalCodeAndTextWhenAnApprovedCatalogMapsThem` — an
  approved diagnosis catalog version maps R41 to market text
  "Migraine (market v1)"; a chart PROBLEM submitted as original text "Headache"
  with original code R41 keeps both originals and its `codeSystem` verbatim
  while `mappedCode`, `mappedText` and `catalogVersion` are supplied (201).
- `doesNotReplaceOriginalCodeOrTextWithAnotherTenantsCatalogMapping` — a
  diagnosis entry approved in one tenant is invisible to a second tenant
  recording the same code; with no mapping supplied, original text, code and
  system are retained (201). This covers the hit-absent path under forced RLS.
- `doesNotApplyAnUnapprovedDraftMappingToTheRetainedOriginal` — with an
  approved version 1 and an unapproved draft version 2, only the approved
  snapshot is mapped and both originals remain unchanged (201).
- `retainedOriginalCodeAndTextSurviveVerificationAndReread` — after approval,
  a recorded entry is verified at `If-Match` version 1 and reread through the
  single-entry, patient-chart and stored-row paths; submitted original text
  "Headache", code R44 and system `diagnosis` are unchanged in every view while
  the approved mapped snapshot stays intact, so neither an original-to-mapped
  overwrite nor a swap can pass (200).
- `retainsOriginalCodeAndTextWithoutMappingThroughVerificationAndReread` —
  a tenant-local code `PROC-77` with no catalog entry, and a PROBLEM with no
  submitted code at all, retain their original text, code and system through
  verification and reread with every mapped field absent (201/200). This
  discriminates a verification-time overwrite because the mapped code is null.

Production classes exercised by these entry points:

- `com.aurevia.smartclinic.chart.record.ChartController` — thin HTTP adapter
  for record, verify, single-entry read and patient-chart read.
- `com.aurevia.smartclinic.chart.record.ChartService` — validates and
  authorizes each command, fills only `mappedCode` / `mappedText` /
  `catalogVersion` from an approved hit, copies `originalText` /
  `originalCode` / `codeSystem` verbatim into the new entry, and carries them
  unchanged into the verified version.
- `com.aurevia.smartclinic.chart.record.ChartRepository` — inserts the
  submitted originals verbatim, updates only verification/author/version, and
  reads originals back through both the single-entry and patient-chart queries.
- `com.aurevia.smartclinic.chart.record.RecordEntryCommand`,
  `com.aurevia.smartclinic.chart.record.ChartEntry`,
  `com.aurevia.smartclinic.chart.record.ChartActionPolicy` — the command,
  immutable entry with its verbatim-retention invariant and the role matrix.
- `com.aurevia.smartclinic.web.CatalogController`,
  `com.aurevia.smartclinic.catalog.CatalogService`,
  `com.aurevia.smartclinic.catalog.CatalogRepository` and
  `com.aurevia.smartclinic.configuration.ConfigurationService` — bounded
  draft/approval flow and the effective approved catalog snapshot read under
  tenant RLS; `com.aurevia.smartclinic.catalog.dictionary.DiagnosisCatalogService`
  and `com.aurevia.smartclinic.catalog.dictionary.DiagnosisCode` — the
  versioned diagnosis domain whose mapping preserves the original code and text.
- Persistence and isolation are supplied by the production Flyway migrations
  in `db/migrations`, including `db/migrations/V13__longitudinal_chart.sql`,
  which defines the original and mapped columns and declares ENABLE plus FORCE
  ROW LEVEL SECURITY on `chart_entry`.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=RetainOriginalCodeTextAfterMappingTest test`
— `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None. The retention rule is implemented by the write-time mapping snapshot and
the verbatim copy on record and verification, and it is proven here through the
real HTTP entry points and the stored row. No production code, contract or
migration change is needed. Real market publication and site clinical
governance of a specific diagnosis catalog remain a separate policy-pack / G5
concern and are not claimed by this synthetic test.
