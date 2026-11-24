# BUILD-15 · Instruction acknowledgment bound to the exact revision

PR #636, tested branch tip 6868f8508b8c1bb731f58b37c0d9662e9b8c2f8d.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed; merge with main left unresolved conflicts). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (TypeScript/frontend and gate lines):

```text
python coverage: WARNING coverage is not installed; the tests ran without the coverage check
FAIL unreferenced-service: apps/api/src/main/java/com/x/NewService.java
FAIL unreferenced-service: apps/api/src/main/java/com/x/OrphanService.java
FAIL unreferenced-service: apps/api/src/main/java/com/synthetic/OrphanService.java
npm warn deprecated eslint@9.39.5: This version is no longer supported. Please see https://eslint.org/version-support for other options.
> orevia-staff-web@0.1.0 typecheck
> eslint . --max-warnings 0
npm warn deprecated eslint@9.39.5: This version is no longer supported. Please see https://eslint.org/version-support for other options.
> patient-mobile@0.1.0 typecheck
> eslint .
npm warn deprecated eslint@9.39.5: This version is no longer supported. Please see https://eslint.org/version-support for other options.
> typecheck
> eslint . --max-warnings=0
frontend standards: policy and tool commands passed
```

Task: BUILD-15

Swarm worker 9. Local checks: failed. Agent result: ok.

# BUILD-15 — Instruction acknowledgment bound to the exact revision

## Summary

MSG-05's fact slice (publication, generic dispatch, first view, receipt-only acknowledgment, overdue follow-up) was **already implemented on `main`** in `messaging/acknowledgment` (from S-G3-08 / READY-39). This worker verified the exact story acceptance and closed the one genuine gap: the service- and HTTP-level scenario *"acknowledge revision 1 while it is current, publish a newer revision 2, and prove revision 2 still needs its own acknowledgment and its own overdue follow-up."* No production code was changed; the change is test-only (one service test, one new HTTP entry-point test). No dependency added. Synthetic data only.

## Affected requirements

- **MSG-05** — separate facts; acknowledgment bound to the exact instruction revision; overdue requested acknowledgments routed to the owning unit.
- Boundaries exercised by existing code: IAM-01/IAM-02 (tenant, membership, role, unit object authorization), IAM-05 (several verified accounts on one chart), OPS-02 (audit facts).

Acceptance mapping:
| Story step | Evidence |
|---|---|
| 1. Read `messaging` | Read all 7 production and 6 test files in `messaging/acknowledgment` plus the sibling HTTP test pattern in `messaging/evidence` and `messaging/flows`. |
| 2. Publication / dispatch / first view / acknowledgment as separate facts | Existing `InstructionFactServiceTest.timelineShowsTheFourFactsAsSeparateEntries`, `notificationDispatchIsAGenericSeparateFact`, `firstViewIsRecordedOnceAndNeverAcknowledges`; new `InstructionFactControllerHttpTest.publicationFirstViewAndAcknowledgmentAreSeparateFactsOverHttp`. |
| 3. Exact revision; later revision needs a new one; overdue tracked | Existing `acknowledgmentBindsToTheExactPublishedRevision`, `overdueRequestedAcknowledgmentRoutesToTheOwningUnit`; **new** `InstructionFactServiceTest.anAcknowledgmentOfAnOlderRevisionDoesNotSatisfyANewerOne` and `InstructionFactControllerHttpTest.anOlderAcknowledgmentDoesNotSatisfyANewerRevisionOverHttp`. |
| 4. Test: acknowledging an old revision does not satisfy a new one | New service test (old rev-1 receipt valid, then rev-2 published: rev-2 has no receipt, its follow-up is open, stale rev-1 ack returns 409 `STALE_REVISION`, fresh rev-2 ack succeeds and clears the queue); new HTTP test repeats this through the real MVC pipeline. |

## Risk class

Routine — test-only change in one domain package. The underlying MSG-05 slice is cross-boundary (HTTP/service/store) but is unchanged here. An independent `orevia-senior-backend` review confirmed no functional defect and identified exactly this test gap.

