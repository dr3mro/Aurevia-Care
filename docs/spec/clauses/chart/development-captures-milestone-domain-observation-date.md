# CLN-04 clause 5 — Development captures milestone domain, observation and date

## Clause

Development captures milestone domain, observation and date.

## Status

Proven by tests

## Evidence

- Test class: `apps/api/src/test/java/com/aurevia/chart/clauses/DevelopmentCapturesMilestoneDomainObservationDateTest.java`
- `developmentTemplateDefinesExactlyMilestoneDomainObservationAndDate` — the approved `PEDIATRIC_DEVELOPMENT` version defines exactly `milestoneDomain` (CODE, `milestone_domain`), `observation` (FREE_TEXT, `en`) and `date` (DATE).
- `completeDevelopmentNoteValidatesAndSavesAllThreeFields` — a note carrying all three values validates with no violations, saves and reloads with all three field meanings and the approved dictionary snapshot.
- `developmentNoteMissingDateIsRefused` — omitting the required `date` reports `missing:date` and save fails with 422 and persists nothing.
- `developmentNoteWithUnknownMilestoneDomainIsRefused` — a domain code outside the approved dictionary reports `dictionary:milestoneDomain` and save fails with 422.
- `developmentObservationWithoutLanguageIsRefused` — a free-text observation without a language tag reports `language:observation`.
- `developmentNoteWithMalformedDateIsRefused` — a non-ISO date reports `format:date`.
- `developmentNoteCannotBeSavedByNonClinician` — a `tenant_admin` cannot save a note (403) and nothing is persisted.

Production classes exercised through their public entry points: `TemplateService` (`effective`, `validate`, `saveNote`, `savedNote`), `BaselineTemplates`, `TemplateKind`, `FieldDefinition`, `FieldType`, `FieldValue`, `TemplateNoteRevision`, `TemplateVersion`, `SavedNoteRevision`, `ValidationReport`, `TemplateActor` and the `InMemoryTemplateStore` test fake in `com.aurevia.clinical.templates`. The durable JDBC implementation is `JdbcTemplateStore` with tables created by `db/migrations/V80__template_store.sql` and `db/migrations/V87__baseline_templates.sql`.

The suite was run with `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=DevelopmentCapturesMilestoneDomainObservationDateTest test`: 7 tests run, 0 failures, 0 errors, 0 skipped.

## Gap

None for the clause rule: the three required fields, their validation and their durable persistence are implemented and proven by the tests above.

One separate architectural gap remains outside this clause and outside this task's scope: CLN-04 template versions and note saves are exposed only through the `TemplateService` domain API. There is no Spring `@RestController` for these commands and no matching path in `contracts/openapi.yaml`, so the behavior is not reachable over HTTP today (unlike the chart record under `chart.record`, which has `ChartController` and an OpenAPI entry). Closing that gap would need its own story spanning a new controller, the OpenAPI contract, regenerated client types and an HTTP-level integration test; it is not required to prove clause 5 itself.
