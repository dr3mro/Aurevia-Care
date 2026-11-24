# CLN-05 clause 6 — signing differs from patient release and PDF rendering

## Clause

Signing differs from patient release and PDF rendering.

## Status

Proven by tests

## Evidence

Active HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database migrated
from `db/migrations`, synthetic fixtures only). Test class:
`com.aurevia.chart.clauses.SigningDiffersPatientReleasePdfRenderingTest`
(path: `apps/api/src/test/java/com/aurevia/chart/clauses/SigningDiffersPatientReleasePdfRenderingTest.java`).
Entry point to test trace: `POST /api/v1/clinical-notes` (create draft) →
`POST /api/v1/clinical-notes/{id}/sign` (sign) → `GET /api/v1/patients/{patientId}/released-revisions`
(release channel) → `GET /api/v1/clinical-notes/{id}` (append-only history).

- `signingRecordsSignatureWithoutReleasingToPatientOrRenderingPdf` — positive proof.
  Signing an original draft appends an immutable `signed` revision carrying the signer,
  attestation, signed instant, content checksum and template version, and preserves the
  draft in history. The signed response contains no release, PDF, render or disclosure
  state. The patient-scoped released-revision query stays empty and the `patient_release`
  ledger has no row, so signing did not release the note to the patient. The
  `rendered_document` ledger also has no row, so signing did not render a PDF.
- `noteControllerDoesNotExposeReleaseOrPdfRoutes` — negative proof. `POST` or `GET` of
  `/api/v1/clinical-notes/{id}/release`, `/pdf` and `/render` all return HTTP 404. Patient
  release and PDF rendering are separate controllers/workflows, not side effects of a
  signature on the note resource.
- `receptionCannotSignBecauseSignPermissionIsDistinctFromReleaseRoles` — negative proof.
  A `reception` member is refused a sign (HTTP 403) and the note stays an editable draft:
  signing authority is not derived from the wider release-reading roles.
- `crossTenantClinicianCannotSignNote` — negative proof. A clinician of another active
  organization cannot sign the note (HTTP 404 under forced row level security) and the
  note stays an editable draft.

Production classes exercised through those entry points:

- `com.aurevia.clinical.notes.ClinicalNoteService` — the signing workflow;
  its class contract states that signing neither releases content to a patient nor
  renders a PDF. `ClinicalNoteRevision` carries signer, attestation, signed instant,
  checksum and template version; `NoteState` distinguishes editable drafts from
  immutable signed/addendum revisions; `NoteView` exposes the append-only history.
- `com.aurevia.clinical.notes.ClinicalNoteController` — thin adapter on
  `/api/v1/clinical-notes`; it publishes sign/amend/entered-in-error routes but no
  release or PDF route.
- `com.aurevia.exchange.release.PatientReleasedRevisionsController` and
  `com.aurevia.exchange.release.ReleaseService` — the separate XCH-06
  patient-release workflow and patient-scoped visibility query.
- `com.aurevia.exchange.release.ReleaseActionPolicy` — release's own action
  policy, independent of the note sign permission matrix.
- `com.aurevia.documents.PdfRenderer` and
  `com.aurevia.documents.DocumentController` — the separate CLN-07 PDF
  rendering workflow on `/api/v1/documents/rendered`.
- Storage boundaries: `db/migrations/V15__clinical_note_signing.sql` (`clinical_note_revision`,
  append-only with RLS and FORCE RLS), `V24__patient_release.sql` (`patient_release`,
  `release_event`, `release_notification`) and `V17__document_associations.sql`
  (`rendered_document`), all with their own tenant policies.

An adjacent integration test,
`com.aurevia.it.clinical.ClinicalNoteRevisionIntegrationTest.noReleaseOrRenderingStateIsIntroduced`,
also asserts that the signed response carries no release/render state and that the note
controller has no `/release` route. This task adds only the dedicated proof class above.

## Gap

None. Signing, patient release and PDF rendering are each built and reachable through
separate controllers, policies and ledgers; this test proves that signing does not
trigger either of the other two. Adjacent context, not a gap in this clause: the
`/api/v1/clinical-notes/**`, `/api/v1/releases/**`, `/api/v1/patients/{id}/released-revisions`
and `/api/v1/documents/rendered` routes are not yet published in `contracts/openapi.yaml`;
that contract gap is tracked separately and is outside this evidence task's scope.

This evidence task added only a test class and this note. It changed no production code,
no existing test, no migration and no contract.
