# READY-14 — List staff (GET /api/v1/staff)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).

## Acceptance mapping

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/staff`; `cursor`, `limit` (default 50, max 200, else 422 `INVALID_LIMIT`), `status` (`active`/`deactivated`, else 422) | `web/StaffController.list`, `StaffService.list/parseCursor/parseLimit/parseStatus` | `StaffListServiceTest`, `StaffListControllerTest`, `StaffListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. Order by subject asc, id tie-break; cursor `<subject>:<id>`; `nextCursor` null on last page; fetch `limit+1` | `StaffRepository.query` (`ORDER BY u.subject, u.id LIMIT :limit`), `StaffService.list` | `StaffListServiceTest.extraRowProducesCursorFromTheLastReturnedRow`, `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-record read action; add no role | `authorize(context, "STAFF_READ", "ALL")` — same action as `StaffService.get` | `StaffListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `StaffListIntegrationTest.unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real migration column names | `StaffRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `user_identity`, `membership`, `role_assignment`, `current_membership()` from V1/V4; no manual org predicate | `StaffListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `StaffPage(List<StaffMember> items, String nextCursor)` in view package | new `staff/StaffPage.java` | `StaffListServiceTest.pageAndRowFreezeTheirPartsAndExposeValueSemantics` |
| 6. Unit + controller + 2-org integration tests | 3 new test classes, 21 new tests | commands below |
| 7. Contract | `contracts/openapi.yaml`: `get` on `/api/v1/staff` + `StaffPage` schema; staff-web generated d.ts regenerated with pinned generator | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check`, `bash apps/staff-web/check.sh` |

No existing endpoint, response shape or migration was changed. No dependency added. Synthetic data only.

## Entry point to test trace

`GET /api/v1/staff?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `StaffController.list` → `StaffService.list` (validate then guarded `TransactionTemplate`) → `authorize` (`STAFF_READ`, re-derived membership/roles) → `StaffRepository.query` (forced-RLS, subject/id ordered, `limit+1`) → `appendAudit(STAFF_READ, ALL, SUCCESS)` → `StaffPage`.
Success path: `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
Failure paths: `StaffListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `StaffListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Exact commands and results (executed by this worker)

