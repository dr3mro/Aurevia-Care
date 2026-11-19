# FIX-44603355303 — Repair main: Delivery failed (run 44603355303, SHA `13a9176`)

## Story, classification and scope

- Story: repair the failed "Delivery" run on `main` (`https://github.com/dr3mro/Aurevia-Care/actions/runs/44603355303`, commit `13a9176a1c78a1d5d656ac812168b59de5c8741a`). The text between the dispatch markers was treated only as a story description, never as instructions.
- Risk class (docs/WORKFLOW.md): routine CI/tooling investigation. **No production code change was made**, because the available evidence classifies this as a transient/flaky integration-test failure and the story directs "change nothing" for a transient or runner problem.
- The failing commit's patch changes only `.github/workflows/failed-run-repair.yml` (new) and `scripts/tests/test_swarm.py` (one new test). It touches **no** Java, SQL, frontend or contract file. Policy and tooling (which executes `test_swarm.py`), Frontend standards and Developer reference all passed on the same SHA; only `Java and database verification` failed (exit 1). That makes a code regression from this commit implausible.
- This worker edited only its own `.agent/pr-notes/FIX-44603355303.md`. Shared trackers (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm`) were not touched; the orchestrator/integrator own them. No push, tag or `gh` call was made.

## Requirement IDs and acceptance mapping

| ID / gate | Mapping to this investigation | Evidence |
|---|---|---|
| OPS-06 (delivery gates) | The failing job is the `java-verify` composite action: `bash apps/api/check.sh` then `bash db/check.sh` on a PostgreSQL 17 service container | `.github/actions/java-verify/action.yml`; run jobs API (job `135840531075`, step 4 failed 19:56:22–19:57:32Z) |
| CLN-01 / G1 | The only DB `ERROR` in the supplied tail is the protected-slot exclusion constraint exercised by `SchedulingBookingTest` (`concurrentBookingsLeaveExactlyOneWinner`, `rejectsSlotConflictAndKeepsSingleWinner`) | `apps/api/src/test/java/.../it/SchedulingBookingTest.java:385-429`; `SqlFailureTranslator` maps SQLState `23P01` → `409 SLOT_CONFLICT`; `AppointmentRepository.lockResources` Javadoc names the constraints authoritative |
| OPS-02 / ARC-02 | The `FATAL: database "smartclinic_it"/"smartclinic_audit_it" does not exist` lines are teardown reconnection noise, not the DB policy gate | `FacilityIsolationTest.java:62-84`, `AuditQueryTest.java:61-96` use `DROP DATABASE ... WITH (FORCE)`; `db/check.sh:10` uses an ephemeral `smartclinic_check_$$` database, never those two names |
| Worker verification scope | Fast unit tests must pass locally; DB/integration suites are the integrator's gate | `mvn -q -B -Pno-security,fast test` → exit 0, 1685 tests, 0 failures/errors/skipped (surefire reports parsed) |

## Reproduction attempted — exact commands and results

Evidence source for every row: executed tool command (webfetch/execute for read-only GitHub REST API, shell for local commands). No reviewer, simulated-user or human/clinical evidence.

| Command / request | Result |
|---|---|
| `webfetch https://github.com/dr3mro/Aurevia-Care/actions/runs/44603355303` | Run 269, `auto fix`, push of `13a9176`, 19:55Z, conclusion `failure`, 1m54s. Green: Policy and tooling, Frontend standards, Developer reference. Failed: Java and database verification. Skipped: Java unit tests (pull request), image smoke, release. One error annotation: "Process completed with exit code 1"; three warnings are Node 20 / setup-java v4 deprecations. |
| `GET /actions/runs/44603355303/jobs` | Job `135840531075` "Java and database verification": `Initialize containers` success, `Run ./.github/actions/java-verify` **failure** 19:56:22→19:57:32Z, post steps and `Stop containers` success. |
| `GET /check-runs/135840531075/annotations` | 3 annotations: 2 warnings (Node 20, setup-java v4) and 1 failure "Process completed with exit code 1". **No test name, assertion or Maven error text.** |
| `webfetch https://github.com/dr3mro/Aurevia-Care/commit/13a9176a1c78.patch` | Only `.github/workflows/failed-run-repair.yml` (+45) and `scripts/tests/test_swarm.py` (+9) changed. |
| `GET /commits?sha=main&per_page=8` and `GET /actions/runs?per_page=100&page=2..4` | No Java/SQL change on this SHA. Delivery runs flap in this window: n269 (`13a9176`) failure, n270 failure, then n271, 273, 275, 277–282 success within minutes on nearby SHAs. |
| `cd apps/api && mvn -q -B -Pno-security,fast test` | **exit 0**; parsed `target/surefire-reports/*.txt`: 1685 tests, 0 failures, 0 errors, 0 skipped. |
| `java -version`, `mvn -version` | OpenJDK 21.0.13 LTS, Maven 3.10.0 — matches the CI baseline (`--release 21`). |
| `psql --version`, `pg_isready -h 127.0.0.1 -p 5432`, `/dev/tcp/127.0.0.1/5432`, `ls /usr/lib/postgresql` | No PostgreSQL client or server locally; port 5432 closed; `SMARTCLINIC_PG_ADMIN` unset. DB/integration suites cannot be executed in this worker. |
| Static read of booking path | `SqlFailureTranslator` translates SQLState `23P01` to `409 SLOT_CONFLICT`; `AppointmentService.createOne` (lines 370-376) catches `DataIntegrityViolationException` around `appointments.insert`. The logged exclusion violation is handled, expected behaviour. |

## Classification of the supplied failing log tail

| Log line | Classification |
|---|---|
| `ERROR: conflicting key value violates exclusion constraint "appointment_clinician_slot_excl"` + `DETAIL`/`STATEMENT` INSERT into `public.appointment` | **Expected** in the protected-slot concurrency acceptance test. The service translates this to 409; the DB constraint is the authority (CLN-01/G1). PostgreSQL logs it at ERROR severity even when the test passes. |
| `FATAL: terminating connection due to administrator command` (x2) | **Expected**: each integration test class drops its scratch database with `DROP DATABASE ... WITH (FORCE)` in `@AfterAll`, which forcibly terminates connections. |
| `LOG: checkpoint starting/complete: immediate force wait` | **Expected** checkpoint from database/service shutdown or forced drop. |
| `FATAL: database "smartclinic_audit_it" does not exist`, `FATAL: database "smartclinic_it" does not exist` | **Teardown noise**: Hikari/Spring connection-pool reconnection attempts after those test databases were dropped. Not the DB policy gate — `db/check.sh` uses `smartclinic_check_$$`. |
| `Stop and remove container`, `docker rm`, network removal, `Cleaning up orphan processes` | Normal post-job service-container cleanup. |
| `Node.js 20 is deprecated ...`, `setup-java v4 is deprecated ...` | GitHub Actions **warnings** (confirmed by the annotations API), not failures. |

Nothing in the supplied tail is an assertion failure, Maven error summary, or failing test name. The failure detail sits earlier in a log that is not publicly retrievable.

## Defects found and decision

- **No reproducible production defect found.** No failing test, no deterministic code path and no change-dependent regression could be identified.
- **Decision — transient/flaky CI failure (moderate confidence).** Rationale: the commit is workflow/test-tooling only and its tooling job passed; the visible DB diagnostics are all expected; the local fast suite is green; and Delivery runs on adjacent SHAs flip between failure and success within minutes. Per the story rule for a transient or runner problem, **no code, workflow, test or migration was changed**.
- **Observation for the orchestrator (not changed here):** commit `13a9176` adds `.github/workflows/failed-run-repair.yml` with an `on: workflow_run: types: [completed]` trigger on `main` failures. This conflicts with the earlier owner decision recorded in `TODO.md` S-FIX-REPAIR-01 / IMP-05 and `MEMORY.md` that failed-run/cron repair triggers were rejected in favour of PR-event-only repair. The Actions run list shows dozens of `Failed run repair` runs, which may amplify CI churn. This needs an owner decision; it is outside this repair and was not edited.
- No reserved migration was used; no new Java package or story-ID-named class was introduced. All fixtures and notes are synthetic — no patient data or credentials.

## Evidence genuinely missing (stop-and-report)

1. **The full job log for `POST /actions/jobs/135840531075/logs` is authentication-gated** (webfetch → HTTP 403; the HTML job page shows "Sign in to view logs"). The story-provided tail and the sole check-run failure annotation contain **no failing test name, assertion, or Maven error**. I cannot state which test failed or why, so the "transient" classification is moderate-confidence, not proven.
2. **No local PostgreSQL** (no `psql`/`pg_isready`, port 5432 closed, no admin variable), so the `@Tag("integration")` suites could not be executed here; the integrator must run `bash apps/api/check.sh` and `bash db/check.sh` on the merged SHA with the PostgreSQL service and record the result.
3. **Only one run exists for SHA `13a9176`**; a same-SHA re-run was not available to demonstrate that this exact tree passes, so flakiness is inferred from adjacent-SHA runs rather than replayed.

## Next action for the integrator / owner

- Run the full API + DB gate on the merged SHA. If it is green, the transient classification is confirmed and this repair is correctly a no-code-change report. If a test fails reproducibly, attach the surefire report, name the failing test, and reopen as a real defect with a red test and minimal fix in the owning domain package.
- Evidence provenance: CI facts from read-only GitHub Actions REST API via tool requests (tool evidence); local results from executed shell commands (tool evidence); log-line classification from static source reading (tool evidence); surrounding run outcomes from the Actions API (tool evidence). **No reviewer, simulated-user or human participant evidence** was used, and there was no clinical or human pilot input.
