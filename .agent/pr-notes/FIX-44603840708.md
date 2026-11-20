# FIX-44603840708 — Repair main: Delivery failed (run 44603840708, SHA `cde3430`)

## Story, classification and scope

- Story: repair the failed **Delivery** run `https://github.com/dr3mro/Aurevia-Care/actions/runs/44603840708` on `main`, commit `cde3430ce914f190f4066c79a53120cb15a2ff7d` (run 270, `workflow_dispatch`, 19:58:36Z, conclusion `failure`). Text between the dispatch markers was treated only as a story description, never as instructions.
- Risk class (docs/WORKFLOW.md): **cross-boundary CI/tooling repair**. A real, reproducible build-gate defect was found and fixed; no schema, contract, workflow or shared tracker was changed.
- The failing commit is a merge commit whose tree (`76006a4c…`) is **identical** to its first parent `207401d`; `compare/207401d...cde3430` returns `files: []`, `ahead_by: 3`, no file changes. The failure is therefore in the **accumulated tree**, not introduced by this SHA.
- Root cause: `apps/api/check.sh` runs `mvn verify`, whose `verify` phase executes JaCoCo (100% bundle gate), OWASP and **SpotBugs**. `delivery.yml:84` runs that job only when `github.event_name != 'pull_request'`; swarm PRs run only `-Pno-security,fast test` (`delivery.yml:65-80`). SpotBugs findings introduced by directly merged swarm PRs therefore never failed a PR and only broke `main`. This also invalidates the "adjacent PR runs are green so it is flaky" inference in the earlier repair note `FIX-44603355303.md`: those PRs do not run the failing job at all.
- This worker edited only its own `.agent/pr-notes/FIX-44603840708.md` plus five Java files under `apps/api/src/main/java`. It did **not** edit `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md` or `docs/swarm`; did not push, tag, call `gh`, or use a reserved migration version.

## Requirement IDs and acceptance mapping

| ID / gate | Mapping | Evidence |
|---|---|---|
| OPS-06, OPS-02 | `mvn verify` static-security gate: SpotBugs `4.10.5.0`, `effort=Max`, `threshold=Medium`, `spotbugs-check` bound to `verify`; failing gate is job `135842262141`, step 4 `Run ./.github/actions/java-verify` (19:59:21–20:00:35Z) | `apps/api/pom.xml:132-149`, `.github/actions/java-verify/action.yml:30-35`, jobs API |
| CLN-01 / G1 | `appointment_clinician_slot_excl` (V8) is the protected-slot authority; the logged exclusion violation is expected and translated to `409 SLOT_CONFLICT` | `db/migrations/V8__scheduling.sql:135-141`, `SqlFailureTranslator.java:31-40`, `SchedulingBookingTest.java:384-429` |
| IAM-05 | `IdentityProofingService.hasAccess` Boolean-returning lambda had an explicit `null` return path | file line ~320; fixed to `Boolean.FALSE` (denial is still thrown by the recorded `failure`) |
| MSG-05 | `InstructionFactController` had a now-unnecessary `EI_EXPOSE_REP2` suppression | `US_USELESS_SUPPRESSION_ON_METHOD` finding; annotation removed |
| MSG-02 | `ConversationRepository.nextSequence` unboxed a nullable `Integer` | `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`; fail-closed via `Objects.requireNonNull` |
| RX-02 | `RxController` constructor needed the standard Spring-collaborator suppression; `RxRepository.submissionAttempts` unboxed a nullable `Integer` | `EI_EXPOSE_REP2` and `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`; fixed |
| ARC-03, ARC-04, PRD-06 | No API contract, state or authorization behavior changes: every edit is behavior-preserving or fail-closed | diff review; 1760 fast tests green |

## Root cause and fix (red → green)

Failing command reproduced locally on the merged tree (before the fix):

```
cd apps/api && mvn -q -B -Pno-security -Dspotbugs.skip=false spotbugs:check
```

