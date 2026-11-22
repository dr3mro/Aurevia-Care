# OB/GYN captures LMP and structured obstetric history

## Clause

OB/GYN captures LMP and structured obstetric history from the approved site dictionary.

## Status

Proven by tests

## Evidence

Test class: `apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ObGynCapturesLmpStructuredObstetricHistoryTest.java`.

Test methods and the behavior each proves:

- `obgynNoteCapturesLmpAndDictionaryBackedObstetricHistory` — the OB/GYN baseline defines `lmp` as a required `DATE` and `obstetricHistory` as a required `CODE` bound to the `obstetric_history` dictionary; a note with a valid ISO LMP and an in-dictionary code validates, saves, snapshots the approved dictionary entries and keeps its frozen field meanings.
- `obstetricCodeOutsideTheApprovedSiteDictionaryIsRefused` — an out-of-dictionary obstetric code yields `dictionary:obstetricHistory` and blocks saving (negative: dictionary membership).
- `obgynNoteWithoutLmpIsRefused` — a note that omits LMP yields `missing:lmp` and blocks saving (negative: required field).
- `obgynNoteWithMalformedLmpIsRefused` — a non-ISO LMP yields `format:lmp` (negative: value format).
- `obgynTemplateCannotBeApprovedBeforeItsDictionaryIsApproved` — governance cannot approve the OB/GYN template version while its referenced dictionary is still pending (negative: "approved" site dictionary).
- `nonClinicianCannotSaveAnObgynNote` — a tenant admin cannot save the note; only the clinician role may capture it (negative: role).

Production classes exercised through their public domain entry point:

- `com.aurevia.smartclinic.clinical.templates.TemplateService` — defines/approves the dictionary and template version, validates and saves the note, and freezes field meanings and dictionary snapshots.
- `com.aurevia.smartclinic.clinical.templates.BaselineTemplates` and `TemplateKind.OBGYN` — the version-one OB/GYN field set (`lmp`, `obstetricHistory`).
- `com.aurevia.smartclinic.clinical.templates.FieldDefinition`, `FieldType`, `FieldValue`, `TemplateNoteRevision` — the structured field model and validation rules.
- `com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore` — the tenant-keyed store used by these synthetic tests; the production adapter is `JdbcTemplateStore` backed by the `template_*` tables in `db/migrations/V80__template_store.sql` and the seed in `V87__baseline_templates.sql`.

The rule is exercised through the domain service because template capture has no dedicated HTTP controller today; `TemplateService` is that behavior's normal public entry point, consistent with the neighbouring CLN-04 acceptance tests (`TemplateNoteAcceptanceTest`, `TemplateVersioningTest`, `TemplatePersistenceTest`).

## Gap

None. The clause is implemented and provable at its domain entry point. A separate, untracked concern is that template definition/approval/capture is not exposed over a dedicated REST controller (unlike other chart surfaces); that is a delivery/wiring matter outside this clause and outside this task's scope, not a gap in the clause behavior itself.
