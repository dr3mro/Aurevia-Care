# CLN-04 clause 4 — Perinatal captures birth and neonatal history

## Clause

Perinatal captures birth and neonatal history.

## Status

Partly proven

## Evidence

The clause is proven at the domain level by a dedicated acceptance test and by pre-existing
active tests and migrations. There is no HTTP-level capture of the structured perinatal fields.

### Dedicated acceptance test

Test class: `com.aurevia.smartclinic.chart.clauses.PerinatalCapturesBirthNeonatalHistoryTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/PerinatalCapturesBirthNeonatalHistoryTest.java`).
It uses the same synthetic fixtures and service construction as the neighbouring
CLN-04 acceptance test `TemplateNoteAcceptanceTest`: an `InMemoryTemplateStore`
behind `TemplateService`, one synthetic organization, and a tenant_admin,
clinical_governance and clinician actor. Every value is synthetic.

Enabled tests that prove the rule:

- `approvedPerinatalTemplateRequiresBirthAndNeonatalHistory` — after clinical
  governance approves the synthetic baseline, the effective perinatal version
  contains exactly two required `FREE_TEXT` fields, `birthHistory` and
  `neonatalHistory`, each with an `en` language tag (rule met at the template layer).
- `clinicianSavesBothHistoriesAndReloadsTheirFieldMeanings` — a full perinatal
  note validates, saves and reloads with both histories and the field meanings
  recorded at save time (rule met at the note layer). Historical-version
  resilience is clause 7 and is not claimed here.
- `perinatalNoteMissingNeonatalHistoryIsRefused` (negative) — a note carrying only
  the birth history is reported with `missing:neonatalHistory` and cannot be saved
  (HTTP-style 422 at the domain boundary).
- `perinatalNoteMissingBirthHistoryIsRefused` (negative) — a note carrying only
  the neonatal history is reported with `missing:birthHistory` and cannot be saved.
  Together these two tests prove that *both* histories are required.
- `perinatalNoteWithBlankNeonatalHistoryIsRefused` (negative) — a blank neonatal
  history is reported with `blank:neonatalHistory` and cannot be saved.
- `nonClinicianCannotSavePerinatalHistory` (negative) — a tenant administrator
  without the clinician role is refused (403) and nothing is saved.

Missing API surface (recorded here rather than as a skipped test):

- An end-to-end HTTP test that posts a perinatal note to
  `POST /api/v1/clinical/templates/notes` and asserts the response carries both
  histories cannot be written yet because no controller, route or OpenAPI operation
  exists. The gap and its enabling steps are recorded under `## Gap`. A skipped
  test is deliberately not added: skipped tests fail the repository quality gate
  (AGENTS.md, Coverage rules).

### Pre-existing domain proofs

These proofs already exist and are not added by this evidence task:

- `apps/api/src/test/java/com/aurevia/smartclinic/it/clinical/TemplateVersioningTest.java`,
  `perinatalBaselineCapturesBirthAndNeonatalHistory` (lines 92-97) — the PERINATAL baseline has
  exactly `birthHistory` and `neonatalHistory`, both `FREE_TEXT` and required.
- `apps/api/src/test/java/com/aurevia/smartclinic/clinical/templates/BaselineTemplatesTest.java`,
  `theFourStoryFamiliesExposeTheirNamedFields` (lines 133-140) — PERINATAL exposes those two keys.
- `apps/api/src/test/java/com/aurevia/smartclinic/clinical/templates/TemplateNoteAcceptanceTest.java`
  — `fullValues(PERINATAL)` (lines 83-86) stores real `birthHistory` and `neonatalHistory` values
  on a saved revision, and `aBlankFreeTextValueIsRejected` (lines 220-230) refuses a blank
  `birthHistory` as `blank:birthHistory`.
- `db/migrations/V87__baseline_templates.sql` (lines 92-94) seeds the two required `FREE_TEXT`
  fields with language `en`; `db/migrations/V80__template_store.sql` includes `PERINATAL` in the
  `template_kind` CHECK constraint.

These prove the field definitions, requiredness, free-text language and version preservation of
the perinatal baseline. They do **not** prove capture of the structured fields through an HTTP
entry point.

### Production classes exercised by these proofs

- `com.aurevia.smartclinic.clinical.templates.BaselineTemplates` —
  `fieldsFor(PERINATAL)` supplies the synthetic `birthHistory` and `neonatalHistory`
  field definitions; the same definitions are frozen in
  `db/migrations/V87__baseline_templates.sql` as approved baseline version one.
