# START-55 · API: negative-path and tenant-isolation tests for the package `lab`

PR #1823, tested branch tip f316ac8a3dc3d376d42ab3da88ce968410775e36.

Task: START-55

Swarm worker 4. Checks: passed. Local checks: failed. Agent result: ok.

# START-55 — API: negative-path and tenant-isolation tests for the package `lab`

## Summary

Added one test-only class, `apps/api/src/test/java/com/aurevia/lab/LabNegativePathsTest.java`
(package `com.aurevia.lab`, 10 tests). It drives the real `LabAccessionController` and real
`LabAccessionService` through standalone MockMvc with the tenant interceptor and uniform error
advice, proving refusal of malformed input, wrong roles, a peer-tenant identifier, stale versions,
a repeated keyed command, patient mismatch and an illegal transition at the HTTP boundary. No
production code, existing test, contract, migration or dependency was changed.

The task text and task file say the scope path is "absent in this snapshot and needs confirmation".
The senior-developer brief names the exact path and package as the planned new file, so that is the
maintainer confirmation; a repo-wide grep showed no stale reference outside the task file. The
plan reviewer reviewed a working-tree draft (its "file already exists" note refers to that draft,
not to an earlier attempt) and its other Required gaps were already satisfied or are recorded below.

## Affected requirements

- LAB-01 — accession identity, patient/accession matching, specimen lifecycle.
- ARC-02 — tenant isolation; peer tenant is concealed as `NOT_FOUND` and the denial is audited.
- Supporting machine-code semantics come from ARC-03/ARC-04; the task's `requirements:` field lists
  only LAB-01 and ARC-02. Recorded as tag drift, not edited by a worker.

## Risk class

Cross-boundary by subject (HTTP, policy, tenant isolation) but test-only: no runtime behavior,
route, schema or contract changes. Read-only synthetic fixtures throughout.

## Review owner

Senior Java and Spring Boot; read-only plan, code, coverage, security and QA reviewers.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix.

## Authorization boundaries and audit events

Synthetic subjects carry `X-Synthetic-Subject` and `X-Active-Organization` under the
`synthetic-auth` profile. Roles are `lab_collector` and `lab_verifier` only. Audit assertions are
limited to outcomes the service actually writes: `DENIED` for wrong role and inactive membership,
and `NOT_FOUND` for the concealed peer read on the GET route. The service writes no audit row for
`STALE_VERSION`, `PATIENT_ACCESSION_MISMATCH`, `INVALID_TRANSITION` or a 404 from `transition()`,
so the new tests do not claim one.

## Migration and rollout considerations

None. V26 already applies forced RLS to `lab_accession` and `lab_accession_event`. The mocked
repository models the empty peer fold; real RLS remains the database integration suite's evidence.

## Test evidence

Entry-point-to-test trace: `POST /api/v1/lab/accessions` and `/{id}/collect`, `/{id}/receive`,
`GET /api/v1/lab/accessions/{id}` → `LabAccessionController` → `LabAccessionService`
→ `LabInputs` / `LabActionPolicy` / `authorize()` / `match()` / `requireVersion()` /
`requireState()` → mocked `LabAccessionRepository` and `AuditAppender` → `ApiErrorHandler`
→ asserted HTTP status, `$.code` and raw-body absence.

Tool evidence (all commands run from the repository root; output under `/tmp/aurevia-start55.*`):

```
$ git merge --no-edit origin/main
Already up to date.

$ mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=AccessionListControllerTest test
rc=0; Surefire: tests="5" errors="0" skipped="0" failures="0"

$ mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=LabNegativePathsTest test
rc=0; Surefire TEST-com.aurevia.lab.LabNegativePathsTest.xml:
tests="10" errors="0" skipped="0" failures="0" flakes="0"

$ mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='com.aurevia.lab.**' test
rc=0; 11 Surefire suites under com.aurevia.lab: 81 tests, 0 errors, 0 failures, 0 skipped

$ mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check
rc=0; apps/api/target/spotbugsXml.xml contains 0 <BugInstance> elements

$ python3 -I scripts/check-pack.py
rc=0
$ python3 -I scripts/check-docs.py
rc=0
$ python3 -I scripts/check-secrets.py
rc=0
$ python3 -I scripts/check-names.py
rc=0
$ python3 -I scripts/check-wiring.py
rc=0
$ python3 -I scripts/check-constraints.py
rc=0
$ python3 -I scripts/check-migrations.py --require-baseline
rc=0
$ python3 -I scripts/gen-opencode.py --check
rc=0

$ python3 -I -m unittest discover -s scripts/tests
Ran 2494 tests in 88.088s — FAILED (failures=5, skipped=1)
```

