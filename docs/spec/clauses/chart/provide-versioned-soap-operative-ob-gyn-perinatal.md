# CLN-04 clause 1 — versioned clinical note templates

## Clause

Provide versioned SOAP, operative, OB/GYN, perinatal and pediatric development templates.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.chart.clauses.ProvideVersionedSoapOperativeObGynPerinatalTest`
(`apps/api/src/test/java/com/aurevia/chart/clauses/ProvideVersionedSoapOperativeObGynPerinatalTest.java`),
a real-PostgreSQL integration test with Flyway V80/V87 applied, the production
JDBC store and the tenant context bound as the HTTP layer binds it. Each test
uses its own pair of synthetic organizations. No `@Disabled` test and no
patient data.

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

Production classes exercised through the test entry point:
`com.aurevia.clinical.templates.TemplateService` (define, approve,
effective, history and validate commands plus role gating),
`com.aurevia.clinical.templates.JdbcTemplateStore` (production
`TemplateStore` adapter), `com.aurevia.clinical.templates.BaselineTemplates`,
`TemplateKind`, `TemplateVersion`, `FieldDefinition`, `FieldType`, `TemplateActor`,
`TemplateNoteRevision`, and `com.aurevia.tenant.TenantContext`.
Durable structure comes from `db/migrations/V80__template_store.sql` (tenant
tables with ENABLE/FORCE RLS, immutable published versions, append-only
revisions) and `db/migrations/V87__baseline_templates.sql` (governance baselines
for all five families).

Discovery also found `com.aurevia.clinical.templates.InMemoryTemplateStore`
in `src/main`, `apps/api/src/test/java/com/aurevia/clinical/templates/BaselineTemplatesTest.java`,
`JdbcTemplateStoreTest.java`, `TemplateNoteAcceptanceTest.java` and
`TemplatePersistenceTest.java` covering the same domain, and the CLN-05
`com.aurevia.clinical.notes.ClinicalNoteController` HTTP adapter for
saved notes. No HTTP controller exposes the template definition commands, so the
entry point here is the domain service behind the production JDBC store; that
is a separate gap from this clause and is recorded in the task notes.

Verified locally:
`OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ProvideVersionedSoapOperativeObGynPerinatalTest test`
— `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0`.

## Gap

None. All five families have durable approved baselines and a versioned define →
approve → effective/history path with governance and tenant boundaries; the
tests above prove those behaviors. The missing HTTP definition controller and
the in-memory store in `src/main` are adjacent quality gaps for a separate
story, not a gap in this clause.
