## Summary

IAM-01 section-2 role matrix, Reception row: added synthetic API-level proof and its clause document. The production behaviour already exists on `main`; this change adds only evidence, no production code. Eleven active tests cover all four allowed verbs, four refused-verb groups, three scope-narrowing cases, and one honest gap (field-level demographic/scheduling scope) recorded in the clause document. No dependency, contract, migration, existing test or tracker was changed. Synthetic fixtures only: no PHI, tokens or credentials. Evidence source: tool (executed Maven/Surefire, policy scripts, SpotBugs) and read-only reviewers (plan, code, coverage, security).

## Affected requirements

IAM-01 (Reception row of the section-2 role matrix); clause document `docs/spec/clauses/authz/role-reception.md`. Related production behaviour surveyed but not changed: ActionPolicy, WorklistPolicy, IdentityActionPolicy, EnrollmentActions, CheckInService, PatientAccessService/PatientAccessController, CommandAuthorization.

## Risk class

Routine (test-only proof, safety-adjacent because it asserts authorization boundaries). No production behaviour, tenant policy, contract or migration changed.

## Review owner

Senior backend / Spring security for the authorization evidence; QA owns acceptance. Read-only reviewers: plan (GO), code, coverage, devsecops (verdicts below).

## Data changes

None. No Flyway migration, backfill, lock or volume impact, rollback or forward-fix. Synthetic in-memory fixtures only.

## Authorization boundaries and audit events

Assertions cover: role allow/refuse per verb; tenant organization scope at the command gate (HTTP 403 `FORBIDDEN`, recorded reason `MEMBERSHIP_INACTIVE`); facility scope at the check-in commands (`outside the assigned scope`, DENIED audit row); unassigned-item refusal (`NO_ACTIVE_ASSIGNEE`); patient-role refusal on the patient-access route. Audit assertions: `VERIFIED_CHECK_IN` SUCCESS, `ENCOUNTER_OPEN` DENIED, `CONTACT_CHANGED` with `ownership_unchanged`, and DENIED on facility refusal in neighbouring evidence. No new audit event was introduced.

## Migration and rollout considerations

Not applicable: no schema or contract change, no route added, no release authority invoked.

## Test evidence

Entry point to test trace:

- HTTP: `POST /api/v1/patient/access/accounts/contact` → `PatientAccessController.changeStaffContact` → real `PatientAccessService` → `EnrollmentActions.CONTACT_EDIT_STAFF` → HTTP 200 for reception / HTTP 403 `FORBIDDEN` for patient. Proven by `manageContactDataAsReceptionIsAllowed` and `patientSubjectCannotUseReceptionRoute` (MockMvc standalone with `ApiErrorHandler`, `TenantContextInterceptor`, synthetic-auth profile).
- Service: `CheckInService.checkIn` / `openEncounter` / `worklist` / `handoff` with real `WorklistPolicy`, mocked repositories and inline transaction. Proven by `checkInAsReceptionIsAllowed`, `receptionCannotRecordVitalsOrOpenEncounter`, `anotherFacilityIsRefused`, `handoffOfUnassignedItemIsRefused`.
- Command gate: `CommandAuthorization.authorize` with an org-scoped `MembershipResolver`. Proven by `anotherOrganizationIsRefused`.

Commands and final lines (all executed on this branch against base `ceda1551`):

