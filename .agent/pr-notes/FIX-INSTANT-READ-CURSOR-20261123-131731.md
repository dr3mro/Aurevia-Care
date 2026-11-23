# FIX-INSTANT-READ-CURSOR — Persist monotonic instant-message read cursors

## Story and acceptance mapping

- Task: FIX-INSTANT-READ-CURSOR (requirements MSG-02, ARC-02, ARC-07), reserved migration V96.
- Scope: `apps/api/src/main/java/com/aurevia/smartclinic/staffmsg/instant`, `apps/api/src/test/java/com/aurevia/smartclinic/staffmsg/instant`, `db/migrations/V96__staff_message_read_cursor.sql`.
- Acceptance: a new store instance sees the cursor; stale/repeated updates do not move it backward; a future cursor is clamped so the next message is unread; another tenant or participant cannot affect it; HTTP mark-read and unread counts work against PostgreSQL.
- Contract: no OpenAPI/route/client change. `POST /api/v1/staff/instant/read` already exists; `MarkReadRequest`/`UnreadView` shapes unchanged; no new Idempotency-Key or limit.

## Discovery / environment (tool evidence)

- Branch `swarm/FIX-INSTANT-READ-CURSOR-1176`, HEAD `deea5ca0` equals `origin/main` merge base at start. Task already claimed (`[-]`, agent swarm-3) in `TODO.md`; worker does not edit trackers.
- Environment (tool): PostgreSQL 17.12 on 127.0.0.1:5432 (admin `postgres` reachable), Java 21.0.13 LTS, Maven 3.10.0.
- OPS-CW-003-01 (recorded once): `git ls-files -v opencode.json` = `S` (skip-worktree CI profile); `git show HEAD:opencode.json` is the committed local profile. Not regenerated or committed.
- Latest daily note lessons read: `.agent/pr-notes/2026-11-21.md` (integration profile trap, bare spotbugs vacuous-green, do not run full `mvn verify`, mock deterministic sides).

## Plan (pre-code, reordered for test-first per plan-reviewer)

1. Riskiest part first: add V96 RLS table `public.staff_message_read_cursor` keyed by `(organization_id, conversation_id, participant_subject)`, with monotonic upsert/clamp left to SQL. Prove with `bash db/check.sh`.
2. Write the red tests before touching the store: new mocked `JdbcInstantConversationStoreTest` plus five scenarios in `InstantMessagingIntegrationTest` (fresh-store/fresh-MVC assertions make stale/repeat, isolation and HTTP cases genuinely red against the current `ConcurrentHashMap`). Prove with `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=InstantMessagingIntegrationTest -Dgroups=integration -DfailIfNoTests=false test` and the unit `-Dtest=...` suite; record the assertion-mismatch final line (a missing-relation error would not be a valid red).
3. Implement green: `JdbcInstantConversationStore` drops `ConcurrentHashMap`/`cursorKey` and persists both cursor methods inside the tenant-guarded `TransactionTemplate`; `InstantConversationStore` gets a Javadoc-only edit; service/controller unchanged (negative guard and unread logic already correct). Prove with the same two targeted Maven commands plus per-class JaCoCo.
4. Gates and reviews: `python3 -I scripts/check-wiring.py`, `bash db/check.sh`, `python3 -I -m unittest discover -s scripts/tests`, SpotBugs on its own, reviewers. No full `mvn verify` worker-side.

