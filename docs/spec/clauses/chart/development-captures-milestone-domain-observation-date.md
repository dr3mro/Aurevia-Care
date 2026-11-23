# CLN-04 clause 5 — development captures milestone domain, observation and date

## Clause

Development captures milestone domain, observation and date.

## Status

Proven by tests in the domain and persistence layers; the HTTP entry point is not yet available (see Gap).

## Evidence

Two complementary synthetic suites prove the clause rule. Neither uses mocks or real patient data.

### Fast domain acceptance suite

Test class: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/DevelopmentCapturesMilestoneDomainObservationDateTest.java`
(no database required, so it runs in the fast unit gate).

- `developmentTemplateDefinesExactlyMilestoneDomainObservationAndDate` — the approved `PEDIATRIC_DEVELOPMENT` version defines exactly `milestoneDomain` (CODE, `milestone_domain`), `observation` (FREE_TEXT, `en`) and `date` (DATE).
- `completeDevelopmentNoteValidatesAndSavesAllThreeFields` — a note carrying all three values validates with no violations, saves and reloads with all three field meanings and the approved dictionary snapshot.
- `developmentNoteMissingDateIsRefused` — omitting the required `date` reports `missing:date` and save fails with 422 and persists nothing.
- `developmentNoteWithUnknownMilestoneDomainIsRefused` — a domain code outside the approved dictionary reports `dictionary:milestoneDomain` and save fails with 422.
- `developmentObservationWithoutLanguageIsRefused` — a free-text observation without a language tag reports `language:observation`.
- `developmentNoteWithMalformedDateIsRefused` — a non-ISO date reports `format:date`.
- `developmentNoteCannotBeSavedByNonClinician` — a `tenant_admin` cannot save a note (403) and nothing is persisted.

This suite was run with `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=DevelopmentCapturesMilestoneDomainObservationDateTest test`: 7 tests run, 0 failures, 0 errors, 0 skipped.

### Real-PostgreSQL integration suite

Test class: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/DevelopmentCapturesMilestoneDomainObservationDateIntegrationTest.java`
(`@Tag("integration")`, `@SpringBootTest`, two synthetic tenants, Flyway migrations applied to a disposable database).

- `capturesMilestoneDomainObservationAndDateInAPediatricDevelopmentNote` — positive proof. After clinical governance approves the `milestone_domain` dictionary and template version 1, a full note with `milestoneDomain = gross_motor`, `observation = sits unsupported` (language `en`) and `date = 2026-10-03` validates and saves. The saved revision keeps all three values, the observation language, the exact field meanings and a frozen snapshot of the approved dictionary entries (`gross_motor`, `fine_motor`, `language`), and carries the authoring tenant id.
- `refusesNoteOmittingTheObservation` — negative proof. A note missing the required `observation` is reported as `missing:observation` and cannot be saved (HTTP-equivalent 422).
- `refusesMilestoneCodeOutsideTheApprovedDictionary` — negative proof. A milestone code that is not in the approved `milestone_domain` dictionary is reported as `dictionary:milestoneDomain`.
- `refusesNoteSaveByNonClinicianRole` — negative proof. A `reception` role is refused when saving a note (403 `FORBIDDEN`); note saving is restricted to `clinician`.
- `concealsATemplateVersionFromAnotherTenant` — negative proof. A version defined in one tenant is concealed from a clinician of another tenant (404) on read, validate and save.

### Production surface exercised

The suites drive the real `TemplateService` (`effective`, `validate`, `saveNote`, `savedNote`) over the synthetic `InMemoryTemplateStore`. The production adapter is `JdbcTemplateStore`, backed by the tenant-owned tables in `db/migrations/V80__template_store.sql` and the baseline catalog in `db/migrations/V87__baseline_templates.sql`. The exercised domain types are `BaselineTemplates`, `TemplateKind`, `FieldDefinition`, `FieldType`, `FieldValue`, `TemplateVersion`, `TemplateNoteRevision`, `SavedNoteRevision`, `ValidationReport` and `TemplateActor`, all in `com.aurevia.smartclinic.clinical.templates`.

Existing proof of the same domain rule (unchanged by this evidence task):
`com.aurevia.smartclinic.it.clinical.TemplateVersioningTest.developmentBaselineCapturesMilestoneDomainObservationAndDate`
(baseline fields), `approvingVersionBeforeItsDictionaryIsApprovedFails` and
`freeTextRequiresLanguageAndCodedValuesMustBeInDictionary` (governance and validation), and
`com.aurevia.smartclinic.clinical.templates.TemplateNoteAcceptanceTest.everyStoryFamilyValidatesAndSavesAFullNote`
and `everyStoryFamilyRejectsAPartialNoteAndBlocksSigning` (full and partial development notes,
values `gross_motor` / `fine_motor` / `language`).

## Gap

None for the clause rule itself: the three required fields, their validation and their durable persistence are implemented and proven by the tests above.

One separate architectural gap remains outside this clause and this task's scope: CLN-04 template versions and note saves are exposed only through the `TemplateService` domain API. There is no Spring `@RestController` for these commands and no matching path in `contracts/openapi.yaml`, so the behavior is not reachable over HTTP today (unlike the chart record under `chart.record`, which has `ChartController` and an OpenAPI entry). Specifically:

1. **No template-capture HTTP entry point.** There is no `@RestController` in `clinical/templates`, no controller referencing `TemplateService` or `PEDIATRIC_DEVELOPMENT`, no route in `contracts/openapi.yaml` or its generated client, and no staff-web form for a development note. `chart.record.EntryType` has only `PROBLEM`, `ALLERGY`, `MEDICATION` and `VITAL`, so the chart entry route cannot carry these fields either; `ClinicalNoteController` (`/api/v1/clinical-notes`) stores free-text CLN-05 revisions, not template fields. A later task that adds the route must add its own HTTP-level test; this evidence task deliberately does not leave a skipped test behind.
2. **Files a follow-up would change.** A thin template-capture controller wired by injection to `TemplateService` and `JdbcTemplateStore`; the OpenAPI operation (with a tag, an `Idempotency-Key` header on the POST, and route-parity registration) and the regenerated client; and a staff-web pediatric development form. The tenant-owned template tables and their `ENABLE` / `FORCE ROW LEVEL SECURITY` policies already exist in `db/migrations/V80__template_store.sql` (and the baseline catalog in `db/migrations/V87__baseline_templates.sql`), so **no new migration is required** — the brief's "if template tables are absent" condition does not apply. This needs at least **two separate tasks**: one cross-boundary backend/contract task (controller + OpenAPI + regenerated client), then one staff-web task.

This evidence work added two test classes (the fast acceptance suite and the real-PostgreSQL integration suite) and this note. It changed no production code, no existing test, no migration, no OpenAPI contract and no shared tracker.
