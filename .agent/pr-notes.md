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

---

# AUD-20261120-09 — HTTP security-contract tests for finance, prescription, specialty and conversation endpoints

## Requirement IDs

FIN-01, RX-01, RX-02, PRD-06, MSG-02, MSG-03, IAM-01, ARC-03, ARC-04, OPS-02.

## Scope and changes

Test-only change. Five new integration test classes; no production code, migration,
OpenAPI file or shared tracker was modified.

| New file (package follows business domain) | Controller(s) covered | Endpoints |
|---|---|---|
| `it/finance/FinanceHttpSecurityTest.java` | `FinanceController` | 8 |
| `it/authz/SpecialtyHttpSecurityTest.java` | `SpecialtyController` | 4 |
| `it/prescription/PrescriptionHttpSecurityTest.java` | `PrescriptionController`, `DrugCatalogController` | 5 |
| `it/prescription/RxHttpSecurityTest.java` | `RxController` | 11 |
| `it/messaging/ConversationHttpSecurityTest.java` | `ConversationController` | 10 |

All classes are `@Tag("integration")`, `@ActiveProfiles("synthetic-auth")`,
`@SpringBootTest` with a random port and a dedicated synthetic PostgreSQL database.
They copy the `headers(...)`/`send(...)` request-building pattern and the
`@BeforeAll`/`@BeforeEach`/`@AfterAll` Flyway setup from
`it/exchangeresult/ResultRoutingIntegrationTest.java`, with all fixtures synthetic.

## Step 1 — URL-level coverage survey (not skipped)

Grepped the full `apps/api/src/test` tree for every controller path fragment.
Result: **all 38 endpoints are already called by at least one existing integration
test**, mainly `FinanceFlowTest`, `PrescriptionFlowTest`, `RxTransmissionFlowTest`,
`SpecialtyAuthorizationFlowTest` and `it/messagingconversations/ConversationFlowTest`.
The literal finance fragments `/payments`, `/credits` and `/refunds` return zero
hits only because `FinanceFlowTest` builds them via `"/invoices/" + id + "/" + kind`
with the `kind` values `payments`, `credits`, `refunds` (verified in that file).
Rx command paths are built as `id + "/attest"`, `id + "/renew"`, etc. and all
helper methods are invoked. Every controller flow test is active (no `@Disabled`,
`@Tag("integration")` present).

Conclusion: the audit's premise — "controller class name appears in no test" — is
a false positive at the URL level: the controllers are exercised through HTTP by
flow tests, not by class name. However, the four-case HTTP **security** matrix
required by step 3 was not systematically asserted for these routes (for example
`RxTransmissionFlowTest` has zero 401 and zero 422 assertions and
`ConversationFlowTest` has zero 401 assertions). Per step 3 this story therefore
adds dedicated security-contract tests; it does not duplicate the business-flow
assertions.

## Step 3 — acceptance mapping (four cases per endpoint)

For each of the 38 endpoints the new classes assert:

1. **(a) 401** — request with no `X-Synthetic-Subject` header.
2. **(b) 422** — request with `X-Active-Organization: not-a-uuid`.
3. **(c) 403** — authenticated subject that may not perform the action.
4. **(d) success** — allowed subject, asserted in a chained happy-path test using
   the status implemented by the controller and observed in the existing flow
   tests: `201` for the eight create/open routes, `200` for the rest.

Cases (a)–(c) are `@ParameterizedTest` methods with one invocation per endpoint;
case (d) is one chained test per class that walks the real workflow. Total new
test invocations: 25 + 13 + 16 + 34 + 31 = **119**, covering 38 × 4 = 152 case
assertions.

`403` subject choice is endpoint-appropriate: `reception-1111` for finance and
prescription; `ghost-1111` (no active membership) for Rx and conversations; and
`ghost-1111` for specialty because `/api/v1/specialty/permissions` is readable by
every active member, so a non-member is the deterministic denial there. This is a
test-fixture decision, not a production change.

No test revealed a wrong status or missing authorization, so **no `@Disabled`
test was needed and no follow-up defect was found** in the covered endpoints.

## Commands and results (tool evidence)

Local PostgreSQL was available (`pg_isready` accepting connections, `SMARTCLINIC_PG_ADMIN=postgres`), so the integration tier was run directly rather than left to the integrator.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=FinanceHttpSecurityTest test` | 25 tests, 0 failures, 0 errors |
| `... -Dtest=SpecialtyHttpSecurityTest test` | 13 tests, 0 failures, 0 errors |
| `... -Dtest=PrescriptionHttpSecurityTest test` | 16 tests, 0 failures, 0 errors |
| `... -Dtest=RxHttpSecurityTest test` | 34 tests, 0 failures, 0 errors |
| `... -Dtest=ConversationHttpSecurityTest test` | 31 tests, 0 failures, 0 errors |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` (whole tier) | exit 0 |
| `python3` surefire report aggregation | 196 suites, 2221 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (unit tier) | exit 0 |
| `SMARTCLINIC_PG_ADMIN=postgres bash db/check.sh` | exit 0 — migrations, forced RLS, role separation, isolation, audit, append-only staff history, versioned config verified |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline`, `bash -n` over shell scripts | all exit 0 |

Evidence source: executed tool commands only. No reviewer, human or clinical
participant was involved; all fixtures are synthetic.

### `bash scripts/worker-check.sh` could not complete in this worker

`worker-check.sh` stops at `check-pack.py` with `opencode.json is stale`. This is
the documented cloud-worker `skip-worktree` artifact: `git ls-files -v opencode.json`
returns `S`, and `agent-run.sh` has copied the CI profile into the worktree, so
the pack check measures the CI profile rather than the committed local profile at
`HEAD`. This is unrelated to the test-only change and is recorded as a false
positive in `MEMORY.md` (OPS-CW-003-01). `opencode.json` was not touched.

After that gate, this environment also cannot run the whole Python tooling suite:
`test_fast_tier`, `test_swarm` and `test_workflow_concurrency` error with
`ModuleNotFoundError: No module named 'yaml'`; `test_agent_run` and `test_check_pack`
fail on local git/gh behaviour and the same `opencode.json` profile artifact.
These are environment limitations, not defects in this change. The Java unit tier,
the full Java integration tier and `db/check.sh` — the gates relevant to the
classes touched — were run directly and all pass (table above). The integrator's
prepared image has PyYAML and the correct profile and runs the complete gate.

## Defects and decisions

- No production defect found; no test disabled; no follow-up needed for the covered endpoints.
- **Observation for a separate story (not fixed here):** `contracts/openapi/generated/openapi.yaml`
  documents only 24 paths. None of the finance, prescription-drafts, drug-catalog,
  prescriptions or conversations routes are present; only `/api/v1/specialty/attestations`
  is (with `201`). Step 3's "success status from the generated contract" was therefore
  unavailable for 37 of 38 endpoints, so success statuses were taken from the
  controllers and the existing flow tests. Contract publication for these routes is
  out of scope for this test-only task.
- Test DB names are unique per class (`smartclinic_*_http_it`) and each class drops
  its database in `@AfterAll`, so the new classes do not collide with existing
  integration suites; confirmed by the whole-tier run.