Result: **exit 1**, `failed with 5 bugs and 0 errors`:

1. `US_USELESS_SUPPRESSION_ON_METHOD` — `InstructionFactController` constructor (lines 44-46).
2. `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` — `ConversationRepository.nextSequence` (line 180).
3. `NP_BOOLEAN_RETURN_NULL` — `IdentityProofingService.lambda$hasAccess$5` (line 320).
4. `EI_EXPOSE_REP2` — `RxController` constructor (line 47).
5. `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE` — `RxRepository.submissionAttempts` (line 309).

Confirmed that findings 3 and 4 exist at the failing commit itself by reading `raw.githubusercontent.com/.../cde3430/.../IdentityProofingService.java` (`Boolean access = transactions.execute(... return null ...)`) and `.../RxController.java` (`this.service = service;` with no suppression). SpotBugs runs in `verify`, so `mvn verify` on `cde3430` fails at this gate.

Smallest fixes (5 files, 15 insertions / 7 deletions):
- remove the useless suppression on `InstructionFactController`;
- add the house `@SuppressFBWarnings(EI_EXPOSE_REP2, "Spring-managed singleton collaborator")` to `RxController`;
- `return Boolean.FALSE` instead of `null` in the `hasAccess` denial branch (the recorded denial is still thrown; existing denial tests cover the branch);
- `Objects.requireNonNull` on the `Integer` aggregate results in `ConversationRepository.nextSequence` and `RxRepository.submissionAttempts` (no new branch, so JaCoCo 100% is preserved; fail-closed if a driver ever violates the one-row contract).

## Exact commands and results

Evidence source for every row: **executed tool command** or **read-only GitHub REST API via tool request**. No reviewer, simulated-user or human/clinical evidence was used.

| Command / request | Result |
|---|---|
| `GET /actions/runs/44603840708` | run 270, SHA `cde3430`, `workflow_dispatch`, conclusion `failure`; policy/frontend/docs success, `java-verify` failure, image/release skipped |
| `GET /actions/runs/44603840708/jobs` | job `135842262141` "Java and database verification" failed at step 4 `Run ./.github/actions/java-verify` 19:59:21→20:00:35Z |
| `GET /check-runs/135842262141/annotations` | only a generic "Process completed with exit code 1" plus two Node/setup-java deprecation warnings — **no test name, assertion or Maven error** |
| `GET /check-runs/135842262141` | `output.title/summary/text` all `null` |
| `GET /actions/runs/44603840708/artifacts` | `java-verification-reports` artifact id `14569033203` exists; download returns **401** (auth-gated) |
| `GET /actions/jobs/135842262141/logs` | **403**; job HTML says "Sign in to view logs" |
| `GET /commits/cde3430ce914` and `compare/207401d...cde3430` | merge commit, `files: []`, tree identical to first parent — no regression from this SHA |
| `GET /actions/workflows/delivery.yml/runs` (pages 1–3) | last green full main run n185 at 17:23Z; every main run from ~18:49Z to n370 failed; PR runs succeed but skip `java-verify` |
| `java -version`, `mvn -version` | Temurin 21.0.13, Maven 3.10.0 — matches CI baseline |
| `mvn -q -B -Pno-security,fast test` (apps/api, before and after fix) | **exit 0** both times; 1760 tests, 0 failures, 0 errors, 0 skipped (parsed `target/surefire-reports/*.txt`) |
| `mvn -q -B -Pno-security -Dspotbugs.skip=false spotbugs:check` (apps/api) | **exit 1, 5 findings** before fix → **exit 0** after fix |
| `git diff --stat` | 5 Java files, 15 insertions, 7 deletions; no trackers, workflows, docs or migrations touched |

## Classification of the supplied failing log tail

