# CLN-07 clause 5 — generate immutable versioned PDFs in English or Arabic

## Clause

Generate immutable versioned PDFs in English or Arabic with embedded fonts, direction-aware layout, page numbers, provenance and print profile.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.documents.clauses.GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest`
(`apps/api/src/test/java/com/aurevia/documents/clauses/GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile with its own
Flyway-migrated database `aurevia_doc_pdf_clause_it`. It drives the real HTTP
entry points `POST /api/v1/documents/rendered`,
`GET /api/v1/documents/rendered/{id}` and
`GET /api/v1/documents/rendered/{id}/content` with synthetic users, a synthetic
chart and the synthetic provenance pair `SYNTHETIC-LAB/REQ-1`.

- `generatesImmutableVersionedEnglishAndArabicPdfsWithEmbeddedFontsPrintProfileAndProvenance`
  (rule met) — asserts from the HTTP metadata and downloaded bytes that English
  renders are `LTR`, Arabic renders are `RTL`, both start at version 1, the
  `sha256` is a 64-character digest that matches the exact served bytes, the PDF
  begins with `%PDF-1.4`, declares a TrueType font with `/FontFile2`, declares
  the print profile `/PrintScaling /None`, renders `Page 1 / 1`, stamps
  `source:SYNTHETIC-LAB/REQ-1` in the Info dictionary, preserves the original
  Arabic script, keeps the `/Direction` marker for each locale, produces
  different bytes for the two locales, returns byte-identical content on
  re-fetch, and gives the next same-locale render a new id and version 2.
- `refusesRenderForAReceptionUserWhoLacksDocumentRender` (negative) — a
  `reception` member, who may link and read documents but has no
  `DOCUMENT_RENDER` permission, is refused a fully-formed render command with
  HTTP 403 and code `FORBIDDEN`.
- `concealsAnotherTenantsRenderedPdf` (negative) — a `clinician` in another
  organization receives HTTP 404 and code `NOT_FOUND` for both the metadata and
  the bytes of the first tenant's render, confirming forced tenant isolation.
- `refusesAnUnsupportedRenderLocale` (negative) — a locale outside the approved
  `en`/`ar` set is refused with HTTP 422 and code `INVALID_LOCALE` rather than
  silently choosing a layout direction.
- `embedsARealFontProgramAndShapesArabicGlyphs` (acceptance gap; complete but
  intentionally not compiled or enabled) — carried verbatim in this story's
  hand-off notes. It reuses the same `registerChart` / `render` / `content`
  HTTP path and asserts that the object-7 `/FontFile2` stream is a real,
  non-placeholder sfnt/OpenType program with a matching `/Length1`, and that
  the Arabic title's `Tj` operand carries shaped glyph ids with a six-letter
  subset tag rather than its raw UTF-16BE code units. Every assertion fails
  against today's 32-zero-byte font program, so the case is recorded rather
  than committed as a skipped test: `scripts/check-constraints.py` rejects any
  added `@Disabled` in Java and AGENTS.md forbids skipping tests. The four
  live methods above remain green and unchanged.

Entry-point-to-test trace:

1. `POST /api/v1/patients` (synthetic `reception-a`) registers the chart;
   `registerChart` reads the new id from the HTTP response.
2. `POST /api/v1/documents/rendered` → `web.DocumentController.render` →
   `documents.DocumentService.render` validates input, checks `DOCUMENT_RENDER`,
   allocates the next version, calls `documents.PdfRenderer.render` and inserts
   the immutable row; the test reads the id and metadata from the HTTP 201.
3. `GET /api/v1/documents/rendered/{id}` → `DocumentService.rendered` returns the
   stored direction, version and database-owned `sha256`.
4. `GET /api/v1/documents/rendered/{id}/content` → `DocumentService.renderedContent`
   returns the retained bytes; the test decodes them with ISO-8859-1 and asserts
   the structural markers, comparing the digest with the stored value.
5. A second render through the same POST asserts version 2 and a new id; the
   refusals come from `documents.DocumentPolicy` and `documents.DocumentInputs`
   through the same POST/GET entry points.

Production classes and files exercised by this request path:

- `com.aurevia.documents.DocumentController` — the thin HTTP adapter
  for the render and content routes; the service is injected, not constructed.
- `com.aurevia.documents.DocumentService` — validation, the
  `DOCUMENT_RENDER` authorization check, version allocation, render dispatch,
  the tenant-guarded transaction and the audit row.
- `com.aurevia.documents.PdfRenderer` — the deterministic single-page
  PDF byte builder, `direction(en|ar)` derivation, flow-direction markers, page
  number, provenance and print-profile objects.
- `com.aurevia.documents.DocumentModel.PdfSpec`, `RenderRequest`,
  `RenderedDocument` — the render input and immutable metadata value types.
- `com.aurevia.documents.DocumentPolicy` — the action matrix that
  reserves `DOCUMENT_RENDER` for the `clinician` role.
- `com.aurevia.documents.DocumentInputs` — the boundary validator
  that restricts the locale to `en` or `ar` and returns `INVALID_LOCALE`.
- `com.aurevia.documents.DocumentRepository` — the RLS-bounded SQL
  boundary that reads the rendered row and serves the retained bytes.
- `db/migrations/V17__document_associations.sql` — the `rendered_document` table,
  its forced row-level security, the version index and the trigger that derives
  `sha256` with PostgreSQL's `encode(sha256(content), 'hex')`.

## Gap

The embedded `/FontFile2` program is a synthetic placeholder: `PdfRenderer.FONT_HEX`
is 32 zero bytes, and the class Javadoc states that this slice validates
structure and direction only. No real font glyphs are subset or embedded, there
is no Arabic glyph shaping or bidi shaping engine beyond word-run reordering,
and no approved font licence or rendering library is integrated. The four live
tests above therefore prove the immutable versioning, locale direction, page numbering,
provenance, print-profile and structural markers, not production Arabic
typography.

The complete failing case for this gap is `embedsARealFontProgramAndShapesArabicGlyphs`,
carried (not compiled) in this story's hand-off notes. It replaces the
structural-only font assertions at lines 262-263 and the original-script
assertion at line 269 of the test class with assertions on the real embedded
program bytes and shaped Arabic glyph runs. Each of those assertions fails
today because `PdfRenderer.FONT_HEX` is `"00".repeat(32)` and `visualOrder()`
only reorders word runs without shaping or subsetting. The case is left
disabled-by-location so this evidence change stays green without skipping a
test or weakening a gate; a later task enables it by copying it into the test
class and deleting its `@Disabled` line.

Closing the gap needs **two** separate production tasks, both outside this
test-only story's scope: (1) select and integrate an approved, licensed PDF
rendering library with a real embedded font pipeline — changing
`apps/api/pom.xml` (the vetted dependency) and
`apps/api/src/main/java/com/aurevia/documents/PdfRenderer.java`
(`FONT_HEX`, the object-7 stream and its `/Length1`, and a subset `/BaseFont`
tag), with security, dependency and coverage review; (2) implement verified
Arabic glyph shaping and font subsetting in `PdfRenderer.visualOrder()` and the
render path, with synthetic visual-regression fixtures and clinical/UX review.
This clause-note change records that evidence; it changes no test, production
code, migration or contract.
