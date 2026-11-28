# START-56 · API: negative-path and tenant-isolation tests for the package `messaging`

PR #1826, tested branch tip 04106fe7aab24e76064b64beac875fba6f67a4e4.

Task: START-56

Swarm worker 4. Checks: passed. Local checks: failed. Agent result: ok.

# START-56 — API: negative-path and tenant-isolation tests for the package `messaging`

Tested revision: 6608b112589fce106e7a2d735622becac5193646

## Summary

Added one test-only class, `apps/api/src/test/java/com/aurevia/messaging/MessagingNegativePathsTest.java`
(package `com.aurevia.messaging`, seven `@Test` methods). It drives three real controller → service →
synthetic-store pipelines through `MockMvcBuilders.standaloneSetup` with `ApiErrorHandler` and
`TenantContextInterceptor` (`synthetic-auth` profile, fixed clock, synthetic UUIDs). It pins hostile
input, wrong roles, peer-tenant concealment with no owning-org audit row, stale version preconditions,
same-key replay, changed-key conflict and missing/malformed tenant headers at the HTTP boundary for MSG-01
and ARC-02. No production code, existing test, contract, migration or dependency was changed.

The task text's `docs/pr/notes/<task-id>.md` is stale wording; the prompt-named notes file wins. The
scope path was confirmed absent and is the planned new file. The brief's exception list is accurate:
`InstructionFactControllerEdgeCasesTest` already pins all six requested categories on the PUBLISH
surface, so this class deliberately adds only still-uncovered per-surface branches and the retry key's
HTTP boundary instead of duplicating those cases.

## Affected requirements

- MSG-01 — server-side validation of patient, destination and context; cross-tenant conversations stay isolated.
- ARC-02 — tenant context from authentication, policy enforced in services, peer tenant concealed as `NOT_FOUND`, missing context fails closed.
- Supporting machine codes come from ARC-03/ARC-04 (412 for stale versions, conflict for reused keys) and MSG-06/MSG-07 service surfaces; the task's `requirements:` field lists only MSG-01 and ARC-02. Recorded as tag drift, not edited by a worker.

## Risk class

Cross-boundary by subject (HTTP, policy, tenant isolation) but test-only: no runtime behavior, route,
schema or contract changes. Synthetic fixtures only.

## Review owner

Senior Java and Spring Boot. Pre-coding: context-optimizer, test-engineer, senior-backend,
senior-java-build, plan-reviewer. Post-code: code-reviewer, coverage-auditor, devsecops, qa-reviewer.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix.

## Authorization boundaries and audit events

Synthetic subjects carry `X-Synthetic-Subject` and `X-Active-Organization` under the `synthetic-auth`
profile. Roles used are `patient`, `tenant_admin` and `lab_collector`. Assertions cover
only audit facts the services write today: governance `DENIED`/`NOT_FOUND`, service-profile
`DENIED`/`NOT_FOUND`, and unchanged owning-tenant audit counts on peer reads. No patient identifier,
credential or clinical text appears in a fixture, log or assertion.

## Migration and rollout considerations

None. Existing migrations and forced RLS are untouched; the in-memory fakes model tenant-scoped folds.
Real RLS remains the database integration suite's evidence.

## Test evidence

Entry-point-to-test trace:

- `POST /api/v1/messaging-governance/{actions,exports,legal-holds}` and `GET /.../retention/{id}` → `MessagingGovernanceController` → `MessagingGovernanceService` → `InMemoryMessagingGovernanceStore` → `ApiErrorHandler`.
- `GET /api/v1/care-units/{unitId}/service-profile`, `POST /api/v1/care-units/{unitId}/service-profiles` and `.../approve` → `ServiceProfileController` → `ServiceProfileService` → `InMemoryServiceProfileStore` → `ApiErrorHandler`.
- `POST /api/v1/instructions/publish` and `/dispatch/retry` → `InstructionFactController` → `InstructionFactService` → `InMemoryInstructionFactStore` → `ApiErrorHandler`.

Tool evidence (all commands from the repository root; scratch output under `/tmp/aurevia-start56.*`):

