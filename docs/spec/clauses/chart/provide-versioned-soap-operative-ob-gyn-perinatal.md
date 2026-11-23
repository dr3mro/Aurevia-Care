# CLN-04 clause 1 — provide versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates

## Clause

Provide versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates.

## Status

Partly proven

## Evidence

Two independent evidence passes proved this clause and are both preserved here:
a real-PostgreSQL integration pass against the production JDBC store, and a
domain-boundary pass against the synthetic in-memory store. Their twelve test
methods are collected in one class,
`com.aurevia.smartclinic.chart.clauses.ProvideVersionedSoapOperativeObGynPerinatalTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/chart/clauses/ProvideVersionedSoapOperativeObGynPerinatalTest.java`).
All dictionaries, dates, identifiers and organizations are synthetic; no test
is disabled and no patient data is used. The merged class was verified on this
tree with `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=ProvideVersionedSoapOperativeObGynPerinatalTest test`
— `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

### Integration evidence — production JDBC store on real PostgreSQL

The integration group runs the domain service against its production JDBC
store on real PostgreSQL with Flyway V80/V87 applied, with the tenant context
bound exactly as the HTTP layer binds it. Each test uses its own pair of
synthetic organizations, so a version really survives a store round trip and a
second tenant cannot see the first tenant's definitions.

- `theDurableBaselineCatalogProvidesEveryFamily` — reads the V87
  `current_clinical_template_baseline` view on real PostgreSQL and asserts one
  row per family whose fields equal the approved Java baseline, plus both
  referenced site dictionaries; guards against a durable catalog that drifted
  from the definitions the other tests use.
- `everyFamilyProvidesAnApprovedVersionedTemplate` — for all five
  `TemplateKind` values (SOAP, OPERATIVE, OBGYN, PERINATAL,
  PEDIATRIC_DEVELOPMENT) a governance-defined version becomes the effective
  approved version, keeps the exact `BaselineTemplates` field set and appears
  in the version history after a store round trip.
- `aNewApprovedVersionBecomesEffectiveWithoutRewritingEarlierVersions` — a
  second approved version of the operative template becomes effective while the
  earlier version remains in history with its original field meanings.
- `definingAVersionIsRefusedForAnActorWithoutTheDefineRole` — a clinician
  (wrong role) cannot define a version (403), and no version is left behind (404).
- `aVersionFromAnotherTenantIsNotVisible` — a version published by one tenant
  is concealed from a second tenant (404).
- `aNoteAgainstAnUnknownVersionIsRefused` — validation against an unknown
  version is refused (404) rather than silently accepted.

Production classes exercised through this entry point:
`com.aurevia.smartclinic.clinical.templates.TemplateService` (define, approve,
effective, history and validate commands plus role gating),
`com.aurevia.smartclinic.clinical.templates.JdbcTemplateStore` (production
`TemplateStore` adapter), `com.aurevia.smartclinic.clinical.templates.BaselineTemplates`,
`TemplateKind`, `TemplateVersion`, `FieldDefinition`, `FieldType`, `TemplateActor`,
`TemplateNoteRevision`, and `com.aurevia.smartclinic.tenant.TenantContext`.
Durable structure comes from `db/migrations/V80__template_store.sql` (tenant
tables with ENABLE/FORCE RLS, immutable published versions, append-only
revisions) and `db/migrations/V87__baseline_templates.sql` (governance baselines
for all five families). This pass verified locally with
`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ProvideVersionedSoapOperativeObGynPerinatalTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

### Domain-boundary evidence — synthetic in-memory store

The domain group exercises the public `TemplateService` API with an
`InMemoryTemplateStore` and synthetic dictionaries only. It proves the five
template families and their governance rules at the domain boundary, including
full-note validation and saving, dictionary governance, cross-tenant concealment
and required fields — assertions the integration group does not make. It does
not claim an HTTP entry point (see `## Gap`).

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
  `InMemoryTemplateStore` (used here as the synthetic test store; it lives in
  `src/main` as pre-existing wiring debt) and the production
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
This pass verified locally with
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ProvideVersionedSoapOperativeObGynPerinatalTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

The domain behavior and its durable schema exist and are now proved by both the
in-memory and the real-PostgreSQL suites, but no HTTP request can reach them:
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
G5 policy concern and are not claimed by these synthetic suites.

The integration pass assessed the same clause as `Proven by tests` with `Gap: None`,
reasoning that the domain behavior and durable V80/V87 schema are complete and
that the missing HTTP definition controller and the in-memory store in `src/main`
are adjacent quality gaps for a separate story rather than a gap in this clause.
That reasoning and its evidence are retained above; this merged record keeps
`Partly proven` because no HTTP entry point exists yet. This evidence change added
only the test class and this document; it changed no production code, no existing
test, no migration and no contract.
