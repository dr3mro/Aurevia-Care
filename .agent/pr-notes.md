# AUD-20261120-15 — Remove the duplicate legacy chart slice and its unhardened write path

## Requirements and acceptance mapping

| Requirement | Acceptance | Evidence |
|---|---|---|
| CLN-03 (clinical author separate from data entry actor) | A recorder-open entry verified through the surviving public route gains an accountable clinical author in both the response and the stored row | `verificationOfRecorderOpenEntryAssignsAccountableClinicalAuthor` (HTTP + SQL assertion) |
| CLN-03 (bounded, provenance-safe chart writes) | Only the hardened slice remains reachable; the duplicate unhardened write path is gone | `legacyChartWriteRouteIsRemoved` (404 on both legacy paths); surviving slice keeps `requireBounded`, `normalizedReason` and `COALESCE` |
| ARC-01 / ARC-02 | One chart contract writing the tenant-scoped `public.chart_entry` table under forced RLS | Integration test reads the row as `smartclinic_migrate` with `app.current_org` bound; 2324-test `mvn verify` green |
| ARC-03 / OPS-02 | Version-bound verification and audit on the surviving route | Existing `ChartRecordIntegrationTest.verificationIsVersionBoundAndConcurrent` and audit-chain assertions remain green |

## Change

- Deleted the whole duplicate `apps/api/src/main/java/com/aurevia/smartclinic/patient/chart` package (11 classes) and its mirror tests (`apps/api/src/test/java/com/aurevia/smartclinic/patient/chart`, `apps/api/src/test/java/com/aurevia/smartclinic/it/patientchart`, including the duplicate `V13__longitudinal_chart.sql`). The duplicate exposed `POST /api/v1/chart/entries` and `GET /api/v1/chart/patients/{id}/entries` without bounded-text validation and without the clinical-author COALESCE on verification.
- Kept the hardened S-G1-03 slice (`chart.record`) and its `/api/v1/chart/records` contract as the single surviving public route. No hardening logic was changed; deleting the duplicate consolidates it.
- Removed the now-stale explicit bean names (`chartRecordService`, `chartRecordRepository`, `chartRecordController`, `chartRecordActionPolicy`) and their "a legacy chart slice shares the default name" comments from the four surviving classes. A repo-wide search found no reference to those names outside the deleted comments.
- Added two tests to `apps/api/src/test/java/com/aurevia/smartclinic/it/chart/record/ChartRecordIntegrationTest.java`:
  1. `legacyChartWriteRouteIsRemoved` — asserts both legacy paths now return 404.
  2. `verificationOfRecorderOpenEntryAssignsAccountableClinicalAuthor` — records a VITAL as `assistant-a` with no `clinicalAuthor`, verifies as `clinician-a` at `If-Match: "1"`, and asserts response and persisted `clinical_author`/`verified_by` are `clinician-a` while `dataEntryActor` stays `assistant-a`.

### Scope note

The task `scope` names only the two `src/main/java` domain paths. The story explicitly requires failing tests first and an integration test, and deleting production classes makes their mirror tests uncompilable, so the test edits are confined to the mirrored chart test packages (`it/chart/record`, `patient/chart`, `it/patientchart`). No file outside the chart domain was changed. `db/migrations`, trackers, workflows, agents and generated config were not touched.

## Failing test first (red → green)

- RED command: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=ChartRecordIntegrationTest test`
- RED result: `Tests run: 7, Failures: 1, Errors: 0, Skipped: 0` — `legacyChartWriteRouteIsRemoved` failed at line 427: `expected: <404 NOT_FOUND> but was: <201 CREATED>` (the legacy route was still live). The new null-author test passed but is the required acceptance evidence.
- GREEN command: identical.
- GREEN result: `Tests run: 7, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.

## Commands run and results (all observed via executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | `BUILD SUCCESS`; Surefire reports total: **1887 tests, 0 failures, 0 errors, 0 skipped** (fast JVM unit tier) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | `Tests run: 2324, Failures: 0, Errors: 0, Skipped: 0`; `jacoco:check` → **"All coverage checks have been met."** (773 classes); `BUILD SUCCESS` |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security clean verify` | Exit 0; Surefire totals `Tests run: 2324 Failures: 0 Errors: 0 Skipped: 0`; packaged jar contains no legacy `patient/chart` class (only an empty directory entry). Rules out stale build artifacts. |
| SpotBugs: `mvn -B -f apps/api/pom.xml -DskipTests -Dowasp.skip=true spotbugs:check` | `BugInstance size is 0`, `Error size is 0`, `No errors/warnings found`, `BUILD SUCCESS` |
| Per-file JaCoCo XML inspection (`apps/api/target/site/jacoco/jacoco.xml`, package `com.aurevia.smartclinic.chart.record`) | All 11 survivors 100% on every counter present: `ChartController` INSTRUCTION 36/36, LINE 8/8, METHOD 5/5, CLASS 1/1; `ChartService` 936/936, 196/196, BRANCH 112/112, 17/17, 1/1; `ChartRepository` 378/378, 85/85, BRANCH 6/6, 15/15, 1/1; `ChartActionPolicy` 35/35, 10/10, BRANCH 7/7, 3/3, 1/1; `ChartEntry`, `ChartView`, `RecordEntryCommand`, `EntryType`, `EntrySource`, `PresenceState`, `VerificationState` all 100%. No deleted `patient.chart` class appears in the report. |
| `bash -n` + `shellcheck` on scripts, `scripts/project-name.py`, `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` | All passed (no findings) |
| `python3 -I -m unittest discover -s scripts/tests -q` | **FAILED for worker-environment reasons only**: `Ran 271 tests, failures=8, errors=10`. 10 errors are `ModuleNotFoundError: No module named 'yaml'` (PyYAML absent in this image); 3 failures are `check_pack` reading the worktree CI `opencode.json` (see below); the rest are `test_agent_run` cases whose fake scripts call the same failing checks and a local git push rejected by the worker's git config. No Python file was changed by this story; no failure touches chart code. |

