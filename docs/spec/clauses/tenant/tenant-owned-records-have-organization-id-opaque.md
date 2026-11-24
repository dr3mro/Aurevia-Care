# ARC-01 clause 1 — Tenant-owned records have organization ID and opaque ID

## Clause

Tenant-owned records have organization ID and opaque ID.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.tenant.clauses.TenantOwnedRecordsHaveOrganizationIdOpaqueTest`
(`apps/api/src/test/java/com/aurevia/tenant/clauses/TenantOwnedRecordsHaveOrganizationIdOpaqueTest.java`).
It is a real-PostgreSQL acceptance test tagged `integration`, using the
`synthetic-auth` profile and two synthetic organizations (`ORG_A`, `ORG_B`) with
the fixtures and database setup of the neighbouring `TenantOrganizationTest` and
`IsolationAcrossSurfacesTest`. A dedicated database
`orevia_arc01_clause1_it` is migrated from `db/migrations` and dropped after
the suite, so the durable row is read back under forced row-level security. All
three methods pass; none is disabled.

Test methods (one positive rule proof and two negative refusals):

- `tenantOwnedTablesCarryOrganizationIdAndOpaqueUuidId` — reads
  `information_schema.columns` and asserts that the foundational tenant-owned
  tables `user_identity`, `facility`, `membership` and `audit_event` each have
  `organization_id uuid NOT NULL` and `id uuid NOT NULL DEFAULT gen_random_uuid()`.
  It then reaches the seeded facility through the normal HTTP entry point
  `GET /api/v1/facilities/{code}` and asserts the response carries the owning
  organization, and reads the same durable row back as the non-owner
  `orevia_app` role inside a `TransactionTemplate` with a bound
  `TenantContext` (transaction-local `app.current_org`). The readback asserts the
  organization id and that the stored `id` parses as a UUID, proving the
  database-generated opaque identifier survives forced RLS.
- `crossTenantForeignKeyIsRefused` — a `membership` for `ORG_B` that references
  an `ORG_A` user id is refused by the composite foreign key with SQLState
  `23503`. The active tenant is set to `ORG_B`, so the row-level-security
  `WITH CHECK` cannot be the cause: the refusal proves the foreign key carries
  the organization discriminator alongside the opaque id.
- `missingOrganizationIdIsRefused` — a facility insert that omits
  `organization_id` is refused with SQLState `23502`. A superuser connection is
  used only for this constraint probe so forced RLS cannot mask the NOT NULL
  violation; no production role is widened.

Wrong-role, stale-version and duplicate-cases are deliberately absent: this is
a schema-level clause with no route, precondition version or idempotency
semantics of its own, so those negative cases belong to their own clauses.

Production artifacts the test exercises (all pre-existing and unchanged):

- Schema: `db/migrations/V1__tenant_schema.sql` — `organization_id uuid NOT NULL`
  and `id uuid NOT NULL DEFAULT gen_random_uuid()` on the foundational
  tenant-owned tables (which also carry `PRIMARY KEY (organization_id, id)`,
  a design detail this scoped test does not assert directly), and the composite
  foreign key `(organization_id, user_id)` referencing
  `user_identity (organization_id, id)`.
- Isolation runtime: `db/migrations/V2__rls_roles_policies.sql` — `public.app_org()`
  and ENABLE/FORCE ROW LEVEL SECURITY with the `tenant_isolation` policy on those
  tables.
- Tenant scope: `com.aurevia.tenant.TenantContext` and
  `com.aurevia.tenant.TenantGuardTransactionManager`, which bind the
  organization discriminator to the transaction; and the HTTP entry point
  `com.aurevia.web.FacilityController` (`GET /api/v1/facilities/{code}`).

The four asserted tables are the foundational tenant-owned records named by the
V1 tenant schema and are representative of the tenant-owned record model. This
evidence is deliberately scoped to them; it does not claim that every later
feature module uses a `uuid id`, and the repository-wide uniformity question is
recorded in the worker notes for a separate task.

## Gap

None for the foundational tenant-owned records in scope: every asserted column
constraint, the durable opaque UUID and the two refusal paths are proven by the
three passing tests. The out-of-scope catalog-wide uniformity check is not
claimed here.
