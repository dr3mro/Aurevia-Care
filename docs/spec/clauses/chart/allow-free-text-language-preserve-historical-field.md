# CLN-04 clause 7 — allow free text with language and preserve historical field meanings

## Clause

Allow free text with language, and preserve historical field meanings with each saved version.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.AllowFreeTextLanguagePreserveHistoricalFieldTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/AllowFreeTextLanguagePreserveHistoricalFieldTest.java`),
a synthetic test over the module's normal domain-service entry point
(`TemplateService` with the `InMemoryTemplateStore` fixture used by the
neighbouring CLN-04 acceptance tests; no real patient data):

- `freeTextWithLanguageValidatesAndTheSavedRevisionKeepsItsVersionFieldMeanings`
  — a SOAP note whose chief concern is Arabic free text tagged `ar` validates
  and saves; the saved revision keeps that language-tagged value and the exact
  version-one field definition (`chief concern`, canonical language `en`, type
  `FREE_TEXT`) after governance renames the field and changes its canonical
  language in version two, and the frozen snapshot rejects caller mutation.
- `freeTextWithoutALanguageIsRejectedAndCannotBeSaved` — the same note with no
  language on the free-text value is reported invalid (`language:chiefConcern`)
  and `saveNote` refuses it with 422, persisting nothing.
- `aSavedHistoricalNoteIsConcealedFromAnotherTenant` — a clinician of another
  tenant reading the saved note gets 404 `NOT_FOUND`, so a preserved meaning
  stays inside its tenant.

Production classes exercised by the entry point:
`com.aurevia.smartclinic.clinical.templates.TemplateService` (the free-text
language rule in `validate`, the field-meaning and dictionary snapshot in
`saveNote`, and read-back in `savedNote`),
`com.aurevia.smartclinic.clinical.templates.FieldValue` and
`com.aurevia.smartclinic.clinical.templates.FieldDefinition` (language tags),
`com.aurevia.smartclinic.clinical.templates.SavedNoteRevision` (immutable
historical field meanings),
`com.aurevia.smartclinic.clinical.templates.TemplateNoteRevision` and
`com.aurevia.smartclinic.clinical.templates.TemplateVersion`, with the synthetic
`com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore` and
`com.aurevia.smartclinic.clinical.templates.BaselineTemplates` fixtures.

Discovery: the clause is implemented in the `chart` neighbour package
`clinical/templates`, not in `chart/record` (which implements CLN-03). The
related catalog types `com.aurevia.smartclinic.catalog.dictionary.LocalizedText`
and `com.aurevia.smartclinic.catalog.dictionary.UiLocale` keep free text and its
content language independent of the interface locale. Migration
`db/migrations/V80__template_store.sql` provides the durable tenant-owned store,
including `field_meanings` and dictionary-entry snapshots on
`template_note_revision`. There is no dedicated template controller or OpenAPI
route, so this test exercises the domain entry point directly, as the existing
`TemplateNoteAcceptanceTest` and `it/clinical/TemplateVersioningTest` do.

Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AllowFreeTextLanguagePreserveHistoricalFieldTest test`
— `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None.
