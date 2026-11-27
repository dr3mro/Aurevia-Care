# START-49 · API: negative-path and tenant-isolation tests for the package `config`

PR #1808, tested branch tip 586b335a891b30acb16d6d4d7d48d7dab9955e79.

Task: START-49

Swarm worker 3. Checks: passed. Local checks: failed. Agent result: ok.

# START-49 — negative-path and tenant-isolation tests for the package `config`

Tested revision: 48ea0c3c477248826ed1fc2325b60221368767c6

Branch `swarm/START-49-1740`; `git merge --no-edit origin/main` -> `Already up to date.`; working tree clean. This is a **stop with no code change**: the declared scope has no uncovered negative path and none of the story's named behaviors exists in it.

## Summary

The story asks for one new test class `apps/api/src/test/java/com/aurevia/config/ConfigNegativePathsTest.java` covering missing/too-long/malformed input, wrong role, another tenant's identifier concealed as not-found, stale version and a repeated idempotency key. The `com.aurevia.config` package contains only Spring wiring and immutable domain values — no controller, role check, version precondition, idempotency handler or HTTP endpoint. Every negative branch it does have is already asserted by the four existing config test classes (32 green tests). Writing the requested class would either duplicate existing tests or fabricate behavior that does not exist; the task explicitly forbids both. No production, test, contract, migration or dependency file was changed.

## Affected requirements

- ADM-01 — administration: implemented and tested in `configuration`/`configuration/clauses`, outside this scope.
- ARC-02 — isolation: cross-tenant concealment implemented and tested in `configuration/ConfigurationService.java` and `it/ConfigurationIsolationTest.java`, outside this scope; `config/rosters/MissingCoverageQueue.java` only performs a tenant filter (omits foreign rows), not a 404.

Requirement-to-evidence map:
- ADM-01/ARC-02 "too-long/malformed input" -> `ConfigurationServiceTest.rejectsLongValue`, `rejectsUnparseableEffectiveFrom`, `rejectsBlankEffectiveFrom` (not in scope).
- ADM-01 "wrong role" -> `ConfigurationServiceTest.createDeniesMissingPermissionAndAudits`, `authz/ConfigurationActionPolicyTest.otherRolesDenyEveryConfigurationAction`, `configuration/clauses/...:172,181` (not in scope).
- ARC-02 "other tenant concealed as not found" -> `ConfigurationServiceTest.approveConcealsMissingEntry`, `it/ConfigurationIsolationTest.crossTenantConfigurationIsConcealed`, `historyHasNoCrossTenantRows` (not in scope).
- "stale version" -> `ConfigurationServiceTest.approveRejectsNonPositiveVersion`, `approveConflictsOnAlreadyApproved`; real `STALE_VERSION`/412 lives in `labflow/results/ResultEntryService`, `clinical/notes/ClinicalNoteService`, `events/DurableEventService` (not in scope).
- "repeated idempotency key" -> `web.IdempotencyFilter`/`events.DurableEventService`; `ConfigurationServiceTest.createRejectsConcurrentDuplicate`/`approveRejectsConcurrentDuplicate` are 409 conflicts, not a key replay (not in scope).

## Risk class

Routine (test-only proposal), but the outcome is a scope stop, not an implementation.

## Review owner

scrum-master / product-owner (re-scope decision). Worker made no change.

## Data changes

None. No migration, backfill, RLS change or rollback; no OpenAPI, client, dependency or script change.

## Authorization boundaries and audit events

None added or changed. `config/rosters/MissingCoverageQueue.java:34` filters by the active tenant id and stamps that id on each returned item; it emits no 404 and reads no foreign record. `AuthorizationConfiguration.java` only wires the durable decision sink with `PROPAGATION_REQUIRES_NEW`.

## Migration and rollout considerations

None.

## Test evidence

All commands below were run by the primary agent (tool evidence) on the tested revision; outputs were captured under a `/tmp/aurevia-orca.*` directory, never inside the repository.

