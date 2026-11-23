# CLN-05 clause 2 — require sign permission, attestation, patient context, draft version and required fields

## Clause

Require sign permission, signer attestation, correct patient context, draft version and required fields.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.RequireSignPermissionSignerAttestationCorrectPatientTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/RequireSignPermissionSignerAttestationCorrectPatientTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile that drives the
real HTTP entry point `POST /api/v1/clinical-notes/{id}/sign` (with `POST /api/v1/clinical-notes`
and `GET /api/v1/clinical-notes/{id}` for setup and reread) using synthetic fixtures. It seeds
two organizations with a patient in each, a `clinician` who may sign and a `reception` member
who must not, and applies only the production Flyway migrations from `db/migrations`.

The `chart.record` package (`ChartController`, CLN-03) has no signing route: it exposes only
create, verify, list and get under `/api/v1/chart/records`. The CLN-05 signing rule is built in
the neighbour package `com.aurevia.smartclinic.clinical.notes`, so the test exercises that
HTTP contract rather than adding any production code.

- `signsDraftWithAttestationAndStoresSignerChecksumAndTemplateVersion` (rule met) — creates
  a draft, signs it with an explicit attestation at `If-Match: "1"`, and asserts HTTP 200,
  `state: signed`, `signerSubject` (a different clinician from the clinical author, with the
  author retained separately), `dataEntrySubject`, the stored attestation, a non-blank `signedAt`
  not before `effectiveAt`, the 64-hex SHA-256 `contentChecksum`, the stored `templateVersion`,
  `supersedesVersion: 1`, `version: 2`, and that a fresh `GET` reread still reports the signed
  revision and the original draft in history. Covers signer identity, signed time, content
  checksum, template version and the draft → signed transition.
- `roleWithoutSignPermissionCannotSign` (negative — sign permission) — a `reception` member and
  a `clinical_assistant` with active membership but no `NOTE_SIGN` permission are each refused
  HTTP 403, a member whose latest membership is revoked is refused HTTP 403, and an unauthenticated
  request is refused HTTP 401.
- `signWithMissingOrBlankRequiredFieldIsRefused` (negative — attestation and required fields) —
  signing with no attestation body, a blank attestation and a 501-character attestation is
  refused HTTP 422 (`INVALID_ATTESTATION`), and creating a draft with a blank required clinical
  field is refused HTTP 422 (`INVALID_CONTENT`), so a note with missing required fields can
  never reach signing.
- `staleDraftVersionCannotSign` (negative — draft version) — signing at a stale `If-Match`
  version is refused HTTP 412 (`STALE_VERSION`), a missing version 428 (`PRECONDITION_REQUIRED`)
  and a malformed version 422 (`INVALID_IF_MATCH`); a reread proves the note is still `draft`,
  a subsequent valid sign succeeds and re-signing the signed revision is refused HTTP 409
  (`INVALID_TRANSITION`).
- `noteForAnotherTenantPatientCannotBeSigned` (negative — correct patient context) — a note
  owned by organization B cannot be signed by organization A's clinician (HTTP 404, row-level
  security conceals the cross-tenant note) and a reread in organization B proves it stays
  `draft`; creating a note for a patient outside the active organization is refused HTTP 422
  (`INVALID_PATIENT_CONTEXT`).

Production classes and files exercised by this request path:

- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — the thin HTTP adapter for
  the `POST /api/v1/clinical-notes/{id}/sign` command (and its create/get neighbours).
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService` — `sign`, `authorize` (403 for
  an absent role permission or inactive membership), `requireVersion` (412 `STALE_VERSION`),
  the DRAFT-only state guard, `requireFields` and `requirePatient` (422
  `INVALID_PATIENT_CONTEXT`), and append of the immutable signed revision.
- `com.aurevia.smartclinic.clinical.notes.NoteInputs` — validates the attestation and the
  required draft fields (blank or missing → 422).
- `com.aurevia.smartclinic.clinical.notes.NoteState` — the DRAFT → SIGNED transition.
- `com.aurevia.smartclinic.clinical.notes.NoteChecksum` — the server-computed SHA-256 content
  checksum persisted with the signed revision.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision`,
  `com.aurevia.smartclinic.clinical.notes.NoteRepository` — the persisted revision and the
  RLS-bounded SQL boundary that keeps each organization's notes invisible to the other.
- `com.aurevia.smartclinic.api.IfMatch` — parses the `If-Match` version precondition.
- `db/migrations/V15__clinical_note_signing.sql` — the append-only revision ledger and its
  forced row-level security.

## Gap

None.