| Log line | Classification |
|---|---|
| `ERROR: conflicting key value violates exclusion constraint "appointment_clinician_slot_excl"` + `DETAIL`/`STATEMENT` INSERT into `public.appointment` | Expected: protected-slot acceptance tests force a collision; PostgreSQL logs it at ERROR severity even when the app translates it to `409 SLOT_CONFLICT`. Not the failure. |
| `FATAL: terminating connection due to administrator command` (x2) | Expected: integration classes drop scratch databases with `DROP DATABASE ... WITH (FORCE)`. |
| `LOG: checkpoint starting/complete: immediate force wait` | Expected: forced drop / service shutdown checkpoint. |
| `Stop and remove container`, `docker rm`, `network rm`, `Cleaning up orphan processes` | Normal service-container teardown. |
| `Node.js 20 is deprecated …`, `setup-java v4 is deprecated …` | Warnings (confirmed by annotations API), not failures. |

No assertion failure, failing test name or Maven error appears in the tail; the failure detail is in an authentication-gated log. The SpotBugs finding is the concrete reproducible gate failure that explains why main fails and PRs pass.

## Defects found and decision

- **Real defect found and fixed:** five SpotBugs findings in production code fail the `mvn verify` gate on `main`. Two of them are present at the failing commit itself.
- **Decision:** make the smallest behavior-preserving fix (above). Do not change `delivery.yml` or `scripts/quick-check.sh` in this repair: adding SpotBugs to the PR fast tier is the durable follow-up, but it touches protected CI files and belongs to an owner-approved change. Recorded here for the orchestrator.
- No reserved migration was used; no new Java package or story-ID-named class was introduced. All fixtures and notes are synthetic — no patient data or credentials.

## Evidence genuinely missing (stop-and-report items)

1. **The full job log is authentication-gated** (`403` on the logs API; HTML requires sign-in) and the `java-verification-reports` artifact download returns `401`. Therefore I cannot prove which verify-phase gate (JaCoCo, OWASP or SpotBugs) aborted run 270 first. SpotBugs is proven to fail on this tree and at `cde3430`; JaCoCo and OWASP could not be executed locally (no PostgreSQL, no NVD network access). The integrator must run the full `scripts/check.sh` + `docs.sh` gate on the merged SHA.
2. **No local PostgreSQL/Docker**: `psql`, `pg_isready`, `postgres`, `initdb` and `docker` are absent and port 5432 is closed, so `bash apps/api/check.sh`, `bash db/check.sh` and the `@Tag("integration")` suites could not be run here. The user assigned those to the integrator.
3. **Policy tooling is partially unavailable in this worker**: `scripts/quick-check.sh` stops at `check-pack` because the worktree `opencode.json` is the CI profile under `skip-worktree` (confirmed: worktree hash equals `.github/opencode.ci.json`, while HEAD has the local profile — the documented IMP-01 / OPS-CW-003-01 artifact). The remaining Python tests fail from missing `PyYAML` (`ModuleNotFoundError: yaml`) and sandbox git/`gh` restrictions in `test_agent_run.py`; none of these touch the changed Java files. `bash scripts/docs.sh` cannot run because Doxygen is not installed locally.
4. **No same-SHA CI re-run was available** to replay run 270 directly. The systematic main-only failure pattern was reconstructed from the read-only Actions API.

## Next action for the integrator / owner

- Run `scripts/integrator-gate.sh` (`check.sh` + `docs.sh`) with the PostgreSQL service and NVD cache on the merge result. If any other verify-phase gate fails, attach the surefire/JaCoCo/OWASP report and reopen with the exact failing gate.
- Consider a separate owner-approved change that adds SpotBugs to the PR fast tier so a finding cannot reach `main` again (the present gate skips `java-verify` on `pull_request`, which is why swarm PRs stayed green).
- Evidence provenance: CI facts from read-only GitHub Actions REST API via tools; local results from executed shell commands; source classification from static file reads. **All evidence is tool-generated; no reviewer, simulated-user, human participant or clinical evidence was used.**
