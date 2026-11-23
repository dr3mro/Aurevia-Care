# CLN-04 clause 1 — provide versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates

## Clause

Provide versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates.

## Status

Partly proven

## Evidence

Test class `com.aurevia.smartclinic.chart.clauses.ProvideVersionedSoapOperativeObGynPerinatalTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ProvideVersionedSoapOperativeObGynPerinatalTest.java`),
a domain-level acceptance suite on the public `TemplateService` API using an
`InMemoryTemplateStore` with synthetic dictionaries, dates and identifiers only.
It proves the five template families and their governance rules at the domain
boundary; it does not claim an HTTP entry point (see `## Gap`). Verified locally:
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ProvideVersionedSoapOperativeObGynPerinatalTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

- `definesAndApprovesVersionedTemplatesForAllFiveFamilies` — for SOAP, OPERATIVE,
  OBGYN, PERINATAL and PEDIATRIC_DEVELOPMENT: the baseline fields are defined as
  version 1 and approved by clinical governance; `effective` returns the approved
  version carrying the clause's named fields (`chiefConcern` / `history` / `exam` /
  `assessment` / `plan`; `procedure` / `procedureDate` / `team` / `findings` /
  `complications` / `followUp`; `lmp` / `obstetricHistory`; `birthHistory` /
  `neonatalHistory`; `milestoneDomain` / `observation` / `date`). A fully populated
  note, with a language on free text, ISO dates and site-dictionary codes, validates
  with no violations and is saved with the exact version's frozen field meanings and
  the tenant identity. Defining and approving a SOAP version 2 keeps version 1 in
  `history` and still valid, so an older approved version is not rewritten.
- `refusesTemplateDefinitionByClinician` — a clinician may author notes but cannot
  define a dictionary or a template version (ApiException 403 `FORBIDDEN`).
- `doesNotReadAnotherTenantsTemplateVersion` — a version approved in one tenant is
  concealed from a clinician of another tenant: both `effective` and `validate`
  return 404 `NOT_FOUND` rather than the other tenant's fields.
- `refusesApprovalWithoutAnApprovedSiteDictionary` — governance cannot approve the
  OBGYN version while its `obstetric_history` dictionary is still pending
  (ApiException 422 `DICTIONARY_NOT_APPROVED`).
- `refusesNoteAgainstUnknownVersion` — the exact approved version validates, while a
  note bound to an unknown version is refused by both `validate` and `saveNote`
  (404 `NOT_FOUND`).
- `refusesNoteMissingARequiredField` — an OPERATIVE note missing `findings` reports
  `missing:findings` and cannot be saved (ApiException 422 `INVALID_NOTE`).

Production classes exercised through that entry point:

- `com.aurevia.smartclinic.clinical.templates.TemplateService` — definition and
  approval of dictionaries and versions, role gates, validation against the exact
  referenced version, note saving with frozen field meanings, tenant-scoped reads
  and an append-only audit trail.
- `com.aurevia.smartclinic.clinical.templates.TemplateKind`, `BaselineTemplates`,
  `FieldDefinition`, `FieldType`, `FieldValue`, `TemplateNoteRevision`,
  `TemplateVersion`, `ValidationReport`, `SavedNoteRevision` and `TemplateActor` —
  the five family baselines, the field/dictionary shape, the language and date
  rules, the saved snapshot and the caller identity snapshot.
- `com.aurevia.smartclinic.clinical.templates.TemplateStore`,
  `InMemoryTemplateStore` (test fixture) and the production
  `JdbcTemplateStore` — the persistence boundary keyed by organization. The durable
  schema is in `db/migrations/V80__template_store.sql` (five tenant-owned tables,
  each with `ENABLE` and `FORCE ROW LEVEL SECURITY` plus a `WITH CHECK` tenant
  policy, append-only triggers and grants) and the governed baselines are in
  `db/migrations/V87__baseline_templates.sql` (the five version-1 field sets and
  synthetic site dictionaries, append-only and SELECT-only to the application).

Adjacent CLN-04 evidence already present and not re-proved by this suite:
`apps/api/src/test/java/com/aurevia/smartclinic/it/clinical/TemplateVersioningTest.java`
(five baseline field sets, version pending until approval, historical meanings,
dictionary and language rules, cross-tenant concealment, concurrency),
`apps/api/src/test/java/com/aurevia/smartclinic/clinical/templates/TemplateNoteAcceptanceTest.java`
(full and partial notes for four families, free-text language, historical field
meanings) and `TemplatePersistenceTest.java` (published-version immutability,
dictionary snapshot, tenant isolation, invalid inputs) in the same directory;
`apps/api/src/test/java/com/aurevia/smartclinic/clinical/templates/JdbcTemplateStoreTest.java`
covers the JDBC row mapping.

Discovery survey: the chart package `apps/api/src/main/java/com/aurevia/smartclinic/chart/record`
has only `PROBLEM`, `ALLERGY`, `MEDICATION` and `VITAL` entry types and no template
family. The only clinical controller is
`apps/api/src/main/java/com/aurevia/smartclinic/clinical/notes/ClinicalNoteController.java`,
which serves the CLN-05 draft/sign/amend routes and does not define or approve CLN-04
templates. No template controller exists under
`apps/api/src/main/java/com/aurevia/smartclinic/web`, no template route appears in
`contracts/openapi.yaml`, and no generated client call exists for this behavior.
The clause is therefore implemented in the domain and schema but is not reachable
over HTTP.

## Gap

The domain behavior and its durable schema exist, but no request can reach them:
there is no template controller, no `TemplateService` bean wiring for production,
no HTTP route and no published contract, so a client cannot define, approve or save
a template note and no HTTP-level integration test can be written today. Closing
this needs, in one cross-boundary backend task, a thin controller with an injected
`TemplateService` and a wired `JdbcTemplateStore`, an HTTP-level integration test
that defines and approves a version and saves a note through that controller, and
the contract work for the new routes: a tag per operation, an `Idempotency-Key`
header on every POST and DELETE, `cursor`, `limit` and `nextCursor` on list GETs,
route parity with no `missing-routes.txt` entry, and a regenerated client
(`gen:api` / `gen:api:check`). A separate staff-web task is needed before clinical
staff can author or govern these templates through the UI. No migration is needed:
V80 and V87 already carry the tenant-isolated schema and baselines. Real site
clinical-governance approval and genuine clinical acceptance remain a separate
G5 policy concern and are not claimed by this synthetic suite.

This evidence task added only the test class and this document. It changed no
production code, no existing test, no migration and no contract.