- `timeout 280 mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleReceptionTest test` → exit 0. Surefire `TEST-com.aurevia.aurevia.authz.clauses.RoleReceptionTest.xml`: `tests="11" errors="0" skipped="0" failures="0" flakes="0"`. No `@Tag("integration")`, so the non-integration form is used and the Surefire count is confirmed.
- `python3 -I scripts/check-docs.py` → exit 0 (no output).
- `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → `BUILD SUCCESS`, `[INFO] BugInstance size is 0`. (Compile precedes the check; a bare check is vacuously green.)
- `bash scripts/worker-check.sh` → exit 1, final line `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented cloud-worker artifact OPS-CW-003-01, not a change defect: `git ls-files -v opencode.json` prints `S`; `git show HEAD:opencode.json | sha256sum` = `1ec420cfae8f40600bf90ed7c62e585863cc6e0e6057f999154d60bba1b66f58`, worktree `sha256sum opencode.json` = `b19e90436f0b416ad6b986dbbd5db110749f156010213f485602102e6df5f52d`. The committed profile is generated correctly; the worktree file is the CI profile copied under skip-worktree. Protected file not edited or regenerated. Remaining gate stages were run individually.
- `python3 -I -m unittest discover -s scripts/tests` → exit 1, `Ran 1432 tests in 30.043s`, `FAILED (failures=6)`. All six are `test_check_pack.*` failures with the same `opencode.json is stale` artifact (OPS-CW-003-01); no other tooling test failed.
- Individual quick-check stages (run after the `worker-check.sh` artifact): `python3 -I scripts/project-name.py displayName` → `Aurevia Care` exit 0; `python3 -I scripts/project-name.py repositorySlug` → `aurevia-care` exit 0; `python3 -I scripts/check-secrets.py` exit 0; `python3 -I scripts/check-names.py` exit 0; `python3 -I scripts/check-wiring.py` exit 0; `python3 -I scripts/check-constraints.py` exit 0; `python3 -I scripts/check-migrations.py --require-baseline` exit 0; `python3 -I scripts/check-docs.py` exit 0.
- `bash scripts/python-coverage.sh` → exit 1; it reruns the tooling suite under coverage and hits the same six `test_check_pack` failures (`opencode.json is stale`, OPS-CW-003-01). No changed script line exists in this change, so no Python coverage obligation was introduced.
- `bash scripts/frontend-check.sh` → exit 1 on a pre-existing, unrelated staff-web test: `tests/spec/platform/automate-critical-journey-supported-role-real-browser.test.ts:166` GAP test expects a stale list of journey spec files (`Test Files 1 failed | 270 passed`, `Tests 1 failed | 3050 passed`). The patient-mobile suite passed (`Test Suites: 99 passed`, `Tests: 1284 passed`). No frontend file was changed by this task; both failures are present on the base and are left untouched.

## Coverage

No production file was added or changed, so there are no new production lines, branches, methods or classes to cover and no JaCoCo gate obligation from this change. Coverage command recorded for this touched area: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=RoleReceptionTest test` → Surefire `tests="11" errors="0" skipped="0" failures="0"`. All 11 tests are active (`skipped="0"`), and each asserted outcome would fail if the owning policy decision changed. Branch/decision map:

- allows-true: `registerAsReceptionIsAllowed`, `scheduleAsReceptionIsAllowed`, `checkInAsReceptionIsAllowed`, `manageContactDataAsReceptionIsAllowed`.
- allows-false (wrong role): `receptionCannotStartOrCompleteEncounter`, `receptionCannotRecordVitalsOrOpenEncounter`, `receptionCannotInviteStaffOrApproveConfig`, `patientSubjectCannotUseReceptionRoute`.
- scope narrowing: `anotherFacilityIsRefused`, `anotherOrganizationIsRefused`, `handoffOfUnassignedItemIsRefused`.
- not built and therefore not asserted as a passing test: demographic/scheduling field restriction, recorded in the clause document `## Gap` and in `task-requests` below. No threshold, exclusion or suppression was added or lowered; no test was skipped.

## Review verdicts

- aurevia-plan-reviewer: GO (after one NO-GO round; all Required gaps fixed and re-reviewed)
- aurevia-code-reviewer: GO (no Critical or Required findings; Optional wording fixes applied)
- aurevia-coverage-auditor: GO (11 active tests, no skipped or assertion-free test; Optional notes/doc fixes applied)
- aurevia-devsecops: GO (no Critical or Required findings; synthetic fixtures, honest partial status)

All three post-code reviewers were consulted after the code and tests were complete; their Optional findings were fixed and checks rerun green.

