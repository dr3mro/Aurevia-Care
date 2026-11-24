# BUILD-25 · Persist the delivery outbox, dead letter and replay store with JDBC

PR #294, tested branch tip ce5536e63e7ec6bfdcff5862ae9c1044c81c8202.

Task: BUILD-25

Swarm worker 6. Local checks: passed. Agent result: ok.

# BUILD-25 — Persist the delivery outbox, dead letter and replay store with JDBC

**Status: COMPLETE — implementation verified, 100% coverage proven on every file in scope.**
This is a re-dispatched worker. The implementation was already merged into `main` under the
earlier equivalent task `AUD-20261120-06` (commit `93b358e` "Integrate swarm PRs (reconciled)",
followed by `cad10de` "Remove SpotBugs EI_EXPOSE_REP2 suppressions"), and its original notes are
`.agent/pr-notes/AUD-20261120-06-20261120-210218.md`. Following the
`.agent/pr-notes/READY-37-20261121-073607.md` precedent, this pass re-read the merged code,
re-ran the targeted unit and real-PostgreSQL tests, proved 100% JaCoCo coverage for every touched
class, and ran every applicable gate stage. **No production code, migration, dependency or
threshold was changed in this pass; its deliverable is the completed evidence and this record.**

- Worker: `swarm-6`; branch `swarm/BUILD-25-418`; base HEAD `206fc2f`.
  Evidence sources: **tool** (every command and its final line is quoted below), plus the earlier
  run's note (tool + simulated reviewer evidence). Skills loaded: `orevia-delivery`,
  `orevia-senior-backend`, `orevia-senior-database`, `orevia-senior-java-build`,
  `orevia-test-engineer`. **No simulated user, clinical reviewer or human participant was
  exercised in this pass; no clinical or real-patient acceptance is claimed. All fixtures are
  synthetic.**

## Summary

Verified the already-merged replacement of the process-local `@Repository`
`InMemoryDeliveryStore` with `events/JdbcDeliveryStore`, a `@Repository` over the existing V55
tables. Confirmed the delivered state:

1. `JdbcDeliveryStore` implements every method of the unchanged `DeliveryStore` port with fully
   parameterised SQL through the tenant-guarded `TransactionTemplate`; the outbox row, its
   audit fact and its idempotency key commit in one transaction in `publishAtomically`.
2. Production wiring uses the JDBC store — the only `@Repository` implementing `DeliveryStore`
   in `src/main` is `JdbcDeliveryStore`; `InMemoryDeliveryStore` has no Spring annotation and
   lives only under `src/test`. Proved by
   `JdbcDeliveryStoreIntegrationTest.productionBeanIsTheJdbcStore`.
3. Real-PostgreSQL acceptance covers the four required scenarios: a separate connection reads
   the committed outbox row after the HTTP command completes (process-loss-after-commit),
   `publishAtomically` reuses the original immutable event id and writes exactly one row for a
   duplicate, a replay keeps the original event id, and tenant B cannot read tenant A's rows
   with identical local identifiers (forced RLS).

No controller, domain-service signature, contract, migration or `pom.xml` was changed by this
story. No new migration version was used (V55 already owns the tables; this story reserves none).

**Entry-point-to-test trace:**
`POST /api/v1/delivery/events` → `DeliveryController.publish` → `DurableEventService.publish`
→ `JdbcDeliveryStore.byIdempotencyKey` / `JdbcDeliveryStore.publishAtomically` →
`delivery_outbox` + `delivery_audit` (V55) →
`JdbcDeliveryStoreIntegrationTest.storesAndReadsBackAnEventWithItsAuditFact` (durability across a
separate connection) and `reStoresTheSameEventIdWithoutCreatingASecondEffect` (duplicate recorded
once). `/sent`, `/failed`, `/retry`, `/dead-letter`, `/dead-letters/review`, `/consumer-events`,
`/replays` and `/external-links` are exercised end-to-end by the same integration class;
replay identity is asserted in `receiptsReplayAndLinksAreDurable`, tenant isolation in
`tenantBCannotReadTenantAEventsOrDeadLetters`.

## Affected requirements

