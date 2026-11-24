# ARC-02 clause 4 — Separate migration and runtime roles

## Clause

Separate migration and runtime roles.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.tenancy.clauses.SeparateMigrationRuntimeRolesTest`
(`apps/api/src/test/java/com/aurevia/tenancy/clauses/SeparateMigrationRuntimeRolesTest.java`),
a real-PostgreSQL integration test tagged `integration` under the `synthetic-auth`
profile. It provisions both roles and a synthetic database, applies the real
Flyway migrations as `aurevia_migrate`, seeds two synthetic organizations and
asserts on the PostgreSQL catalog and real JDBC connections rather than on
synthetic-auth HTTP headers, which bind only application context.

- `migrationAndRuntimeRolesAreDistinctAndLeastPrivileged` (positive) — the two
  roles exist as distinct login roles with `rolbypassrls`, `rolcreatedb` and
  `rolcreaterole` all false; `aurevia_app` owns no table in `public`, cannot
  create schema objects and is not the owner of `public.facility` (the migration
  role is); the six V2 tenant tables all have `relrowsecurity` and
  `relforcerowsecurity`, which is what constrains even the owning role; and the
  application's autowired datasource authenticates as `aurevia_app`, not as
  the migration owner.
- `runtimeRoleCannotBypassForcedRowLevelSecurity` (negative) — with a
  transaction-local `app.current_org`, the runtime role sees only that tenant's
  facilities (`MAIN`, `ONLY-A` for organization A; `MAIN`, `ONLY-B` for
  organization B) and cannot read the peer tenant's rows; the migration owner
  with no tenant setting also reads zero facilities, so FORCE RLS is proven to
  bind the owner and not just non-owners.
- `runtimeRoleWithoutTenantContextSeesNoRows` (negative) — a fresh runtime
  connection with no tenant setting has `app_org()` null and reads zero
  facilities, so a missing context fails closed.
- `runtimeRoleCannotRunMigrationDdl` (negative) — the runtime role's `CREATE
  TABLE` attempt is refused with SQLSTATE `42501` (insufficient privilege),
  `public` CREATE is not granted to it and no probe table is left behind.

Production sources whose behavior is proven (none were changed by this test):

- `db/migrations/V2__rls_roles_policies.sql` — `public.app_org()` from
  `app.current_org`, ENABLE and FORCE row level security with the
  `tenant_isolation` policy on the tenant tables, and least-privilege GRANTs to
  `aurevia_app`. It deliberately does not create the roles or grant DDL to
  the runtime role.
- `infra/app/start.sh` — the deployment bootstrap that this test mirrors:
  provisions `aurevia_migrate` and `aurevia_app` as `LOGIN NOBYPASSRLS
  NOCREATEDB NOCREATEROLE`, creates the database with `OWNER aurevia_migrate`,
  applies migrations as `aurevia_migrate` and starts the API with
  `AUREVIA_DB_USER=aurevia_app`. The test reproduces these settings on a
  synthetic database; it does not execute the image bootstrap.
- `apps/api/src/main/resources/application.yml` — the runtime datasource
  defaults to `aurevia_app` and Spring Flyway is disabled, so application
  traffic never runs as the migration role.
- `db/check.sh` — the existing database gate asserting the same catalog
  invariants (no BYPASSRLS, no tenant table owned by the runtime role, unset
  context yields no rows).
- `apps/api/src/main/java/com/aurevia/tenant/TenantContext` and
  `TenantGuardTransactionManager` — bind the selected organization to
  `app.current_org` inside each transaction, including on the runtime role's
  connections.

No HTTP route, OpenAPI entry or regenerated client belongs to this clause: the
separation is enforced in the database role and deployment configuration, so the
proof is at the JDBC and catalog level.

## Gap

None.