## Review owner

`orevia-senior-backend` (bounded read-only review, subagent session `ses_dffcee784ffe8vk2TnelsgzdyB`): "No functional defect found … The actionable gap is a test gap, not a code defect." `orevia-test-engineer` and `orevia-senior-java-build` skills loaded and applied.

## Data changes

None. No migration, backfill, lock, volume impact, rollback or forward-fix. No file outside the two allowed scope prefixes was edited.

## Authorization boundaries and audit events

No new authorization or audit code. New tests reuse the existing tenant-scoped fake and assert the existing boundaries: fresh membership/role, unit assignment for staff actions, verified chart + active connection for patient actions, receipt-only scope (`RECEIPT`), and 404/403 concealment. No audit fact was added or changed.

## Migration and rollout considerations

None — test-only. The PostgreSQL store (`JdbcInstructionFactStore`) and its migration already exist on `main`; the integrator runs the database and full integration gate.

## Test evidence

Commands run (all synthetic fixtures):

1. `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='InstructionFactServiceTest#anAcknowledgmentOfAnOlderRevisionDoesNotSatisfyANewerOne' test` → `EXIT=0`; Surefire `tests="1" errors="0" skipped="0" failures="0"`.
2. `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='InstructionFactControllerHttpTest' test` → `EXIT=0` (after fixing record-component assertions; the initial red was the test asserting a non-serialized `type` field, see Findings).
3. `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='InstructionFactServiceTest,InstructionFactsTest,InstructionFactControllerTest,InstructionFactControllerHttpTest,InMemoryInstructionFactStoreTest' test jacoco:report` → `MVN_EXIT=0`; 78 tests, 0 failures/errors.
4. `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` → `UNIT_EXIT=0`; 353 suites, **3299 tests, 0 failures, 0 errors**.
5. `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → `SPOTBUGS_EXIT=0`.
6. `bash scripts/frontend-check.sh` → `FRONTEND_EXIT=0`; final line `frontend standards: policy and tool commands passed`.
7. Policy steps of `scripts/quick-check.sh` run individually: shell syntax OK, shellcheck OK, project-name OK, `check-docs` OK, `check-secrets` OK, `check-names` OK, `check-wiring` OK (baseline notes only), `check-migrations --require-baseline` OK.
8. `git merge --no-edit origin/main` → `Already up to date.` No conflict markers in either changed file (`git grep -nE '^(<<<<<<<|>>>>>>>) '` on the two files: none).

Entry-point-to-test trace: `POST /api/v1/instructions/publish` → `InstructionFactController.publish` → `InstructionFactService.publish`; `POST /api/v1/instructions/acknowledge` → `InstructionFactController.acknowledge` → `InstructionFactService.acknowledge` (STALE_REVISION at `InstructionFactService.java:223-226`); `GET /api/v1/instructions/overdue-follow-ups` → `InstructionFactController.overdue` → `InstructionFactService.overdueFollowUps` (filters at `:260-275`); `GET /api/v1/instructions/{id}/facts` → `InstructionFactController.facts`. All four are exercised by `InstructionFactControllerHttpTest` through `MockMvcBuilders.standaloneSetup` with `ApiErrorHandler` and `TenantContextInterceptor` under `synthetic-auth`.

## Coverage

Command (step 3 above): `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='...' test jacoco:report`, then parsed `apps/api/target/site/jacoco/jacoco.xml`.

Result: every production class exercised by these tests has **0 missed instructions, 0 missed branches, 0 missed methods and 0 missed classes**. Notably `InstructionFactService` = 1283/1283 instructions, 76/76 branches, 200/200 lines, 34/34 methods; `InstructionFactController` 41/41, `InstructionFactActions` 38/38 with 7/7 branches, `InstructionFactInputs` 88/88 with 16/16 branches; all `InstructionFacts` records/enums 0 missed. `JdbcInstructionFactStore` shows 0 covered here because its PostgreSQL integration test was 
