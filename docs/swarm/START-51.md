# START-51 · API: negative-path and tenant-isolation tests for the package `documents`

PR #1815, tested branch tip 1dcee21d597fa42809b3005b2c512d924b4148bb.

Task: START-51

Swarm worker 4. Checks: passed. Local checks: failed. Agent result: ok.

# START-51 — Negative-path and tenant-isolation tests for `documents`

## Summary

Add one new test class, `apps/api/src/test/java/com/aurevia/documents/DocumentsNegativePathsTest.java`, recording **assertion-strengthening
evidence** for the existing `documents` API: machine-readable error codes at the real controller boundary, exact audit outcomes for
denials and foreign-tenant reads, branch-specific compare-and-set failures, and `verify`-level proof that content-based retries do
not write twice. This story adds evidence only: no production code, route, contract, migration, dependency, threshold or existing
test changes. The pre-code reviews established that the production branches are already covered elsewhere; this class raises the
strength of the assertions rather than duplicating branch coverage.

## Affected requirements

CLN-07 (link originals, retain bytes, void with reason, versioned PDFs), ARC-02 (tenant context from authentication, policy in
application services, fail closed when context or membership is absent).

## Risk class

Cross-boundary verification of two HTTP entry points (`DocumentController`, `DocumentArtifactController`) and their domain
services. Safety-critical evidence for tenant isolation and wrong-role denial; no production boundary is changed.

## Review owner

Orchestrator / integrator. Read-only reviewers: plan-reviewer, test-engineer, senior-backend (pre-code); code-reviewer,
coverage-auditor, devsecops and qa-reviewer (post-code).

## Data changes

None. No Flyway migration, RLS change, backfill, table or contract edit. All fixtures are synthetic UUIDs and payload metadata;
no patient identifiers or credentials.

## Authorization boundaries and audit events

No new boundary. Tests assert the existing behaviour: `DocumentService.authorize` denies absent membership and missing role with a
`DENIED` audit and 403; a foreign-tenant identifier resolves empty and yields 404 with a `NOT_FOUND` audit and never a `SUCCESS`
audit; `DocumentArtifactController.actor()` fails closed for a patient role (403) and a null context (401); `PdfBindingService.validateRevision`
records a 403 `WRONG_TENANT` `ACCESS_DENIED` fact carrying only the synthetic revision identifier and no payload, checksum or foreign
organization.

## Migration and rollout considerations

None — test-only change on the existing branch.

## Skills loaded

- aurevia-delivery — every story; drives the test-first, evidence and review loop.
- aurevia-senior-backend — Java/Spring controllers and services under `apps/api/src/main/java/com/aurevia/documents`.
- aurevia-test-engineer — new negative-path JUnit 5 tests and branch-to-test mapping.
- aurevia-plan-reviewer — mandatory pre-code plan review.
- aurevia-code-reviewer — post-code correctness/reachability review.
- aurevia-coverage-auditor — branch-to-test coverage evidence for the new test class.
- aurevia-devsecops — wrong-role, other-tenant and PHI-free-audit assertions.

No additional skill matched: this is a Java test-only change with no script, frontend, migration, CI or real patient-data slice.

## Test evidence

Baseline harness check (tool evidence, before any edit):
`mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=DocumentVerifiedDownloadTest -Dsurefire.failIfNoSpecifiedTests=false test`
→ exit 0; Surefire `TEST-com.aurevia.documents.DocumentVerifiedDownloadTest.xml` reports `tests="9" errors="0" skipped="0" failures="0"`.
Git reconciliation at start: `git merge --no-edit origin/main` → `Already up to date.`; no conflict markers; HEAD `8b61497c392eae0894999d7104b03ee6094cdc97`.

Tested revision: 8b61497c392eae0894999d7104b03ee6094cdc97 (`git rev-parse HEAD` at the time the checks below ran; no commit had been made).

Commands and final lines (tool evidence, captured under `/tmp/aurevia-orca.*`):

