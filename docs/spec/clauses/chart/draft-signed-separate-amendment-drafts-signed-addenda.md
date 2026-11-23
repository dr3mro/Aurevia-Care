# CLN-05 clause 1 — draft to signed, separate amendment drafts and signed addenda

## Clause

Draft → signed, with separate amendment drafts and signed addenda.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.DraftSignedSeparateAmendmentDraftsSignedAddendaTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/DraftSignedSeparateAmendmentDraftsSignedAddendaTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
HTTP entry points `POST /api/v1/clinical-notes`, `POST /api/v1/clinical-notes/{id}/sign`,
`POST /api/v1/clinical-notes/{id}/amend`, `POST /api/v1/clinical-notes/{id}/sign-addendum`
and `GET /api/v1/clinical-notes/{id}` with synthetic fixtures. It seeds a first organization with a `clinician` and a
`reception` member, and a second organization with a `clinician`, registering the same
synthetic patient in both tenants for the cross-tenant negative; it applies only the
production Flyway migrations from `db/migrations`. All routes are already baselined
in `contracts/openapi/missing-routes.txt` (lines 25, 158–162 and 354), so no contract
change is required.

- `draftSignsThenSeparateAmendmentDraftSignsAsAddendum` (rule met) — creates an
  original draft at version 1, signs it with an explicit attestation using
  `If-Match: "1"` (asserting the `signed` state, version 2, `supersedesVersion` 1,
  signer subject, attestation, signed and effective instants, template version and
  content checksum), opens a separate `amendment_draft` at version 3 with
  `If-Match: "2"` (`supersedesVersion` 2), signs it as an `addendum` at version 4 with
  `If-Match: "3"` (`supersedesVersion` 3 and the signer subject), then reads the note
  and asserts that the append-only history still contains the original `draft`, the
  `signed` revision, the `amendment_draft` and the `addendum`.
- `receptionCannotSignADraft` (negative) — a `reception` member is refused HTTP 403
  when signing, and the note stays a draft.
- `staleVersionCannotSignADraft` (negative) — signing with `If-Match: "9"` is refused
  with HTTP 412 and code `STALE_VERSION`, and the note stays a draft.
- `signingAmendmentDraftAsOriginalIsRefused` (negative) — after signing and amending,
  signing the amendment draft through the original sign route is refused with HTTP
  409 and code `INVALID_TRANSITION`, so an amendment cannot masquerade as an original.
- `signingWithoutAttestationIsRefused` (negative) — a signature with no attestation is
  refused with HTTP 422 and code `INVALID_ATTESTATION`, and the note stays a draft.
- `otherTenantCannotReadOrSignTheNote` (negative) — a clinician in a second tenant
  receives HTTP 404 for both the read and the sign, while the owning tenant can still
  read the note.

Production classes and files exercised by this request path (the test lives in the
`chart.clauses` package as clause evidence, but the behaviour it proves is owned by the
`clinical.notes` domain):

- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — the thin HTTP
  adapter for the create, sign, amend, sign-addendum and get routes under
  `/api/v1/clinical-notes`; it binds inputs and passes every `If-Match` through the
  shared parser, and receives its service by injection.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService` — the invariant owner:
  sign permission, signer attestation, patient context, version precondition and
  required fields, the `INVALID_TRANSITION` refusal, and the append-only draft →
  signed → amendment draft → addendum transitions with transactional audit.
- `com.aurevia.smartclinic.clinical.notes.NoteState` — the legal state machine
  (`DRAFT`, `SIGNED`, `AMENDMENT_DRAFT`, `ADDENDUM`, `ENTERED_IN_ERROR`) with
  `editable()` and `signatureBearing()`; signing differs from release or rendering.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision` and
  `com.aurevia.smartclinic.clinical.notes.NoteView` — the immutable revision and the
  current-plus-history projection that proves the original survives every transition.
- `com.aurevia.smartclinic.clinical.notes.NoteRepository` — the row-level-security
  bounded SQL boundary for the guarded commands and the history read.
- `com.aurevia.smartclinic.clinical.notes.NoteInputs`, `NoteChecksum` and
  `RevisionInput` — trust-boundary validation (422 codes), checksum material and the
  append command carrying the signature metadata.
- `com.aurevia.smartclinic.api.IfMatch` and `com.aurevia.smartclinic.api.ApiException`
  — the shared version precondition parser and the structured 403/409/412/422 errors.
- `com.aurevia.smartclinic.tenant.TenantContext` and
  `com.aurevia.smartclinic.audit.AuditAppender` — the caller tenant/subject scope and
  the transactional audit, including failure audits committed after a rollback.
- `db/migrations/V15__clinical_note_signing.sql` — the `clinical_note_revision` ledger
  with forced row-level security and tenant policy, the append-only mutating trigger,
  trigger-owned version/actor/checksum, and the succession guard that prevents a
  signed revision from being replaced or a version from being superseded twice.

Verification: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration
-Dtest=DraftSignedSeparateAmendmentDraftsSignedAddendaTest test` produced Surefire report
`TEST-com.aurevia.smartclinic.chart.clauses.DraftSignedSeparateAmendmentDraftsSignedAddendaTest.xml`
with `tests="6" errors="0" skipped="0" failures="0"`.

## Gap

None.