### Coverage rule compliance

No production line or branch was added: the change is a deletion plus removal of four annotation/comment lines and two test methods. Every surviving production branch is still covered — per-file 100% is proven above, not just the bundle gate. No threshold, exclusion, `@Disabled`, `.skip` or generated-file rule was changed.

## New or changed branches and their tests

| Branch / path | Test |
|---|---|
| No new Java branch (deletion + annotation/comment removal only) | n/a |
| Existing `ChartService.verified()` ternary, null-author side — `entry.clinicalAuthor() == null ? actor : ...` | New `verificationOfRecorderOpenEntryAssignsAccountableClinicalAuthor` (HTTP + DB) and existing unit test `verifyAssignsAccountableClinicalAuthorWhenRecorderLeftItOpen` |
| Existing `ChartService.verified()` ternary, non-null side | Existing unit test `verifySuccessReturnsVerifiedEntryAtNextVersion` |
| Existing `ChartRepository.verify()` SQL `COALESCE(clinical_author, :actor)` (not a Java branch) | New `verificationOfRecorderOpenEntryAssignsAccountableClinicalAuthor` asserts the persisted `clinical_author` equals the verifier |
| Legacy route absence (Spring mapping, no Java branch) | New `legacyChartWriteRouteIsRemoved` |

## Known worker-environment artifacts (not defects in this change)

1. **`scripts/quick-check.sh` stops at `check-pack: opencode.json is stale`.** This worker's worktree `opencode.json` is the CI profile copied under `skip-worktree` (`git ls-files -v` shows `S`), so `check-pack.py`/`gen-opencode.py --check` judge the CI profile, not the committed local profile. I verified HEAD truth with the generator instead of regenerating or committing config (out of scope, orchestrator-owned): `HEAD:opencode.json == generator.render(local)` → True; `HEAD:.github/opencode.ci.json == generator.render(ci)` → True; worktree file == CI render → True. This is the already-closed OPS-CW-003-01 artifact. I did not edit `opencode.json`.
2. **PyYAML is not installed**, so the workflow/swarm Python tests error on import. Unrelated to this Java-only change.
3. **OWASP dependency analysis was skipped**: `dependency-check` could not transfer NVD metadata (`PKIX ... unable to find valid certification path`, HTTP 401 from Maven feeds) and reported "Skipping dependency-check". SpotBugs ran green locally; CI/integrator with the NVD cache owns the OWASP gate.

## Defects found (out of scope, for the orchestrator)

- `CREATE TABLE public.chart_entry` exists only in the test-scoped `apps/api/src/test/java/com/aurevia/smartclinic/it/chart/record/migrations/V13__longitudinal_chart.sql`; `db/migrations` has no V13. `.agent/pr-notes/S-G1-03.md` records that the integrator was supposed to sequence V13 into `db/migrations`. I left the surviving test copy intact and did not touch `db/migrations` (outside this task's scope), so production still lacks the authoritative DDL. This predates this story and is unchanged by it.

## Decisions

- Deleted rather than deprecated the duplicate: deprecation would leave the unhardened write path live and would contradict the 100%-coverage rule (dead code must be removed).
- Kept `chart.record` and `/api/v1/chart/records` as the surviving contract: it is the S-G1-03-delivered, hardened slice; the duplicate is the one the story identifies as legacy.
- Removed the explicit bean names: they existed only to disambiguate the two slices; a repo-wide search found no remaining reference.

## Evidence sources

- Tool evidence: all commands above were executed in this worker; JaCoCo XML parsed programmatically; no logs pasted into trackers.
- Reviewer evidence: bounded read-only review by the `smartclinic-senior-backend` specialist (simulated reviewer) — **no blocking findings**. It confirmed by static inspection: no dangling references to the deleted package/routes, all three CLN-03 hardening controls intact in `chart.record`, both new assertions correct with correctly bound RLS context (`app.current_org` set before the `FORCE ROW LEVEL SECURITY` read), and no bean-name/compile risk. It did not execute checks (no shell in its context); execution evidence above is mine. It noted that TODO.md:1780 is still `[-]` and must be closed by the orchestrator/integrator — tracker state, not a code defect (I am forbidden from editing TODO.md). No human, clinical or patient-participant evidence; no real patient data used (synthetic fixtures only).
