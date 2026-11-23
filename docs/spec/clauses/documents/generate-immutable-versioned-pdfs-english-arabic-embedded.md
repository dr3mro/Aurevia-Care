# CLN-07 clause 5 — API test evidence

## Clause

Generate immutable versioned PDFs in English or Arabic with embedded fonts, direction-aware layout, page numbers, provenance and print profile.

## Status

Partly proven

## Evidence

Test class: `com.aurevia.smartclinic.documents.clauses.GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/GenerateImmutableVersionedPdfsEnglishArabicEmbeddedTest.java`).
All three tests are active (none are disabled) and pass with synthetic fixtures only:

- `rendersImmutableVersionedEnglishPdfWithEmbeddedFontPageNumberProvenanceAndPrintProfile` — positive
  byte-level proof of every structural element named in the clause. The bytes begin with `%PDF-1.4`,
  declare a `/TrueType` font with an embedded `/FontFile2` program, set `/PrintScaling /None` and
  `/PrintArea /CropBox` on an A4 `/MediaBox [0 0 595.28 841.89]`, mark `/Direction` as `LTR`, write
  `Page 1 / 1` as UTF-16 text, stamp `source:SYNTHETIC-LAB/REQ-1` in `/Subject`, and encode the
  version (`v3`), subject and effective time in `/Keywords` as UTF-16 hex. Rendering the same
  specification twice yields byte-identical output, while a new version or rotation yields
  different bytes and a different `/ID`, so the artifact is deterministic, immutable and versioned.
- `rendersArabicRightToLeftWithLogicalTextPreserved` — positive direction proof. The same renderer
  marks Arabic as `RTL`, emits the visually reordered words (the last logical word leads), keeps an
  LTR value/unit run intact even when the digits are Arabic-Indic, preserves the original logical
  line in a `%LOGICAL` provenance comment, keeps the shared UTF-16 page number, and produces bytes
  distinct from the English render.
- `refusesToBindPdfToAStaleVersionForeignTenantOrUnauthorizedRole` — negative immutability proof
  through `PdfBindingService`. A stale `expectedVersion` on `finishRender` is refused with 412
  `STALE_VERSION`; a completed render cannot be bound to a different revision version or checksum
  (409 `REVISION_MISMATCH`), cannot be bound by a foreign tenant (403 `WRONG_TENANT`) or by a
  `security_scanner` (403 `FORBIDDEN`); a second completed render cannot rebind the already-bound
  revision (409 `PDF_ALREADY_BOUND`); and a corrected revision cannot inherit the earlier binding
  (409 `PDF_INCOMPLETE`). The exact revision still binds, records a `PDF_BOUND` fact, and therefore
  cannot be silently replaced.

Production classes exercised:

- `com.aurevia.smartclinic.documents.PdfRenderer` — `render(PdfSpec)` builds the deterministic
  single-page PDF objects and `direction(String)` derives `RTL`/`LTR` from the server locale. It is
  reached in production through `POST /api/v1/documents/rendered` → `DocumentController.render` →
  `DocumentService.render` → `PdfRenderer.render`; `GET /api/v1/documents/rendered/{id}/content`
  serves the retained bytes.
- `com.aurevia.smartclinic.documents.DocumentModel.PdfSpec` — the 12-argument deterministic render
  input, including its defensive copy of the content lines.
- `com.aurevia.smartclinic.documents.PdfBindingService` — `requestRender`, `finishRender`, `bind`
  and `release` enforce exact-revision binding and the immutability denials.
- `com.aurevia.smartclinic.documents.DocumentGuard` and `DocumentActions` — shared authorization,
  denial/conflict/stale factories and the role-to-action matrix used by the negative cases.
- `com.aurevia.smartclinic.documents.DocumentContract` — `Actor`, `SourceRevision`, `RenderRequest`,
  `RenderFinishRequest`, `BindRequest`, `ReleaseRequest`, `PdfBinding` and `DocumentEvent`.
- `com.aurevia.smartclinic.documents.InMemoryDocumentStore` (test scope) — the tenant-scoped store
  behind the binding service in these tests.

Source survey (not changed by this task): the implementation lives under
`apps/api/src/main/java/com/aurevia/smartclinic/documents`. The render entry is
`POST /api/v1/documents/rendered` in `DocumentController`; the asynchronous render, binding and
release commands are exposed under `/api/v1/documents/artifacts/renders` and
`/api/v1/documents/artifacts/bindings` in `DocumentArtifactController`. The print handout route
`POST /api/v1/printing/handouts` is declared in `contracts/openapi.yaml`. Durability is provided by
`db/migrations/V17__document_associations.sql` (append-only rendered-document history and
`next_render_version`) and `db/migrations/V81__document_store.sql` (`document_pdf_job`,
`document_pdf_binding`, the immutability trigger and row level security with `FORCE`). Neighbouring
tests already exercising these classes are `it/documents/PdfRendererTest`,
`documents/DocumentArtifactTest` and `documents/DocumentArtifactControllerHttpIntegrationTest`.

Entry-point-to-test trace: the byte assertions reproduce exactly what the HTTP render path returns
through `DocumentService.render`, and the binding denials reproduce the domain decisions that the
artifact controller delegates to; no new production path was added by this task.

## Gap

All elements the clause enumerates are structurally present and proven by the active tests above.
The embedded font is not yet a production typographic font:

1. **Real glyph shaping and a licensed font program.** `PdfRenderer.render` embeds a synthetic
   32-byte placeholder stream (`FONT_HEX`), and its Javadoc (lines 24-26) explicitly states that
   this slice validates structure and direction only and that glyph shaping and font licensing for
   production Arabic layout belong to an approved rendering library with its own security and
   coverage review. A viewer therefore sees the correct structure, direction markers and logical
   text, but not a fully shaped, licensed typeset page. Closing this needs one production task in
   `apps/api/src/main/java/com/aurevia/smartclinic/documents/PdfRenderer.java` to embed an approved
   font program and perform real bidi glyph shaping, plus an approved rendering library and a
   font-licensing decision from the owning unit, with dependency/static security review and 100%
   coverage on the new rendering branches; this status document is then updated to "Proven by
   tests". No schema, contract or migration change is implied: **one implementation task plus its
   security and coverage review**.

Separate pre-existing debt, outside this clause's scope and not changed by this task: the document
render and artifact routes are not all published in `contracts/openapi.yaml`; closing that is a
contract task and is not missing clause-5 behaviour.