- `com.aurevia.smartclinic.clinical.templates.TemplateKind` — `PERINATAL`.
- `com.aurevia.smartclinic.clinical.templates.FieldType`, `FieldDefinition`, `FieldValue`,
  `TemplateNoteRevision`, `TemplateVersion`, `ValidationReport` and `SavedNoteRevision` — the
  domain types that carry and freeze the two histories and their field meanings; `FieldDefinition`
  defines the two required free-text fields.
- `com.aurevia.smartclinic.clinical.templates.TemplateService` — the public entry
  point for `defineVersion`, `approveVersion`, `validate`, `saveNote` and
  `savedNote`, including role gating and the required-field, blank-value and
  frozen-snapshot rules. Versioned templates and persisted values live behind this
  domain entry point through `com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore`.
- `com.aurevia.smartclinic.clinical.templates.TemplateStore`, instantiated with the
  synthetic `com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore` adapter
  that currently lives under `src/main` (a pre-existing condition, unchanged here).
  The durable adapter is `com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore`
  over `db/migrations/V80__template_store.sql` (tenant-owned, forced-RLS tables).
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — the only text-bearing note
  route, `POST /api/v1/clinical-notes`, which accepts free-text `content` plus a template code and
  version but no structured field-value list.
- `com.aurevia.smartclinic.chart.record.EntryType` — has only `PROBLEM`, `ALLERGY`, `MEDICATION`
  and `VITAL`; no perinatal or section family.

No controller under `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates` exists,
and no class, controller or workflow captures `birthHistory` or `neonatalHistory` over HTTP.
`POST /api/v1/clinical-notes` is itself absent from `contracts/openapi.yaml`, as are the chart
routes.

The production migrations `V80__template_store.sql` and
`V87__baseline_templates.sql`, and the `JdbcTemplateStore` adapter, were surveyed
but are not exercised by the dedicated test class: it constructs the service over the
in-memory test double, matching the existing CLN-04 acceptance tests. The
integrator's database gate covers those migrations.

## Gap

The domain slice is built and durable at the domain and persistence layers, but the HTTP capture
the clause describes is missing: there is no HTTP controller over `TemplateService`, no
`/api/v1/clinical/templates/**` route and no structured-field capture route over the clinical
notes API, and no matching entry in `contracts/openapi.yaml`. Consequently no end-to-end HTTP
test can be written and status is "Partly proven" rather than "Proven by tests".

1. **No structured perinatal capture entry point.** `ClinicalNoteController.CreateRequest`
   (lines 149-151) carries `content` as free text, not the structured `values` list that
   `TemplateService` / `TemplateNoteRevision` validate. No route stores or returns
   `birthHistory` / `neonatalHistory` as structured, versioned fields, so role and tenant
   boundaries cannot be asserted at an HTTP entry point for those fields. Files that would
   change: a structured-value request/response contract and a capture route in `clinical/notes`
   (or `clinical/templates`), persistence through `JdbcTemplateStore`, a Flyway migration with
   `ENABLE` and `FORCE ROW LEVEL SECURITY` plus tenant policies for any new table, entries in
   `contracts/openapi.yaml` with a regenerated client, and a staff-web capture form. **Two
   separate tasks** (one cross-boundary backend/database/contract task, then one staff-web task;
   a QA/clinical review task is advisable before clinical use).
2. **Published contract (adjacent context).** Neither the clinical-notes nor the chart routes are
   in `contracts/openapi.yaml`. Enabling structured capture must add the new route to the contract
   and regenerate the client; this is part of item 1, not a separate ask of this evidence task.

To close the gap:

1. Add a thin controller under `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates`
   that injects `TemplateService`, derives each `TemplateActor` from active
   membership on every request, and exposes the define, approve, validate, save
   and read commands (including perinatal note capture).
2. Document those routes and schemas in `contracts/openapi.yaml` and regenerate the
   client types; wire the durable `JdbcTemplateStore` as the production
   `TemplateStore` bean through the tenant-guarded transaction manager.
3. Add the end-to-end `perinatalHistoryCanBeCapturedThroughTheHttpApi` test, wire a
   real server port and a synthetic-PostgreSQL fixture, and add the negative HTTP
   cases (wrong role, other tenant, stale/unapproved version).

Estimate: two implementation tasks (controller + contract, and production store
wiring) plus one HTTP-test task. This task is test-only by scope and does not write
production code, migrations, the contract or an existing test.

A complete HTTP-level proof cannot be added under this task's scope: the task forbids production
changes, and the repository constraint forbids an added skipped test. The complete proof was not
left in the tree as a skipped test; the gap above is handed off to the properly scoped task in
item 1. This evidence task changed no production code, no existing test, no migration and no
contract.