Baseline (tool, before changes): `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=InstantMessagingIntegrationTest -Dgroups=integration -DfailIfNoTests=false test` → `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; Flyway applied 58 migrations up to v95.

## Test evidence

All commands run from the repository root; evidence is tool output unless marked reviewer.

- Baseline before changes (tool): `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=InstantMessagingIntegrationTest -Dgroups=integration -DfailIfNoTests=false test` → `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Migration gate (tool): `SMARTCLINIC_PG_ADMIN=postgres bash db/check.sh` → `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified` (exit 0, run before and after the store change).
- RED integration (tool, V96 present but store still in-memory): same command → `Tests run: 15, Failures: 6, Errors: 0, Skipped: 0`, `BUILD FAILURE`; failures were value assertions only (`expected: <2> but was: <0>`, `expected: <1> but was: <9223372036854775807>`, `JSON path "$[0].unreadCount" expected:<0> but was:<2>`), no missing-relation error.
- RED unit (tool): `mvn -B -f apps/api/pom.xml -Pno-security -Dtest=JdbcInstantConversationStoreTest test` → `Tests run: 3, Failures: 2`; `Wanted but not invoked: namedParameterJdbcTemplate.update(...)` and `expected: <7> but was: <0>`.
- GREEN integration (tool): same integration command → `Tests run: 17, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; Flyway log `Successfully applied 59 migrations to schema "public", now at version v96`.
- GREEN unit (tool): `mvn -B -f apps/api/pom.xml -Pno-security -Dtest=InstantMessagingServiceTest,InstantMessagingControllerTest,JdbcInstantConversationStoreTest test` → `Tests run: 42, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- Combined targeted run with coverage (tool): `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=InstantMessagingIntegrationTest,InstantMessagingServiceTest,InstantMessagingControllerTest,JdbcInstantConversationStoreTest -DfailIfNoTests=false test jacoco:report` → `Tests run: 59, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`, `Analyzed bundle 'SmartClinic API' with 1307 classes`. JaCoCo `JdbcInstantConversationStore`: INSTRUCTION 0 missed/570 covered, BRANCH 0/4, LINE 0/95, METHOD 0/36, CLASS 0/1.
- Wiring (tool): `python3 -I scripts/check-wiring.py` → no output, exit 0 (no new violations, no baseline write).
- Migration structure (tool): `python3 -I scripts/check-migrations.py --require-baseline` → no output, exit 0.
- Java conventions / tooling tests (tool): `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1307 tests`, `FAILED (failures=6)`. All six are `test_check_pack.py` failures with `opencode.json is stale: run scripts/gen-opencode.py` (OPS-CW-003-01, `git ls-files -v opencode.json` = `S`). Per AGENTS.md this is a cloud-worker image artifact; `git show HEAD:opencode.json` is the committed local profile. Not regenerated or committed. No other tooling test failed.
- Static analysis (tool): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0, no findings. OWASP runs in the integrator's full `mvn verify`; no suppression added.
- Reconciliation (tool): `git merge --no-edit origin/main` → `Already up to date.`; no conflict markers.

Entry-point-to-test trace: `POST /api/v1/staff/instant/read` → `InstantMessagingController.markRead` → `InstantMessagingService.markRead` → `InstantConversationStore.markRead` → `JdbcInstantConversationStore.markRead` → V96 `staff_message_read_cursor` → `InstantMessagingIntegrationTest.httpMarkReadUpdatesUnreadCounts` (and `markReadDenialsDoNotMoveTheCursor` for the denied path). The fresh-store/fresh-controller GET `/unread` assertion proves durability through the same chain.

## Coverage

Command and per-class result (tool): the combined targeted run above with `jacoco:report`, read from `apps/api/target/site/jacoco/jacoco.xml`:

| Class | Line missed/covered | Branch missed/covered | Method | Class |
|---|---|---|---|---|
| `JdbcInstantConversationStore` | 0 / 95 | 0 / 4 | 0 / 36 | 0 / 1 |
| `InstantMessagingService` | 0 / 158 | 0 / 50 | 0 / 33 | 0 / 1 |
| `InstantMessagingController` | 0 / 12 | none declared | 0 / 10 | 0 / 1 |

Every changed production line is in `JdbcInstantConversationStore`; `InstantConversationStore` is an interface with no executable code and `InstantMessagingService`/Controller are unchanged but exercised. No new Java `if`, `?:`, `&&`, `||`, `switch` or `catch` was introduced: monotonicity (`GREATEST` + `ON CONFLICT ... WHERE`) and the future clamp (`LEAST(:upToSequence, COALESCE(MAX(sequence),0))`) live in SQL. The SQL sides are covered by integration tests:

