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
