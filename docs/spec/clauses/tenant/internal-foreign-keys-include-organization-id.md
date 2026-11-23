# ARC-01 clause 2 — internal foreign keys include organization ID

## Clause

Internal foreign keys include organization ID.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.tenant.clauses.InternalForeignKeysIncludeOrganizationIdTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/tenant/clauses/InternalForeignKeysIncludeOrganizationIdTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration` that migrates a
dedicated database (`smartclinic_arc01_clause2_it`) from `db/migrations` with a
NOBYPASSRLS runtime role, seeds two synthetic tenants with `app.current_org` and
`app.actor` bound, and drops the database afterwards. No HTTP route belongs to a
schema-key rule, so the test drives the authoritative schema directly.

Test methods (one positive rule proof, one negative refusal):

- `everyInternalForeignKeyOnTenantOwnedTablesIncludesOrganizationId` — reads the
  live `pg_constraint` / `pg_attribute` catalog of the migrated schema. For every
  foreign key declared on a tenant-owned table (a `public` table other than the
  global `organization` registry that has an `organization_id` column) it asserts
  that the referenced columns include `organization_id`. It then asserts that the
  *only* foreign keys whose referencing columns omit `organization_id` are exactly
  the three `destination_org -> public.organization(organization_id)` exchange
  pointers (`exchange_authorization`, `exchange_order`, `exchange_work_item`),
  which is the ARC-02 cross-tenant-root exception; any new cross-tenant pointer
  fails the test. A non-zero catalog count and a V1 membership composite-key
  sentinel make the assertion non-vacuous. Against the current schema the scan
  covers 305 foreign keys: 0 have a referenced side without `organization_id`, and
  the referencing-side exception set equals those three pointers exactly.
- `crossTenantReferenceWithoutMatchingOrganizationIdIsRefused` — seeds one user
  identity in each synthetic tenant. A same-tenant `membership` insert succeeds,
  then an insert whose `(organization_id, user_id)` pairs ORG_A with ORG_B's user
  id is refused with SQLSTATE 23503 on
  `membership_organization_id_user_id_fkey`. This proves the composite key refuses
  a reference that a single-column `user_id -> user_identity(id)` key would allow.

Production artifacts the proof belongs to (the test asserts the database contract
they rely on; it sets the GUCs directly and loads no Spring context or controller):

- Schema: `db/migrations/V1__tenant_schema.sql` defines `user_identity` with
  `PRIMARY KEY (organization_id, id)` and `membership` with
  `FOREIGN KEY (organization_id, user_id) REFERENCES user_identity (organization_id, id)`.
  The same composite-key pattern is present across the schema, including
  `db/migrations/V10__patient_identity_proofing.sql`, the V19–V26 exchange
  migrations, `db/migrations/V45__finance.sql`, `db/migrations/V46__specialty_attestation.sql`,
  `db/migrations/V84__report_release_store.sql`, `db/migrations/V85__therapy_store.sql`,
  `db/migrations/V86__recurrence_templates.sql` and
  `db/migrations/V93__membership_facility_scope.sql` (for example its
  `(organization_id, membership_id)` and `(organization_id, facility_id)` keys).
  All migrated tenant tables carry enabled and forced row-level security.
- Tenant scope runtime: `com.aurevia.smartclinic.tenant.TenantContext` (the
  request-scoped organization binding) and
  `com.aurevia.smartclinic.tenant.TenantGuardTransactionManager` (transaction-local
  `app.current_org` / `app.actor` binding), which the request path binds before
  reaching the web controllers under `apps/api/src/main/java/com/aurevia/smartclinic/web`.
  The test sets those same GUCs directly over JDBC and does not call a controller.
- Contract: none — a schema key rule has no route, OpenAPI entry or generated
  client. `contracts/` declares no artifact for this clause.

## Gap

None