1. `git merge --no-edit origin/main` -> final line `Already up to date.`; `git status --short` -> empty; `git rev-parse HEAD` -> `48ea0c3c477248826ed1fc2325b60221368767c6`.
2. `mvn -f apps/api/pom.xml -Pno-security -Dtest=ProductionSafetyCheckTest,AuthorizationConfigurationTest,OperationalQueueItemTest,MissingCoverageQueueTest test` -> `Tests run: 32, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`; `Total time: 28.979 s`. (Existing config tests only; no `fast` profile. `ConfigNegativePathsTest` was deliberately not created.)
3. `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` -> exit `0`, no findings (compile before SpotBugs; no `@SuppressFBWarnings` or exclusion added).
4. `PATH=/opt/homebrew/bin:$PATH python3 -I -m unittest discover -s scripts/tests` -> `Ran 2457 tests in 112.235s`, `FAILED (failures=5, skipped=1)`. All five are pre-existing baseline failures on this clean checkout, unrelated to `config`: three `test_check_pack` cases (`test_committed_opencode_json_is_generated`, `test_simulated_user_skills_need_no_process_sections`, `test_system_namespace_skill_is_not_a_project_skill`) reporting `.opencode/opencode.json is stale: run scripts/gen-opencode.py`; `test_notes_files.NotesFileTests.test_daily_archives_keep_the_documented_dated_shape` (`[] is not true : the daily notes workflow archives originals`); `test_process_env.ProductionBoundaryTest.test_run_gate_reaps_a_started_background_descendant` (`background descendant 86708 survived gate cleanup`). No yaml-import failures occurred. Per AGENTS.md the stale generated config is real drift, not a false positive, and workers may not edit `.opencode/`; it is recorded, not suppressed, and needs the trusted integrator/owner to regenerate.
5. `bash scripts/worker-check.sh` -> exit `1`, caused solely by the same five pre-existing tooling failures in `scripts/quick-check.sh`. PostgreSQL was available (no "POSTGRESQL is not available" skip warning); SpotBugs inside the gate passed.
6. `python3 -I scripts/check-constraints.py --base origin/main --require-base` -> exit `0` (no threshold, exclusion or suppression weakening). `python3 -I scripts/check-names.py` -> exit `0`. `python3 -I scripts/check-wiring.py` -> exit `0` (test-only change cannot introduce a wiring finding).
7. `python3 -I scripts/check-verdicts.py .agents/notes/START-49-20261127-161205.md --base origin/main --require "aurevia-code-reviewer,aurevia-coverage-auditor,aurevia-devsecops,aurevia-qa-reviewer"` -> `verdicts: only documentation changed; nothing to review`, exit `0`. This is expected because the only changed path is this prompt-named Markdown note; the four required `GO` verdicts were still obtained and are recorded below.
8. `git diff --stat origin/main...HEAD` -> empty; `git status --short` -> only `?? .agents/notes/START-49-20261127-161205.md` (before the notes commit). Confirms no production, test, contract, migration or dependency change was made.

Entry point to test trace: none added. The nearest executable entry points for the story's named behaviors are `ConfigurationController.create/approve/get/history` -> `ConfigurationService` -> `tests: ConfigurationServiceTest`, `it/ConfigurationIsolationTest`, `authz/ConfigurationActionPolicyTest`, `configuration/clauses/TenantAdminsConfigureFacilitiesReferralPartnerDirectoryTest` — all outside the declared `config` test scope.

CI run for exact SHA: none. This worker does not push or call `gh`; the only commit on branch `swarm/START-49-1740` is this prompt-named notes file, which is outside production scope.

## Coverage

Production Java is **untouched** (no file added, modified or deleted), so there is no new production line, branch, method or class to cover and no new test to name. No threshold, exclusion, suppression or skip was introduced.

Targeted coverage command for the touched area: `mvn -f apps/api/pom.xml -Pno-security -Dtest=ProductionSafetyCheckTest,AuthorizationConfigurationTest,OperationalQueueItemTest,MissingCoverageQueueTest test` -> 32 tests, 0 failures. JaCoCo `report`/`check` bind to `verify` in `apps/api/pom.xml`, so the targeted `test` phase does not emit a fresh report; the full 100% JaCoCo gate is deferred to the trusted integr
