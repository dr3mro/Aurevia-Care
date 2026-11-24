# READY-38 · Persist messaging governance in PostgreSQL (V62) instead of memory

PR #287, tested branch tip ac6b69ccc7930d2581c4c3ce91f078197c855a83.

Task: READY-38

Swarm worker 9. Local checks: passed. Agent result: ok.

# READY-38 — Persist messaging governance in PostgreSQL (V62) instead of memory

**Status: COMPLETE — implementation verified and 100% coverage proven for the new
production class.** Worker `swarm-9`, branch `swarm/READY-38-416`, base HEAD
`c03a307`. All fixtures are synthetic; no patient data, credential or real
domain name is used. Evidence sources: **tool** (every command below was run),
**simulated reviewer** (two read-only subagent reviews, findings incorporated),
**simulated user** (none), **human participant** (none — no clinical acceptance
is claimed). Skills loaded: `aurevia-delivery`, `aurevia-senior-backend`,
`aurevia-senior-database`, `aurevia-senior-java-build`,
`aurevia-test-engineer`.

## Summary

Replaced the process-local `@Repository` `InMemoryMessagingGovernanceStore` with
a PostgreSQL adapter behind the unchanged `MessagingGovernanceStore` interface.
`V62__messaging_governance.sql` adds eleven tenant-owned tables (audit event,
retention policy, resource, retention duty, legal hold, record-access policy,
connection, provenance link, export, copy request, revocation effect), each with
`organization_id`, `created_at`, forced RLS, a `public.app_org()` policy and
explicit grants. Append-only ledgers also carry reject-mutation triggers.
`JdbcMessagingGovernanceStore` implements all 26 interface methods through the
tenant-guarded `TransactionTemplate` with parameterised SQL, reusing the
authoritative V4 membership/role folds. The in-memory adapter lost `@Repository`
and moved to `src/test` with the same package, so both existing unit test files
compile unchanged and Spring sees exactly one store bean.

## Affected requirements

| ID | Acceptance mapping |
|---|---|
| ARC-01 | Eleven tenant-owned tables with `organization_id` in every primary key and business key; opaque `id`, database `seq` and server instants. The integration test stores identical local ids, subjects, resources and connections in two tenants and reads them back separately. |
| ARC-02 | `ENABLE`/`FORCE ROW LEVEL SECURITY` plus `USING`/`WITH CHECK (organization_id = public.app_org())` on all eleven tables; every store call runs inside the tenant-guarded transaction; three cross-tenant write attempts are rejected by `WITH CHECK`. `db/check.sh`'s catalog scan passes. |
| OPS-02 | Append-only audit, provenance, copy-request, revocation-effect and export ledgers have no UPDATE/DELETE/TRUNCATE grant and reject-mutation triggers; only non-sensitive governance facts are persisted. `appendOnlyFactsAreImmutableForTheMigrationOwnerToo` proves an UPDATE is rejected even for the migration owner. |

## Risk class

Cross-boundary (`docs/WORKFLOW.md`): new tenant tables, a new SQL boundary and
replacement of a `@Repository` collaborator of an existing `@Service` behind its
existing HTTP controller. Not safety-critical in the clinical sense — no signed
record, cross-organization sharing, prescribing or critical-result workflow is
changed. No real-patient approval is implied.

## Review owners and evidence

Senior PostgreSQL/Flyway (schema, RLS, grants, triggers), Senior Java and Spring
Boot (adapter and bean wiring), Senior Java Build and Test Tooling (JaCoCo,
SpotBugs, Javadoc), Test Engineer (coverage mapping), QA (two-tenant isolation).
Two read-only specialist subagents returned bounded findings: the database
reviewer required immutable exports, `DEFAULT now()` on every created column,
fold reuse and an accurate seeding comment; the backend reviewer confirmed the
legal-hold upsert, the fail-closed policy default, the `@Repository` two-bean
risk and the need to call all 26 methods from the integration test. All their
applicable findings were incorporated. Reviewer evidence is simulated/tool-based;
no independent human reviewer was available.

## Data changes

`V62__messaging_governance.sql` is additive (`-- Change: expand`) and uses only
the reserved version V62. Eleven empty tables; no backfill, no edit to any
existing migration. Lock/volume impact is negligible. Row-level isolation and
grant details are in the migration header. Migration order is last under both
`sort -V` and Flyway numeric order, so `organization`, `public.app_org()` and
`public.reject_immutable_mutation()` all exist first. The tagged test creates and
drops its own `aurevia_governance_it` database.

## Authorization boundaries and audit events

Every adapter method runs inside the shared tenant-guarded `TransactionTemplate`,
which binds `app.current_org` and `app.actor` transaction-locally before SQL.
Reads name the active organization explicitly and rely on forced RLS; writes
carry the method's `organizationId` so `WITH CHECK` fails closed on mismatch
(proved for retention policies, connections and audit facts). No authorization
decision changed: `MessagingGovernanceService` still re-checks membership and
roles on every request through the authoritative fold. No audit event type was
added or changed.

## Migration and rollout considerations

Runtime-written current-state tables (retention policy, duty, legal hold,
record-access policy, connection) use `ON CONFLICT DO UPDATE` and therefore hold
the UPDATE grant. Exports are append-only (`DO NOTHING`, INSERT-only grant) so a
duplicate id cannot rewrite an OPS-07 record. The resource reference is
SELECT-only: V62 seeds no rows, so an authorized setup path must populate it
before hold and retention commands can act (Finding 3). Forward/rollback: a
later contract removing a table must point at an earlier `Change: expand` per
`docs/decisions/0002`.

## Test evidence

All commands run from the repo root, Java 21.0.13.1, Maven 3.10.0, PostgreSQL
17.12 on 127.0.0.1:5432, `AUREVIA_PG_ADMIN=postgres`. Final lines / results:

| Command | Exit | Result / final line |
|---|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -DskipTests test-compile` | 0 | clean compile |
| `AUREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=JdbcMessagingGovernanceStoreIntegrationTest test` | 0 | Surefire: `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`; Flyway: `Successfully applied 33 migrations to schema "public", now at version v62` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=MessagingGovernanceServiceTest,MessagingAuditDomainTest test` | 0 | `Tests run: 26` and `Tests run: 14`, 0 failures/errors/skipped |
| `mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report` | 0 | report generated at `apps/api/target/site/jacoco/jacoco.xml` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (all unit tests, integration excluded) | 0 | green |
| `python3 -I scripts/check-migrations.py --require-baseline` | 0 | green |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py` | 0 | green; wiring reports two now-stale baseline notes (Finding 2) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 0 | `Ran 2 tests ... OK` |
| `bash scripts/frontend-check.sh` | 0 | `frontend standards: policy and tool commands passed` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` | 0 | no findings |
| `bash db/check.sh` (the AUD-20261120-04 forced-RLS gate) | 0 | `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` |
| `bash scripts/worker-check.sh` | 1 | fails at its first stage: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — known CI-profile-under-skip-worktree false positive (Finding 1); every later stage was run individually and is green above |
| `python3 -I -m unittest discover -s scripts/tests -q` | 1 | `Ran 436 tests ... FAILED (failures=3)`; all three are `test_check_pack` assertions on the same stale `opencode.json` artifact (Finding 1) |

**Entry-point-to-test trace:**
`MessagingGovernanceController` (`/api/v1/messaging-governance/a
