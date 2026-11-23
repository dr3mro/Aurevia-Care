# CLN-04 clause 2 — operative notes include procedure, date, team, findings, complications and follow-up

## Clause

Operative notes include procedure, date, team, findings, complications and follow-up.

## Status

Domain half proven by active tests; the HTTP half is documented as a gap (partly proven overall).

## Evidence

Active, synthetic-data proof of the rule through the public `TemplateService` domain entry point:

- Test class: `com.aurevia.smartclinic.chart.clauses.OperativeNotesIncludeProcedureDateTeamFindingsTest`
  (`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/OperativeNotesIncludeProcedureDateTeamFindingsTest.java`).
  All seven tests are active (none are disabled) and pass with synthetic data only. They exercise
  the versioned-template domain service through its normal public API with an `InMemoryTemplateStore`
  fixture, so no external infrastructure, tenant context, database or patient data is used. No test
  goes through the durable `JdbcTemplateStore`, the forced-RLS tables in
  `db/migrations/V80__template_store.sql`, the frozen OPERATIVE row in
  `db/migrations/V87__baseline_templates.sql`, or an HTTP route; those durable layers were surveyed
  only, not executed by this evidence task.

Positive proof of the field set and the rule being met:

- `operativeTemplateDeclaresAllSixFieldsAsGovernanceRequired` — positive proof of the field set.
  The OPERATIVE baseline carries exactly `procedure`, `procedureDate`, `team`, `findings`,
  `complications` and `followUp`, all governance-required; `procedureDate` is a `DATE`, `findings` is
  `FREE_TEXT` with a language, so each of procedure, date, team, findings, complications and
  follow-up is represented with the right value kind.
- `validatesAndSavesAnOperativeNoteWithAllSixRequiredFieldsInTheDomain` — defines and approves the
  synthetic OPERATIVE baseline as version one, validates a note carrying all six fields, and saves it.
  The saved revision returns every authored value and the exact six field definitions frozen with
  the version; the saved revision is read back through the domain to prove the field meanings survive
  a reload, not just the in-memory save call.
- `operativeNoteWithAllSixFieldsValidatesAndSaves` — after governance approves version one, a note
  documenting all six fields validates with no violations, saves successfully and can be read back.

Negative proof (the rule is enforced, not optional):

- `operativeNoteOmittingAnyRequiredFieldIsRefused` — for each of the six fields, removing it makes
  validation invalid with `missing:<field>`, and saving the incomplete note is refused with HTTP 422
  `INVALID_NOTE`; nothing is persisted.
- `refusesAnOperativeNoteMissingARequiredFieldInTheDomain` — omits `complications`, asserts the
  `missing:complications` violation, that `saveNote` refuses the note with HTTP 422, and that a later
  read of the refused note yields HTTP 404 (the refused note is not persisted).
- `operativeNoteWithABlankRequiredFieldIsRefused` — a blank `complications` value yields
  `blank:complications` and the note is refused with 422 `INVALID_NOTE`.
- `aRoleThatCannotAuthorClinicalNotesIsRefusedAnOperativeNote` — a `reception` actor cannot save an
  operative note: the service denies with 403 `FORBIDDEN` naming `TEMPLATE_NOTE_SAVE`.

Production classes exercised by the active tests (none changed by this task):

- `com.aurevia.smartclinic.clinical.templates.BaselineTemplates` — `fieldsFor(TemplateKind.OPERATIVE)`
  is the authoritative in-process definition of clause 2's six fields, all required with their types,
  labels and free-text language.
- `com.aurevia.smartclinic.clinical.templates.TemplateService` — `defineVersion`, `approveVersion`,
  `validate`, `saveNote`, `savedNote` and the role authorization in `authorize` enforce requiredness
  and role authorization and freeze field meanings into the saved revision.
