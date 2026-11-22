# CLN-03 clause 1 — capture chief concern, history, exam, assessment/diagnoses, plan, vitals and the longitudinal chart

## Clause

Capture chief concern, history, exam, assessment/diagnoses, plan, vitals with units and measurement time, and longitudinal problems, allergies and medications.

## Status

Partly proven

## Evidence

Active HTTP-level proof (`synthetic-auth` profile, a fresh PostgreSQL database migrated from
`db/migrations`, synthetic fixtures only):

- Test class: `com.aurevia.smartclinic.chart.clauses.CaptureChiefConcernHistoryExamAssessmentDiagnosesTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/CaptureChiefConcernHistoryExamAssessmentDiagnosesTest.java`).
- `capturesProblemsAllergiesMedicationsAndVitalsWithUnitsAndProvenance` — positive proof. Through
  `POST /api/v1/chart/records/entries`: a PROBLEM retains its submitted code/text and snapshots a
  versioned diagnosis-catalog mapping (`mappedCode`, `mappedText`, `catalogVersion`), captures
  `source` and effective dates, and keeps `clinicalAuthor` distinct from `dataEntryActor`; an
  ALLERGY records an explicit ABSENT ("none") assertion; a MEDICATION is entered by a clinical
  assistant who leaves clinical authorship open; a VITAL carries a `value`, a `unit` and a
  `measuredAt` measurement time. `GET /api/v1/chart/records/patients/{id}/entries` returns all four
  families together as one longitudinal chart.
- `refusesVitalWithoutUnitsAndMeasurementTime` — negative proof. A vital missing its unit or its
  measurement time is refused with HTTP 422, so "vitals with units and measurement time" is an
  enforced rule rather than an optional field.
- `refusesCaptureByRoleWithoutChartPermission` — negative proof. A `reception` role cannot capture
  a chart entry (HTTP 403); clinical capture is restricted to clinical roles.
- `concealsCapturedEntryFromAnotherTenant` — negative proof. A captured entry is concealed from a
  clinician of another active organization (HTTP 404) under forced row level security.

Complete-but-disabled proof for the missing SOAP narrative part:

- `capturesChiefConcernHistoryExamAssessmentAndPlanAsChartEntries` — `@Disabled("GAP: …")`. It posts
  chief concern, history, exam, assessment narrative and plan as chart sections and asserts they are
  returned as chart entries. It stays disabled until the gap below is built; the pull request stays
  green.

Production classes exercised through the normal HTTP entry point:

- `com.aurevia.smartclinic.chart.record.ChartController` — thin adapter publishing
  `GET/POST /api/v1/chart/records/...`.
- `com.aurevia.smartclinic.chart.record.ChartService` — capture validation, role authorization,
  provenance derivation, optional diagnosis mapping and the clinical-author/data-entry-actor split.
- `com.aurevia.smartclinic.chart.record.RecordEntryCommand`, `ChartEntry`, `ChartView` and the
  `EntryType` / `EntrySource` / `PresenceState` / `VerificationState` enums — the capture contract.
- `com.aurevia.smartclinic.chart.record.ChartRepository` — tenant-guarded SQL boundary; inserts and
  reads `chart_entry` from `db/migrations/V13__longitudinal_chart.sql`.
- `com.aurevia.smartclinic.chart.record.ChartActionPolicy` — the clinical-role matrix that refuses
  non-clinical capture.

Adjacent production found in the survey but not part of this clause's proof: the SOAP narrative
fields (chief concern, history, exam, assessment, plan) are defined as free-text fields of the
CLN-04 template family in `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates/BaselineTemplates.java`
(`TemplateKind.SOAP`) and saved as note revisions by `clinical.templates.TemplateService` /
`clinical.notes.ClinicalNoteService`. No controller under `apps/api/src/main/java/com/aurevia/smartclinic/web`
captures those sections for the CLN-03 chart. The chart routes are not currently published in
`contracts/openapi.yaml`. No new class, controller or workflow was found that captures the SOAP
narrative sections as CLN-03 chart content.

## Gap

The structured half of the clause — assessment/diagnoses, vitals with units and measurement time,
and longitudinal problems, allergies and medications — is built and proven by the active tests
above. The remaining half is missing:

1. **SOAP narrative capture as chart content.** Chief concern, history, exam, assessment narrative
   and plan are only free-text fields of the CLN-04 SOAP template (`clinical/templates/BaselineTemplates.java`,
   `TemplateKind.SOAP`) and values of saved note revisions (`clinical/templates/TemplateService.java`,
   `clinical/notes/ClinicalNoteService.java`). `chart.record.EntryType` has only `PROBLEM`, `ALLERGY`,
   `MEDICATION` and `VITAL`, so `RecordEntryCommand` cannot capture these sections and
   `GET /api/v1/chart/records/patients/{id}/entries` cannot return them alongside the other families.
   Files that would change: add a section family to `chart/record/EntryType.java`,
   `RecordEntryCommand.java`, `ChartEntry.java`, `ChartService.java` (validation) and
   `ChartRepository.java` (insert/select mapping); add a Flyway migration altering `chart_entry`
   (or a companion table) with `ENABLE` and `FORCE ROW LEVEL SECURITY` plus tenant policies; add the
   capture fields to `contracts/openapi.yaml`; and extend the staff-web chart capture form. One
   cross-boundary backend/database/contract task, then one staff-web task: **two separate tasks**
   (a third QA/review task is advisable before clinical use).
2. **Published chart contract (adjacent).** `GET/POST /api/v1/chart/records/...` are absent from
   `contracts/openapi.yaml`. This does not block the HTTP proof above (the routes are wired and
   served) but it is required before generated clients can call them and is tracked separately; it
   is listed here as context, not as a new ask of this evidence task.

This evidence task added only a test class and this note. It changed no production code, no existing
test, no migration and no contract.
