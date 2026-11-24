# READY-37 · Persist flow policies in PostgreSQL (V61) instead of memory

PR #275, tested branch tip 1cb93eccf4cc9f54f88fa9238e2a1ecf9d860882.

Task: READY-37

Swarm worker 1. Local checks: passed. Agent result: ok.

# READY-37 — Persist flow policies in PostgreSQL (V61) instead of memory

**Status: COMPLETE — implementation verified and 100% coverage proven.** This is
attempt 2. Attempt 1 (worker `swarm-3`, branch `swarm/READY-37-375`) already
implemented and merged the change as PR #266 (commit `a3cbd84`) but left its
notes flagged `IN PROGRESS — coverage evidence pending` with the full JaCoCo
gate unrun. This pass continues from that work: it re-reads the merged code,
re-runs the targeted test and coverage for the touched class, runs every
worker gate stage, and records the missing evidence. **No patient data,
credential or real domain name used — all fixtures are synthetic.**

- Worker: `swarm-1`; branch `swarm/READY-37-411`; base HEAD `de837cd` (contains
  PR #266). Evidence sources: **tool** (all commands and their final lines are
  quoted below), plus the earlier run's `.agent/pr-notes/READY-37-20261121-015744.md`
  (tool + simulated reviewer evidence). Skills loaded: `orevia-delivery`,
  `orevia-senior-backend`, `orevia-senior-database`,
  `orevia-senior-java-build`, `orevia-test-engineer`. **No simulated
  user or human participant was exercised in this pass; no clinical acceptance
  is claimed.**

## Summary

Verified the already-merged replacement of the process-local `@Repository`
`InMemoryFlowPolicyStore` with a PostgreSQL adapter. Confirmed the delivered
state: `db/migrations/V61__flow_policies.sql` creates three tenant-owned tables
(`flow_policy`, `flow_approved_notice`, `flow_access_audit`) with forced RLS, a
`public.app_org()` policy, `created_at` and explicit grants; the audit ledger is
append-only. `JdbcFlowPolicyStore` implements all five `FlowPolicyStore`
methods through the tenant-guarded `TransactionTemplate` and parameterised SQL,
reusing the authoritative `public.has_active_membership` fold. The in-memory
adapter has no `@Repository` and lives only under `src/test`. This pass added no
production code; its deliverable is the completed coverage evidence and gate
verification recorded here.

## Affected requirements

| ID | How the delivered change serves it |
|---|---|
| ARC-01 | Tenant-owned tables with `organization_id` in every primary key and business key (`locale`/`platform`, `notice_kind`); opaque `id`, identity `seq` and server instants. |
| ARC-02 | `ENABLE`/`FORCE ROW LEVEL SECURITY` + `USING`/`WITH CHECK (organization_id = public.app_org())` on all three tables; tenant GUCs bound transaction-locally by `TenantGuardTransactionManager.doBegin`; non-owner `orevia_app` with `NOBYPASSRLS`. Membership re-read from the authoritative fold each request. |
| OPS-02 | Append-only `flow_access_audit` with no UPDATE/DELETE/TRUNCATE grant and reject-mutation triggers; only non-sensitive facts stored (`subject`, `purpose`, `action`, `correlationId`, `outcome`, `occurredAt`). |

## Risk class

Cross-boundary (`docs/WORKFLOW.md`): new tenant tables, a new SQL boundary and
replacement of a `@Service` collaborator. Not safety-critical in the clinical
sense — no signed data, cross-organization sharing, patient-account
association, prescribing or critical-result workflow is touched. No real-patient
approval is implied.

## Review owner

Senior PostgreSQL/Flyway (schema, RLS, grants, triggers), Senior Java and Spring
Boot (adapter and bean wiring), Senior Java Build and Test Tooling (JaCoCo,
SpotBugs, Javadoc), Test Engineer (coverage mapping), QA (two-tenant isolation
evidence). Attempt 1 recorded two bounded read-only specialist subagent reviews
(database and backend), incorporated into its notes. All reviewer evidence is
simulated/tool-based; no independent human reviewer was available.