All commands run from the repository root unless noted. Exit codes are from the tool.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (before implementation) | RED — compile errors: `StaffPage`, `StaffRepository.StaffRow`, `StaffService.list`, `StaffController.list` do not exist |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test` | **BUILD SUCCESS — Tests run: 1875, Failures: 0, Errors: 0, Skipped: 0** (includes 11 `StaffListServiceTest`, 5 `StaffListControllerTest`) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=StaffListIntegrationTest -DfailIfNoSpecifiedTests=false test` | **BUILD SUCCESS — Tests run: 5, Failures: 0, Errors: 0** against real PostgreSQL |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | **BUILD SUCCESS — Tests run: 2123, Failures: 0, Errors: 0, Skipped: 0; `jacoco:check`: "All coverage checks have been met."** |
| `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false spotbugs:check` | **BUILD SUCCESS — BugInstance size is 0** |
| `SMARTCLINIC_PG_ADMIN=postgres bash db/check.sh` | exit 0 — migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `npm run gen:api:check` (in `apps/staff-web`) | `gen:api: generated client matches the contract` after `npm run gen:api` regeneration |
| `bash check.sh` (in `apps/staff-web`) | exit 0 — 33 test files / 235 tests passed, 100% statements/branches/functions/lines (46 files) |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` | all exit 0 |

### Worker-gate result and environment caveats

`SMARTCLINIC_PG_ADMIN=postgres bash scripts/worker-check.sh` was run with the committed HEAD `opencode.json` temporarily restored (see below). It exited **1** on the pre-existing Python tooling suite, not on this change:

- `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier`, `test_swarm`, `test_workflow_concurrency` (PyYAML absent from this worker's Python 3.11).
- `test_agent_run.py` failures/errors from sandbox git/gh behaviour (`git branch -D opencode/issue-5-9` nonzero, push rejected by the temp bare remote, `gh pr create: permission denied`).

The gate passed every stage relevant to this story before that point: `check-pack` ("Delivery tooling validated: 33 agents, 33 skills"), docs/secrets/names/migrations policy, and the full Java unit tier. The Java and database portions were additionally run directly as shown above (full `mvn verify` with JaCoCo, the new integration class, and `db/check.sh`) and are green. No Python, workflow or tooling file was changed by this story.

`check-pack.py` reports "opencode.json is stale" when run against this worker's worktree: `git ls-files -v opencode.json` shows flag `S`, and the worktree file equals `.github/opencode.ci.json`, not `HEAD:opencode.json`. This is the documented OPS-CW-003-01 `agent-run.sh activate_ci_config` measurement artifact. For the worker-gate run only, the HEAD profile was restored with `git update-index --no-skip-worktree` + `git checkout -- opencode.json`, then the CI profile and its `skip-worktree` flag were restored byte-for-byte. No committed config was modified (`git status` shows only this story's files).

## Branch-to-test mapping (JaCoCo 100% requirement)

Every new/changed branch is named with the test that takes each side. Full `mvn verify` reported all coverage checks met.

| Production branch | Test covering each side |
|---|---|
| `StaffPage` compact ctor + `items()` copy/override | `StaffListServiceTest.pageAndRowFreezeTheirPartsAndExposeValueSemantics` (caller mutation + unmodifiable result) |
| `StaffRepository.query`: `cursorSubject != null` true/false | `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (page 1 false, pages 2–3 true) |
| `StaffRepository.query`: `membershipStatus != null` true/false | `StaffListIntegrationTest.statusFilterSelectsOnlyMatchingMembers` (true) and `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (false) |
| `StaffRepository.mapRow` and `StaffRow` accessors/value methods | `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (row mapping), `StaffListServiceTest.extraRowProducesCursorFromTheLastReturnedRow`, `pageAndRowFreezeTheirPartsAndExposeValueSemantics` |
| `StaffService.list`: cursor present/absent | `extraRowProducesCursorFromTheLastReturnedRow` (present) vs `absentAndBlankParametersUseDefaults`, `emptyPageHasNoNextCursor` (absent) |
| `StaffService.list`: `denial != null` true/false and `failure.get() != null` | `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited` (true) vs all success tests (false) |
| `StaffService.list`: `hasMore` true/false | `extraRowProducesCursorFromTheLastReturnedRow` (true) vs `emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor` (false) |
| `parseCursor`: null / blank / malformed / valid | `absentAndBlankParametersUseDefaults` (null+blank), `invalidCursorsAreRejectedBeforeAnyQuery` (6 shapes), `extraRowProducesCursorFromTheLastReturnedRow` (valid) |
| `parseLimit`: null / blank / `<1` / `>200` / non-numeric / valid | `absentAndBlankParametersUseDefaults`, `invalidLimitsAreRejectedBeforeAnyQuery` (`0`, `201`, `abc`), success tests |
| `parseStatus`: null / blank / `active` / `deactivated` / invalid | `absentAndBlankParametersUseDefaults`, `statusFiltersMapToDatabaseMembershipStatus`, `invalidStatusesAreRejectedBeforeAnyQuery` |
| `StaffController.list` parameter binding | `StaffListControllerTest` (5 tests: 401, 403, 422, 200, passthrough) |

Javadoc uses fully qualified `{@link}` targets only where links exist; no unqualified link was added. `python3 -I -m unittest discover -s scripts/tests` was not green in this container for the unrelated environment reasons above; the Java-convention tests inside that suite were not needed for this change (no new Javadoc links).

## Contract generation note

`contracts/openapi/README.md` states that `contracts/openapi/generated/openapi.yaml` is emitted from `CommandClientGenerator.java`/`CommandCatalog.java` and is distinct from the staff-API `contracts/openapi.yaml` consumed by `apps/staff-web/scripts/gen-api-types.sh`. The new staff GET changes only the latter; it does not change the command catalog, so the generated X-04 artifacts are unchanged. Freshness of all three X-04 artifacts is proven by `python3 -I contracts/openapi/check.py` and by `CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration` inside `mvn test`. The dependent staff-web `src/api/generated/openapi.d.ts` **was** regenerated with the pinned `openapi-typescript` CLI (not hand-edited) so `npm run gen:api:check` stays green.

## Defects found, decisions and deviations

