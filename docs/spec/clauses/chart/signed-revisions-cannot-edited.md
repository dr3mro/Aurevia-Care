# CLN-05 clause 4 — signed revisions cannot be edited

## Clause

Signed revisions cannot be edited.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.SignedRevisionsCannotEditedTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/SignedRevisionsCannotEditedTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants.
Each test creates a synthetic draft, signs it, then attempts the edit through the
real entry point `PUT /api/v1/clinical-notes/{id}` and reads the note back to
assert the signed content, version and state survived unchanged:

- `editingSignedRevisionIsRefusedAndSignedContentSurvives` — the rule being met:
  a clinician's edit of the signed revision at its current version is refused
  with 409 `IMMUTABLE_REVISION`, the message names the rule, and the signed
  content, version (`2`) and state (`signed`) are unchanged with no appended
  revision.
- `editingSignedRevisionByWrongRoleIsRefused` — negative: an active member with
  the `reception` role (no clinical-note edit permission) is refused with 403 and
  the signed revision is unchanged.
- `editingSignedRevisionFromAnotherTenantIsRefused` — negative: a clinician of
  another active organization is refused with 404 (the note is concealed) and the
  signed revision is unchanged.
- `editingSignedRevisionWithStaleVersionIsRefused` — negative: an edit carrying a
  stale `If-Match` (`"1"` against signed version `2`) is refused with 412
  `STALE_VERSION` and the signed revision is unchanged.

Production classes exercised by this entry point:

- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — binds
  `PUT /api/v1/clinical-notes/{id}` and parses the `If-Match` precondition through
  `com.aurevia.smartclinic.api.IfMatch`.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService` — `updateDraft`
  calls `requireEditable`, which rejects a non-editable revision with the
  `IMMUTABLE_REVISION` conflict; every command re-derives authorization and the
  note head inside one tenant-guarded transaction.
- `com.aurevia.smartclinic.clinical.notes.NoteState` — `editable()` is `true` only
  for `DRAFT` and `AMENDMENT_DRAFT`; `SIGNED`, `ADDENDUM` and `ENTERED_IN_ERROR`
  are immutable.
- `com.aurevia.smartclinic.clinical.notes.NoteRepository` — the RLS-bounded SQL
  ledger; the append-only trigger in the Flyway migration under `db/migrations`
  rejects direct UPDATE/DELETE/TRUNCATE of a stored revision for every role.

Discovery: the clause is implemented in the `clinical.notes` package, not in the
`chart.record` package named in the task. `chart.record` implements CLN-03
(record/verify/read) and has no signing lifecycle. The `clinical.notes` package is
the CLN-05 owner; the broader CLN-05 flow (draft → sign, amendment drafts,
addenda, entered-in-error) has its own integration suite in
`com.aurevia.smartclinic.it.clinical.ClinicalNoteRevisionIntegrationTest`. This
story proves clause 4 only and does not re-test the other clauses.

## Gap

None.
