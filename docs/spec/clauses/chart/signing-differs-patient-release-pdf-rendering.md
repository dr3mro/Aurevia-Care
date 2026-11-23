# CLN-05 clause 6 — signing differs from patient release and PDF rendering

## Clause

Signing differs from patient release and PDF rendering.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.SigningDiffersPatientReleasePdfRenderingTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/SigningDiffersPatientReleasePdfRenderingTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It drives the
HTTP entry point `POST /api/v1/clinical-notes/{id}/sign` and the draft create and
edit routes against a fresh database built only from the production Flyway
migrations. Each case seeds two synthetic tenants, a synthetic patient and a
synthetic clinician; no real identity or patient data is used.

- `signingAppendsSignedRevisionWithoutPatientReleaseOrPdfRender` (rule met) —
  signs a draft with a valid attestation and `If-Match: "1"`, asserts HTTP 200 and
  `state=signed`, then proves the tenant-bound database connection sees two
  committed revisions and zero rows in `patient_release`, `release_event`,
  `report_release_revision`, `document_pdf_job` and `rendered_document`.
- `thereIsNoReleaseOrRenderEndpointOnAClinicalNote` (negative) — after a real
  sign succeeds, POST `/api/v1/clinical-notes/{id}/release` and
  POST `/api/v1/clinical-notes/{id}/render` both return 404, and every release
  and rendering ledger stays empty.
- `aNonClinicianCannotSignAndCannotCreateARelease` (negative) — a reception
  member is refused HTTP 403 on sign, cannot reach a release or render route,
  and no release or render row appears.
- `aClinicianFromAnotherTenantCannotSignAndCannotCreateARelease` (negative) —
  a clinician in another tenant cannot see or sign the note (404) and cannot
  create a release or render row in either tenant; both tenants' counts have
  their own positive visibility control.
- `aStaleVersionCannotSignAndCannotCreateARelease` (negative) — after the draft
  is edited to version 2, signing with version 1 returns HTTP 412 and leaves no
  release or render row.
- `aSignWithoutAttestationIsRefusedAndLeavesNoReleaseOrRender` (negative) — an
  empty attestation returns HTTP 422 and leaves no release or render row.

The absence assertions are deliberately not response-body pattern matches. The
migration owner is subject to forced row-level security, so each count binds
`app.current_org` on the same connection and first reads a positive control: the
note's revision count in the owning tenant, and the seeded identity row in the
other tenant where the note must stay concealed. A zero count without that
control would prove nothing.

Production classes and files exercised by this request path:

- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — the thin
  HTTP adapter; its only note routes are create, edit, sign, sign-addendum,
  amend, entered-in-error and get. It has no release or render mapping.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService` — contains the
  sign invariant and records signer, attestation, effective and signed instants,
  checksum and template version on a new revision; it has no release or PDF
  rendering dependency.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision`,
  `com.aurevia.smartclinic.clinical.notes.NoteState`,
  `com.aurevia.smartclinic.clinical.notes.NoteRepository` — the persisted signed
  revision, its state and the tenant-guarded append boundary.
- `com.aurevia.smartclinic.api.IfMatch` — parses the version precondition used
  by the sign command.
- `db/migrations/V15__clinical_note_signing.sql` — the revision ledger with
  forced row-level security and its state and identity constraints.
- `db/migrations/V24__patient_release.sql`, `db/migrations/V84__report_release_store.sql`,
  `db/migrations/V81__document_store.sql`, `db/migrations/V17__document_associations.sql`
  — the separately owned release and rendering stores asserted empty.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=SigningDiffersPatientReleasePdfRenderingTest test` —
`tests="6" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the clinical-note routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the clinical-note
decision routes, including `POST /api/v1/clinical-notes/{id}/sign`, are absent
from `contracts/openapi.yaml` and `contracts/openapi/generated/openapi.yaml`
(see `docs/spec/clauses/product/clinical-users-decide-attest-diagnosis-treatment-release.md`,
lines 85-90). This test asserts that no note release or render route exists and
changes no contract. Documenting the note routes would change the contract, the
generated client and the contract tests — one separate cross-boundary contract
task.
