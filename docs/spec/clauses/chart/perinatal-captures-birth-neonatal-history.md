# CLN-04 clause 4 — perinatal captures birth and neonatal history

## Clause

Perinatal captures birth and neonatal history.

## Status

Partly proven

## Evidence

The clause is proven at the domain level by pre-existing active tests and migrations. There is
no HTTP-level capture of the structured perinatal fields.

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

Production classes exercised by these proofs, or named by the missing route:

- `com.aurevia.smartclinic.clinical.templates.TemplateKind` — `PERINATAL`.
- `com.aurevia.smartclinic.clinical.templates.BaselineTemplates` and `FieldDefinition` — the two
  required free-text fields.
- `com.aurevia.smartclinic.clinical.templates.TemplateService` and `JdbcTemplateStore` — versioned
  templates and persisted values behind the domain entry point.
- `com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` — the only text-bearing note
  route, `POST /api/v1/clinical-notes`, which accepts free-text `content` plus a template code and
  version but no structured field-value list.
- `com.aurevia.smartclinic.chart.record.EntryType` — has only `PROBLEM`, `ALLERGY`, `MEDICATION`
  and `VITAL`; no perinatal or section family.

No controller under `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates` exists,
and no class, controller or workflow captures `birthHistory` or `neonatalHistory` over HTTP.
`POST /api/v1/clinical-notes` is itself absent from `contracts/openapi.yaml`, as are the chart
routes.

## Gap

The domain slice is built; the HTTP capture the clause describes is missing:

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

A complete HTTP-level proof cannot be added under this task's scope: the task forbids production
changes, and the repository constraint forbids an added skipped test. The complete proof was not
left in the tree as a skipped test; the gap above is handed off to the properly scoped task in
item 1. This evidence task changed no production code, no existing test, no migration and no
contract.
