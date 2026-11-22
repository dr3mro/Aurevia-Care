# CLN-04 clause 4 — Perinatal captures birth and neonatal history

## Clause

Perinatal captures birth and neonatal history.

## Status

Partly proven

## Evidence

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
- `clinicianSavesBothHistoriesAndReloadsTheirFrozenMeanings` — a full perinatal
  note validates, saves and reloads with both histories and the exact field
  meanings it was saved with (rule met at the note layer).
- `perinatalNoteMissingNeonatalHistoryIsRefused` (negative) — a note carrying only
  the birth history is reported with `missing:neonatalHistory` and cannot be saved
  (HTTP-style 422 at the domain boundary).
- `perinatalNoteWithBlankNeonatalHistoryIsRefused` (negative) — a blank neonatal
  history is reported with `blank:neonatalHistory` and cannot be saved.
- `nonClinicianCannotSavePerinatalHistory` (negative) — a tenant administrator
  without the clinician role is refused (403) and nothing is saved.

Disabled test recording the missing API surface:

- `perinatalHistoryCanBeCapturedThroughTheHttpApi` — `@Disabled("GAP: ...")`;
  posts a perinatal note to `POST /api/v1/clinical/templates/notes` and asserts the
  response carries both histories. Kept disabled so this test-only change stays
  green; a later task enables it once the HTTP entry point exists.

Production classes and files exercised by the enabled request path:

- `com.aurevia.smartclinic.clinical.templates.BaselineTemplates` —
  `fieldsFor(PERINATAL)` supplies the synthetic `birthHistory` and `neonatalHistory`
  field definitions; the same definitions are frozen in
  `db/migrations/V87__baseline_templates.sql` as approved baseline version one.
- `com.aurevia.smartclinic.clinical.templates.TemplateKind`, `FieldType`,
  `FieldDefinition`, `FieldValue`, `TemplateNoteRevision`, `TemplateVersion`,
  `ValidationReport` and `SavedNoteRevision` — the domain types that carry and
  freeze the two histories and their field meanings.
- `com.aurevia.smartclinic.clinical.templates.TemplateService` — the public entry
  point for `defineVersion`, `approveVersion`, `validate`, `saveNote` and
  `savedNote`, including role gating and the required-field, blank-value and
  frozen-snapshot rules.
- `com.aurevia.smartclinic.clinical.templates.TemplateStore` with the in-test
  `InMemoryTemplateStore` test double; the durable adapter is
  `com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore` over
  `db/migrations/V80__template_store.sql` (tenant-owned, forced-RLS tables).

The production migrations `V80__template_store.sql` and
`V87__baseline_templates.sql`, and the `JdbcTemplateStore` adapter, were surveyed
but are not exercised by this test class: it constructs the service over the
in-memory test double, matching the existing CLN-04 acceptance tests. The
integrator's database gate covers those migrations.

## Gap

The rule is implemented and durable at the domain and persistence layers, but it
is not reachable through a normal API entry point: there is no HTTP controller over
`TemplateService`, no `/api/v1/clinical/templates/**` route, and no matching entry
in `contracts/openapi.yaml`. Consequently the end-to-end HTTP test is disabled and
status is "Partly proven" rather than "Proven by tests".

To close the gap:

1. Add a thin controller under `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates`
   that injects `TemplateService`, derives each `TemplateActor` from active
   membership on every request, and exposes the define, approve, validate, save
   and read commands (including perinatal note capture).
2. Document those routes and schemas in `contracts/openapi.yaml` and regenerate the
   client types; wire the durable `JdbcTemplateStore` as the production
   `TemplateStore` bean through the tenant-guarded transaction manager.
3. Enable `perinatalHistoryCanBeCapturedThroughTheHttpApi` (remove `@Disabled`),
   wire a real server port and a synthetic-PostgreSQL fixture, and add the negative
   HTTP cases (wrong role, other tenant, stale/unapproved version).

Estimate: two implementation tasks (controller + contract, and production store
wiring) plus one test-enablement task. This task is test-only by scope and does not
write production code, migrations, the contract or an existing test.
