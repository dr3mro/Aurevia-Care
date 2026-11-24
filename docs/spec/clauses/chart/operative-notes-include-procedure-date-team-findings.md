# CLN-04 clause 2 — API test evidence

## Clause

Operative notes include procedure, date, team, findings, complications and follow-up.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.chart.clauses.OperativeNotesIncludeProcedureDateTeamFindingsTest`
(path: `apps/api/src/test/java/com/aurevia/chart/clauses/OperativeNotesIncludeProcedureDateTeamFindingsTest.java`).
All five tests are active (none are disabled) and pass with synthetic data only.
They exercise the versioned-template domain service through its normal public API with an
`InMemoryTemplateStore` fixture, so no external infrastructure or patient data is used.

- `operativeTemplateDeclaresAllSixFieldsAsGovernanceRequired` — positive proof of the field set.
  The OPERATIVE baseline carries exactly `procedure`, `procedureDate`, `team`, `findings`,
  `complications` and `followUp`, all governance-required; `procedureDate` is a `DATE`,
  `findings` is `FREE_TEXT` with a language, so procedure, date, team, findings, complications
  and follow-up are each represented with the right value kind.
- `operativeNoteWithAllSixFieldsValidatesAndSaves` — positive proof of the rule being met.
  After governance approves version one, an operative note documenting all six fields validates
  with no violations, saves successfully, and its saved revision carries all six field meanings
  and can be read back.
- `operativeNoteOmittingAnyRequiredFieldIsRefused` — negative proof (missing input). For each
  of the six fields, removing it makes validation invalid with `missing:<field>`, and saving the
  incomplete note is refused with HTTP 422 `INVALID_NOTE`; nothing is persisted.
- `operativeNoteWithABlankRequiredFieldIsRefused` — negative proof (forbidden state). A blank
  `complications` value yields `blank:complications` and the note is refused with 422.
- `aRoleThatCannotAuthorClinicalNotesIsRefusedAnOperativeNote` — negative proof (wrong role).
  A `reception` actor cannot save an operative note: the service denies with 403 `FORBIDDEN`
  naming `TEMPLATE_NOTE_SAVE`.

Production classes exercised (none changed by this task):

- `com.aurevia.clinical.templates.BaselineTemplates` — synthetic baseline field sets;
  its OPERATIVE family is the authoritative in-process definition of clause 2's six fields.
- `com.aurevia.clinical.templates.TemplateService` — public API entry point;
  `defineVersion`, `approveVersion`, `validate`, `saveNote` and `savedNote` enforce requiredness
  and role authorization and freeze field meanings into the saved revision.
- `com.aurevia.clinical.templates.FieldDefinition`, `FieldType`, `FieldValue`,
  `TemplateNoteRevision`, `ValidationReport` and `SavedNoteRevision` — the value types that
  carry field meaning, authored values and validation outcomes through that API.
- `com.aurevia.clinical.templates.InMemoryTemplateStore` and `TemplateActor` —
  synthetic store and caller-identity fixtures.

Neighbouring implementation observed in the source survey (not changed by this task): the
authoritative governance baseline is also frozen in migration `db/migrations/V87__baseline_templates.sql`
(`clinical_template_baseline` and `current_clinical_template_baseline`), with tenant-owned template
version, field-dictionary and note-revision tables in `db/migrations/V80__template_store.sql` and the
JDBC adapter `com.aurevia.clinical.templates.JdbcTemplateStore`. The migration baseline
and the Java baseline are already kept in lockstep by `BaselineTemplatesTest`. No HTTP controller
maps a dedicated CLN-04 template endpoint; the domain-service API is the exercised entry point,
matching the neighbouring clause-proof tests.

## Gap

None.

CLN-04 clause 2 is built and proven by active tests. This task added proof only; it did not change
production code, an existing test, the OpenAPI contract, a migration or any file outside its scope.
