# RAD-01 clause 5 — images remain under partner access control

## Clause

Images remain under partner access control.

## Status

Proven by tests

## Evidence

Active HTTP-level proof over the in-memory imaging kernel (`synthetic-auth` profile,
standalone MockMvc, synthetic identifiers only; no database, no live PACS and no
external network call). All seven tests are active — none is disabled — and pass.

- Test class: `com.aurevia.radiology.clauses.ImagesRemainUnderPartnerAccessControlTest`
  (path: `apps/api/src/test/java/com/aurevia/radiology/clauses/ImagesRemainUnderPartnerAccessControlTest.java`).
  It reuses the fixture style of
  `com.aurevia.radiology.ImagingControllerHttpTest` (standalone MockMvc,
  `TenantContextInterceptor`, `MockEnvironment("synthetic-auth")`, mocked
  `StaffRepository` and `TransactionTemplate`, `tools.jackson.databind.ObjectMapper`,
  `@AfterEach TenantContext.clear()`) and the `accessioned()` intake → schedule →
  accession sequence of `com.aurevia.radiology.StudyLinkReviewTest`.

- `studyLinkRecordsOnlyTheUidAndPartnerWorkstationReference` — positive proof. An
  accessioned order is linked through `POST /api/v1/imaging/orders/{orderId}/studies`
  with a DICOM Study Instance UID and a partner workstation reference, then read back
  through `GET /api/v1/imaging/orders/{orderId}`. The response records only the UID and
  the workstation reference (plus the verified patient/accession labels); the exact
  wire field set is asserted against the 22 known `ImagingOrder` components, so an
  added pixel, blob, base64 or image field fails the test.

- `wrongRoleCannotLinkAStudy` — negative proof of role gating. A `reception` subject
  is refused with HTTP 403 `FORBIDDEN`; a follow-up read shows the order still
  `ACCESSIONED` with no study UID and no workstation reference.

- `peerTenantCannotReadALinkedStudy` — negative proof of tenant gating. After a
  successful link in the owning organization, the same order read under a different
  active organization is concealed with HTTP 404 `NOT_FOUND` and the error body
  contains neither the Study Instance UID nor the workstation reference.

- `staleVersionCannotRelinkAStudy` — negative proof of the version precondition. A
  link carrying an outdated expected version is refused with HTTP 412
  `STALE_VERSION` and the order stays unlinked.

- `missingWorkstationReferenceIsRefused` — negative proof of required input. A blank
  workstation reference is refused with HTTP 422 `INVALID_WORKSTATION` before any
  link is written.

- `mismatchedLabelsNeverLinkAndOpenAReview` — negative proof of verification and
  failure handling. Both a wrong patient label and a wrong accession label are
  refused with HTTP 422 `LABEL_MISMATCH`; the order stays `ACCESSIONED` at version 3
  with a null study UID, and exactly one `OPEN` review carrying the UID is raised in
  the tenant queue (the repeated mismatch does not duplicate the review item).

- `orderAndControllerExposeNoImagePixels` — structural absence proof. Reflection over
  every `ImagingOrder` record component asserts the exact reference-only field set and
  that no component is a byte/image/blob/stream type. Reflection over every
  `ImagingController` handler mapping asserts the five known routes, no `image/*`
  `produces`, no image/pixel/DICOM path and no binary or resource return type. This
  proves absence structurally instead of relying on an invented image URL, whose 404
  would say nothing about the real contract.

Entry-point-to-test trace: the fixture drives `ImagingService.intake` → `schedule` →
`accession` (there are no HTTP schedule/accession routes), then the HTTP entry point
`POST /api/v1/imaging/orders/{orderId}/studies` reaches
`ImagingService.associateStudy`; `GET /api/v1/imaging/orders/{orderId}` reaches
`ImagingService.read`; `GET /api/v1/imaging/orders/study-link-reviews` reaches
`ImagingService.studyLinkReviews`. The refusals are asserted in
`ImagingControllerHttpTest`-style MockMvc chains backed by `ApiErrorHandler`.

Production classes exercised through those entry points:

- `com.aurevia.radiology.ImagingService` — `associateStudy` records only
  the external study UID and workstation reference (lines 204–233); `read`,
  `load`/`loadRevisions` (510–521), `requireVersion` (549–553), `matches` and
  `queueStudyLinkReview` (561–580) enforce tenant, version and label invariants.
  Its Javadoc (40–43, 190–198) states that image pixels are never retrieved.
- `com.aurevia.radiology.ImagingOrder` — the 22-component immutable
  revision carrying `studyInstanceUid` and `workstationRef` and no pixel field.
- `com.aurevia.radiology.ImagingController` — the thin HTTP adapter with
  five JSON routes and no image-serving mapping.
- `com.aurevia.radiology.RadRules` — `RADIOLOGY_STAFF` / `RECEPTION`
  boundary that gates study association.
- `com.aurevia.radiology.RadPolicyCatalog`, `RadTypes`, `StudyLinkReview`,
  `com.aurevia.api.ApiErrorHandler` and
  `com.aurevia.web.TenantContextInterceptor` — approved modality policy,
  state/review enums, the review record, uniform error rendering and tenant binding.

Survey result for the surrounding code: no imaging table or Flyway migration exists
(the only `radiology` mention in `db/migrations` is a `unit_key` in
`V33__conversation_lifecycle.sql`), no imaging path exists in `contracts/openapi.yaml`
(only `IMAGING_ORDER` as a document-kind enum member), and there is no DICOMweb/PACS
client, image fetch or blob column. `exchange/fhir/ImagingStudyMapper` maps reference
identifiers only.

"Proven by tests" is bounded to this in-memory kernel's non-storage/non-serving
invariant and the HTTP refusals above. No live partner PACS/DICOMweb access control is
under test: the clause says images *remain* with the partner precisely because this
service never takes possession of pixels.

## Gap

None for RAD-01 clause 5 as written: the gateway never ingests, persists or serves
pixels, and the only study reference it records is tenant- and role-gated.

Adjacent debt is deliberately outside this evidence task and does not change that
verdict:

1. The imaging workflow is an in-memory kernel with no imaging table, `Jdbc*` store or
   published `contracts/openapi.yaml` path, so the UID/workstation reference does not
   survive a restart. This is tracked separately (audit tasks
   `AUD-20261121-05` / `AUD-20261121-23`) and would be one cross-boundary
   backend/database/contract task. Adding persistence here would not improve this
   clause — it would store more of the very data the clause keeps at the partner.
2. `ImagingController.read` requires a bound tenant but does not apply a role check;
   the `RadRules.mayAssociate` gate applies to study association (the write path), and
   the read returns only the reference-shaped order, never pixels. If an owner reads
   the clause as requiring role-gated *reads* of the study reference, that is a
   production change in `ImagingController` and `RadRules`, raised as a separate task.
3. Contract publication of the imaging routes is outstanding but is not a gap in this
   clause and was out of scope (no contract edit was permitted).

This evidence task added only the test class and this document. It changed no
production code, no existing test, no migration and no contract.
