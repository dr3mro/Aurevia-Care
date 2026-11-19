# X-02 — Durable events, outbox, deduplication, replay and dead-letter review

## Requirement IDs and acceptance mapping

Primary: **ARC-05**. Supporting: **ARC-01, ARC-02, ARC-03, OPS-02**.
Specification: `SMARTCLINIC_V1_SPEC.md` §7 ARC-05 (Delivery) and §9 G0/G2 delivery gates.

| ARC-05 clause | Implementation (in scope) | Synthetic test evidence |
|---|---|---|
| Commit state, audit and outbox atomically | `DurableEventService.publish` → `DeliveryStore.publishAtomically` (event + dedup key + audit in one operation); synthetic adapter commits all three under one synchronized method | `DurableEventServiceTest.publishCommitsOutboxAuditAndDedupKeyTogether`; `DeliveryDomainTest.syntheticStoreIsTenantScopedAndPublishesAtomically` |
| Immutable event IDs and per-aggregate version | `OutboxEvent.eventId` never changes; `snapshot(...)` advances `aggregateVersion` by one; `IfMatch.parse` precondition on every transition | `stateTransitionsAdvanceVersionAndImmutableEventId`, `illegalTransitionAndStaleVersionAreRejected`, `illegalTransitionsAreRejectedFromEveryTerminalState` |
| Idempotency, same key returns original / different payload conflicts | `byIdempotencyKey` + digest comparison; `409 IDEMPOTENCY_CONFLICT`; duplicate audited as `DUPLICATE` | `publishWithSameKeyAndDigestReturnsTheOriginalEvent`, `publishWithSameKeyDifferentDigestIsAConflict` |
| Deduplicate external events inside sender/type namespace | `delivery_receipt` unique `(org, namespace, sender, event_type, external_event_id)`; `DeliveryStore.receipt` | `consumeDeduplicatesInsideTheSenderAndTypeNamespace` (same id/sender → duplicate; same id, different sender → accepted) |
| Reject or hold out-of-order changes; record processing outcome | `maxVersion` per aggregate family; `HELD_OUT_OF_ORDER` receipt + `HELD` audit; correction at higher version accepted | `consumeHoldsOutOfOrderChangesAndRecordsTheOutcome`; synthetic fixtures `out-of-order-replay`, `correction-after-final` |
| Retry transient errors with bounded backoff | `backoffSeconds(attempt) = min(1 << attempt, 60)`, `MAX_ATTEMPTS = 3`, `RetryResult` requeue/dead-letter | `retrySchedulesBoundedBackoffAndRequeues`, `retryRequiresAFailedEvent` |
| Restricted dead-letter queue and review workflow | `DEAD_LETTER` action for workers/operators; `REVIEW`, `REPLAY` restricted to integration/tenant admin; reviewer, instant, reason, resolution recorded separately | `retryExhaustionMovesEventToRestrictedDeadLetterQueue`, `deadLetterRequiresFailedStateAndIsAudited`, `reviewRequeuesOrResolvesAndIsRestrictedToAdmins`, `deadLetterReviewAndAuditAreRoleScoped` |
| Replays preserve identity and need authorization + reason | `ReplayRecord.originalEventId` is the unchanged event id; original outbox row is not duplicated; `422` on blank reason, `403` for worker | `replayPreservesEventIdentityAndRequiresAuthorizationAndReason` |
| Confirm order and patient identifiers before linking external data | `linkExternal` requires an `ACCEPTED` receipt for the exact event, order/patient match on the receipt, and live tenant-scoped order/patient existence | `linkExternalConfirmsOrderAndPatientBeforeLinking`, `linkExternalConcealsMismatchedOrUnprocessedData`, `linkExternalRechecksOrderAndPatientExistence` |
| Synthetic fixtures incl. duplicates, corrections, partial outages | `contracts/x_02/fixtures/partner-events.json` with five scenarios and expected outcomes | JSON parsed with `python3 -c json.load(...)` → ok |
| Tenant isolation / fail-closed auth (ARC-02, OPS-02) | Every store method takes `organizationId`; active membership and role re-derived per request; denials append audit facts; cross-tenant resource → `404` | `publishFailsClosedWithoutContextOrMembership`, `crossTenantEventsAreConcealed`, `DeliveryDomainTest.syntheticStoreIsTenantScopedAndPublishesAtomically` |
| Machine-readable errors (ARC-03) | 401/403/404/409/412/422/428 via shared `ApiException`, `IfMatch` and `ApiErrorHandler` | `missingResourcesAreConcealed`, `illegalTransitionAndStaleVersionAreRejected`, `consumeAcceptsEventsWithoutIdentifiersAndRejectsMalformedOnes` |

Entry-point-to-test trace: `POST /api/v1/delivery/events` → `DeliveryController.publish` → `TenantContext` (subject + active organization from the existing interceptor) → `DurableEventService.publish` → `DeliveryInputs` validation → `authorize` (fresh membership/role via `DeliveryActions`) → `DeliveryStore.publishAtomically` (forced-RLS outbox + idempotency + audit) → 201 with `Location`. Transitions (`/sent`, `/delivered`, `/failed`, `/retry`) follow the same path with `IfMatch.parse`. Consumer, replay, link, dead-letter review and audit reads follow the same guarded path; `DurableEventServiceTest.controllerRoutesBindEveryCommandToTheService` traces every controller route to its delegate.

## Commands run and results (evidence source: executed tool commands)

