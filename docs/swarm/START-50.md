# START-50 · API: negative-path and tenant-isolation tests for the package `configuration`

PR #1809, tested branch tip a5bc84c64ef7158d2f35303799cd251c69e141b7.

Task: START-50

Swarm worker 4. Checks: passed. Local checks: failed. Agent result: ok.

# START-50 · API negative-path and tenant-isolation tests for the package `configuration`

Tested revision: 48ea0c3c477248826ed1fc2325b60221368767c6

## Summary

One new test class at `apps/api/src/test/java/com/aurevia/configuration/ConfigurationNegativePathsTest.java` (package `com.aurevia.configuration`) consolidates today's negative service-path semantics for configuration administration: malformed/missing keys on approve/get/history, active membership with an insufficient role, and a peer-tenant key that must look like "not found". It mirrors `ConfigurationServiceTest` (mocked `ConfigurationRepository`, mocked `TransactionTemplate` running the callback, `TenantContext.set/clear`) and asserts only behaviour that exists today. No production code, existing test, contract, migration, tracker, script or workflow was edited. All fixtures are synthetic.

## Affected requirements

- ADM-01 (named approval, effective dates, audit and version history): the tests pin the `403/DENIED`, `404/NOT_FOUND` and `422/INVALID_KEY` outcomes that guard the approval/history surface; no approval or history behaviour is changed.
- ARC-02 (isolation): the peer-tenant case asserts the service answers `404/NOT_FOUND` without echoing a representative record value, and records that forced-RLS proof belongs to `ConfigurationIsolationTest.crossTenantConfigurationIsConcealed`, not to a Mockito stub.
- ARC-03/ARC-04 (version preconditions and request replay) are explicitly out of scope here: no `If-Match`/412 or `Idempotency-Key` dedupe exists in this package (see Findings and `task-requests`).

## Risk class

Cross-boundary by requirement, but test-only in effect: no production state machine, tenant policy, contract or data path is altered. Clinical safety sign-off is not required for a test-only characterization.

## Review owner

Primary agent (orca); read-only specialist verdicts recorded under Review verdicts.

## Data changes

None. No migration, RLS policy, backfill, lock or volume impact; no rollback or forward-fix needed.

## Authorization boundaries and audit events

No new audit event is introduced. The new tests assert today's `CONFIG_APPROVE/DENIED`, `CONFIG_READ/DENIED`, `CONFIG_HISTORY/DENIED` and the corresponding `NOT_FOUND` outcomes in an explicitly bound synthetic tenant context, plus `INVALID_KEY` before any transaction. Every organization, subject, key, correlation id and record-value stand-in is synthetic; no patient data, credential or token appears.

## Migration and rollout considerations

None.

## Skills loaded

- `aurevia-delivery` — every story (delivery loop and swarm hand-off).
- `aurevia-senior-backend` — Java/Spring service and repository boundary; SpotBugs command.
- `aurevia-test-engineer` — negative-path matrix and branch-to-test map.
- `aurevia-plan-reviewer` — pre-coding plan review (first pass NO-GO; corrected plan re-reviewed).
- `aurevia-code-reviewer`, `aurevia-coverage-auditor`, `aurevia-devsecops` — post-code reviews.
- `aurevia-qa-reviewer` — post-code QA review required by AGENTS.md and `scripts/quality-gate.sh` but omitted from the brief; loaded and run for that reason.
- No additional skill matched: `aurevia-senior-java-build` and `aurevia-context-optimizer` were explicitly dropped by the brief (no build-tool or repository-context change) and SpotBugs was taken from `aurevia-senior-backend` as the brief directs.

## Brief discrepancies (carried forward, not instructions)

