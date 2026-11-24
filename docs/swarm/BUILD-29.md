# BUILD-29 · Persist patient accounts, enrollment and recovery with JDBC

PR #300, tested branch tip 8522579e9b5fb59a1e5a7db2ffd12782345e5bec.

Task: BUILD-29

Swarm worker 8. Local checks: passed. Agent result: ok.

# BUILD-29 — Persist patient accounts, enrollment and recovery with JDBC

**Status: requested scope complete; 100% coverage proven on every changed file; all targeted tests green.** One pre-existing boundary gap (no HTTP controller/OpenAPI entry for this domain) is recorded under Findings and is outside this story's scope. No patient data, credential, token value or real domain name was used — every fixture is synthetic.

- Worker branch: `swarm/BUILD-29-418`. Evidence sources: **tool** (all commands and their final lines below), **simulated reviewer** (three bounded specialist planning reports: senior-database, senior-backend, test-engineer). No simulated user, clinician or human participant was exercised; no clinical acceptance is claimed.

## Summary

Replaced the process-local patient-access state with a PostgreSQL adapter. `V83__patient_access_store.sql` adds six tenant-owned tables with forced RLS, append-only ledgers and an atomic invitation-consume function. `JdbcPatientAccessStore` implements the full `PatientAccessStore` interface through the tenant-guarded `TransactionTemplate`, stores only SHA-256 hashes of invitation codes and access tokens, and uses compare-and-set updates for single-use consumption. `PatientAccessService` is now a Spring `@Service` using the injected JDBC store. Atomic consume was added to the store port so two simultaneous redemptions cannot both create an account; failed-attempt and token-use updates were likewise moved behind raw-code store operations so no raw credential is re-stored.

## Affected requirements

| ID | How the change serves it |
|---|---|
| PAT-03 | Durable account state, lockout counter, recovery requests and single-use invitations; a reused or expired invitation fails and a lockout survives a new service instance. |
| IAM-05 | Only digests of opaque invitation codes and tokens are persisted; site-verified fact digests are unchanged; no demographic field links an account. |
| ARC-02 | Six tables with `(organization_id, …)` keys, `ENABLE`/`FORCE ROW LEVEL SECURITY`, `USING`/`WITH CHECK (organization_id = public.app_org())`, non-owner `orevia_app` grants and transaction-local tenant binding. Two tenants with the same chart UUID and the same subject are isolated in the integration test. |

## Risk class

Cross-boundary with a safety-critical identity surface (`docs/WORKFLOW.md`): a new persistence boundary, a new schema and patient identity/recovery state. No signed record, cross-organisation release or prescribing workflow is changed. Clinical/human acceptance is not implied.

## Review owner

Senior PostgreSQL/Flyway (V83 schema, RLS, grants, atomic function), Senior Java and Spring Boot (adapter and bean wiring), Senior Java Build and Test Tooling / Test Engineer (JaCoCo and branch mapping). Three bounded read-only specialist plans were received and incorporated (simulated reviewer evidence; files were tool-inspected, runtime claims were re-run by me). No independent human reviewer was available.

## Data changes

`V83__patient_access_store.sql` is additive (`-- Change: expand`), reserved version V83 only, and creates:

- `patient_access_account` — current-state projection (subject `UNIQUE (org, subject)`, `text[]` chart links, fact digest, `active|locked`, failure counter, nullable contact digest). V10's `patient_account` is immutable and cannot hold a mutable lockout counter, so a separate state row is required.
- `patient_access_invitation` — `code_hash` only (no raw code), chart id, fact digest, `pending|redeemed`, failure counter, expiry and issuer; no account FK, so a pre-enrollment invitation is possible.
- `patient_access_token` — `token_hash` only, purpose, subject, expiry, `used`, DB-owned `created_at`.
- `patient_access_support_case` — bounded kind/state checks, nullable assignee.
- `patient_access_event` and `patient_access_notification` — append-only ledgers with reject-UPDATE/DELETE/TRUNCATE triggers for the owner too; the notification row has no clinical/free-text field.
- `consume_patient_access_invitation(text)` — invoker-rights compare-and-set that flips `pending → redeemed` only while unexpired, using `GET DIAGNOSTICS ROW_COUNT`; forced RLS confines it to the active tenant.

No existing migration was edited, no backfill; six empty tables and one function have negligible lock/volume impact. Forward-fix/rollback follows `docs/decisions/0002` (a later contract migration must reference an earlier expand).

## Authorization boundaries and audit events

Every adapter method runs inside the shared tenant-guarded `TransactionTemplate`; `app.current_org`, `app.actor`, `app.purpose` and `app.correlation_id` are bound transaction-locally. Reads rely on forced RLS; writes bind `organization_id` explicitly so `WITH CHECK` fails closed on mismatch; the consume function has no organization argument and cannot cross tenants under forced RLS. No new audit event type was added — the service's existing `EventType` vocabulary is used unchanged. No authorization rule changed: `EnrollmentActions` and the service's membership-independent actor snapshot are untouched. The generic-notification table has no detail column, so a notification cannot carry a chart id, diagnosis or reason.

## Migration and rollout considerations

V83 sorts last under both `sort -V` (`db/check.sh`) and Flyway numeric order, after `organization`, `app_org()` and `reject_immutable_mutation()` exist. Tables are seed-free: forced RLS `WITH CHECK` rejects a seed without `app.current_org`. The production bean change is immediate on deploy; no dual-write is needed because no prior production caller existed. The tagged integration test creates and drops its own `orevia_patient_access_it` database; `db/check.sh` passed with V83 applied.

## Test evidence

All commands run from the repo root on `swarm/BUILD-29-418`, Java 21.0.13.1, Maven 3.10.0, PostgreSQL 17.12 at 127.0.0.1:5432, `OREVIA_PG_ADMIN=postgres`.

| Command | Exit | Final line / result |
|---|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=JdbcPatientAccessStoreBranchTest,PatientAccessServiceConsumeTest,InMemoryPatientAccessStoreBranchTest,PatientAccessTest test` | 0 | 48 tests, 0 failures/errors (Surefire XML: branch 15, consume 1, in-memory 2, `PatientAccessTest` 30) |
| `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=JdbcPatientAccessStoreIntegrationTest test` | 0 | `tests="7" errors="0" failures="0"`; Flyway `Successfully applied 33 migrations … now at version v83` |
| Combined targeted run + `mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report` | 0 | 55 tests green; report generated (coverage below) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` | 0 | no findings |
| `OREVIA_PG_ADMIN=postgres bash db/check.sh` | 0 | `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-migrations.py --require-baseline` | 0 each | `check-wiring` prints only stale-baseline notes (`PatientAccessStore` durable finding is now fixed); no new finding |
| `python3 -I -m unittest discover -s scripts/tests -q` | 1 | `Ran 436 tests … FAILED (failures=3)`; all three are `test_check_pack.*` — the documented CI-profile-under-skip-worktree false positive. `git ls-files -v opencode.json` → flag `S`; I did not edit `opencode.json`. The Java-conventions subset is green: `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → `Ran 2 tests … OK`. |
| `python3 -I scripts/check-pack.py` | 1 | `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — same known false positive (MEMORY OPS-CW-003-01, prior READY-37 note). Committed file was not modified. |

I deliberately did **not** run `scripts/c