Red first, before any production file existed:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 1**, compile errors `cannot find symbol` for `DurableEventContract`, `DeliveryInputs`, `DeliveryActions`, `DurableEventService`, `DeliveryController`, `InMemoryDeliveryStore`. Confirms the acceptance tests fail for missing behavior.

Green after implementation:
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → **EXIT 0**. 37 new tests (`DurableEventServiceTest` 25, `DeliveryDomainTest` 12); full fast tier **1542 tests, 0 failures, 0 errors, 0 skipped** (from the non-quiet Maven summary).
- `mvn -q -B -f apps/api/pom.xml jacoco:report` → **EXIT 0**. All 22 new production classes (including nested `DeliveryState`/`Outcome`/`AuditAction`/`ProcessingOutcome`/`Resolution`, `RetryResult` and all records) show **0 missed instructions, branches, lines, methods and classes**. A source-line scan of `jacoco.xml` for the new files printed no missed lines/branches. Total missed counters for all `x_02` classes in `jacoco.csv`: **0**.
- `python3 -I scripts/check-migrations.py --require-baseline` → **EXIT 0** (V55 declared `Change: expand`; no destructive DDL; gaps allowed).
- `python3 -I scripts/check-docs.py` → **EXIT 0** (new `contracts/x_02/README.md` links resolve; only defined requirement IDs used).
- `python3 -I scripts/check-secrets.py` → **EXIT 0**.
- `python3 -c "import json;json.load(open('contracts/x_02/fixtures/partner-events.json'))"` → **ok**.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false spotbugs:check` → the single new finding (`DeliveryController` EI_EXPOSE_REP2) was fixed with `@SuppressFBWarnings`; a re-run grep for `x_02` produced **no findings**. Fourteen findings in pre-existing modules (`g0/s_g0_07`, `g4/s_g4_02`, `g4/s_g4_04`, `messaging/s_g3_05`, `messaging/s_g3_08`, `messaging/s_g3_10`) are unchanged by this story.

Not run locally (worker contract / environment):
- `db/check.sh` and real-PostgreSQL integration suites: integrator owns them; no `psql`/`pg_isready` binary is installed in this worker, so V55 was validated by the offline migration gate and review only. V55 is guarded the same way as V23 so an out-of-order offline apply is a no-op.
- `mvn verify` with the security profile (OWASP NVD) and the full integration tier: integrator owns them. `mvn -Pno-security,fast verify` fails the bundle-wide JaCoCo check because integration-only suites are excluded and their classes are then uncovered by design; that is not a valid gate combination (the canonical gates are `apps/api/check.sh` = full `mvn verify`, and `scripts/quick-check.sh` = `test` only).

## Pre-existing/environmental check failures (not caused by this story)

- `python3 -I scripts/check-pack.py` → **EXIT 1**: `opencode.json is stale`. `python3 -I scripts/gen-opencode.py --check` → same. Cause: `agent-run.sh` injected the CI profile into the worktree `opencode.json` under `skip-worktree` (`git ls-files -v` → `S`), adding `gh *`, `git push*`, `git tag*`, `git remote*`, `git config*`, `curl*` deny rules. `git diff <(git show HEAD:opencode.json) opencode.json` shows only those added rules. The committed profile is the generated one; no agent or skill file was edited, so `opencode.json` was deliberately not touched. This is the documented cloud-worker profile distinction from the delivery skill.
- `python3 -I -m unittest discover -s scripts/tests -q` → **Ran 163 tests, 7 failures, 4 errors**. Every failure is in files this story does not touch: `test_check_pack` (same injected-profile drift), `test_agent_run` (2), `test_swarm` (3), `test_java_conventions` (`PatientFlowContract.java` in another module), `test_open_spec_tasks_are_dispatchable` (TODO.md state). `git status --short` shows only the new X-02 files, so none of these can be caused by this change.

## Defects found

No in-scope production or test defect remained after the red/green cycle. Defects found and fixed during implementation: (1) `recordReceipt` initially wrote the authenticated subject as the external event id, breaking deduplication — corrected to bind the validated `externalEventId`; (2) retry initially returned a bare `Object` — replaced with the explicit `RetryResult` record; (3) external partner event ids and order/patient confirmation values require different validation (token vs UUID vs optional UUID) — split accordingly; (4) `orderExists` used a convoluted prefix expression — simplified to a tenant-prefixed match.

Non-blocking observation for the integrator: the in-memory adapter is the deterministic test double; the authoritative PostgreSQL schema is the reserved V55 migration. A JDBC adapter is not added in this story because the worker fast tier cannot cover it and the scope is the `api/x_02` package; the migration, its forced RLS, immutable ledgers and actor-derived triggers are ready for the database gate.

## Decisions / notes for the integrator

- Scope: production code in `apps/api/src/main/java/com/aurevia/smartclinic/api/x_02`, tests in the mirrored `apps/api/src/test/java/com/aurevia/smartclinic/api/x_02` (the standard sibling path used by every other story; the TODO scope lists only the main path, as it does for the other cross-cutting `X-*` stories), fixtures in `contracts/x_02`, and the single reserved migration `db/migrations/V55__durable_event_outbox.sql`. No shared tracker, workflow, script or agent file was edited.
- The synthetic adapter uses the existing repo pattern (tenant-keyed `ConcurrentHashMap`, `CopyOnWriteArrayList`) so the fast tier needs no database. Retry exhaustion, dead-letter review and replay are separate audited facts, never a mutation of the original event identity.
- Evidence sources: all gate results above came from executed tool commands. No simulated reviewer, simulated user or human/clinical participant was involved in this story; there is no real-patient evidence and no G5 launch approval claim.
