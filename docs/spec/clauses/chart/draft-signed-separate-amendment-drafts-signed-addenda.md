## Clause

Draft → signed, with separate amendment drafts and signed addenda.

## Status

Proven by tests

## Evidence

Test class `DraftSignedSeparateAmendmentDraftsSignedAddendaTest`
(`apps/api/src/test/java/com/aurevia/chart/clauses/`):

- `draftSignsThenSeparateAmendmentDraftSignsAsAddendum` — walks the HTTP entry
  points `POST /api/v1/clinical-notes` (draft, version 1),
  `POST /{id}/sign` (signed, version 2, `supersedesVersion` 1),
  `POST /{id}/amend` (a separate `amendment_draft`, version 3,
  `supersedesVersion` 2, no signature) and `POST /{id}/sign-addendum`
  (`addendum`, version 4, `supersedesVersion` 3, signed by the clinician), then
  reads `GET /{id}` and asserts the original draft, signed revision, amendment
  draft and addendum all remain in the append-only history with the original
  content intact.
- `aDraftCannotBeAmendedBeforeItIsSigned` — `POST /{id}/amend` on an unsigned
  draft is refused with 409 `INVALID_TRANSITION` and the draft stays unchanged.
- `anAmendmentDraftCannotBeSignedAsAnOriginalDraft` — `POST /{id}/sign` on an
  amendment draft is refused with 409 `INVALID_TRANSITION`; signing an amendment
  must use the addendum command and leaves the amendment draft current.
- `aNonClinicianCannotSignADraft` — a clinician-authored draft stays unsigned
  when a reception user sends `POST /{id}/sign`: the request is refused with 403,
  so permission to sign is separate from permission to author.

Production classes exercised through these entry points:
`com.aurevia.clinical.notes.ClinicalNoteController` (thin HTTP
adapter), `ClinicalNoteService` (state machine, signer attestation, version
preconditions, role checks and audit), `NoteState` (`DRAFT`, `SIGNED`,
`AMENDMENT_DRAFT`, `ADDENDUM`), `ClinicalNoteRevision`, `NoteView` and
`NoteRepository`. The durable ledger is `public.clinical_note_revision` from
`db/migrations/V15__clinical_note_signing.sql`, which enforces the legal
predecessor states, append-only history, tenant RLS with FORCE RLS, and
trigger-owned versions, actors and content checksums.

## Gap

None.
