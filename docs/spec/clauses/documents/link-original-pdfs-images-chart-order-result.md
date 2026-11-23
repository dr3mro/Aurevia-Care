# CLN-07 clause 1 — link original PDFs and images to chart, order or result as authorized

## Clause

Link original PDFs and images to chart, order or result as authorized.

## Status

Proven by tests

## Evidence

Active HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database
migrated from `db/migrations`, synthetic fixtures only):

- Test class: `com.aurevia.smartclinic.documents.clauses.LinkOriginalPdfsImagesChartOrderResultTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/documents/clauses/LinkOriginalPdfsImagesChartOrderResultTest.java`).
- `linksOriginalPdfToChartAsAuthorizedClinician` — positive proof. A clinician
  posts an original PDF against a chart registered through `POST /api/v1/patients`
  to `POST /api/v1/documents/associations` and receives HTTP 201 with the stored
  subject pair, media type, presentation rotation and a database-derived SHA-256
  that matches the submitted bytes, and the association history records the
  `link` event. The checksum, exact-byte download and rotation metadata are
  asserted as supporting context for the stored link; the guarantees themselves
  are separate CLN-07 sentences proven by their own evidence tasks.
- `linksOriginalPdfToChartAsAuthorizedReception` — positive proof. A `reception`
  role, the other role granted `DOCUMENT_LINK`, attaches an original PDF to a
  chart and receives HTTP 201 with exactly one stored association.
- `linksOriginalImageToOrder` — positive proof. A clinician attaches an original
  PNG to a real `public.exchange_order` subject and receives HTTP 201 with the
  order subject and a matching checksum; the retained bytes download unchanged.
- `linksOriginalPdfToResult` — positive proof. A clinician attaches an original
  PDF to a real `public.xch04_report_revision` subject and receives HTTP 201 with
  the result subject and a matching checksum; the retained bytes download
  unchanged.
- `refusesLinkByRoleWithoutDocumentLinkPermission` — negative proof. A `finance`
  role with an otherwise active membership is refused with HTTP 403 and creates
  no association: `DOCUMENT_LINK` is not granted to that role.
- `concealsAnotherTenantsSubjectFromLink` — negative proof. A clinician of
  another active organization attempting to link the first tenant's chart gets
  HTTP 404 under forced row level security and creates no association.
- `refusesLinkToUnknownSubject` — negative proof. A well-formed but unknown chart
  identifier is refused with HTTP 404 by the chart pre-check and creates no
  association.
- `refusesLinkToUnknownOrderSubject` — negative proof. A well-formed but unknown
  order identifier is refused with HTTP 404 by the repository's subject re-check
  inside `insert` and creates no association.
- `refusesLinkForInactiveMembership` — negative proof. A revoked membership with
  a still-attached clinician grant is refused with HTTP 403 and creates no
  association: a revoked membership is not an authorized link actor.

`mediaType` is accepted as bounded opaque metadata with no per-type branch in
`DocumentService.link`, so the three accepted pairs (PDF→chart, PNG→order,
PDF→result) cover the linkage code paths for both an image and a PDF; both roles
granted `DOCUMENT_LINK` are proven positively.

Production classes and artifacts exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.documents.DocumentController` — thin adapter publishing
  `POST /api/v1/documents/associations` and `GET /api/v1/documents/associations/{id}[/content]`.
- `com.aurevia.smartclinic.documents.DocumentService` — `link` resolves the
  subject inside the caller's tenant transaction, derives authorization and
  records one audit row; `associationContent` re-verifies the retained bytes.
- `com.aurevia.smartclinic.documents.DocumentPolicy` — `DOCUMENT_LINK` role matrix
  (clinician and reception only, checked through `allows`).
- `com.aurevia.smartclinic.documents.DocumentRepository` — `chartExists` and the
  order/result subject re-check inside `insert`; a missing subject yields zero
  inserted rows and a concealed 404.
- `com.aurevia.smartclinic.documents.DocumentInputs` — bounded link request input.
- `db/migrations/V17__document_associations.sql` (RLS and FORCE RLS, checksum and
  author triggers, presentation-only rotation), `db/migrations/V19__exchange_orders.sql`
  (`public.exchange_order`) and `db/migrations/V22__result_routing.sql`
  (`public.xch04_report_revision`).

Adjacent production found in the survey but not part of this clause's proof:
the retained-bytes/checksum guarantee, rotation metadata, reasoned voiding and
immutable localized PDF rendering are separate CLN-07 sentences and are proven by
other evidence (for example
`documents/DocumentDownloadHttpIntegrationTest.java`). The document routes are
not currently published in `contracts/openapi.yaml`; `POST /api/v1/documents/associations`
is already listed in `contracts/openapi/missing-routes.txt` as a pre-existing,
separately tracked contract gap. It does not block this HTTP proof and is context,
not a new ask of this evidence task.

## Gap

None. The clause sentence is implemented and proven for all three subject types
and for each refusal this task exercises. No production code, existing test,
migration or contract was changed by this evidence task.
