# 0001 — G0 tenant isolation foundation

Date: 2026-11-18 · Requirements: IAM-01, ARC-01, ARC-02, OPS-02 · Gate: G0

## Context

Three application modules and the migration directory were stubs. G0 requires two tenants with identical local IDs to remain isolated across API, search, files, workers and exports, with revocation effective on the next protected request. This decision covers the REST + PostgreSQL foundation; other surfaces are follow-on stories.

## Decision

- Tenant-owned tables carry `(organization_id, id)` composite keys; every foreign key includes `organization_id`.
- `aurevia_migrate` owns migrations; `aurevia_app` is a non-owner role without `BYPASSRLS`. Every tenant table has `ENABLE` and `FORCE ROW LEVEL SECURITY` with `USING`/`WITH CHECK organization_id = app_org()`.
- Tenant context is derived from the synthetic authenticated subject and the explicit `X-Active-Organization` header. It is bound with `set_config(..., true)` in `TenantGuardTransactionManager.doBegin` after the transaction opens; missing context fails closed before any SQL.
- Membership and role assignment are re-read inside each protected transaction, so deactivation denies the next request without token or session expiry.
- Audit is append-only with a per-tenant hash chain built from core `sha256`, committed in the same transaction as the command.

## Alternatives considered

- Inferring the tenant from the request body or the first membership — rejected: IAM-01 requires explicit organization switching and prevents cross-tenant inference.
- Setting GUCs in `DataSource.getConnection()` — rejected: autocommit is still on at that point, so transaction-local settings are discarded.
- pgcrypto `digest()` — rejected in favour of core `sha256(bytea)` to avoid an extension dependency on managed PostgreSQL.

## Consequences

Positive: a single write command proves tenant derivation, forced RLS, fail-closed context, identical local IDs across tenants and atomic audit. Negative: the synthetic subject header is gated on the `synthetic-auth` profile and is not real authentication; managed OIDC, idempotency, staff lifecycle history, search/files/workers/export isolation and concurrency stress remain separate stories.

## Verification

`apps/api/check.sh` runs 42 tests including 10 real-PostgreSQL scenarios with JaCoCo at 100%; `db/check.sh` applies all three migrations and asserts RLS, role separation, two-tenant isolation, `WITH CHECK`, append-only audit and chain validity. Evidence is tool-generated and synthetic; no human pilot feedback.
