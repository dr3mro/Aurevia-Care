# ARC-01 clause 6 — record clinical effective time, ingestion time, author, source system, source ID and version

## Clause

Record clinical effective time, ingestion time, author, source system, source ID and version where applicable.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.tenant.clauses.RecordClinicalEffectiveTimeIngestionTimeAuthorTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/tenant/clauses/RecordClinicalEffectiveTimeIngestionTimeAuthorTest.java`),
an HTTP-level integration test on a fresh real-PostgreSQL database created from
the production Flyway migrations, with synthetic subjects in two tenants and one
synthetic patient. It enters through the running `POST /api/v1/clinical-notes`
route and reads the persisted revision row, so "recorded" means both returned in
the response and durable in the tenant-guarded table:

- `createRecordsEffectiveTimeAuthorAndVersionWithIngestionTime` — the response
  and the persisted `public.clinical_note_revision` row carry the caller's
  `effectiveAt = 2024-06-01T10:15:30Z`, the database-stamped `created_at`
  (ingestion instant, asserted later than the effective instant), the
  `clinicalAuthorSubject`, the trigger-stamped `dataEntrySubject`, revision
  `version = 1` and `templateVersion = 2`.
- `futureOrMalformedEffectiveTimeIsRefused` — a future instant and a malformed
  string are both refused (422 `INVALID_EFFECTIVE_AT`) with no row written; this
  covers both failure sides of `NoteInputs.instantOrNow`.
- `actorWithoutANoteRoleCannotRecordProvenance` — a `reception` actor is refused
  (403) and no row is written.
- `crossTenantActorCannotReadOrWriteTheRecord` — a clinician of another tenant
  gets 404 for both a read and a versioned edit of the record, and the stored
  author and version are unchanged (forced RLS).
- `staleVersionIsRefused` — a syntactically valid but stale `If-Match` gets 412
  `STALE_VERSION` and the stored version stays at 1.
- `dataEntryActorAndIngestionTimeCannotBeForgedFromTheRequestBody` — extra
  `dataEntrySubject` and `createdAt` JSON properties are ignored; the response
  and row keep the authenticated data-entry actor and the database-owned
  ingestion instant.

Production classes and objects exercised through `POST /api/v1/clinical-notes`,
`PUT /api/v1/clinical-notes/{id}` and `GET /api/v1/clinical-notes/{id}`:
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteController` (thin HTTP
adapter and `CreateRequest`), `com.aurevia.smartclinic.clinical.notes.ClinicalNoteService`
(`createDraft`, `updateDraft`, compare-and-set and authorization),
`com.aurevia.smartclinic.clinical.notes.NoteInputs` (trust-boundary validation
of the effective instant and template version),
`com.aurevia.smartclinic.clinical.notes.ClinicalNoteRevision` and `RevisionInput`
(the six-field-bearing record), `com.aurevia.smartclinic.clinical.notes.NoteRepository`
(SQL boundary and row mapping), `com.aurevia.smartclinic.api.IfMatch`, and the
tenant isolation plus server-owned version/actor/ingestion columns of
`db/migrations/V15__clinical_note_signing.sql` (RLS and FORCE RLS, version and
actor triggers, `created_at DEFAULT now()`).

Discovery of where the clause is implemented today (honest status — no claim that
one owner covers all six fields):

- Clinical effective time: `ClinicalNoteController.CreateRequest.effectiveAt`,
  `ClinicalNoteService.createDraft`, `NoteInputs.instantOrNow`,
  `ClinicalNoteRevision.effectiveAt`, `NoteRepository.insert`, `V15` column
  `effective_at` with `CHECK (signed_at IS NULL OR effective_at IS NULL OR
  signed_at >= effective_at)`.
- Ingestion time: `V15` column `created_at DEFAULT now()`, mapped by
  `NoteRepository` into `ClinicalNoteRevision.createdAt`. Server-owned, not a
  request field.
- Author: `ClinicalNoteController.CreateRequest.clinicalAuthorSubject`,
  `ClinicalNoteService.createDraft`, `V15` columns `clinical_author_subject` and
  trigger-stamped `data_entry_subject`. The clinical author is caller-declared;
  the data-entry actor is derived from `TenantContext` (see Gap).
- Version: caller-supplied `templateVersion` (`NoteInputs.version`, `V15`
  `template_version`) and the trigger-owned monotonic revision `version`
  (`V15` version function/triggers, `IfMatch` precondition). No source-document
  version is stored on a clinical note.
- Source system and source ID: not present on any clinical-note route, class or
  migration. They exist elsewhere: `tenant/importing/ImportProvenance.java`
  (snake-case `source_system`/`source_version`), `OnboardingImportService.validateProvenance`,
  `ImportController`, `ControlledImportController` and
  `db/migrations/V90__onboarding_import.sql` (`source_id`, `source_system`,
  `source_version`); `tenant/export/ExportModel.Provenance` (`sourceSystem`,
  `sourceId`, `version`); `db/migrations/V25__observation_trending.sql`
  (`source_system`, `source_version`, `created_by`, `created_at`) behind the
  running `POST /api/v1/trends/observations` (`exchange/trend/TrendController.java`,
  `TrendService`, `TrendRepository`); `db/migrations/V23__critical_results.sql`
  (`source_system`, `source_id`) behind `exchange/critical/CriticalResultRepository`;
  and `db/migrations/V17__document_associations.sql` (`source_system`, `source_id`)
  behind `documents/DocumentRepository`. None of these is the clinical-note
  create route, so this class does not re-test them.

Verified locally: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml
-Pno-security -Dgroups=integration -Dtest=RecordClinicalEffectiveTimeIngestionTimeAuthorTest test`
— `tests="6" errors="0" skipped="0" failures="0"` in the Surefire XML report.
"Partly proven" is limited to the clause behaviour above; it does not claim that
the clinical-notes route is published in the committed OpenAPI contract.

## Gap

Two kinds of gap remain, none of which is papered over with a disabled or failing
test in this change:

1. Missing on the chosen route. `POST /api/v1/clinical-notes` stores no
   `source_system`, `source_id` or source-document `version`; those fields are
   carried by the import/export, trend, critical-result and document-association
   slices named above, not by clinical notes. One later task can add them to the
   note create command, its revision record and a new versioned migration (with
   RLS/FORCE RLS and a store) and extend this class; a second task can publish the
   route and a six-field provenance schema in `contracts/openapi.yaml` and
   regenerate the client — the committed contract has no `/api/v1/clinical-notes`
   path (`contracts/openapi/missing-routes.txt`) and no consolidated provenance
   schema, only isolated handout `sourceId`/`sourceSystem` strings. Two tasks.
2. Author forgery is not refused on this route. `ClinicalNoteService.createDraft`
   validates `clinicalAuthorSubject` only as non-blank text of at most 128
   characters; a known subject of the same tenant may be named as clinical author
   (which is intended by CLN-03's separation of clinical author from data-entry
   actor), while an unknown or cross-tenant subject fails the `V15` foreign key
   with SQLSTATE 23503 and surfaces as HTTP 500 because `ClinicalNoteService`
   does not use `SqlFailureTranslator`. No green negative test can claim that a
   forged author is refused here, so none was written. Fixing it (bind or
   authorize the claimed author, translate the FK to a 4xx) is production work
   outside this test-only scope; one separate task.

The `dataEntrySubject` and ingestion-time overrides are not a gap: both are
server-owned and the tests above prove an extra JSON property cannot replace
them.
