# CLN-05 clause 5 — API test evidence

## Clause

Entered-in-error preserves the prior revision and records actor, reason and affected recipients.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.chart.clauses.EnteredErrorPreservesPriorRevisionRecordsActorTest`
(path: `apps/api/src/test/java/com/aurevia/chart/clauses/EnteredErrorPreservesPriorRevisionRecordsActorTest.java`).
All seven tests are active (none disabled), use only synthetic fixtures and drive the real HTTP
entry point against a freshly migrated database. The test class is tagged `integration` and
uses its own database `orevia_chart_clause05_it`, following the neighbouring
`com.aurevia.it.clinical.ClinicalNoteRevisionIntegrationTest` fixtures and setup.

- `enteredInErrorAppendsASupersedingRevisionAndRecordsActorReasonAndRecipients` — positive proof.
  After a synthetic note is created and signed by `clinician-a` at version 2, a *different*
  organization-A clinician (`clinician-c`) posts
  `POST /api/v1/clinical-notes/{id}/entered-in-error` with `If-Match: "2"`, reason
  `wrong patient` and recipients `["lab-a","clinic-b"]`. It returns HTTP 200 with state
  `entered_in_error`, version 3, `supersedesVersion=2`, `enteredErrorReason`,
  `enteredErrorActor=clinician-c`, both recipient identifiers and the unchanged content and
  checksum. Using a distinct actor proves the row records the current request identity
  rather than copying the prior signer. `GET /api/v1/clinical-notes/{id}` still shows the
  `signed` original, and a direct query of the revision ledger confirms the draft and
  signed rows are untouched (the signed row has no error reason or actor) while the new
  row carries the actor, reason and recipients.
- `enteredInErrorRefusesAnUnsignedDraft` — negative proof of the forbidden state. A draft
  (version 1) is refused with HTTP 409 `INVALID_TRANSITION` and no revision is appended.
- `enteredInErrorRefusesAStaleVersion` — negative proof of the version precondition. A stale
  `If-Match` against a signed note is refused with HTTP 412 `STALE_VERSION`.
- `enteredInErrorRefusesABlankReason` — negative proof of missing input. A blank reason is
  refused with HTTP 422 before any write.
- `enteredInErrorRefusesAReceptionRole` — negative proof of the wrong role. A `reception`
  subject with active membership is refused with HTTP 403 `FORBIDDEN`, and the history view
  confirms no revision was appended.
- `enteredInErrorRefusesAnotherTenant` — negative proof of tenant isolation. The note id from
  organization A is concealed from organization B with HTTP 404 `NOT_FOUND`, with no revision
  appended.
- `enteredInErrorRefusesMalformedRecipients` — negative proof of the recipient boundary. A
  non-array `affectedRecipients` value is refused with HTTP 422 `INVALID_RECIPIENTS` before
  any revision is appended.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.clinical.notes.ClinicalNoteController` — thin adapter; its
  `enteredInError` method binds the `ErrorRequest` and `If-Match` precondition to
  `ClinicalNoteService.markEnteredInError` on `POST /api/v1/clinical-notes/{id}/entered-in-error`.
- `com.aurevia.clinical.notes.ClinicalNoteService` — domain owner of the
  `ENTERED_IN_ERROR` command; re-derives membership and role authorization, loads and locks
  the note head, checks the version precondition and that the source is signature-bearing,
  then appends a new revision that links to the prior version and carries the reason and
  recipients. It never mutates or deletes an earlier revision.
- `com.aurevia.clinical.notes.NoteInputs` — trust-boundary validation of the
  reason and the affected-recipient JSON array.
- `com.aurevia.clinical.notes.NoteRepository` — the single SQL boundary; `insert`
  writes a new immutable row and does not supply the actor, which is owned by the database.
- `com.aurevia.clinical.notes.ClinicalNoteRevision`, `RevisionInput` and
  `NoteState` — the revision value types carrying `supersedesVersion`, `enteredErrorReason`,
  `enteredErrorActor`, `affectedRecipients` and the `entered_in_error` wire state.
- `db/migrations/V15__clinical_note_signing.sql` — append-only, forced-RLS revision ledger;
  the succession guard requires a prior signed version, and the actor trigger stamps
  `entered_error_actor` from the transaction-local `app.actor` identity, never from the
  request body.

Source survey: the package `apps/api/src/main/java/com/aurevia/chart/record`
contains only CLN-03 entry recording and verification (`ChartController`, `ChartService`,
`ChartRepository`, `ChartEntry`, `RecordEntryCommand` and the verification state types).
No draft/sign/entered-in-error implementation exists there. The chart's signed-note ledger
for CLN-05 is the neighbouring `clinical.notes` package listed above; controllers under
`com.aurevia.web` only delegate authorization. This task changed no production
code, contract or migration.

## Gap

None.

The clause is built and proven by the active tests above (and by the owning service and
integration suites). There is no missing behaviour to schedule.
