# CLN-04 clause 5 — development captures milestone domain, observation and date

## Clause

Development captures milestone domain, observation and date.

## Status

Partly proven

## Evidence

Active domain proof (real `TemplateService` over the synthetic
`InMemoryTemplateStore`, no mocks, synthetic fixtures only, two synthetic tenants):

- Test class:
  `com.aurevia.smartclinic.chart.clauses.DevelopmentCapturesMilestoneDomainObservationDateTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/DevelopmentCapturesMilestoneDomainObservationDateTest.java`).
- `capturesMilestoneDomainObservationAndDateInAPediatricDevelopmentNote` — positive proof. After
  clinical governance approves the `milestone_domain` dictionary and template version 1, a full
  note with `milestoneDomain = gross_motor`, `observation = sits unsupported` (language `en`) and
  `date = 2026-10-03` validates and saves. The saved revision keeps all three values, the
  observation language, the exact field meanings and a frozen snapshot of the approved dictionary
  entries (`gross_motor`, `fine_motor`, `language`), and carries the authoring tenant id.
- `refusesNoteOmittingTheObservation` — negative proof. A note missing the required `observation`
  is reported as `missing:observation` and cannot be saved (HTTP-equivalent 422).
- `refusesMilestoneCodeOutsideTheApprovedDictionary` — negative proof. A milestone code that is
  not in the approved `milestone_domain` dictionary is reported as `dictionary:milestoneDomain`.
- `refusesNoteSaveByNonClinicianRole` — negative proof. A `reception` role is refused when saving
  a note (403 `FORBIDDEN`); note saving is restricted to `clinician`.
- `concealsATemplateVersionFromAnotherTenant` — negative proof. A version defined in one tenant
  is concealed from a clinician of another tenant (404) on read, validate and save.

Complete-but-disabled proof for the missing HTTP entry point:

- `capturesPediatricDevelopmentNoteThroughTheChartApi` — `@Disabled("GAP: …")`. It posts a pediatric
  development note with the three fields to a template-capture route and asserts they are returned
  (201). It stays disabled until the gap below is built; the pull request stays green.

Production classes exercised by the active proof:

- `com.aurevia.smartclinic.clinical.templates.BaselineTemplates` — the
  `TemplateKind.PEDIATRIC_DEVELOPMENT` baseline field set: `milestoneDomain` (CODE, required,
  dictionary `milestone_domain`), `observation` (FREE_TEXT, required, language `en`) and `date`
  (DATE, required).
- `com.aurevia.smartclinic.clinical.templates.TemplateService` — dictionary/version definition and
  governance approval, note validation (`unknown:` / `missing:` / `blank:` / `language:` /
  `dictionary:` / `format:` violations), clinician-only note saving, role authorization and
  tenant-scoped lookups.
- `com.aurevia.smartclinic.clinical.templates.TemplateNoteRevision`, `FieldValue`,
  `FieldDefinition`, `SavedNoteRevision` and `ValidationReport` — the authored note, its values and
  languages, the frozen field meanings and dictionary snapshot, and the validation outcome.
- `com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore` — synthetic tenant-keyed
  store used only by tests; `com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore` is the
  production adapter.

Existing proof of the same domain rule (unchanged by this evidence task):
`com.aurevia.smartclinic.it.clinical.TemplateVersioningTest.developmentBaselineCapturesMilestoneDomainObservationAndDate`
(baseline fields), `approvingVersionBeforeItsDictionaryIsApprovedFails` and
`freeTextRequiresLanguageAndCodedValuesMustBeInDictionary` (governance and validation), and
`com.aurevia.smartclinic.clinical.templates.TemplateNoteAcceptanceTest.everyStoryFamilyValidatesAndSavesAFullNote`
and `everyStoryFamilyRejectsAPartialNoteAndBlocksSigning` (full and partial development notes,
values `gross_motor` / `fine_motor` / `language`).

## Gap

The rule itself is built and tested in the domain and persistence layers. What is missing is the
HTTP surface that would let a caller capture the three fields:

1. **No template-capture HTTP entry point.** There is no `@RestController` in `clinical/templates`,
   no controller referencing `TemplateService` or `PEDIATRIC_DEVELOPMENT`, no route in
   `contracts/openapi.yaml` or its generated client, and no staff-web form for a development note.
   `chart.record.EntryType` has only `PROBLEM`, `ALLERGY`, `MEDICATION` and `VITAL`, so the chart
   entry route cannot carry these fields either; `ClinicalNoteController` (`/api/v1/clinical-notes`)
   stores free-text CLN-05 revisions, not template fields. The disabled test above is the complete
   proof for the route a later task will add.
2. **Files a follow-up would change.** A thin template-capture controller wired by injection to
   `TemplateService` and `JdbcTemplateStore`; the OpenAPI operation (with a tag, an
   `Idempotency-Key` header on the POST, and route-parity registration) and the regenerated client;
   and a staff-web pediatric development form. The tenant-owned template tables and their
   `ENABLE` / `FORCE ROW LEVEL SECURITY` policies already exist in
   `db/migrations/V80__template_store.sql` (and the baseline catalog in
   `db/migrations/V87__baseline_templates.sql`), so **no new migration is required** — the brief's
   "if template tables are absent" condition does not apply. This needs at least **two separate
   tasks**: one cross-boundary backend/contract task (controller + OpenAPI + regenerated client),
   then one staff-web task.

This evidence task added one test class and this note. It changed no production code, no existing
test, no migration, no OpenAPI contract and no shared tracker.