- Red run before fixes: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=DocumentsNegativePathsTest -Dsurefire.failIfNoSpecifiedTests=false test` → exit 1, `Tests run: 19, Failures: 3` (wrong audit action on rendered content, replayed bind wrongly expected to append a fact, first replay job id stubbed too early). All three were test-assertion defects; no production code was changed.
- Green targeted run, same command → exit 0; Surefire `TEST-com.aurevia.documents.DocumentsNegativePathsTest.xml` → `tests="19" errors="0" skipped="0" failures="0"`.
- Neighbour suite: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='DocumentsNegativePathsTest,DocumentVerifiedDownloadTest,DocumentServiceSubjectLinkTest,DocumentServiceSubjectRenderTest,DocumentArtifactTest,AuthorizeUploadCompletionDownloadPreferringMediatedDownloadsTest' -Dsurefire.failIfNoSpecifiedTests=false test` → `neighbour_exit=0`; Surefire XMLs report 38/6/9/9/19/15 tests with 0 errors, 0 skipped, 0 failures (96 tests).
- SpotBugs after compile: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → `spotbugs_exit=0` (0 findings).
- Wiring: `python3 -I scripts/check-wiring.py` → `wiring_exit=0`, `{"violations": []}`.
- Python tooling suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 2460 tests in 157.740s`, `FAILED (failures=5, skipped=1)`. All five are pre-existing worker-image artifacts, not caused by this test-only change: three `test_check_pack` cases read the worktree `.opencode/opencode.json` while this worker has the CI profile installed under skip-worktree; `test_daily_archives_keep_the_documented_dated_shape` asserts `.agents/notes/archive` exists (the directory is absent in this sparse worker); `test_run_gate_reaps_a_started_background_descendant` reports a surviving child process. The first four classes are already recorded as worker-image limits in `.agents/reports/2026-11-26.md` Findings; the missing archive directory was confirmed directly with `ls -d .agents/notes/archive` (no such directory in this checkout). Authoritative checks run individually all exit 0: `python3 -I scripts/gen-opencode.py --check`, `python3 -I scripts/check-pack.py`, `python3 -I scripts/check-constraints.py`, `python3 -I scripts/check-names.py`, `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-docs.py`, `git diff --check`. No threshold, suppression, skip or exclusion was added.
- Entry-point-to-test trace: `POST /api/v1/documents/associations` → `DocumentController.link` → `DocumentService` → `DocumentInputs` (8 code assertions, 3 tests); `POST .../void` → `DocumentController.voidAssociation` → `DocumentService.authorize`/`voidAssociation` (3 tests); `GET .../associations/{id}`, `GET .../rendered/{id}`, `GET .../rendered/{id}/content` → `DocumentController` → `DocumentService` (3 concealment tests); `POST /api/v1/documents/artifacts/renders|bindings|releases` → `DocumentArtifactController` → `PdfBindingService`/`DocumentGuard` (6 tests).

No frontend is touched, so `apps/patient-mobile/check.sh` and `apps/staff-web/check.sh` are not applicable.

## Coverage

No production file was changed: the only in-scope file is a new JUnit test class, which JaCoCo does not instrument. The 100% line/branch/method/class thresholds and exclusion list are untouched (`scripts/check-constraints.py` exit 0), and the full `mvn verify` JaCoCo gate remains the integrator's step. Touched-area command and result (also under `## Test evidence`): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=DocumentsNegativePathsTest -Dsurefire.failIfNoSpecifiedTests=false test` → exit 0, `tests="19" errors="0" skipped="0" failures="0"`. Every branch newly *asserted* here is already covered by neighbours; this class adds status+code, audit-outcome, call-count and branch-specific assertions. Branch-to-test map:

| Production branch | Test |
|---|---|
| `DocumentInputs.uuid` null / malformed → 422 INVALID_ID | `linkRefusesMalformedSelectorsWithStableCodes` |
| `DocumentInputs.subjectType` null / unsupported → 422 INVALID_SUBJECT_TYPE 
