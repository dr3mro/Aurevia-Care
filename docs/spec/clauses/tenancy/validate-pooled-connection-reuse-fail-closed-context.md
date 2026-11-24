# ARC-02 clause 7 — Validate pooled connection reuse and fail closed if context is missing

## Clause

Validate pooled connection reuse and fail closed if context is missing.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.tenancy.clauses.ValidatePooledConnectionReuseFailClosedContextTest`
(`apps/api/src/test/java/com/aurevia/tenancy/clauses/ValidatePooledConnectionReuseFailClosedContextTest.java`),
a real-PostgreSQL integration test under the `synthetic-auth` profile. It drives the
HTTP entry point `GET /api/v1/facilities/{code}` and inspects both a dedicated
one-connection Hikari pool and the application's own single-connection pool.
Two synthetic organizations reuse the facility code `MAIN`, while an `ONLY-A`
code exists only in organization A so a foreign read has an observable empty result.

- `reusedPooledConnectionDropsTenantContextAcrossTransactions` (rule met) — compares
  `pg_backend_pid()` across borrows of the same physical connection, binds organization A
  with `set_config(..., true)`, reads only A's facility, commits and asserts that all four
  custom settings are cleared and no tenant row is visible. A session-level binding is
  planted as a positive control, reset, and organization B is then bound on the reused
  connection and reads only B's facility.
- `applicationPoolReusesACleanConnectionAfterGuardedTransactions` (production path) — sends
  `FacilityService.get` through the guarded transaction manager for two tenants and asserts
  that the same application-pool backend connection is returned with no context each time.
- `missingTenantContextFailsClosed` (negative) — a cleared thread context makes the direct
  service call throw `IllegalStateException`; HTTP with no subject returns 401, with no active
  organization returns 422; an unbound runtime-role connection sees no rows and an insert is
  rejected by forced RLS with SQLState 42501.
- `foreignTenantContextSeesNoRows` (negative) — organization B receives 404 for A's `ONLY-A`
  facility over HTTP, and a transaction-local organization B binding sees zero rows while
  organization A sees one.

Production classes and files exercised by these request paths:

- `com.aurevia.tenant.TenantGuardTransactionManager` — `doBegin` fails closed
  with `IllegalStateException` when `TenantContext.current()` is null and binds the four
  context values with `set_config(..., true)` so they are transaction-local.
- `com.aurevia.tenant.TenantContext` — request-scoped context with `set`,
  `current` and `clear`.
- `com.aurevia.web.TenantContextInterceptor` and `ClaimsTenantResolver` —
  derive the context from the authenticated subject and the explicit organization selection;
  401 for a missing subject, 422 for a missing or invalid selection.
- `com.aurevia.tenancy.FacilityController`, `FacilityService` and
  `FacilityRepository` — the read entry point and its membership re-check inside the
  guarded transaction.
- `db/migrations/V1__tenant_schema.sql` — tenant-owned keys and the `facility` table;
  `db/migrations/V2__rls_roles_policies.sql` — `ENABLE` plus `FORCE` row-level security keyed
  on `public.app_org()` and a non-bypass runtime role; `db/migrations/V4__staff_lifecycle.sql`
  — the `has_active_membership` check that enforces the client's organization selection.
- `contracts/openapi.yaml` — the `GET /api/v1/facilities/{code}` entry, including the
  `ActiveOrganization` parameter and its declared 200, 403 and 404 responses. The 401
  (missing subject) and 422 (missing or invalid organization) responses asserted above
  are emitted by the global `TenantContextInterceptor` before the operation is entered.

Relation to existing suites: `ConcurrentTenantIsolationTest` already proves general pooled
reuse and RLS fail-closed under concurrency. This suite adds the deterministic single-connection
same-backend proof, the application-pool reuse proof after real guarded transactions, and the
explicit ORG_A-only foreign-read case under this clause.

## Gap

None.
