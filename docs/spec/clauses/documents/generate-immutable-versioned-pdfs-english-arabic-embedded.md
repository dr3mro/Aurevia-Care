# CLN-07 clause 5 — generate immutable versioned PDFs in English or Arabic

## Clause

Generate immutable versioned PDFs in English or Arabic with embedded fonts, direction-aware layout, page numbers, provenance and print profile.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.documents.clauses.GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile with its own
Flyway-migrated database `smartclinic_doc_pdf_clause_it`. It drives the real HTTP
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

- `com.aurevia.smartclinic.documents.DocumentController` — the thin HTTP adapter
  for the render and content routes; the service is injected, not constructed.
- `com.aurevia.smartclinic.documents.DocumentService` — validation, the
  `DOCUMENT_RENDER` authorization check, version allocation, render dispatch,
  the tenant-guarded transaction and the audit row.
- `com.aurevia.smartclinic.documents.PdfRenderer` — the deterministic single-page
  PDF byte builder, `direction(en|ar)` derivation, flow-direction markers, page
  number, provenance and print-profile objects.
- `com.aurevia.smartclinic.documents.DocumentModel.PdfSpec`, `RenderRequest`,
  `RenderedDocument` — the render input and immutable metadata value types.
- `com.aurevia.smartclinic.documents.DocumentPolicy` — the action matrix that
  reserves `DOCUMENT_RENDER` for the `clinician` role.
- `com.aurevia.smartclinic.documents.DocumentInputs` — the boundary validator
  that restricts the locale to `en` or `ar` and returns `INVALID_LOCALE`.
- `com.aurevia.smartclinic.documents.DocumentRepository` — the RLS-bounded SQL
  boundary that reads the rendered row and serves the retained bytes.
- `db/migrations/V17__document_associations.sql` — the `rendered_document` table,
  its forced row-level security, the version index and the trigger that derives
  `sha256` with PostgreSQL's `encode(sha256(content), 'hex')`.

## Gap

The embedded `/FontFile2` program is a synthetic placeholder: `PdfRenderer.FONT_HEX`
is 32 zero bytes, and the class Javadoc states that this slice validates
structure and direction only. No real font glyphs are subset or embedded, there
is no Arabic glyph shaping or bidi shaping engine beyond word-run reordering,
and no approved font licence or rendering library is integrated. The tests above
therefore prove the immutable versioning, locale direction, page numbering,
provenance, print-profile and structural markers, not production Arabic
typography.

Closing the gap needs **two** separate production tasks, both outside this
test-only story's scope: (1) select and integrate an approved, licensed PDF
rendering library with a real embedded font pipeline, adding its security,
dependency and coverage review; (2) implement verified Arabic glyph shaping and
font subsetting with synthetic visual-regression fixtures and clinical/UX
review. No file is changed by this clause note.
