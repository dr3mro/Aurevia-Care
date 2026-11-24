- [-] AUDIT-FIX-DB-AUDIT · Audit append-only test is a false positive and actor derivation lacks tenant predicate
  - agent: orca
  - requirements: OPS-02, ARC-02
  - scope: db/check.sh, db/migrations/V3__audit_chain.sql, scripts/tests/test_check_migrations.py, tasks/01270300-AUDIT-FIX-DB-AUDIT.md
  - details:
    db/check.sh probes audit UPDATE only as the app role, which already lacks
    the privilege, so the trigger is never exercised; include audit_event in
    the owner-role immutability loop. Add the tenant predicate to the actor
    lookup in V3. Use a new Flyway version for the SQL change rather than
    editing a released migration, and cover the gate script change with tests.

  - agent: orca