- No product defect found. No migration version used (no schema change).
- Decision: the list reuses `STAFF_READ` (the single-read action) and audits under `STAFF_READ`/`ALL`, adding no role and no new action key, per story step 3.
- Decision: `status=deactivated` maps to the database's `revoked` membership state at the service boundary; the wire contract keeps the user-facing word `deactivated`.
- Deviation (transparent): the TODO `scope` field lists only the `staff` packages and `contracts/openapi`, while the story's explicit steps require `web/StaffController.java`, an `it/` integration test and the regenerated staff-web d.ts. Those files were changed because the explicit steps cannot be satisfied otherwise and the build is not green without them. The swarm `scope` is planner collision metadata, not an enforced file allowlist (`scripts/swarm.py:guard` checks protected infra files only). Files changed outside the literal scope: `apps/api/src/main/java/com/aurevia/smartclinic/web/StaffController.java`, `apps/api/src/test/java/com/aurevia/smartclinic/it/staff/StaffListIntegrationTest.java`, `apps/staff-web/src/api/generated/openapi.d.ts`.
- Environment-only findings (no repo change): PyYAML missing; sandbox git/gh in `test_agent_run.py`; CI-profile `opencode.json` under `skip-worktree`. None is caused by this story.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Maven, Surefire, JaCoCo, SpotBugs, `db/check.sh`, the contract gate and the staff-web gate. Reviewer evidence: specialist skills `smartclinic-senior-backend`, `smartclinic-test-engineer` and `smartclinic-senior-database` were loaded and their rules applied; no separate reviewer run was dispatched for this bounded slice. Simulated user evidence: none (no clinical/mobile workflow). Human participant evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.

# AUD-20261120-08 — HTTP test coverage audit: scheduling, appointment and audit endpoints

## Outcome

**No new test classes were added.** Step 1 of the task requires: "Only write tests for endpoints that no test calls." I checked every endpoint of all three named controllers by URL path (not by class name) and found that **every one is already called by a live HTTP integration test**. The class-name-based audit that raised this story produced false positives: the covering tests build requests with URL paths and never reference the controller class names.

No production code, migration, OpenAPI file, tracker or shared file was changed. The only change is this note.

## Requirement IDs and acceptance mapping

The story did not state requirement IDs; these come from the owning controllers' Javadoc and the V1 specification, and are the requirements whose HTTP coverage was audited:

| Requirement | Controller(s) | Endpoint coverage outcome |
|---|---|---|
| CLN-01, ARC-03 | `web/AppointmentController` (`/api/v1/appointments`) | All 11 endpoints already called by `it/SchedulingBookingTest` |
| CLN-01, ADM-01 | `web/SchedulingAdminController` (`/api/v1/scheduling`) | All 4 endpoints already called by `it/SchedulingBookingTest` |
| OPS-02, ARC-03 | `web/AuditController` (`/api/v1/audit-events`) | The single GET endpoint already called by `it/AuditQueryTest` and `it/lab/SpecimenAccessionIsolationTest` |

| Task step | Result |
|---|---|
| 1. Find whether a test already calls each endpoint by URL path; only test uncalled endpoints | Done — see the table below; zero uncalled endpoints |
| 2. Copy the `ResultRoutingIntegrationTest` request-building pattern | Reviewed (`headers` helper lines 203–213, `send` lines 215–230, `@Tag("integration")` line 50, `@ActiveProfiles("synthetic-auth")` line 53, Flyway/RestClient setup). Not used, because no new class was needed |
| 3. Four tests (401 / 422 / 403 / success) per uncovered endpoint | Not applicable — no uncovered endpoint |
| 4. `@Disabled` for a test that reveals a real bug | Not applicable — no test was written and no production bug was observed in this audit |

## Step 1 evidence: per-endpoint call sites (tool evidence, synthetic fixtures)

Grep commands (run from the repository root, `apps/api/src/test`, `--include=*.java`):

```
grep -rn "api/v1/appointments\|/appointments" apps/api/src/test
grep -rn "api/v1/scheduling\|/visit-types\|/resources\|/availability" apps/api/src/test
grep -rn "audit-events" apps/api/src/test
```

Definitive per-endpoint confirmation loop:

