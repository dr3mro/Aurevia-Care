# Database

Flyway migrations are applied by `smartclinic_migrate`; the application uses `smartclinic_app` (non-owner, no `BYPASSRLS`).

- `V1__tenant_schema.sql` — tenant-owned tables with `(organization_id, id)` keys.
- `V2__rls_roles_policies.sql` — runtime roles, `app_org()` and forced RLS on every tenant table.
- `V3__audit_chain.sql` — append-only audit with a per-tenant `sha256` hash chain and verifier.

Run `bash ../check.sh` from `db/` against a local PostgreSQL to apply the migrations and assert policy, isolation and audit behavior on synthetic data.
