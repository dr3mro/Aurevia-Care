# CLN-05 clause 4 — signed revisions cannot be edited

## Clause

Signed revisions cannot be edited.

## Status

Proven by tests

## Evidence

Active HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database migrated from
`db/migrations`, synthetic fixtures only):

- Test class: `com.aurevia.smartclinic.chart.clauses.SignedRevisionsCannotEditedTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/SignedRevisionsCannotEditedTest.java`).
  The clause is implemented in the neighbouring `clinical.notes` slice, so this class drives the real
  `POST /api/v1/clinical-notes`, `POST /api/v1/clinical-notes/{id}/sign`, `PUT /api/v1/clinical-notes/{id}`
  and `GET /api/v1/clinical-notes/{id}` routes; the chart clause paths are fixed by this story's scope.
- `editingASignedNoteRevisionIsRefusedAndTheSignedRowIsUnchanged` — positive proof. A draft is created,
  signed at the version it was read at, and then edited with a still-valid `If-Match`. The edit is
  refused with HTTP 409 and code `IMMUTABLE_REVISION`, with the public message naming the rule
  ("a signed revision cannot be edited"). A tenant-scoped SQL read of the current revision verifies
  that `content`, `content_checksum`, `signer_subject`, `version` and `signed_at` are unchanged, and a
  fresh HTTP read shows the originally signed content and checksum still present, no appended revision
  and the attempted late-edit content absent.
- `receptionCannotSignOrEditASignedNoteRevision` — negative proof. A `reception` member is refused
  HTTP 403 for both sign and edit, and the stored draft and signed rows are unchanged. Authorization
  runs before the version and state checks.
- `anotherTenantCannotReadOrEditASignedNoteRevision` — negative proof. A clinician of another active
  organization receives HTTP 404 for read, edit and sign, while the owning tenant's signed row is
  unchanged. Forced row level security conceals the row before any state or version decision.
- `staleVersionCannotEditASignedNoteRevision` — negative proof. An edit carrying the version read
  before signing is refused with HTTP 412 and code `STALE_VERSION`, and the signed row is unchanged.
  The precondition is evaluated before the immutable-state guard.
- `signingWithoutSignerAttestationIsRefused` — negative proof. A sign with no attestation is refused
  with HTTP 422, no signed metadata is appended, and the revision remains a draft.

Production classes and files exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — thin adapter publishing the draft,
  sign, edit and read routes; it binds `PUT /api/v1/clinical-notes/{id}` and parses the `If-Match`
  precondition through `com.aurevia.smartclinic.api.IfMatch`.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService` — `updateDraft` guard order
  (authorize, require version, require editable) and the `requireEditable` refusal of a signed
  revision with 409 `IMMUTABLE_REVISION`; `sign` performs attestation, required-field, patient-context
  and version validation.
- `com.aurevia.smartclinic.clinical.notes.NoteState` — `DRAFT`, `SIGNED`, `AMENDMENT_DRAFT`, `ADDENDUM`
  and `ENTERED_IN_ERROR`; `editable()` is `true` only for `DRAFT` and `AMENDMENT_DRAFT`, while
  `SIGNED`, `ADDENDUM` and `ENTERED_IN_ERROR` are immutable.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision`, `NoteRepository`, `NoteChecksum` and
  `NoteInputs` — the immutable revision record, tenant-guarded SQL boundary, SHA-256 content checksum
  and trust-boundary validation.
- `db/migrations/V15__clinical_note_signing.sql` — append-only ledger with state, signer, signed time,
  attestation, content checksum and template version, tenant policies, trigger-owned versions and
  no-update/no-delete/successor guards that preserve every prior revision. BEFORE triggers reject
  direct UPDATE, DELETE and TRUNCATE of a stored revision, and the app role's UPDATE/DELETE/TRUNCATE
  grants are revoked, so immutability holds in storage for every role, not only through the service.

Additional active evidence already on the default branch:
`com.aurevia.smartclinic.it.clinical.ClinicalNoteRevisionIntegrationTest.signedRevisionsCannotBeEditedOrReSigned`
(HTTP edit of a signed revision refused with 409 `IMMUTABLE_REVISION`, re-sign refused) and
`PlatformAcceptanceTest.staleDraftCannotOverwriteSignedNote` (stale 412, version-valid 409 and signed
content preserved).

Survey of the slice named by the story: `com.aurevia.smartclinic.chart.record.ChartController` publishes
only `POST /api/v1/chart/records/entries`, `POST /api/v1/chart/records/entries/{id}/verify`,
`GET /api/v1/chart/records/patients/{id}/entries` and `GET /api/v1/chart/records/entries/{id}`.
`ChartService`, `ChartRepository`, `ChartEntry`, `RecordEntryCommand` and `ChartActionPolicy` expose
capture, verification and reads only (`CHART_READ`, `CHART_ENTRY_CREATE`, `CHART_ENTRY_VERIFY`); there is
no sign, edit, amend, signature, checksum or template-version concept there. Verification is not
signing. Migration `db/migrations/V13__longitudinal_chart.sql` has `verification`, `verified_by` and
`verified_at` but no signed state, signer, checksum or template-version column. Adjacent signing code
outside this clause's proof is `therapy.TherapyService.signNote`, the `signInterpretation` command in
`CommandCatalog` with `SpecialtyActions.RAD_SIGN_INTERPRETATION`, and `radflow.report.ReportRules` with
`radflow.report.ReportController`.

## Gap

None for this clause. The rule is implemented in `clinical.notes` and proven by the active tests above,
so no production change, migration, contract edit or new task is needed for CLN-05 clause 4.

Two pieces of context are deliberately not recorded as gaps in this clause:

1. `chart.record` has no DRAFT/SIGNED lifecycle and no sign or edit route. Adding one would be a separate
   product decision, not a missing behaviour of this clause; this evidence task changed no production
   code and surveyed the chart slice only because the story asked it to.
2. Neither `/api/v1/clinical-notes` nor `/api/v1/chart/records` is published in `contracts/openapi.yaml`.
   That is tracked separately (see
   `docs/spec/clauses/chart/capture-chief-concern-history-exam-assessment-diagnoses.md`) and does not
   affect the HTTP proof because the routes are wired and served. No endpoint was added, so no
   `Idempotency-Key`, cursor or regenerated client applies to this change.