```
$ git rev-parse HEAD
6608b112589fce106e7a2d735622becac5193646

$ mvn -q -B -s /tmp/aurevia-start56-settings.xml -f apps/api/pom.xml -Pno-security \
    -Dtest='InstructionFactControllerEdgeCasesTest,PatientAccessControllerTest,AttachmentControllerHttpTest' test
rc=0 (baseline before the new file); Surefire: EdgeCases tests=22, PatientAccess tests=5,
AttachmentHttp tests=8; errors=0 failures=0 skipped=0 on each.

RED (new file, before the two corrections):
$ mvn -q -B -s /tmp/aurevia-start56-settings.xml -f apps/api/pom.xml -Pno-security -Dtest='MessagingNegativePathsTest' test
rc=1; testCompile: "unreported exception java.lang.Exception" at setUp (fixed by declaring throws).
rc=1; Tests run: 7, Failures: 2 — otherTenantsIdentifierLooksNotFound expected 404 but was 403
(peer patient member not seeded in the profile store); replayedKeyReturnsOriginalFact compared whole
JSON bodies although the correlation id is minted per request (assert stable fact fields instead).

GREEN:
$ mvn -q -B -s /tmp/aurevia-start56-settings.xml -f apps/api/pom.xml -Pno-security -Dtest='MessagingNegativePathsTest' test
rc=0; TEST-com.aurevia.messaging.MessagingNegativePathsTest.xml:
tests="7" errors="0" skipped="0" failures="0" (equal to the 7 selected @Test methods).

$ mvn -q -B -s /tmp/aurevia-start56-settings.xml -f apps/api/pom.xml -Pno-security \
    -Dtest='MessagingNegativePathsTest,InstructionFactControllerEdgeCasesTest,PatientAccessControllerTest,AttachmentControllerHttpTest' test
rc=0; Surefire: MessagingNegativePaths 7, InstructionFactControllerEdgeCases 22,
AttachmentControllerHttp 8, PatientAccessController 5; errors=0 failures=0 skipped=0 on all four.
The same aggregate command was rerun after the review fixes with the identical counts and rc=0.

$ mvn -q -B -s /tmp/aurevia-start56-settings.xml -f apps/api/pom.xml -Pno-security \
    -Dspotbugs.skip=false -DskipTests compile spotbugs:check
rc=0; apps/api/target/spotbugsXml.xml contains 0 <BugInstance> elements (SpotBugs 4.10.5.0).
The plugin has includeTests=false, so this is static-analysis/environment evidence for the change.

$ python3 -I -m unittest discover -s scripts/tests
Ran 2494 tests in 80.375s — FAILED (failures=5, skipped=1), all five pre-existing cloud-worker
environment failures; four are recorded in .agents/reports/2026-11-26.md and the archive-shape case is
additionally recorded in START-55's notes:
test_check_pack.PackTests.test_committed_opencode_json_is_generated,
test_check_pack.PackTests.test_simulated_user_skills_need_no_process_sections,
test_check_pack.PackTests.test_system_namespace_skill_is_not_a_project_skill,
test_notes_files.NotesFileTests.test_daily_archives_keep_the_documented_dated_shape,
test_process_env.ProductionBoundaryTest.test_run_gate_reaps_a_started_background_descendant.
The first three read the worktree .opencode/opencode.json (the CI profile is installed under
skip-worktree); the fourth inspects untracked archive zips; the fifth is a sandbox process-reaping limit.

$ python3 -I scripts/check-pack.py
$ python3 -I scripts/gen-opencode.py --check
$ python3 -I scripts/check-docs.py
$ python3 -I scripts/check-secrets.py
$ python3 -I scripts/check-names.py
$ python3 -I scripts/check-wiring.py
$ python3 -I scripts/check-constraints.py --base origin/main --require-base
$ python3 -I scripts/check-migrations.py --require-baseline
all rc=0.

OWASP Dependency-Check disposition: no dependency changed, so OWASP/NVD scanning is unchanged by this
test-only file; the cloud worker has no `NVD_API_KEY` and the integrator runs the full `mvn verify`
gate. No suppression was added.

$ bash scripts/worker-check.sh
rc=1; it stopped inside scripts/quick-check.sh at the same five pre-existing Python tooling
failures ("Ran 2494 tests ... FAILED (failures=5, skipped=1)"), so its frontend, integration and
database stages were not reached. No new suppression, exclusion or threshold change was made;
the authoritative checks above were run individually and pass.

Environment note (tool evidence): the default Maven Central install returned HTTP 429 for
software.amazon.awssdk:b
