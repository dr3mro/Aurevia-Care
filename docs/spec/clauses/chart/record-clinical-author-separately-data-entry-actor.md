# CLN-03 clause 3 — record clinical author separately from data entry actor

## Clause

Record clinical author separately from data entry actor.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.RecordClinicalAuthorSeparatelyDataEntryActorTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/RecordClinicalAuthorSeparatelyDataEntryActorTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic subjects and one synthetic
patient:

- `verifyingClinicianBecomesClinicalAuthorWhileDataEntryActorRemainsTheRecorder`
  — a `clinical_assistant` enters an entry without claiming authorship, a
  `clinician` verifies it, and the response plus the persisted row carry
  `clinicalAuthor = clinician-b`, `dataEntryActor = assistant-a` and
  `verifiedBy = clinician-b` as three independent values.
- `explicitClinicalAuthorSurvivesVerificationByAnotherClinician` — an author
  claimed at intake is not replaced when a different clinician verifies; the
  row keeps `clinical_author = clinician-a`, `verified_by = clinician-b`.
- `recorderCannotAttributeClinicalAuthorshipToAnotherSubject` — a claim that
  names a different subject is refused (422, `INVALID_CLINICAL_AUTHOR`) and no
  row is written.
- `blankAndOverlongClinicalAuthorAreRefused` — blank and overlong author values
  are refused (422, `INVALID_CLINICAL_AUTHOR`) and no row is written.
- `actorWithoutAChartRoleCannotRecordAnAttributedEntry` — a `reception` actor
  cannot record an attributed entry (403) and no row is written.
- `crossTenantClinicianCannotVerifyAnEntryAndAcquireAuthorship` — a clinician
  of another tenant cannot verify the entry (404); the author stays unset and
  verification stays `UNVERIFIED`.

Production classes exercised through `POST /api/v1/chart/records/entries` and
`POST /api/v1/chart/records/entries/{id}/verify`:
`com.aurevia.smartclinic.chart.record.ChartController` (thin HTTP adapter),
`com.aurevia.smartclinic.chart.record.ChartService` (derives `dataEntryActor`
from the tenant context, validates authorship and assigns the verifier to an
open author), `com.aurevia.smartclinic.chart.record.RecordEntryCommand`,
`com.aurevia.smartclinic.chart.record.ChartEntry`,
`com.aurevia.smartclinic.chart.record.ChartRepository` (separate
`clinical_author` / `data_entry_actor` columns and the compare-and-set that
fills the author), `com.aurevia.smartclinic.chart.record.ChartActionPolicy`
(clinician-only verification) and the tenant RLS/FORCE RLS supplied by
`db/migrations/V13__longitudinal_chart.sql`.

Discovery also found the same separation in the clinical-note slice:
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteService`,
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision`,
`com.aurevia.smartclinic.clinical.notes.RevisionInput`,
`com.aurevia.smartclinic.clinical.notes.NoteRepository` and
`db/migrations/V15__clinical_note_signing.sql`, which persist
`clinical_author_subject` and `data_entry_subject` independently. Those classes
are exercised by their own suite and are not re-tested here. No controller or
migration outside these two slices stores the pair; the committed OpenAPI
contract contains no chart-records path or provenance fields.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=RecordClinicalAuthorSeparatelyDataEntryActorTest test` —
`tests="6" errors="0" skipped="0" failures="0"` in the Surefire XML report.

## Gap

None.