- `com.aurevia.smartclinic.clinical.templates.FieldDefinition`, `FieldValue`, `FieldType`,
  `TemplateNoteRevision`, `TemplateKind`, `SavedNoteRevision`, `ValidationReport`, `TemplateActor` —
  the value types that carry field meaning, authored values and validation outcomes through the API.
- `com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore` — the existing synthetic store.

The test class is a plain unit test with synthetic subjects; it creates no database and starts no
Spring context. Surefire for the targeted run: `tests="7" errors="0" skipped="0" failures="0"`.
No test was weakened, skipped or disabled. The would-be HTTP proofs are specified as follow-up task
requests rather than left as disabled tests, because a skipped test cannot exercise an absent
endpoint and would only obscure the gap.

Discovery survey (where the rule lives today): the chart package
`apps/api/src/main/java/com/aurevia/smartclinic/chart/record` has `EntryType` with only `PROBLEM`,
`ALLERGY`, `MEDICATION` and `VITAL`; there is no operative entry family. The versioned template
kernel lives in `apps/api/src/main/java/com/aurevia/smartclinic/clinical/templates` and in migrations
V80 and V87. Every controller under `apps/api/src/main/java/com/aurevia/smartclinic/web` (and
`clinical/notes/ClinicalNoteController`) was checked: none calls `TemplateService`, none handles
`TemplateKind.OPERATIVE`, and `contracts/openapi.yaml` has no template path. The template service is
listed as `unreferenced-service` in `scripts/wiring-baseline.json`, confirming there is no caller.
Neighbouring implementation observed in the survey (not changed): the authoritative governance
baseline is also frozen in `db/migrations/V87__baseline_templates.sql`
(`clinical_template_baseline` and `current_clinical_template_baseline`), with tenant-owned template
version, field-dictionary and note-revision tables in `db/migrations/V80__template_store.sql` and the
JDBC adapter `com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore`. The migration baseline
and the Java baseline are kept in lockstep by `BaselineTemplatesTest`. The domain-service API is the
exercised entry point, matching the neighbouring clause-proof tests (`TemplateVersioningTest`,
`TemplateNoteAcceptanceTest`).

## Gap

The domain half of the rule is built and proven by the active tests; there is no gap in that proof.
The HTTP half is not proven here: the six-field rule is built and enforced in the domain, but an
operative note cannot be captured or read over HTTP, so the API half of the clause is not proven.

1. **No HTTP entry point for versioned templates or operative notes.** There is no
   `TemplateController` (or equivalent) under `apps/api/src/main/java/com/aurevia/smartclinic/web`,
   no `POST /api/v1/templates/notes` and no `GET /api/v1/templates/notes/{noteId}`, and no template
   path in `contracts/openapi.yaml`. Files that would change: a new tenant-scoped controller that
   receives `TemplateService` by injection and derives `TemplateActor` from the active membership,
   request/response records for the note values and frozen meanings, the two OpenAPI operations (each
   with a tag, an `Idempotency-Key` header on the POST and route-parity coverage), and the regenerated
   client. The 422 response must carry the machine-readable validation violations (for example
   `missing:complications`); the current `TemplateService` error carries only `INVALID_NOTE`. The durable `JdbcTemplateStore` and the V80/V87 migrations already exist; no new migration,
   RLS policy or table is needed. Adding the HTTP-level tests (save/read, 422, 403, cross-tenant
   404) and proving 100% coverage of the new controller is part of this work.
2. **No staff-web capture surface.** `apps/staff-web` has no operative-template form; it only calls
   `/api/v1/clinical-notes` for CLN-05 free-text revisions. A form that saves the six fields against
   an approved template version, with UX and clinical review, is separate work.

That is **two separate tasks**: (1) backend controller + contract + generated client + HTTP test addition
with 100% coverage; (2) staff-web capture and clinical review. Real clinical acceptance remains a
separate G5 human gate and is not claimed here.

This evidence task added only a test class and this note. It changed no production code, no existing
test, no migration, no contract, no tracker and no file outside its scope.
