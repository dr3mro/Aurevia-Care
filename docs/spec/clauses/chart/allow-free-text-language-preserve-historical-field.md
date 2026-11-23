# CLN-04 clause 7 — free text with language, and historical field meanings

## Clause

Allow free text with language, and preserve historical field meanings with each saved version.

## Status

Partly proven

## Evidence

Domain-level proof with synthetic fixtures only (no production code added by this evidence task):

- Test class: `com.aurevia.smartclinic.chart.clauses.AllowFreeTextLanguagePreserveHistoricalFieldTest`
  (path: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/AllowFreeTextLanguagePreserveHistoricalFieldTest.java`).
  It exercises the real public entry points of the template slice with `InMemoryTemplateStore` and
  `TemplateService`; governance defines and approves an OPERATIVE baseline before any note is authored.
- `freeTextWithLanguageIsAcceptedAndSavedWithFrozenFieldMeanings` — positive proof. A FREE_TEXT
  `findings` value carrying the explicit language `ar` validates with no violations and is saved. The
  saved revision's `fieldMeanings()` equals the exact version-one field set and the authored value keeps its
  language. Governance then defines and approves a changed version two that renames `findings` and relaxes
  the requiredness of `procedure`; reloading the saved note still returns the original label, requiredness
  and language. The defensive-copy accessors reject mutation of the frozen collections.
- `dictionarySnapshotSurvivesAfterTheSourceDictionaryChanges` — positive proof of the non-empty half of
  the freeze, using OBGYN (which has a coded field). A note saved against `obstetric_history = [G0P0]`
  keeps that snapshot and its original `lmp` label after the live dictionary is replaced with `[NEW_CODE]`.
  A directly constructed `SavedNoteRevision` also shows that clearing the caller's mutable source
  collections cannot rewrite the frozen field meanings or dictionary entries.
- `freeTextWithoutLanguageIsRejectedAndNotSaved` — negative proof. A missing or blank language produces
  the machine-readable violation `language:findings`, and `saveNote` refuses the note with HTTP 422
  without adding a revision to the store. The rule requires a non-blank language tag; it does not
  validate the tag against a list of language codes.
- `nonClinicianCannotSaveAndSavedNoteIsConcealedAcrossTenants` — negative proof. A `reception` actor is
  refused with HTTP 403 on save, and a clinician of another active organization receives HTTP 404 when
  reading the note id, under the tenant-keyed store that mirrors FORCE RLS in the migration.
- `staleTemplateVersionIsNotFoundOnSave` — negative proof. A note bound to version 99 is refused with
  HTTP 404 because that version does not exist for the tenant.

Production classes exercised through their normal domain entry points:

- `com.aurevia.smartclinic.clinical.templates.TemplateService` — `validate` enforces the FREE_TEXT
  language rule; `saveNote` freezes the version's field definitions and dictionary entries into the saved
  revision; `savedNote` reloads that frozen meaning; `authorize` applies the role matrix.
- `com.aurevia.smartclinic.clinical.templates.SavedNoteRevision`, `FieldValue`, `FieldDefinition`,
  `FieldType`, `TemplateNoteRevision`, `ValidationReport`, `TemplateActor`, `BaselineTemplates` and
  `InMemoryTemplateStore` — the immutable note/field contract and synthetic store.
- `db/migrations/V80__template_store.sql` — `template_note_revision.field_meanings jsonb` and
  `dictionaries jsonb` persist the frozen meaning with ENABLE and FORCE ROW LEVEL SECURITY, tenant
  policies, append-only triggers and grants. No migration was added or changed by this task.

This evidence is deliberately limited to the domain boundary because no HTTP controller, OpenAPI entry
or generated client exposes template notes; see `## Gap`. The existing `TemplateNoteAcceptanceTest` and
`TemplatePersistenceTest` corroborate the same behaviors from the template-slice side; this class adds the
clause-specific `ar` language tag, the combined save/reload freeze assertion and a proof that the dictionary
snapshot survives a live dictionary change.

## Gap

The rule is implemented and proven at the domain boundary, but it is not reachable through the HTTP API,
so this clause cannot be called fully proven. `contracts/openapi.yaml` has no template-note endpoint, and
no controller injects or calls `clinical.templates.TemplateService`: the only clinical-note controller is
`clinical/notes/ClinicalNoteController`, which accepts a language for a note revision but does not store a
field-meaning or dictionary snapshot, so it cannot demonstrate the historical-freeze half of the clause.

Closing the gap needs, in order:

1. One cross-boundary backend task: expose template define/approve and note validate/save/read through a
   thin controller that injects `TemplateService` (and bind `JdbcTemplateStore` in place of the in-memory
   store), with an HTTP-level integration test that saves a note through the controller and reloads it
   after a new template version is approved. Add the template routes to `contracts/openapi.yaml` with the
   required tag, `Idempotency-Key` on POST/DELETE and `cursor`/`limit`/`nextCursor` on list GETs, then run
   `gen:api` so the client types are regenerated; add the route-parity entry.
2. One staff-web task: build the authoring UI that sends an explicit language with free-text values and
   renders a saved revision against its frozen field meanings.

A QA/review pass over the tenant, role and append-only audit behavior is advisable before clinical use.
No `@Disabled` test was added: adding one is forbidden by the coverage constraints, so the missing HTTP
reachability is recorded here instead and the green tests prove the rule that exists today.