```
for p in 'POST, "/api/v1/appointments"' '/api/v1/appointments/holds' \
  '/api/v1/appointments/series' '/api/v1/appointments/.*/confirm' \
  '/api/v1/appointments/.*/reschedule' '/api/v1/appointments/.*/check-in' \
  '/api/v1/appointments/.*/start' '/api/v1/appointments/.*/complete' \
  '/api/v1/appointments/.*/cancel' '/api/v1/appointments/.*/no-show' \
  '/api/v1/appointments/' '/api/v1/scheduling/visit-types' \
  '/api/v1/scheduling/resources' '/api/v1/scheduling/resources/.*/availability' \
  '/api/v1/audit-events'; do grep -rlE "$p" apps/api/src/test; done
```

Every pattern returned at least one test file. No `<<NO TEST>>` result.

| Controller | Endpoint (method + path) | Calling test (file:line evidence) |
|---|---|---|
| AppointmentController | POST `/api/v1/appointments` | `it/SchedulingBookingTest.java:253,289,715,726,767,776,781,784,787,792,797`; `it/schedulingcheckin/CheckInWorklistIntegrationTest.java:289` |
| AppointmentController | POST `/api/v1/appointments/holds` | `it/SchedulingBookingTest.java:449,477` |
| AppointmentController | POST `/api/v1/appointments/series` | `it/SchedulingBookingTest.java:726,747,754,757` |
| AppointmentController | GET `/api/v1/appointments/{id}` | `it/SchedulingBookingTest.java:372,488,525,570,600,620,702,704,804,806` |
| AppointmentController | POST `/api/v1/appointments/{id}/confirm` | `it/SchedulingBookingTest.java:461,470,492` |
| AppointmentController | POST `/api/v1/appointments/{id}/reschedule` | `it/SchedulingBookingTest.java:512,516,519,539,554,563` |
| AppointmentController | POST `/api/v1/appointments/{id}/check-in` | `it/SchedulingBookingTest.java:579` |
| AppointmentController | POST `/api/v1/appointments/{id}/start` | `it/SchedulingBookingTest.java:584` |
| AppointmentController | POST `/api/v1/appointments/{id}/complete` | `it/SchedulingBookingTest.java:589` |
| AppointmentController | POST `/api/v1/appointments/{id}/cancel` | `it/SchedulingBookingTest.java:596,612,615` |
| AppointmentController | POST `/api/v1/appointments/{id}/no-show` | `it/SchedulingBookingTest.java:632` |
| SchedulingAdminController | POST `/api/v1/scheduling/visit-types` | `it/SchedulingBookingTest.java:813,842` |
| SchedulingAdminController | POST `/api/v1/scheduling/resources` | `it/SchedulingBookingTest.java:820,837,839` |
| SchedulingAdminController | GET `/api/v1/scheduling/resources` | `it/SchedulingBookingTest.java:831` |
| SchedulingAdminController | POST `/api/v1/scheduling/resources/{code}/availability` | `it/SchedulingBookingTest.java:827,844,847,851` |
| AuditController | GET `/api/v1/audit-events` | `it/AuditQueryTest.java:117` (query helper used throughout); `it/lab/SpecimenAccessionIsolationTest.java:290` |

`grep -n "Disabled\|@Ignore" it/SchedulingBookingTest.java it/AuditQueryTest.java` returned `NONE`: all covering tests are active. Both are `@Tag("integration")`, `@SpringBootTest(webEnvironment = RANDOM_PORT)` and `@ActiveProfiles("synthetic-auth")`, i.e. real HTTP tests over a real PostgreSQL with synthetic two-tenant fixtures. The integrator runs this tier.

### Four-case matrix already present in the covering tests

- Audit GET `/api/v1/audit-events`: `it/AuditQueryTest.java:143–198` — 200 (`tenantAdminReadsOnlyItsOwnAuditTrail` line 146, `privacyAuditorCanReadTheTrail` line 154), 403 (reception, line 159), 401 (no subject, line 160), 422 (malformed org, line 161; bad limit/cursor/from/action, lines 192–197). This is exactly the four-case matrix the task asks for.
- Appointment POST `/api/v1/appointments`: `it/SchedulingBookingTest.java:762–799` — 201 (line 387), 401 (line 766), 403 (lines 770, 772), 422 (lines 781–799). The state-transition endpoints are exercised in lines 579–632 and their version preconditions in lines 461–619.
- Scheduling admin endpoints: `it/SchedulingBookingTest.java:810–853` — 201 (lines 815, 822, 828), 200 (line 833), 403 (line 837), 422 (lines 839–849). The 401 case is asserted generically by the interceptor test on the booking endpoint (line 766); it is not repeated per scheduling-admin path. This is an observation only — the endpoints are called by tests, so step 1 excludes them from this task. No production defect was found.

