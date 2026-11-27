# START-48 · API: negative-path and tenant-isolation tests for the package `clinical`

PR #1802, tested branch tip 022a3d69cad0eb3e3c30ca33e0ec5eaf710b3d3e.

Task: START-48

Swarm worker 4. Checks: passed. Local checks: failed. Agent result: ok.

# START-48 · API negative-path and tenant-isolation tests for `clinical`

Tested revision: 1318e3ba775b1ac14bf6be1151eb9e4bcbf98160

## Summary

One new test class at `apps/api/src/test/java/com/aurevia/clinical/ClinicalNegativePathsTest.java` (package `com.aurevia.clinical`) locks today's controller-boundary denial semantics for malformed create input, wrong-role sign, another tenant's note id, stale/malformed/missing `If-Match`, and a repeated keyed create. It drives the real `ClinicalNoteController` and `ClinicalNoteService` over a mocked `NoteRepository`, `AuditAppender`, inline `TransactionTemplate` and fixed `Clock`. No production, contract, migration or existing-test edits.

## Affected requirements

- ARC-02 (isolation: fail closed, conceal inaccessible ids, audit NOT_FOUND in the caller's tenant).
- ARC-03 (version preconditions and retry keys): the stale/parse cases are asserted; replay remains an owned gap (see Findings and `task-requests`).
- CLN-06 is named on the task, but `docs/reference/modules/api-clinical.md:5` states CLN-06 follow-up suggestions live in `scheduling/followup` + `V74`, not in this package. There is no follow-up entry point here, so this class asserts no follow-up behavior.

## Risk class

Safety-critical domain (authorization, tenant isolation, clinical note writes), but the change is test-only: no production behavior is altered.

## Review owner

Primary agent (orca); read-only specialist verdicts recorded under Review verdicts.

## Data changes

None. No migration, backfill, lock or volume impact; no rollback or forward-fix needed.

## Authorization boundaries and audit events

No new audit events. The tests assert today's `NOTE_SIGN/DENIED`, `NOTE_READ/NOT_FOUND`, `NOTE_EDIT/NOT_FOUND` and `NOTE_*/CONFLICT` facts under an explicitly bound synthetic tenant context. All fixtures use synthetic UUIDs, subjects and text; no patient data or credentials appear anywhere.

## Migration and rollout considerations

None.

## Clinical and architecture review

Test-only change: no signed data, release, order or patient-association behavior is altered, and no clinical or site sign-off is required. The assertions lock existing safety semantics (403/DENIED, 404/NOT_FOUND concealment, 412/STALE_VERSION) for review; no clinical workflow is enabled or changed. Architecture impact is limited to a new test class in the existing business-domain package; controller, service, migration and contract are untouched.

## Skills loaded

- `aurevia-delivery` — every story (delivery loop and swarm hand-off).
- `aurevia-senior-backend` — Java/Spring controller and service boundary.
- `aurevia-senior-java-build` — Maven, SpotBugs and Java convention gates.
- `aurevia-context-optimizer` — bounded discovery and requirement mapping.
- `aurevia-test-engineer` — negative test matrix and red-proof method.
- `aurevia-plan-reviewer` — pre-coding plan review.
- `aurevia-code-reviewer`, `aurevia-coverage-auditor`, `aurevia-devsecops` — post-code reviews.
- `aurevia-senior-spring-security` — authorization and audit denial semantics.
- No additional skill matched the story scope.

## Test evidence

All commands ran from the repository root on the branch `swarm/START-48-1738`. Captured logs are under `/tmp` only; no scratch file was written in the worktree.

- `git merge --no-edit origin/main` → `Already up to date.`; no conflict markers (`git grep -nE '^(<<<<<<<|>>>>>>>) ' -- .` produced no output).
- `mvn -f apps/api/pom.xml -Pno-security -Dtest=ClinicalNegativePathsTest -DfailIfNoTests=false test` → exit 0; `[INFO] Running com.aurevia.clinical.ClinicalNegativePathsTest`; `[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`; `[INFO] BUILD SUCCESS`. Surefire report `TEST-com.aurevia.clinical.ClinicalNegativePathsTest.xml`: `tests="5" errors="0" skipped="0" failures="0" flakes="0"`.
- Assertion sensitivity proof (test-only change, no production mutation): temporarily changed one expected code in the new test from `INVALID_LANGUAGE` to `RED_PROOF_NOT_THE_REAL_CODE` and reran the same command → exit 1, `Tests run: 5, Failures: 1, Errors: 0, Skipped: 0`, `expected: <RED_PROOF_NOT_THE_REAL_CODE> but was: <INVALID_LANGUAGE>` at `ClinicalNegativePathsTest.java:143`. Restored the exact original expectation and reran → exit 0, `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`. No failing test, suppression or production edit remains.
- `mvn -f apps/api/pom.xml -Pno-security -Dtest=ClinicalNegativePathsTest,ClinicalNoteServiceTest,ClinicalNoteControllerTest -DfailIfNoTests=false test` → exit 0; `[INFO] Tests run: 44, Failures: 0, Errors: 0, Skipped: 0`; `[INFO] BUILD SUCCESS` (the new class runs cleanly beside its neighbouring clinical suites).
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0, no findings. Compile ran first, so the check is not vacuously green.
- `python3 -I scripts/gen-opencode.py --check` → exit 0 (no output). `python3 -I scripts/check-pack.py` → exit 0, `Delivery tooling validated: 39 agents, 39 skills`. `git ls-files -v .opencode/opencode.json` → `S .opencode/opencode.json` (the cloud worker's skip-worktree CI profile).
- `python3 -I -m unittest discover -s scripts/tests` → exit 1, `Ran 2457 tests in 124.117s`, `FAILED (failures=5, skipped=1)`. The five failures are unrelated to a new Java test file: three `test_check_pack` cases read the skip-worktree worktree copy of `.opencode/opencode.json` directly (CI profile differs from the interactive generator render) while the authoritative skip-worktree-aware checks above both pass; one `test_notes_files` daily-archive shape check failed in the batch but passed when rerun alone (`python3 -I -m unittest discover -s scripts/tests -p test_notes_files.py` → `OK`); one `test_process_env.test_run_gate_reaps_a_started_background_descendant` failed with `background descendant 44306 survived gate cleanup` (sandbox process reaping) and its module rerun (`python3 -I -m unittest discover -s scripts/tests -p test_process_env.py` → `FAILED (failures=1)`). The config module was also rerun alone: `python3 -I -m unittest discover -s scripts/tests -p test_check_pack.py` → `FAILED (failures=3)`, all three naming the same worktree-profile stale-config message. No protected file was edited to make these pass; worker scope forbids `.opencode/` and `scripts/` edits.
- Final rerun after a comment-only edit to the test file: `mvn -f apps/api/pom.xml -Pno-security -Dtest=ClinicalNegativePathsTest -DfailIfNoTests=false test` → exit 0, `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; `git merge --no-edit origin/main` → `Already up to date.`
- `python3 -I scripts/check-wiring.py` → exit 0 (no output); test-only change, no new wiring and no baseline write.
- `git status --short` → only `?? .agents/notes/START-48-20261127-121247.md` and `?? apps/api/src/test/java/com/aurevia/clinical/ClinicalNegativePathsTest.java`.

Entry-point-to-test trace: `ClinicalNoteController.create/edit/get/sign` → real `ClinicalNoteService` → mocked `NoteRepository`/`AuditAppender` → assertions on status, code, audit outcome and no-write. `scripts/worker-check.sh` was not run: it delegates to full API integration suites, which the worker prompt forbids (ten-minute timeout); the four targeted commands above substitute for the API tier in this worker.

Evidence sources: executed tool commands (tool evidence) and bounded read-only specialist reports (reviewer evidence). No simulated-user walkthrough or authorized human feedback was available or claimed.

## Coverage

No production line, branch, method or class changed, so JaCoCo cannot regress and no new production branch exists. Full `mvn verify`/`scripts/coverage-check.sh` is integrator-owned; per `aurevia-senior-java-build` a worker must not run it. The new file is a test source and is outside JaCoCo's productio
