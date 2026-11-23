# CLN-05 clause 5 — entered-in-error preserves the prior revision and records actor, reason and affected recipients

## Clause

Entered-in-error preserves the prior revision and records actor, reason and affected recipients.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.EnteredErrorPreservesPriorRevisionRecordsActorTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/EnteredErrorPreservesPriorRevisionRecordsActorTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic subjects and one synthetic
patient:

- `enteredInErrorAppendsRevisionKeepingSignedOriginalAndRecordingActorReasonRecipients`
  — a `clinician` signs a draft in the owning tenant, then posts entered-in-error
  at the signed version with reason `wrong patient` and recipients `["lab-a"]`.
  The response carries `state = entered_in_error`, version 3,
  `supersedesVersion = 2`, the reason, the actor `clinician-a` derived from the
  authenticated header and the recipients. A GET still shows the `signed`
  revision, and SQL over the append-only ledger confirms three rows: the draft,
  the signed original with unchanged SHA-256 checksum, and the entered-in-error
  row that supersedes version 2 with the recorded reason, actor and recipients.
- `clerkCannotMarkNoteEnteredInError` — a `reception` subject is denied (403,
  `FORBIDDEN`) and no entered-in-error row is appended.
- `crossTenantClinicianCannotMarkNoteEnteredInError` — a clinician of another
  organization cannot see the note (404); the owning tenant's ledger keeps its
  two revisions and gains no entered-in-error row.
- `staleVersionCannotMarkNoteEnteredInError` — an `If-Match` of the draft version
  after signing is refused (412, `STALE_VERSION`) and appends nothing.
- `blankReasonAndMalformedRecipientsAreRefused` — a blank reason is refused
  (422, `INVALID_REASON`) and a recipients value that is not a JSON array is
  refused (422, `INVALID_RECIPIENTS`); neither appends a revision.
- `unsignedDraftCannotBeMarkedEnteredInError` — a draft that carries no signature
  is refused (409, `INVALID_TRANSITION`) and the ledger stays at one revision.

Production classes exercised through `POST /api/v1/clinical-notes`,
`POST /api/v1/clinical-notes/{id}/sign`,
`POST /api/v1/clinical-notes/{id}/entered-in-error` and
`GET /api/v1/clinical-notes/{id}`:
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` (thin HTTP
adapter and `ErrorRequest` binding),
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteService`
(`markEnteredInError`: authorization, version precondition, signature-bearing
guard and append of a new revision),
`com.aurevia.smartclinic.clinical.notes.NoteInputs` (reason and recipient
trust-boundary validation),
`com.aurevia.smartclinic.clinical.notes.NoteRepository` (tenant-bounded reads and
append-only insert),
`com.aurevia.smartclinic.clinical.notes.NoteState` (signature-bearing guard) and
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision` (wire fields). The
database invariants come from `db/migrations/V15__clinical_note_signing.sql`:
forced RLS, append-only triggers, the trigger-stamped `entered_error_actor` and
`content_checksum`, and the successor guard that permits entered-in-error only
from a signed or addendum revision. `supersedes_version` is supplied by the
service, not trigger-stamped.

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.smartclinic.it.clinical.ClinicalNoteRevisionIntegrationTest`
(`enteredInErrorPreservesPriorAndRecordsActorReasonAndRecipients` covers the
happy path), `com.aurevia.smartclinic.clinical.notes.ClinicalNoteServiceTest`
(`enteredInErrorRequiresReasonRecipientsAndSignedSource` and
`allMutationCommandsDenyInactiveMembership` cover the service-level branches)
and `com.aurevia.smartclinic.clinical.notes.ClinicalNoteControllerTest`
(`signatureAndCorrectionRoutesDelegateExactly` covers request binding).

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=EnteredErrorPreservesPriorRevisionRecordsActorTest test` —
`tests="6" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the clinical-notes routes are published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the clinical-notes
routes are baselined as undocumented in `contracts/openapi/missing-routes.txt`
(lines 25, 158-162 and 354 for the GET, POST and PUT families), so no OpenAPI
edit, generated-client regeneration or contract test change is part of this
clause-evidence task. Documenting those routes would be a separate ARC-04/WEB-05
contract task.