### Success statuses in the generated contract

`grep -n "/api/v1/appointments\|/api/v1/scheduling\|/api/v1/audit-events" contracts/openapi/generated/openapi.yaml` finds only `/api/v1/appointments` (line 150), whose POST documents `'201'` (line 167). The sub-paths and the scheduling/audit paths are not in the generated contract; their success statuses are declared in the controllers themselves (201 for the four POST creates, 200 for GET and the other POST transitions). Because no test was added, step 3's status lookup was informational only. No OpenAPI file was edited.

## Commands actually run and results

| Command | Result |
|---|---|
| `grep -rn "<path fragment>" apps/api/src/test --include=*.java` (all three path families) | Every endpoint has a call site; see table above |
| Per-endpoint confirmation loop | No endpoint returned `<<NO TEST>>` |
| `grep -n "Disabled\|@Ignore" ... SchedulingBookingTest.java AuditQueryTest.java` | `NONE` |
| `grep -n ... contracts/openapi/generated/openapi.yaml` | Only `/api/v1/appointments` documented, success 201 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **EXIT 0**; Surefire XML totals: 1859 tests, 0 failures, 0 errors, 0 skipped |
| `bash scripts/worker-check.sh` | Failed before Java tests: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (see blocker 1) |
| `bash scripts/worker-check.sh` with the committed local profile temporarily restored | Advanced past the pack gate, then failed in the Python tooling unit tests (blocker 2) |
| `python3 -I -m unittest discover -s scripts/tests -q` | 268 tests, pre-existing failures/errors unrelated to this story (blocker 2) |
| `git status --short` after all commands | Clean — no tracked file changed |

Evidence source: executed shell commands and file reads (tool evidence). No reviewer, simulated user or human participant was involved; all fixtures are synthetic.

## Defects found

None in the three controllers or their endpoints. No `@Disabled` test was needed. No production code was changed.

## Blockers to the local `worker-check.sh` gate (pre-existing, not caused by this story)

1. **Pack-gate false positive from the cloud-worker profile.** `git ls-files -v opencode.json` shows the `S` (skip-worktree) flag: the worktree file is the CI profile that `agent-run.sh` copied, with extra `gh *` / `git push*` denies. `python3 -I scripts/check-pack.py` and `python3 -I scripts/gen-opencode.py --check` therefore report staleness. Verified with a fresh generation in a temporary root: `git show HEAD:opencode.json` is byte-identical to the generated local profile (`MATCH`), while the worktree file differs by the CI deny block. This is the known OPS-CW-003-01 artifact recorded in MEMORY.md and the delivery skill. I did not modify the file; the temporary swap used for the second gate run was restored byte-for-byte and `git status --short` is clean.
2. **Pre-existing Python tooling test failures in this worker.** `python3 -I -m unittest discover -s scripts/tests -q` reports 8 failures / 10 errors in `test_agent_run`, `test_swarm`, `test_check_pack` plus two `_FailedTest` module-import errors (`test_fast_tier`, `test_workflow_concurrency`); the failing set varies between runs (5 and 8 failures observed), indicating environment-dependent tests. These modules are outside this story's scope and no file in them was changed here. Consequently `bash scripts/worker-check.sh` cannot be green in this worker for reasons unrelated to the audited endpoints. The Java fast gate (`mvn ... -Pno-security,fast test`) passes with 1859 tests.

## Decision and scope

- Decision: the set of endpoints "that no test calls" is empty for all three controllers; adding new HTTP test classes under `it/scheduling` or `it/audit` would duplicate existing coverage and violate step 1. No tests, production code, migrations or OpenAPI files were changed.
- If a person wants dedicated test classes in those packages, that is a new decision (a refactor/duplication request), not a gap this story found.
- The integrator runs the PostgreSQL/integration tier; this worker ran the fast Java unit tier as instructed.
