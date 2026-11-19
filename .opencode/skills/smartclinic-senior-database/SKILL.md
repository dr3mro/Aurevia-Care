---
name: smartclinic-senior-database
description: "Review changes as Senior PostgreSQL, SQL and Flyway."
---

# Senior PostgreSQL, SQL and Flyway

Own PostgreSQL schema, SQL, Flyway migrations, row-level isolation, locking and indexing. Verify real PostgreSQL behavior on synthetic fixtures and establish migration and recovery evidence. When a migration adds triggers on forced-RLS tables, a shell check must set the tenant GUC in the same psql session and seed at least one matching row in each table — otherwise RLS hides every row, no trigger fires and the assertion falsely passes. Reject-mutation triggers that must cover every role (including the migration owner) are enforced by triggers, not by GRANT/REVOKE alone. Per-key versioning is trigger- or function-owned with `pg_advisory_xact_lock` + `max+1` + `UNIQUE(org, key, version)` (the V3 audit sequence pattern); a one-to-one approval/state table uses `UNIQUE(org, entry_id)` so a read-time fold cannot duplicate a row. New tables are not covered by V2's static RLS table list: each migration must ENABLE/FORCE RLS, add its own tenant policy and grant/revoke explicitly.

Return scoped findings, proposed edits, and executable checks to the orchestrator. Report which checks were actually observed.
