# CLN-04 clause 3 — OB/GYN captures LMP and structured obstetric history

## Clause

OB/GYN captures LMP and structured obstetric history from the approved site dictionary.

## Status

Proven by tests

## Evidence

Active proof at the domain-service and JDBC-store layer, on a fresh real-PostgreSQL
database created from the production Flyway migrations, with synthetic subjects,
codes and patient references only. There is no CLN-04 HTTP controller, so the tests
drive the production `TemplateService` bean over the production `JdbcTemplateStore`
bean inside the tenant-guarded transaction (`TenantContext` + `TransactionTemplate`),
which binds `app.current_org` and `app.actor` for forced RLS. The clause behaviour,
not a user-facing route, is what these tests prove.

Test class: `com.aurevia.chart.clauses.ObGynCapturesLmpStructuredObstetricHistoryTest`
(`apps/api/src/test/java/com/aurevia/chart/clauses/ObGynCapturesLmpStructuredObstetricHistoryTest.java`):

- `clinicianSavesObgynNoteWithIsoLmpAndApprovedDictionaryCode` — a tenant admin defines
  the `obstetric_history` dictionary with the approved synthetic codes `G0P0`…`G4P0`
  and clinical governance approves it; a tenant admin defines the OB/GYN version and
  governance approves it. A clinician saves `lmp = 2026-10-01` (ISO date) with
  `obstetricHistory = G1P0`. The test asserts the saved values, the frozen field
  meanings (`lmp` DATE required labelled "last menstrual period"; `obstetricHistory`
  CODE required with dictionary key `obstetric_history`), the frozen dictionary
  snapshot, and the persisted `template_note_revision` row read back with the tenant
  bound on the connection.
- `wrongRoleCannotSaveAnObgynNote` — a `reception` actor is refused with 403
  `FORBIDDEN` and no note row is written.
- `crossTenantActorCannotReadTheSavedObgynNote` — a clinician of another active
  organization cannot read the saved note (404 `NOT_FOUND`) and the owning tenant's
  row is unchanged.
- `templateVersionWithUnapprovedDictionaryCannotBeApproved` — a version whose CODE
  field references a dictionary that governance has not approved cannot be approved
  (422 `DICTIONARY_NOT_APPROVED`). The version stays pending.
- `outOfDictionaryCodeAndMalformedOrMissingLmpAreRejected` — validation names
  `dictionary:obstetricHistory` for the out-of-dictionary code `G9P9`, `format:lmp`
  for `01/10/2026`, and `missing:lmp` when the required date is omitted; a save that
  carries the invalid code and malformed date is refused with 422 `INVALID_NOTE` and
  writes no row.

Production classes and artifacts exercised:

- `com.aurevia.clinical.templates.TemplateKind` — `OBGYN` family.
- `com.aurevia.clinical.templates.BaselineTemplates` — `fieldsFor(OBGYN)`
  defines `lmp` as a required DATE and `obstetricHistory` as a required CODE with
  dictionary key `obstetric_history`.
- `com.aurevia.clinical.templates.TemplateService` — dictionary and
  version definition/approval, governance gate (`DICTIONARY_NOT_APPROVED`), note
  validation and role authorization (`CLINICIAN` save, `TENANT_ADMIN` define,
  `CLINICAL_GOVERNANCE` approve), plus freezing of field meanings and dictionary
  entries at save time.
- `com.aurevia.clinical.templates.JdbcTemplateStore` — the production
  tenant-scoped JDBC store over the V80 tables.
- `com.aurevia.tenant.TenantContext` and
  `com.aurevia.tenant.TenantGuardTransactionManager` — bind the tenant
  GUCs for every store call under forced RLS.
- `db/migrations/V80__template_store.sql` — `template_field_dictionary`,
  `template_version`, `template_version_sequence`, `template_note_revision` and
  `template_audit_event`, each with `ENABLE` + `FORCE ROW LEVEL SECURITY` and a
  tenant isolation policy, plus immutability triggers. `OBGYN` is an allowed kind.
- `db/migrations/V87__baseline_templates.sql` — the global version-1 baseline catalog
  freezing the LMP and obstetric-history field definitions with the approved
  synthetic site codes `G0P0`…`G4P0`. It is a global reference catalog, not a tenant
  seed; tenant dictionaries and approvals are created through `TemplateService`.

Verified locally with `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml
-Pno-security -Dtest=ObGynCapturesLmpStructuredObstetricHistoryTest test`: the Surefire
report shows `tests="5" errors="0" skipped="0" failures="0"`.

## Gap

The clause behaviour is proven at the service/store layer, but there is no HTTP entry
point and no OpenAPI path for CLN-04 templates or OB/GYN note capture. `TemplateService`
has no caller in `src/main`; there is no `TemplateController`, and
`contracts/openapi.yaml` documents no template/OBGYN/obstetric operation. The adjacent
`/api/v1/clinical-notes` routes belong to CLN-05 and carry plain `templateCode` /
`templateVersion` fields without calling `TemplateService`; `POST
/api/v1/patient-instructions/templates` is an unrelated messaging route. No user-facing
flow can therefore claim to capture LMP and obstetric history through a published API.

Publishing one needs a thin `TemplateController` that receives `TemplateService` by
injection, a `contracts/openapi.yaml` entry (tag, `Idempotency-Key` on writes, route
parity), regenerated clients, and a staff-web task for the capture form — at least two
separate cross-boundary tasks. This evidence story adds only a test class and this
document; it changes no production code, existing test, contract or migration.