| ID | How the delivered change serves it |
|---|---|
| ARC-05 | Durable outbox; immutable event ids and per-aggregate version; sender/type-namespaced deduplication; bounded retry with next-attempt instant; restricted dead-letter and reviewed replay; outbox + audit + idempotency key committed atomically in `publishAtomically`. |
| ARC-02 | Every statement runs in the tenant-guarded `TransactionTemplate` that binds `app.current_org`, `app.actor`, `app.purpose` and `app.correlation_id` transaction-locally; forced RLS on all six V55 tables confines reads and writes; non-owner `orevia_app` has `NOBYPASSRLS`; two tenants with identical local ids stay isolated. |

Supporting IDs recorded by the original implementation: ARC-01, ARC-03, OPS-02.

## Risk class

Cross-boundary (`docs/WORKFLOW.md`): replacement of a persistence adapter behind an existing
domain service. No contract, migration or HTTP surface change and no clinical decision made by
the adapter; not safety-critical in the clinical sense. No real-patient approval is implied.

## Review owner

Senior Java and Spring Boot (adapter and bean wiring), Senior PostgreSQL/Flyway (V55 RLS, grants,
triggers), Senior Java Build and Test Tooling (JaCoCo, SpotBugs, Javadoc), Test Engineer (coverage
mapping), QA (two-tenant isolation evidence). The original implementation recorded a bounded
simulated `orevia-senior-database` read-only review with three findings (dispositions in
Findings below). All reviewer evidence is simulated/tool-based; no independent human reviewer was
available.

## Data changes

No new migration and no schema edit. The adapter writes the existing V55 tables
`delivery_outbox`, `delivery_receipt`, `delivery_dead_letter`, `delivery_replay`, `delivery_link`
and `delivery_audit`; it reads `exchange_order` (V19) and `patient_record` (V7) only for
identifier confirmation. `db/check.sh` applies and verifies the full migration set. Rollback is a
redeploy of the previous artifact; no backfill or forward-fix applies. This pass created no file
outside `.agent/pr-notes/`.

## Authorization boundaries and audit events

Tenant isolation is unchanged: the interceptor and
`TenantGuardTransactionManager.doBegin` bind the active tenant into `app.current_org()` before any
SQL, so reads add no manual organization predicate and forced RLS confines them. Inserts pass
`organization_id` explicitly as a uuid, matching `WITH CHECK`; the V55 trigger derives
`actor_subject` from `app.actor` and rejects facts without an authenticated actor. No audit
action, role or permission changed; the adapter stores only opaque ids, server instants, wire
keys and a payload digest — no clinical payload.

## Migration and rollout considerations

V55 already exists with `Change: expand`, so this is a pure code replacement with no deploy-time
data step. The production bean change takes effect on redeploy: the JDBC adapter reads the same
authoritative membership fold, so no dual-write is required. `InMemoryDeliveryStore` remains a
test fixture only. The integration test creates and drops its own
`orevia_delivery_store_it` database.

## Test evidence

All commands run from the repository root on `swarm/BUILD-25-418`, Java 21.0.13.1 (CI baseline),
Maven 3.10.0, PostgreSQL 18.6 on 127.0.0.1:5432, `OREVIA_PG_ADMIN=postgres`. Evidence source:
executed tool commands; no human or patient feedback.

| Command | Exit | Result / final line |
|---|---|---|
| `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=JdbcDeliveryStoreBranchTest,JdbcDeliveryStoreIntegrationTest test` | 0 | Flyway `Successfully applied 32 migrations ... now at version v61`; Tomcat started; HTTP 201/200/404 logged as expected |
| `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=JdbcDeliveryStoreBranchTest,DurableEventServiceTest,DeliveryDomainTest,JdbcDeliveryStoreIntegrationTest test` | 0 | all four classes green (counts below) |
| — `JdbcDeliveryStoreBranchTest` | — | `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` |
| — `DurableEventServiceTest` | — | `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0` |
| — `DeliveryDomainTest` | — | `Tests run: 12, Failures: 0, Errors: 0, Skipped: 0` |
| — `JdbcDeliveryStoreI
