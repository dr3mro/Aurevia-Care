# CLN-03 clause 3 — record clinical author separately from data entry actor

## Clause

Record clinical author separately from data entry actor.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.chart.clauses.RecordClinicalAuthorSeparatelyDataEntryActorTest`
(`apps/api/src/test/java/com/aurevia/chart/clauses/RecordClinicalAuthorSeparatelyDataEntryActorTest.java`),
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
  row keeps `clinical_author = clinician-a`, `verified_by = clinician-b`. A
  replayed verification at the next version is refused (409) and the row is
  unchanged.
- `dataEntryActorCannotVerifyIntoClinicalAuthorship` — the data-entry actor
  (`clinical_assistant`) cannot verify their own entry to become its clinical
  author (403); the row stays `UNVERIFIED` with no author.
- `dataEntryActorComesFromTheTenantContextNotTheRequestBody` — a request body
  that tries to set `dataEntryActor` is ignored; the response and the row keep
  the authenticated recorder as the data-entry actor.
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
`com.aurevia.chart.record.ChartController` (thin HTTP adapter),
`com.aurevia.chart.record.ChartService` (derives `dataEntryActor`
from the tenant context, validates authorship and assigns the verifier to an
open author), `com.aurevia.chart.record.RecordEntryCommand`,
`com.aurevia.chart.record.ChartEntry`,
`com.aurevia.chart.record.ChartRepository` (separate
`clinical_author` / `data_entry_actor` columns and the compare-and-set that
fills the author), `com.aurevia.chart.record.ChartActionPolicy`
(clinician-only verification) and the tenant RLS/FORCE RLS supplied by
`db/migrations/V13__longitudinal_chart.sql`.

Corroborating suites already on `main` (not re-tested here):
`com.aurevia.it.chart.record.ChartRecordIntegrationTest`
(`verificationOfRecorderOpenEntryAssignsAccountableClinicalAuthor` asserts the
same open-author assignment), `com.aurevia.it.chart.record.ChartRecordServiceTest`
(service-level author/actor distinctions) and
`com.aurevia.chart.record.ChartRepositoryVerifyTest` (both outcomes
of the verification compare-and-set).

Discovery also found the same separation in the clinical-note slice:
`com.aurevia.clinical.notes.ClinicalNoteService`,
`com.aurevia.clinical.notes.ClinicalNoteRevision`,
`com.aurevia.clinical.notes.RevisionInput`,
`com.aurevia.clinical.notes.NoteRepository` and
`db/migrations/V15__clinical_note_signing.sql`, which persist
`clinical_author_subject` and `data_entry_subject` independently. Those classes
are exercised by their own suite and are not re-tested here. No controller or
migration outside these two slices stores the pair; the committed OpenAPI
contract contains no chart-records path or provenance fields.

Verified locally: `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=RecordClinicalAuthorSeparatelyDataEntryActorTest test` —
`tests="8" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Proven by tests" is limited to the clause behaviour above; it does not claim
that the chart-records route is published in the committed OpenAPI contract.

## Gap

None for the clause behaviour. Out of scope and pre-existing: the committed
OpenAPI contract has no `/api/v1/chart/records` path and no author/actor fields
(`contracts/openapi.yaml` already lists those routes). Documenting
them would change `contracts/openapi.yaml`, the generated client and the
contract tests — one separate ARC-04/WEB-05 contract task.