Those five failures were present before and are independent of this test-only change (tool evidence,
not simulated-user or human feedback):

1. `test_check_pack.PackTests.test_committed_opencode_json_is_generated`
2. `test_check_pack.PackTests.test_simulated_user_skills_need_no_process_sections`
3. `test_check_pack.PackTests.test_system_namespace_skill_is_not_a_project_skill`

   All three read the worktree `.opencode/opencode.json`, which in this cloud worker is the CI
   profile installed under the skip-worktree bit (`git ls-files -v` reports `S`). The authoritative
   skip-worktree-aware checks `python3 -I scripts/check-pack.py` and
   `python3 -I scripts/gen-opencode.py --check` both exit 0; no generated file was edited.

4. `test_notes_files.NotesFileTests.test_daily_archives_keep_the_documented_dated_shape` — the test
   inspects *tracked* files under `.agents/notes/archive/`; this sparse snapshot has zips on disk
   but none tracked. No notes-file change by this task caused it.

5. `test_process_env.ProductionBoundaryTest.test_run_gate_reaps_a_started_background_descendant` —
   "background descendant 45650 survived gate cleanup", a sandbox process-reaping limit.

`bash scripts/worker-check.sh`, `scripts/check.sh`, `mvn verify` and `scripts/coverage-check.sh`
were intentionally not run: the brief limits a worker to targeted suites, and `worker-check.sh`
delegates to the full unit suite, frontend gates and PostgreSQL integration suites. The individual
cheap policy stages, targeted Java suites, compile-then-SpotBugs and Python unit tests were run
instead and are recorded above.

Tested revision: e3824145dbff812d560cf742736638e2b12bbf64

## Coverage

No production file was added or changed, so the 100% JaCoCo gate is unaffected; no threshold,
exclusion, suppression or skip was introduced. Coverage command for the touched area is the
targeted Surefire run above (`tests="10" errors="0" skipped="0" failures="0"`). Each production
branch the new class exercises is named below with the asserting test:

| Branch / failure path | Test |
|---|---|
| `LabInputs.require`: null accession number; pattern mismatch at 65 chars | `missingAndOversizedNumberAndMalformedIdAreRejected` |
| `LabInputs.id`: malformed path id → `INVALID_ID` | `missingAndOversizedNumberAndMalformedIdAreRejected` |
| `ApiErrorHandler.handleUnreadableBody`: malformed JSON → 400 | `missingAndOversizedNumberAndMalformedIdAreRejected` |
| `LabInputs.instant`: null branch; `DateTimeParseException` branch | `missingAndUnparseableCollectionTimeAreRejected` |
| `LabInputs.version`: value `< 1` | `zeroExpectedVersionIsRejected` |
| `LabActionPolicy.allows`: unknown role reception; collector denied receive | `wrongRoleAndInactiveMembershipAreForbiddenOnReceive` |
| `LabAccessionService.authorize`: no active membership; no permitted role | `wrongRoleAndInactiveMembershipAreForbiddenOnReceive` |
| `get()`: found row (owner control) and empty fold (peer) → 404 concealment + audit | `peerTenantReadIsConcealedAsNotFound` |
| `requireVersion`: row version ahead of precondition | `versionBehindTheRowIsStale` |
| `transition()`: `updated == 0` lost compare-and-set | `lostCompareAndSetIsStale` |
| `accession()`: `accessionExists` true after one successful create, no second insert/event | `repeatedKeyedCommandConflictsWithoutReplay` |
| `match()`: patient identity differs from the stored requisition | `mismatchedPatientIsRejecte