## Clinical and architecture review

Not required for a test-only change: no signed data, cross-organization sharing, patient association, prescribing or critical-result behaviour changed. Patient-role and tenant-organization denials are asserted defensively with synthetic fixtures.

## Findings

- **Rule conflict (task text vs gate).** Task steps 3 and 4 ask for a would-prove test marked `@Disabled("GAP: ...")` for unbuilt behaviour. AGENTS.md Coverage rules forbid a skipped test and `scripts/check-constraints.py:56` flags every ADDED Java `@Disabled`/`@Ignore` line; `quick-check.sh` runs that check, so the added line would fail the worker and quality gates. The brief is advisor guidance that cannot change those rules, and it directs me to say so in this notes file and carry on from the story and AGENTS.md. Resolution: no skipped test; the complete would-prove test is written in the clause document `## Gap` (Markdown is exempt at `check-constraints.py:27-30`), the status is exactly `Partly proven`, a `task-requests` entry is filed, and the worker gate stays green. Reviewer accepted this as the governing-rule decision path.
- **Stale record.** `docs/spec/clauses/authz/facility-scope-further-narrow-access.md` still says "Not built" and describes disabled tests. Current code (`CheckInService.facilityDenial`, `WorklistRepository.outsideAssignedFacilities`) and active tests disprove it. The new clause document cites the current state and does not repeat the stale claim. Fixing that document is outside this task's scope.
- **Partial coverage of "manage patient contact data".** `PatientAccessService.changeStaffContact` changes only a recovery-contact digest, not a general contact-data field set. This is the narrowest implemented meaning of the verb; the broader field-level capability is in `## Gap`.
- **Cross-org nuance.** `PatientAccessService.authorize` uses the actor's role snapshot and does not itself re-check `hasActiveMembership`; the controller re-derives roles from active membership per request. The cross-organization test therefore uses the command gate (`CommandAuthorization`), where the membership resolver is organization-scoped, rather than claiming the patient-access service alone proves tenant isolation.
- **No production change.** This story delivered proof only. The role policies, service entry points, migrations, contract and existing tests were already on `main`; nothing in them was modified.

## Lessons for workers

- When a clause task asks for `@Disabled("GAP: ...")` but AGENTS.md and `scripts/check-constraints.py` reject an added skipped test, keep the suite active and put the complete would-prove test in the Markdown clause document's `## Gap` with a `task-requests` entry; the doc is an exempt surface and the gate stays honest.
- The `opencode.json is stale` failure in a cloud worker is the skip-worktree CI-profile artifact (OPS-CW-003-01). Confirm once with `git ls-files -v` and the HEAD/worktree hashes, record the exact command and final line, run the other gate stages individually, and never regenerate or commit that file.
- For an "API test" clause story, a MockMvc standalone controller over the real service and policy is the smallest credible HTTP proof; it preserves the tenant interceptor and error handler without needing PostgreSQL.
- Survey `docs/spec/clauses/` for stale status claims before citing a neighbour: a neighbouring clause doc can be obsolete while its tests and production code are live.

## task-requests

```json
[
  {
    "title": "Enforce demographic and scheduling field scope for reception",
    "details": "IAM-01 Reception row: add a validated demographic/scheduling field allowlist at patient registration and staff contact change; refuse clinical or out-of-scope fields for reception with a stable error code and a DENIED audit row. Enable the would-prove test sketched in docs/spec/clauses/authz/role-reception.md ## Gap and add the contact-change rejection test. Reception's assigned-facility scope is already enforced; only the field half is missing. Synthetic fixtures only.",
    "scope": ["apps/api/src/main/java/com/aurevia/aurevia/patient/", "apps/api/src/main/java/com/aurevia/aurevia/messaging/enrollment/", "apps/api/src/test/java/com/aurevia/aurevia/patient/", "apps/api/src/test/java/com/aurevia/aurevia/messaging/enrollment/", "contracts/openapi.yaml"],
    "depends": ["00772000"]
  }
]
```
