# CLN-05 clause 2 — API test evidence

## Clause

Require sign permission, signer attestation, correct patient context, draft version and required fields.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.chart.clauses.RequireSignPermissionSignerAttestationCorrectPatientTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/RequireSignPermissionSignerAttestationCorrectPatientTest.java`).
All seven tests are active (none disabled) and pass with synthetic data only. The class uses the
fast, mocked unit tier of the neighbouring clause tests (`authz/clauses`) and of
`ClinicalNoteServiceTest`: a mocked `NoteRepository`, `AuditAppender` and `TransactionTemplate`,
a fixed clock and a synthetic `TenantContext`; no real patient data and no new dependency.

- `clinicianSignsDraftWhenPermissionAttestationPatientVersionAndFieldsAllHold` — positive proof.
  An active `clinician` signs a valid draft of a patient in the active organization at the version
  it read, with an explicit attestation. The captured append carries state `signed`,
  `supersedesVersion=1`, the signer's attestation and signed instant, the patient id, the original
  content and checksum, the template version and the author/effective instant; the sign command is
  audited `SUCCESS`.
- `signDeniesANonSigningRoleEvenWithAnActiveMembership` — negative proof of sign permission. A
  member holding `clinical_assistant` and `reception` roles (both active) is refused with 403
  `FORBIDDEN`, nothing is appended and the attempt is audited `DENIED`.
- `signDeniesAMemberWhoseMembershipIsInactive` — negative proof of sign permission at the identity
  boundary: an inactive membership is refused with 403 `FORBIDDEN` and audited `DENIED`.
- `signRefusesABlankOrMissingSignerAttestation` — negative proof of signer attestation: both blank
  and absent attestation are refused with 422 and nothing is appended.
- `signRefusesAPatientOutsideTheActiveOrganization` — negative proof of patient context: a patient
  that is not in the active organization is refused with 422 `INVALID_PATIENT_CONTEXT`, nothing is
  appended and the attempt is audited `CONFLICT`.
- `signRefusesADraftVersionTheCallerDidNotRead` — negative proof of draft version: a stale
  version (stored 3, caller sent 1) is refused with 412 `STALE_VERSION` and nothing is appended.
- `signRefusesADraftWithMissingRequiredFields` — negative proof of required fields: a draft whose
  content is blank is refused with 422 before any append.

Production classes exercised through the normal command entry point
(`ClinicalNoteController.sign` binds `POST /api/v1/clinical-notes/{id}/sign` and delegates to
`ClinicalNoteService.sign`, which owns every invariant of this clause):

- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService` — `sign` re-checks active membership
  and the `SIGN` action permission, locks the note, loads the head, requires the caller's version
  (`requireVersion`), requires state `DRAFT`, validates required fields (`requireFields`), verifies
  the patient against the active organization (`requirePatient`) and appends an immutable `SIGNED`
  revision. `allows` grants `SIGN` only to the `clinician` role.
- `com.aurevia.smartclinic.clinical.notes.NoteInputs` — trust-boundary validation of the attestation
  text, identifiers, template version and required content fields.
- `com.aurevia.smartclinic.clinical.notes.NoteChecksum` — server-computed SHA-256 of the content.
- `com.aurevia.smartclinic.clinical.notes.NoteState` — `DRAFT` is the only editable, signable state;
  `SIGNED` is immutable.
- `com.aurevia.smartclinic.clinical.notes.RevisionInput` and `ClinicalNoteRevision` — the appended
  signed revision and its attestation, signer, effective/signed instants, checksum and template
  version.
- `com.aurevia.smartclinic.clinical.notes.NoteRepository` — mocked tenant-scoped append/read boundary;
  the production `Jdbc` implementation and its database guards are covered by the unchanged
  `ClinicalNoteRevisionIntegrationTest`.
- `com.aurevia.smartclinic.tenant.TenantContext` — supplies the active organization used for the
  patient-context check and the audit record.

Source survey (recorded per the task; nothing below was changed): the clause is implemented in
`apps/api/src/main/java/com/aurevia/smartclinic/clinical/notes/` (`ClinicalNoteService`,
`ClinicalNoteController`, `NoteInputs`, `NoteState`, `NoteChecksum`, `NoteRepository`,
`RevisionInput`, `ClinicalNoteRevision`); the `chart` package
(`apps/api/src/main/java/com/aurevia/smartclinic/chart/record/`) holds the separate longitudinal
chart entry workflow and does not implement signing; the schema is
`db/migrations/V15__clinical_note_signing.sql` (append-only `clinical_note_revision` with
trigger-owned version, actors and checksum plus forced RLS); the HTTP routes
`POST /api/v1/clinical-notes/{}/sign` and the other note routes are currently baselined in
`contracts/openapi/missing-routes.txt`, not published in `contracts/openapi.yaml`. The existing
HTTP/database proof is `apps/api/src/test/java/com/aurevia/smartclinic/it/clinical/ClinicalNoteRevisionIntegrationTest.java`
and the existing fast unit proof is `.../it/clinical/ClinicalNoteServiceTest.java`; this task adds a
clause-scoped proof only.

## Gap

None.

The clause is built and proven by active tests. This task added proof only: it did not change
production code, an existing test, the OpenAPI contract or a migration. The note routes being
baselined as missing from the published OpenAPI contract is a separate WEB-05 documentation gap,
tracked elsewhere, and is not part of this clause's minimum rule.