- The brief names `docs/pr/notes/START-50.md` as the notes path. The worker prompt names `.agents/notes/START-50-20261127-161202.md`, and AGENTS.md says the path in the prompt wins over older `<task-id>.md` wording in a task/story. This file is therefore the hand-off.
- The brief lists four post-code reviewer verdicts (plan, code, coverage, security) but omits `qa-reviewer`; AGENTS.md's quality loop and the `--require` list in `scripts/quality-gate.sh` include it. It was loaded and run.
- The story asks to "confirm the owning entry point and scope with the maintainer". No maintainer channel is available in this worker, so the entry point is taken from code: `ConfigurationController` routes → `ConfigurationService` → `ConfigurationRepository`, already exercised end to end by `ConfigurationIsolationTest` and `TenantAdminsConfigureFacilitiesReferralPartnerDirectoryTest`. No new route, service or store is introduced.

## Entry-point-to-test trace

`ConfigurationController.create/approve/get/history` → real `ConfigurationService` → mocked `ConfigurationRepository` (with a mocked `TransactionTemplate` executing the callback) → assertions on status, machine code, audit outcome and absence of config-data reads/writes. The new class exercises the service layer, matching `ConfigurationServiceTest`; the HTTP/RLS chain is already proven by the two named integration suites.

## Test evidence

All commands ran from the repository root on branch `swarm/START-50-1740` at the tested revision above. Captured output lives only under a per-run `/tmp/aurevia-start50.*` directory; no scratch file was written in the worktree.

- `git merge --no-edit origin/main` → `Already up to date.` at the start and again after coding; `git grep -nE '^(<<<<<<<|>>>>>>>) ' -- .` → no output (no conflict markers).
- Baseline before the new file existed: `mvn -f apps/api/pom.xml -Pno-security -Dtest=ConfigurationServiceTest -DfailIfNoTests=false test` → exit 0, `Tests run: 25, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`.
- First run with the new class (no production change, so green on first run as the brief directs): `mvn -f apps/api/pom.xml -Pno-security -Dtest=ConfigurationServiceTest,ConfigurationNegativePathsTest test` → exit 0, `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.aurevia.configuration.ConfigurationNegativePathsTest`; `Tests run: 25 ... in ConfigurationServiceTest`; `Tests run: 28, Failures: 0, Errors: 0, Skipped: 0`; `BUILD SUCCESS`. Surefire `TEST-com.aurevia.configuration.ConfigurationNegativePathsTest.xml`: `tests="3" errors="0" skipped="0" failures="0" flakes="0"`.
- Assertion sensitivity proof (test-only change; not a red-first claim): temporarily replaced the expected code on line 131 with `RED_PROOF_NOT_THE_REAL_CODE`, reran `mvn -f apps/api/pom.xml -Pno-security -Dtest=ConfigurationNegativePathsTest test` → exit 1, `Tests run: 3, Failures: 1, Errors: 0, Skipped: 0`, `expected: <RED_PROOF_NOT_THE_REAL_CODE> but was: <INVALID_KEY>`. Restored the original expectation from a `/tmp` copy and reran the full targeted command → exit 0, `Tests run: 28 ... BUILD SUCCESS`. `grep RED_PROOF` on the restored file → no output. No failing test, suppression or production edit remains.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0, no findings; `apps/api/target/spotbugsXml.xml` has 0 `BugInstance` entries. Compile ran first, so the check is not vacuously green. SpotBugs Maven plugin 4.10.5.0; `includeTests` is not set, so the new test source adds no analysed class. No new `@SuppressFBWarnings` was added.
- Policy checks, each exit 0: `python3 -I scripts/check-wiring.py`; `python3 -I scripts/check-constraints.py --base 48ea0c3c477248826ed1fc2325b60221368767c6 --require-base`; `python3 -I scripts/check-names.py`; `python3 -I scripts/check-secrets.py`; `python3 -I scripts/check-docs.py`; `python3 -I scripts/gen-opencode.py --check`; `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`.
- `python3 -I -m unittest discover -s scripts/tests` → exit 1, `Ran 2457 tests in 109.907s`, `FAILED (failures=5, skipped=1)`. The identical five failures were present in the baseline run *before* any edit (`Ran 2457 tests in 116.508s`, `FAILED (failures=5, skipped=1)`), so they are pre-existing and unrelated to a Java test file: three `test_check_pack` cases reading the skip-worktree CI profile directly (t