## Data changes

`V61__flow_policies.sql` is additive (`-- Change: expand`) and creates three
tables: `flow_policy` `(organization_id, id)` key + `UNIQUE (organization_id,
locale, platform)`; `flow_approved_notice` `(organization_id, id)` key +
`UNIQUE (organization_id, notice_kind)`; `flow_access_audit` `(organization_id,
id)` key, database-owned `seq`/`id`, `UNIQUE (organization_id, seq)` with
reject-UPDATE/DELETE/TRUNCATE triggers. Every table has `organization_id NOT
NULL REFERENCES public.organization`, `created_at timestamptz NOT NULL DEFAULT
now()`, forced RLS, a `tenant_isolation` policy with `USING` and `WITH CHECK`,
and explicit grants/revokes to `orevia_app`. No existing migration was
edited; no backfill or data migration; three empty tables, negligible lock/volume
impact. This pass created no new migration (V61 remains the single reserved
version used).

## Authorization boundaries and audit events

Every adapter method runs inside the shared tenant-guarded `TransactionTemplate`,
so `app.current_org`, `app.actor`, `app.purpose` and `app.correlation_id` are
bound transaction-locally before SQL. Reads rely on forced RLS; inserts write the
method's `organizationId` so `WITH CHECK` fails closed on mismatch —
`auditFactsAreOrderedTenantScopedAndFailClosedOnCrossTenantWrite` proves a
rejected cross-tenant write and `auditFactsAreImmutableForTheMigrationOwnerToo`
proves the append-only trigger. No authorization decision changed:
`PatientFlowPresentationService` still re-checks membership on every request
through the authoritative fold. No audit event type was added or changed.

## Migration and rollout considerations

V61 is applied last under both `sort -V` (`db/check.sh`) and Flyway numeric
order, so `organization`, `app_org()` and `reject_immutable_mutation()` exist.
The policy/notice tables are seed-free in the migration: forced RLS `WITH CHECK`
would reject a seed without `app.current_org` set. The production bean change is
immediate on deploy; the adapter reads pre-existing folds, so no dual-write is
required. Forward/rollback: a later contract removing a table must point at an
earlier `Change: expand` per `docs/decisions/0002`. The tagged test creates and
drops its own `orevia_flow_policy_it` database.

## Test evidence

All commands run from the repo root on `swarm/READY-37-411`, Java 21.0.13.1
(CI baseline), Maven 3.10.0, PostgreSQL on 127.0.0.1:5432, `OREVIA_PG_ADMIN=postgres`.
Exit codes and final lines:

| Command | Exit | Result / final line |
|---|---|---|
| `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=JdbcFlowPolicyStoreIntegrationTest test` | 0 | `Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in ...JdbcFlowPolicyStoreIntegrationTest`; Flyway log `Successfully applied 32 migrations ... now at version v61` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=PatientFlowPresentationTest test` | 0 | `Tests run: 13, Failures: 0, Errors: 0, Skipped: 0` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report` | 0 | report generated (coverage below) |
| `bash scripts/frontend-check.sh` | 0 | `frontend standards: policy and tool commands passed` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` | 0 | no findings |
| `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` | 0 | all integration suites green |
| `python3 -I scripts/worker_gate.py 0` | 0 | `integration tests passed` |
| `bash db/check.sh` (the AUD-20261120-04 forced-RLS gate) | 0 | `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 0 | `Ran 2 tests ... OK` |
| `bash scripts/worker-check.sh` | 1 | fails early at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — known CI-profile-under-skip-worktree false positive (Findings 1); every later stage was run individually and is green above |

**Entry-point-to-test trace:**
`PatientFlowPresentationService.presentation` / `.notificationText` →
`FlowPolicyStore` (production bean `JdbcFlowPolicyStore`, asser
