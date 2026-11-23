# CLN-04 clause 2 — operative notes include procedure, date, team, findings, complications and follow-up

## Clause

Operative notes include procedure, date, team, findings, complications and follow-up.

## Status

Partly proven

## Evidence

Active, synthetic-data proof of the **domain half** of the rule (unit level, through the public
`TemplateService` entry point):

- Test class: `com.aurevia.smartclinic.chart.clauses.OperativeNotesIncludeProcedureDateTeamFindingsTest`
  (`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/OperativeNotesIncludeProcedureDateTeamFindingsTest.java`).
- `validatesAndSavesAnOperativeNoteWithAllSixRequiredFieldsInTheDomain` — defines and approves the
  synthetic OPERATIVE baseline as version one, validates a note carrying all six fields, and saves it.
  The saved revision returns `procedure`, `procedureDate`, `team`, `findings`, `complications` and
  `followUp` with their authored values and the exact six field definitions frozen with the version.
- `refusesAnOperativeNoteMissingARequiredFieldInTheDomain` — omits `complications`, asserts the
  validation violation `missing:complications` and that `saveNote` refuses the note with HTTP 422.
  The six-field requiredness is therefore an enforced rule, not an optional field set.

Production classes exercised by the active tests:

- `com.aurevia.smartclinic.clinical.templates.BaselineTemplates` — `fieldsFor(TemplateKind.OPERATIVE)`
  defines all six fields as required with their types, labels and free-text language.
- `com.aurevia.smartclinic.clinical.templates.TemplateService` — `defineVersion`, `approveVersion`,
  `validate`, `saveNote`, `savedNote` and the role authorization in `authorize`.
- `com.aurevia.smartclinic.clinical.templates.FieldDefinition`, `FieldValue`, `FieldType`,
  `TemplateNoteRevision`, `TemplateKind`, `SavedNoteRevision`, `ValidationReport`, `TemplateActor` —
  the synthetic note and validation contract.

The two test methods use `InMemoryTemplateStore` (the existing synthetic store) so they are
deterministic and need no tenant context; they prove the in-process rule only. The class fixture does
execute SQL against the forced-RLS `template_version` and `template_version_sequence` tables created
by `db/migrations/V80__template_store.sql` and reads the frozen OPERATIVE row in
`db/migrations/V87__baseline_templates.sql`, but no active test method goes through the durable
`JdbcTemplateStore` or an HTTP route.

Complete-but-disabled proof for the **HTTP half** (`@Disabled(GAP)`, one shared reason naming the
missing controller, routes and contract entries):

- `savesAndReadsAnOperativeNoteWithAllSixRequiredFields` — saves a full note with
  `POST /api/v1/templates/notes` (HTTP 201) and reads it with
  `GET /api/v1/templates/notes/{noteId}` (HTTP 200), asserting all six fields on both responses.
- `refusesAnOperativeNoteMissingARequiredField` — a note without `complications` is refused with
  HTTP 422 and the `INVALID_NOTE` code. The machine-readable `missing:complications` violation is
  asserted by the active domain test; surfacing it in the HTTP error body is part of the gap below.
- `refusesOperativeNoteCaptureByNonClinicalRole` — a `reception` actor is refused with HTTP 403.
- `concealsAnOperativeNoteFromAnotherTenant` — a note saved in one tenant is concealed from a
  clinician of another tenant with HTTP 404.

The class uses the throwaway database `smartclinic_cln04_operative_c02_it`, migrated from
`db/migrations` and dropped in `@AfterAll`, with synthetic subjects in both tenants. Its fixture
adopts the V87 OPERATIVE baseline as approved tenant version one (V87 is a global catalog and cannot
write a tenant row itself) and sends a stable `Idempotency-Key` on every command POST; each HTTP test
uses a distinct note id. Surefire for the
targeted run: `tests="6" errors="0" skipped="4" failures="0"` — the four skips are exactly the
gap-marked HTTP tests, so the pull request stays green. No test was weakened or deleted.

Discovery survey (where the rule lives today): the chart package
`apps/api/src/main/java/com/aurevia/smartclinic/chart/record` has `EntryType` with only `PROBLEM`,
`ALLERGY`, `MEDICATION` and `VITAL`; there is no operative entry family. The versioned template
kernel lives in `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates` and in migrations
V80 and V87. Every controller under `apps/api/src/main/java/com/aurevia/smartclinic/web` (and
`clinical/notes/ClinicalNoteController`) was checked: none calls `TemplateService`, none handles
`TemplateKind.OPERATIVE`, and `contracts/openapi.yaml` has no template path. The template service is
listed as `unreferenced-service` in `scripts/wiring-baseline.json`, confirming there is no caller.

## Gap

The six-field rule is built and enforced in the domain, but an operative note cannot be captured or
read over HTTP, so the API half of the clause is not proven.

1. **No HTTP entry point for versioned templates or operative notes.** There is no
   `TemplateController` (or equivalent) under `apps/api/src/main/java/com/aurevia/smartclinic/web`,
   no `POST /api/v1/templates/notes` and no `GET /api/v1/templates/notes/{noteId}`, and no template
   path in `contracts/openapi.yaml`. Files that would change: a new tenant-scoped controller that
   receives `TemplateService` by injection and derives `TemplateActor` from the active membership,
   request/response records for the note values and frozen meanings, the two OpenAPI operations (each
   with a tag, an `Idempotency-Key` header on the POST and route-parity coverage), and the regenerated
   client. The 422 response must carry the machine-readable validation violations (for example
   `missing:complications`); the current `TemplateService` error carries only `INVALID_NOTE`. The durable `JdbcTemplateStore` and the V80/V87 migrations already exist; no new migration,
   RLS policy or table is needed. Enabling the four disabled tests and proving 100% coverage of the
   new controller is part of this work.
2. **No staff-web capture surface.** `apps/staff-web` has no operative-template form; it only calls
   `/api/v1/clinical-notes` for CLN-05 free-text revisions. A form that saves the six fields against
   an approved template version, with UX and clinical review, is separate work.

That is **two separate tasks**: (1) backend controller + contract + generated client + test enablement
with 100% coverage; (2) staff-web capture and clinical review. Real clinical acceptance remains a
separate G5 human gate and is not claimed here.

This evidence task added only a test class and this note. It changed no production code, no existing
test, no migration, no contract and no tracker.
