# SPEC-ROLE-CLINICAL-ASSISTANT — Clinical assistant role matrix (API test)

## Summary

Test-and-doc-only clause proof for the spec section 2 `clinical_assistant` row (IAM-01, CLN-02). One new characterization test class records which verbs are enforced today; one new clause doc records the honest status and the would-prove tests for the unbuilt parts. No production code, migration, contract, existing test or tracker changed.

## Affected requirements

- IAM-01 — active membership, required action permission, domain authorization, further narrowing.
- CLN-02 — check-in and worklist slice that carries the intake/handoff verbs.

## Risk class

Routine (read-only clause evidence, no behavior change).

## Review owner

Senior backend, senior Java build, senior Spring Security; read-only plan/code/coverage/security reviewers.

## Data changes

None. No Flyway migration; no RLS or backfill.

## Authorization boundaries and audit events

No new boundaries or events. Evidence covers the existing tenant context, active-membership check, role→action check, facility narrowing and the existing DENIED/SUCCESS audit rows in `CheckInService`.

## Migration and rollout considerations

None.

## Plan (fixed after plan review)

1. Bounded evidence: `WorklistPolicy.ASSISTANT_ACTIONS` (lines 48-49) grants WORKLIST_READ, VITALS_RECORD, HANDOFF, INTAKE_LINK; `CheckInService.authorize` (428-439) checks membership + role; `facilityDenial` (452-459) narrows by facility via `outsideAssignedFacilities`; `handoff` (278-325) checks the current assignee's and target's membership and version but never compares `context.subject()` to the assignee or care team; `ResultActionPolicy.ALLOWED` (34-40) has no assistant action; `SpecialtyRoles.ALL` (35-38) has no CLINICAL_ASSISTANT; `ChartActionPolicy` (24-26) grants vitals entry to the assistant. Verified by tool reads and by smartclinic-context-optimizer and smartclinic-senior-spring-security.
2. Correct the plan-reviewer Required gaps before coding:
   - R1: active membership, facility scope, unassigned item and stale version are **generic IAM-01/workflow/facility guards**, not proof of this row's scope constraint ("Assigned encounters or care team"). The green tests are labelled that way in `## Evidence`; the would-prove test for "another encounter / caller is not the assignee or care team" is in `## Gap`; Status stays **Partly proven** specifically because the scope constraint is unbuilt.
   - R2: name the exact gates and state the `@Disabled` conflict as an explicit deviation: story step 3 asks for `@Disabled("GAP: ...")` gap tests, but `scripts/check-constraints.py:56` rejects every added `@Disabled` and a new file is all added lines, so gap tests are documented in `## Gap` and no test is skipped.
   - Optional fixes adopted: call out that vitals is policy-only (no CLN-02 service command) and reconciliation preparation is unbuilt; add a "field outside its fields" negative proof at policy level (`ChartActionPolicy.CHART_ENTRY_VERIFY`); list refused other-role verbs explicitly.
3. Critical correction from senior-backend: stale version is **412 `STALE_VERSION`** (`CheckInService.requireVersion`/`requireUpdated`), not 409 as the brief text said. All status assertions follow the code.
4. Tests first: green characterization tests through `CheckInService` and the real policies, with the fixture from `EligibleRolesRecordVitalsOpenEncounterTest`; then the doc; then gates; then read-only reviewers.

## Test evidence

Entry-point trace: `GET /api/v1/clinic/worklist`, `POST /api/v1/clinic/worklist/intake` and `POST /api/v1/clinic/worklist/{id}/handoff` → `ClinicController` → `CheckInService` → `WorklistPolicy` / `ChartActionPolicy` → mocked stores → `AuditAppender`. HTTP reachability of those routes is proven by the named integration suites below; this task adds the clause-focused proof.

- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleClinicalAssistantTest test` — exit 0. Surefire final line: `tests="12" errors="0" skipped="0" failures="0"`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleClinicalAssistantTest,EligibleRolesRecordVitalsOpenEncounterTest,WorklistPolicyTest test` — exit 0. Surefire: `RoleClinicalAssistantTest: tests="12" errors="0" skipped="0" failures="0"`, `EligibleRolesRecordVitalsOpenEncounterTest: tests="6" errors="0" skipped="0" failures="0"`, `WorklistPolicyTest: tests="5" errors="0" skipped="0" failures="0"`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — exit 0, no findings (compiled first, not a vacuous check).
- `python3 -I scripts/check-docs.py` — exit 0.
- `python3 -I scripts/check-constraints.py` — exit 0. `python3 -I scripts/check-secrets.py`, `python3 -I scripts/check-names.py`, `python3 -I scripts/check-wiring.py`, `python3 -I scripts/check-migrations.py --require-baseline` — all exit 0.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` — exit 0; aggregate over 300 Surefire XML files: `tests=4613 errors=0 skipped=0`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ClinicControllerTest,CheckInWorklistIntegrationTest,FacilityScopeFurtherNarrowAccessTest,FacilityScopeEnforcementTest test` — exit 0; `ClinicControllerTest: tests=6`, `CheckInWorklistIntegrationTest: tests=9`, `FacilityScopeFurtherNarrowAccessTest: tests=5`, `FacilityScopeEnforcementTest: tests=10`, all with `errors=0 failures=0 skipped=0`.
- `bash db/check.sh` — exit 0; final line: `db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified`.
- `timeout 280 mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` — stopped at the five-minute rule (exit 124). Reports written before the stop: 138 classes / `tests=978 errors=0 failures=0 skipped=3` (the 3 skips are pre-existing). Targeted clinic integration suites were then run directly and passed (above); the integrator owns the full integration run.

Known cloud-worker false positives (confirmed once, not re-diagnosed):

- `bash scripts/worker-check.sh` — exit 1; final line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` is `S` (skip-worktree); the CI overlay adds `gh *` deny rules, while `git show HEAD:opencode.json` is byte-identical to the renderer output. Not edited; integrator owns the gate.
- `python3 -I -m unittest discover -s scripts/tests` — exit 1; `Ran 1432 tests` / `FAILED (failures=6)`. All six are `test_check_pack` cases with the identical stale-`opencode.json` assertion; `bash scripts/python-coverage.sh` exits 1 for the same six. No failure from this change's files.
- `bash scripts/frontend-check.sh` — exit 1 on an untouched pre-existing staff-web test: `automate-critical-journey-supported-role-real-browser.test.ts:166` expected 9 role spec files, received 14. Staff-web `tsc --noEmit` and `eslint . --max-warnings=0` passed; patient-mobile `npm test` final line `Test Suites: 99 passed, 99 total / Tests: 1284 passed, 1284 total`. No frontend file was changed by this task.

## Coverage

No production code changed: zero new production lines, methods, classes or branches, so there is no new JaCoCo burden and no branch-to-test mapping to add. `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleClinicalAssistantTest test` — exit 0, `tests="12" errors="0" skipped="0" failures="0"`; the full unit suite reports `tests=4613 errors=0 skipped=0`. No test is skipped, disabled or excluded; no suppression, coverage-ignore marker or threshold change was added (`scripts/check-constraints.py` exit 0).

## Review verdicts

(pending)

## Findings

- **Conflict — story step 3 vs constraint gate.** The story asks for `@Disabled("GAP: ...")` would-prove tests. `scripts/check-constraints.py` line 56 flags every added `@Disabled`/`@Ignore`, and a new test file consists entirely of added lines, so that would fail `scripts/worker-check.sh` and the integrator quality gate. Resolution: green characterization tests only; the two would-prove tests are specified in the clause doc `## Gap`.
- **Brief error — stale-version status.** The brief labels `handoffRefusesStaleVersion` as 409. Production `requireVersion`/`requireUpdated` throw 412 `STALE_VERSION`; tests assert the real contract.
- **Stale doc — facility scope.** `docs/spec/clauses/authz/facility-scope-further-narrow-access.md` still says "Not built" and references disabled tests, but live code enforces facility narrowing (`FacilityScopeEnforcementTest`, `FacilityScopeFurtherNarrowAccessTest` are green). This task is test-and-doc-only in its own scope, so that doc was not edited; it is reported here.
- **Plan reviewer's "four implemented verbs" caution adopted.** Three verbs (intake link, handoff, worklist read) are reachable through `CheckInService` in this slice; vitals is granted by `ChartActionPolicy` and proven at policy level; reconciliation preparation has no action anywhere.

## Lessons for workers

- Verify status codes against the production source, not the task brief: `STALE_VERSION` is 412 here, state conflicts such as `NO_ACTIVE_ASSIGNEE` are 409.
- Do not add `@Disabled` for a gap in a new file — the constraint check reads added lines only, so every added `@Disabled` fails the worker gate. Put the would-prove test in the clause doc instead.
- A clause's "scope constraint" must be proven with a resource/relationship check. Facility narrowing is a different row's constraint; do not present it as proof of "assigned encounters or care team".
- Stub `scheduling.setting("clinic.worklist.default_priority")` before any intake happy path: an unstubbed mock returns null and `.orElse(null)` throws NPE inside `priorityFor`.
