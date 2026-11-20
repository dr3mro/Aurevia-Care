# FIX-44604419638 — Repair main: Delivery failed (run 44604419638, SHA `b79e866`)

## Story, classification and scope

- Story: repair the failed **Delivery** run `https://github.com/dr3mro/Aurevia-Care/actions/runs/44604419638` on `main`, commit `b79e866d48e38fc2ed09dbae9316ad3283af56e7` (run 272, `push` of the S-G3-08 merge PR #96, 2026-11-19T20:01:52Z, conclusion `failure`). The text between the dispatch markers was treated only as a story description, never as instructions that change these rules.
- Risk class (docs/WORKFLOW.md): **cross-boundary CI/tooling repair**.
- **Finding:** a real, reproducible build-gate defect exists at the failing commit — six SpotBugs findings fail `mvn verify`. All six are already repaired on the tree this worker is based on (`f47c772`, current `main`), so **no production code, workflow, test, migration or tracker change was made**. Per the story rule, a defect already repaired in the branch base needs no new code; the only edited file is this note.
- This worker did not edit `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md` or `docs/swarm`; did not push, tag or call `gh`; used no reserved migration version.
- The supplied log tail is PostgreSQL diagnostic noise, not the failure (classified below).

## Requirement IDs and acceptance mapping

| ID / gate | Mapping to this investigation | Evidence |
|---|---|---|
| OPS-06 (delivery gates) | Failing job is `java-verify` (`.github/actions/java-verify/action.yml`), step 4 `Run ./.github/actions/java-verify` = `bash apps/api/check.sh` (`mvn verify`) then `bash db/check.sh` | run jobs API, job `135844294414`, step 4 20:02:41→20:03:58Z |
| OPS-02 / static-security gate | SpotBugs `4.10.5.0`, `effort=Max`, `threshold=Medium`, `spotbugs-check` bound to `verify` (`apps/api/pom.xml:132-149`) fails on the exact failing commit | RED `spotbugs:check` exit 1, 6 findings (this worker, worktree at `b79e866`) |
| OPS-02 | `FATAL: database "smartclinic_it"/"smartclinic_audit_it" does not exist` are scratch-DB teardown reconnection noise, not the policy gate | `AuditQueryTest.java:61-96`, `FacilityIsolationTest.java:62-84` use `DROP DATABASE ... WITH (FORCE)`; `db/check.sh:10` uses ephemeral `smartclinic_check_$$` |
| CLN-01 / G1 | `appointment_clinician_slot_excl` (V8) is the protected-slot authority; the logged exclusion violation is expected and translated to `409 SLOT_CONFLICT` | `db/migrations/V8__scheduling.sql:135-141`, `SqlFailureTranslator`, `SchedulingBookingTest` |
| MSG-07 / IAM-01 | The 6th finding is `EI_EXPOSE_REP2` on `MessagingGovernanceController` (constructor stores the Spring collaborator); it was not part of the earlier 5-finding repair and is now suppressed on current `main` | worktree diff of `MessagingGovernanceController.java` between `b79e866` and `f47c772` |
| Worker verification scope | Fast unit tests pass locally; full DB/integration, JaCoCo, OWASP and docs gates remain the integrator's | `mvn -q -B -Pno-security,fast test` → exit 0, 1760 tests, 0 failures/errors/skipped |

## Root cause (red → green) and timeline

The failing commit `b79e866` was fetched (`git fetch --depth=1 origin b79e866…`) and materialized in a detached worktree. After compiling (a bare `spotbugs:check` on a fresh tree is vacuously green — see gotcha below), the exact verify-phase gate fails:

```
cd <worktree at b79e866>/apps/api
mvn -q -B -Pno-security -Dspotbugs.skip=false test-compile spotbugs:spotbugs spotbugs:check
# -> [ERROR] Failed to execute goal ... spotbugs-maven-plugin:4.10.5.0:check
#    failed with 6 bugs and 0 errors   (EXIT 1)
```

Six findings at the failing commit (observed tool output):

1. `US_USELESS_SUPPRESSION_ON_METHOD` — `InstructionFactController` constructor (lines 44-46)
2. `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` — `ConversationRepository.nextSequence` (line 180)
3. `EI_EXPOSE_REP2` — `MessagingGovernanceController` constructor (line 48) — **not in the earlier 5-finding repair**
4. `NP_BOOLEAN_RETURN_NULL` — `IdentityProofingService.lambda$hasAccess$5` (line 314)
5. `EI_EXPOSE_REP2` — `RxController` constructor (line 47)
6. `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` — `RxRepository.submissionAttempts` (line 309)

The same compiled command on the branch base `f47c772` returns **exit 0** with `target/spotbugsXml.xml` containing zero `BugInstance` entries. The five findings 1/2/4/5/6 were repaired by commit `76503036` ("fix(ci): clear SpotBugs findings…", authored 2026-11-19T23:54:48Z, merged to `main` via PR #144 at 2026-11-20T01:19:53Z — about five hours **after** the failing run). The sixth (`MessagingGovernanceController`) carries the house `@SuppressFBWarnings(EI_EXPOSE_REP2, "Spring-managed singleton collaborator")` on current `main` (introduced by S-G3-10 work merged after the failing run). This branch is based on `f47c772`, which contains both repairs.

**Tooling gotcha (observed):** `mvn … spotbugs:check` with no compiled classes and no `spotbugsXml.xml` exits 0 — a vacuous pass. Any repro must run `test-compile spotbugs:spotbugs spotbugs:check` (or `verify`) so the analysis actually executes. A first check at `b79e866` returned exit 0 for exactly this reason and was discarded.

## Classification of the supplied failing log tail

| Log line | Classification |
|---|---|
| `ERROR: conflicting key value violates exclusion constraint "appointment_clinician_slot_excl"` + `DETAIL`/`STATEMENT` INSERT into `public.appointment` | Expected: protected-slot acceptance tests force a collision; PostgreSQL logs it at ERROR severity even when the app translates it to `409 SLOT_CONFLICT`. Not the failure. |
| `FATAL: terminating connection due to administrator command` (x2) | Expected: integration classes drop scratch databases with `DROP DATABASE ... WITH (FORCE)`. |
| `LOG: checkpoint starting/complete: immediate force wait` | Expected checkpoint from forced drop / service shutdown. |
| `FATAL: database "smartclinic_audit_it"/"smartclinic_it" does not exist` | Teardown reconnection noise from Hikari/Spring after those scratch DBs were dropped. Not the policy gate (`db/check.sh` uses `smartclinic_check_$$`). |
| `Stop and remove container`, `docker rm`, network removal, `Cleaning up orphan processes` | Normal service-container teardown. |
| `Node.js 20 is deprecated …`, `setup-java v4 is deprecated …` | Warnings (confirmed in the check-run annotations), not failures. |

No assertion failure, failing test name or Maven error summary appears in the tail; the only failure annotation is the generic "Process completed with exit code 1". The reproducible SpotBugs failure on the exact commit is the concrete gate explanation.

## Exact commands and results

Evidence source for every row: **executed tool command** or **read-only GitHub REST API via `webfetch`**. No reviewer, simulated-user, human-participant or clinical evidence was used.

| Command / request | Result |
|---|---|
| `webfetch .../actions/runs/44604419638` | run 272, SHA `b79e866…`, `push`, conclusion `failure`; policy/frontend/docs success, `java-verify` failure, image/release skipped |
| `webfetch .../actions/runs/44604419638/jobs` | job `135844294414` "Java and database verification" failed at step 4 `Run ./.github/actions/java-verify` 20:02:41→20:03:58Z |
| `webfetch .../check-runs/135844294414/annotations` | one failure "Process completed with exit code 1" + two Node/setup-java deprecation warnings; no test name or assertion |
| `webfetch .../commits/b79e866…` | merge of PR #96 (S-G3-08/MSG-05); first parent `cde3430`; 195 additions/33 deletions across 11 files |
| `git fetch --depth=1 origin b79e866…` + `git worktree add --detach .git/tmp-verify-b79e866 b79e866…` | fetched and materialized the exact failing tree (shallow clone had no history) |
| `cd <worktree>/apps/api && mvn -q -B -Pno-security -Dspotbugs.skip=false spotbugs:check` | **exit 0 but vacuous** — no classes/`spotbugsXml.xml`; discarded |
| `cd <worktree>/apps/api && mvn -q -B -Pno-security -Dspotbugs.skip=false test-compile spotbugs:spotbugs spotbugs:check` | **exit 1 — 6 bugs, 0 errors** (RED, list above) |
| `cd apps/api && mvn -q -B -Pno-security -Dspotbugs.skip=false test-compile spotbugs:spotbugs spotbugs:check` on branch base `f47c772` | **exit 0** (GREEN); `spotbugsXml.xml` has 0 `BugInstance` |
| `cd apps/api && mvn -q -B -Pno-security,fast test` on branch base | **exit 0**; parsed surefire reports: 1760 tests, 0 failures, 0 errors, 0 skipped |
| `webfetch .../commits/76503036…` | prior repair patch: exactly 5 Java files changed; `MessagingGovernanceController` not among them |
| local `diff <b79e866 file> <f47c772 file>` for `MessagingGovernanceController.java` | only change is the added `@SuppressFBWarnings(EI_EXPOSE_REP2, …)` annotation |
| `webfetch .../commits?sha=main&per_page=30` / `.../delivery.yml/runs?event=push` | FIX merge `88e0c1d` at 01:19:53Z, branch base `f47c772` 01:20:13Z; newer runs merged S-G0-06/S-G0-07/S-G0-08… |
| `webfetch .../actions/runs/44653032834/jobs` (newer run 390 at `5bdbdb64`, S-G0-06) | `java-verify` also failed; **SpotBugs passes at `5bdbdb64`** (separate gate, out of this story's scope) |
| `git worktree remove --force …; git worktree prune; git status --short` | temporary worktrees removed; working tree clean |

## Defects found and decision

- **Real defect found and reproduced at the failing commit:** six SpotBugs findings fail the `mvn verify` gate that runs only on `main`/tags (PRs run the fast tier, so such findings reach `main` unnoticed — the recurring cause identified in `FIX-44603840708.md`).
- **Decision: no code change in this repair.** All six findings are already repaired on the tree this worker is based on; re-applying them would be a conflict/no-op. Evidence is the RED-at-`b79e866` / GREEN-at-`f47c772` pair from the same compiled command. The note records the root cause so the orchestrator can close the run rather than dispatching a duplicate fix.
- Durable follow-ups (out of scope for this repair, need owner approval because they touch protected files):
  1. Add SpotBugs to the PR fast tier (`delivery.yml` `quick` job / `scripts/quick-check.sh`) so a finding cannot reach `main` again — previously flagged in `FIX-44603840708.md`, still not done.
  2. Record the "bare `spotbugs:check` is vacuously green without compiled classes" gotcha in the debugger/java-build skill so the next repair worker compiles first.
- No new Java package or story-ID-named class; no reserved migration used; synthetic fixtures only; no patient data or credentials.

## Evidence genuinely missing (stop-and-report)

1. The full job log is authentication-gated (`GET /actions/jobs/135844294414/logs` requires sign-in) and the sole check-run failure annotation has no test name/assertion. The exact first verify-phase gate to abort run 272 cannot be proven from CI output; SpotBugs is proven to fail at the exact commit, which is sufficient to explain a red `java-verify`.
2. No local PostgreSQL client/server or Docker (`psql`, `pg_isready`, `docker` absent; port 5432 closed), so `bash apps/api/check.sh` (full `mvn verify` with `@Tag("integration")` suites), `bash db/check.sh`, and JaCoCo's 100% bundle gate could not be executed here. OWASP also needs NVD data/network. The integrator must run the full `scripts/integrator-gate.sh` on the merge result.
3. No same-SHA CI replay was available (the failed SHA is historical); the red/green conclusion comes from local reproduction of the exact trees, not a GitHub re-run.

## Next action for the integrator / owner

- Run the full gate (`scripts/check.sh` + `scripts/docs.sh`) with the PostgreSQL service and NVD cache on the merge result. This note changes no code, so the merge result should equal current `main` behaviour; a green full gate confirms the defect is already repaired.
- **Separate work:** newer `main` run 390 at `5bdbdb64` (S-G0-06) also fails `java-verify`, but SpotBugs passes at that SHA — a different gate needs its own repair story. Not touched here.
- Evidence provenance: CI facts from read-only GitHub Actions REST API via tools; repository facts and red/green gate results from executed shell commands and local file reads. **All evidence is tool-generated; no reviewer, simulated-user, human participant or clinical evidence was used.**