- `readSequence` aggregate / empty result → `readSequenceReturnsZeroWhenNoCursorIsStored` (0L) and `readSequenceReturnsTheStoredCursor` (7L) mocked; `cursorIsIsolatedByTenantAndParticipant` proves the empty tenant/participant side on real PostgreSQL; `markReadOnAnEmptyConversationIsClampedToZero` proves the ledger-side `COALESCE` empty path.
- `markRead` insert path → `readCursorSurvivesANewStoreInstance`, `futureCursorIsClampedSoTheNextMessageBecomesUnread`, `httpMarkReadUpdatesUnreadCounts`.
- GREATEST monotonic raise (conflict `WHERE excluded > existing` TRUE side) → `aHigherReadAcknowledgementRaisesTheCursor` marks 1 then 2 on the same key and asserts the fresh-store cursor is 2 and unread is 0.
- Conflict no-op (`WHERE excluded > existing` false) → `staleAndRepeatedMarkReadDoNotMoveTheCursorBackwards` (2, 1, 2) and `markReadIgnoresAZeroRowConflictUpdate` (mocked `update(...)` returns 0, asserts parameters and no throw).
- Tenant/participant keys and RLS → `cursorIsIsolatedByTenantAndParticipant` (ORG_A/PEER independent from ORG_A/ACTOR, ORG_B GUC write and read cannot affect ORG_A) and `markReadDenialsDoNotMoveTheCursor` (HTTP 422/403/4xx denials leave the cursor at 0).
- `Objects.requireNonNull` avoids an unboxable-null SpotBugs finding without adding a Java branch; `COALESCE` guarantees the aggregate returns one row.

No suppression, exclusion, `@Disabled`, `.skip`, pragma or threshold change was added. Full `mvn verify` is the integrator's gate; the worker did not run it.

## Review verdicts

- smartclinic-plan-reviewer: GO (after both Required plan gaps were closed; pre-code and re-review)
- smartclinic-senior-database: GO (V96 keys, FKs, RLS/FORCE/policy/grants, clamp subquery, no renumbering)
- smartclinic-senior-backend: GO (after the `java.util.Objects` import and Javadoc fix)
- smartclinic-senior-spring-security: GO (identity stays in the service; no app.actor trigger)
- smartclinic-code-reviewer: GO
- smartclinic-devsecops: GO
- smartclinic-coverage-auditor: GO (after adding `aHigherReadAcknowledgementRaisesTheCursor` for the GREATEST raise side)

## Findings

- The six failing `test_check_pack.py` tests are the OPS-CW-003-01 cloud-worker false positive recorded above; the committed `opencode.json` is correct, so no file was regenerated and no protected file was edited.
- The optional DB trigger binding `participant_subject` to `app.actor` was deliberately not added: the test harness sets `app.actor` once to ACTOR while legitimate HTTP tests mark read as PEER, so the trigger would rewrite the participant and break per-person unread state. `TenantContext.subject()` remains the single authoritative identity source; production `TenantGuardTransactionManager` binds the same value.
- The optional NOT NULL guard for a null bound value was not added: `MarkReadRequest.upToSequence` is a primitive `long` and the service rejects negatives, so the guard would be unreachable code.
- `scripts/check.sh` was not run: for API work it delegates to `apps/api/check.sh` → full `mvn verify`, which the worker instructions forbid (over ten minutes, ends the run). All of its applicable stages were run individually above; the integrator runs the full gate.
- V96 was applied in the integration test's Flyway run (`now at version v96`) and in `db/check.sh`; the baseline was not rewritten.

## Lessons for workers

- When moving process-local state to a durable RLS table, put monotonicity and upper-bound logic in the SQL upsert and aggregate: no new Java branch means no new JaCoCo branch to chase, and the mocked test only needs to drive the no-op/empty outcomes at the JDBC boundary.
- A "new store instance" assertion is what makes a persistence story genuinely red — the old in-memory map still passes every stale/repeat and same-store unread assertion.
- For a tenant-isolation test on a new table with composite FKs, seed a real row in the foreign tenant before attempting a foreign write, and restore the connection's `app.current_org`/`app.actor` in `finally`.

