# Database

Flyway migrations are applied by `orevia_migrate`; the application uses `orevia_app` (non-owner, no `BYPASSRLS`).

- `V1__tenant_schema.sql` — tenant-owned tables with `(organization_id, id)` keys.
- `V2__rls_roles_policies.sql` — runtime roles, `app_org()` and forced RLS on every tenant table.
- `V3__audit_chain.sql` — append-only audit with a per-tenant `sha256` hash chain and verifier.
- `V4__staff_lifecycle.sql` — append-only staff membership and role grant history.
- `V5__tenant_configuration.sql` — versioned, approved tenant configuration and its fold.
- `V6__catalog_fold.sql` — approved catalog family fold.
- `V7__patient_registration.sql` — append-only patient records and issuer-scoped MRN.

Run `bash ../check.sh` from `db/` against a local PostgreSQL to apply the migrations and assert policy, isolation and audit behavior on synthetic data. That is the runtime and policy gate.

## Change-safety gate (OPS-06)

`python3 -I scripts/check-migrations.py --require-baseline` runs in `scripts/check.sh`, `scripts/quick-check.sh` and the Delivery policy job. The baseline flag means an empty or missing migration directory fails rather than silently disabling the gate. It enforces unique version numbers and expand/contract discipline before any database is needed:

- A filename must match `V<n>__name.sql` and versions must be unique. Gaps are allowed: each parallel worker owns a reserved version (V9…V57) and merges independently, and Flyway applies by version order.
- A statement-start destructive DDL must declare `Change: contract`. The detected forms are `DROP` of TABLE, VIEW, MATERIALIZED VIEW, INDEX, TYPE, SEQUENCE, SCHEMA, FUNCTION, PROCEDURE, POLICY, TRIGGER, DATABASE, ROLE, COLUMN or CONSTRAINT; `DELETE FROM`; `TRUNCATE [TABLE]`; and `ALTER TABLE … DROP COLUMN|CONSTRAINT`. Matching is done after comments, strings, quoted identifiers and dollar-quoted bodies are blanked and statements are split on semicolons.
- `Change` is restricted to `expand` or `contract` when present.
- A contract must declare `Forward-fix:` as a `V<n>__name.sql` reference (the follow-up that restores the removed capability; it may not exist yet) and `Requires-expand: V<n>` where the referenced version exists, is earlier, and declares `Change: expand`.
- `Forward-fix:` is rejected without `Change: contract`, and a malformed `Forward-fix:` is rejected on its own.

Declare these in a contiguous leading comment block, for example:

```sql
-- Change: contract
-- Forward-fix: V9__restore_legacy_view.sql
-- Requires-expand: V6

DROP VIEW legacy_view;
```

Detection is intentionally narrow: tokens in comments, string literals, dollar-quoted bodies, trigger definitions and `REVOKE` statements are ignored. The header is a developer declaration — the checker proves its structure and references, not that the SQL matches the label. Every contract migration still needs human review; `db/check.sh` remains the authoritative apply-and-assert gate.
