# ARC-02 clause 3 — Forced RLS, non-owner runtime role, transaction-local tenant context

## Clause

Use PostgreSQL row-level security with forced enforcement on tenant tables, a non-owner runtime role without bypass, and transaction-local tenant context.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.tenancy.clauses.UsePostgresqlRowLevelSecurityForcedEnforcementTest`
(`apps/api/src/test/java/com/aurevia/tenancy/clauses/UsePostgresqlRowLevelSecurityForcedEnforcementTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile with a private
per-class database migrated from `db/migrations`. Every catalog probe asserts that a
row was returned and every RLS assertion starts from a seeded ORG_A facility, so a
zero count cannot be vacuous.

- `forcedRlsAndNoBypassRoleConcealCrossTenantFacilityRows` — `pg_class` reports both
  row-level security and forced row-level security on all six V2 tenant tables
  (`organization`, `user_identity`, `facility`, `membership`, `role_assignment`,
  `audit_event`); `pg_policies`
  reports the `tenant_isolation` policy with both `USING` and `WITH CHECK`; `pg_roles`
  reports `aurevia_app` as neither `rolbypassrls` nor `rolsuper`, owning no tenant
  table. The seeded ORG_A row is visible under ORG_A and concealed under ORG_B both at
  SQL and through the documented HTTP entry point. An ORG_A `WITH CHECK` insert owned
  by the active tenant is visible to ORG_A and invisible to ORG_B.
- `nonOwnerRoleCannotBypassForcedRls` (negative, wrong role/tenant) — the migrate role
  with matching context sees the row; the non-owner runtime role with a peer tenant
  context sees none, and the same connection with the owning tenant context does see
  it, so the zero is caused by forced RLS and not by a missing grant.
- `missingTenantContextFailsClosed` (negative) — an empty `app.current_org` resolves to
  NULL through `public.app_org()` and exposes zero rows; the HTTP entry point answers
  401 without a subject and 422 without an active organization selection.
- `foreignTenantWriteIsRefused` (negative) — with a valid competing ORG_A context and
  a same-tenant insert as a control on the same connection, an insert whose
  `organization_id` differs from `app.current_org` is refused with SQLSTATE `42501`
  and a row-level-security message; the refused row does not persist when probed
  with the target tenant's context, while the control row does persist.
- `tenantGuardBindsTenantContextOnlyInsideEachTransaction` — the injected production
  `TransactionTemplate` (built on `TenantGuardTransactionManager`) binds
  `current_setting('app.current_org', true)` to the active organization inside a real
  transaction, refuses to open a transaction with no bound context, and does not carry
  the previous organization into the next transaction. The same `set_config(..., true)`
  idiom reverts on commit on a fresh non-bypass connection, so a pooled connection
  cannot leak tenant context.

Production path exercised by the HTTP assertions: `contracts/openapi.yaml` entry
`GET /api/v1/facilities/{code}` → `com.aurevia.web.FacilityController` →
injected `com.aurevia.tenancy.FacilityService` →
`com.aurevia.tenancy.FacilityRepository` (the JDBC store bounded by forced
RLS) → `public.facility`. Request context is derived by
`com.aurevia.web.TenantContextInterceptor` into
`com.aurevia.tenant.TenantContext` and bound transaction-locally by
`com.aurevia.tenant.TenantGuardTransactionManager`. The migrations that
create the catalog state are `db/migrations/V1__tenant_schema.sql` and
`db/migrations/V2__rls_roles_policies.sql`. The wider HTTP fail-closed and
cross-surface matrix is covered by `IsolationAcrossSurfacesTest`; this suite proves the
database enforcement clause itself.

## Gap

None.
