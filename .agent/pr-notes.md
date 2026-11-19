# S-G3-09 — Service hours, response targets, named owners, backup coverage and after-hours guidance (MSG-06)

## Requirement IDs

- **MSG-06** (primary): each enabled care unit publishes service hours, response target, topics and urgent contact instructions; these are shown before sending; a named queue owner, backup coverage and overdue escalation are required; outside hours the next handling time is shown; backlog, unassigned work, failed notifications and overdue responses are surfaced to supervisors; approved urgent and downtime channels are used for time-sensitive care.
- Supporting: **ADM-01** (named approval, effective dates, version history), **UX-01** (en/ar guidance, RTL layout, IANA zone display), **ARC-02** (tenant-scoped reads/writes), **ARC-03/ARC-04** (version precondition, 401/403/404/409/412/422 semantics), **OPS-02** (audit of views and denials; no PHI-shaped copy), **PAT-01** (generic, non-clinical display before sending).

## Scope actually used

- API production: `apps/api/src/main/java/com/aurevia/smartclinic/messaging/service` (package `com.aurevia.smartclinic.messaging.service`).
- API tests: the matching test package `apps/api/src/test/java/com/aurevia/smartclinic/messaging/service`.
- Mobile: `apps/patient-mobile/src/features/s_g3_09`.

**Naming decision:** the untrusted story text listed the API path as `messaging/s_g3_09`, but TODO.md (authoritative) scopes it to `messaging/service` and AGENTS.md/CODING_STYLE.md forbid story-ID package names. The business-domain `service` package was used. No reserved V37 migration was needed: this slice uses a tenant-scoped in-memory store (same pattern as the other G3 messaging stories), so V37 stays unused and no other migration version was touched.

## Acceptance mapping

| ID | Acceptance | Evidence |
|---|---|---|
| A1 | Versioned service profile publishes hours, target, topics, owner, backup, escalation, urgent/downtime channels | `ServiceProfileContract.ServiceProfile`, `ServiceProfileService.publish` + tests |
| A2 | Nothing effective without a named, distinct approver and a reached effective date | `ServiceProfileService.approve`, `aDraftOrFutureDatedProfileIsNeverServed`, `approvalRequiresADifferentApproverAndAnUnapprovedDraft` |
| A3 | Zone-aware open/closed and next absolute handling instant, incl. midnight wrap and DST | `ServiceHoursCalculator` + 13 `ServiceHoursCalculatorTest` cases |
| A4 | Patient sees a unit only through an active connection to that exact unit; revoked link fails closed | `requireUnitAccess`, `aPatientWithoutAnActiveConnectionToThatUnitIsConcealed`, `aCrossTenantActiveConnectionCannotResurrectARevokedLocalConnection` |
| A5 | Cross-tenant unit with identical local id concealed (404); inactive membership denied (403) | `aCrossTenantUnitIsConcealedEvenWithTheSameLocalIdentifier`, `anInactiveMembershipFailsClosedOnTheNextRequest` |
| A6 | Stale publish/approve → 412; already approved → 409; unknown → 404; bad input → 422 | `ServiceProfileServiceTest` + `ServiceProfileDomainTest` |
| A7 | New unit can publish its first profile with `expectedVersion = 0` (defect fixed here) | `aBrandNewUnitCanPublishItsFirstProfileWithVersionZero`, `versionAllowsZeroForAnInitialPublishButRejectsInvalidValues` |
| A8 | Supervisor sees backlog, unassigned, failed notifications and overdue responses; patient cannot | `SupervisorSnapshotCalculator` + 5 calculator cases + `aSupervisorSeesDerivedCountersOnlyForAnAssignedUnit`, `aPatientCannotReadTheSupervisorSnapshot` |
| A9 | Every view and denial is audited; guidance copy rejects PHI-shaped digit runs | `ServiceProfileService.deny/audit`, `urgentInstructionsRequireSupportedLanguagesAndSafeNonSensitiveCopy` |
| A10 | Patient notice shows hours/target/topics/owner/backup/escalation/urgent+after-hours guidance before acknowledgement, en/ar and RTL | `ServiceProfileNotice.tsx`, `serviceNotice.ts`, 12 `index.test.tsx` cases |
| A11 | No service-hours arithmetic or device clock on the device; absolute UTC instants formatted in facility zone | `formatFacilityInstant`, `noticeLines` |

## Work done in this worker

The baseline already contained the full MSG-06 slice (prior attempts) with unit tests. I verified it, then fixed one real defect test-first:

- **Defect S-G3-09-D1 (first publish impossible).** `ServiceProfileService.publish` validated `expectedVersion` with the *minute* validator (`ServiceProfileInputs.minutes`, min 1), so a brand-new unit (whose `latestVersion` is 0) could never pass the precondition: `expectedVersion = 0` returned `422 expectedVersion must be 1-2147483647 minutes`, and `null` was also rejected. Red test `aBrandNewUnitCanPublishItsFirstProfileWithVersionZero` reproduced it (`ApiException` at `ServiceProfileServiceTest.java:180`). Fix: added `ServiceProfileInputs.version(Number, String)` accepting 0…`Integer.MAX_VALUE` and used it in `publish`; `null`, negative and above-int values still yield structured 422. No other version semantics changed. This is a functional gap against MSG-06 ("each enabled unit must publish service hours …"), not a cosmetic issue.
- **Documentation cleanup.** Replaced two malformed enum-constructor Javadoc blocks (blank lines with empty `@param wire`) and a truncated `ServiceAvailability` `@return` description in `ServiceProfileContract.java` with valid TSDoc/Javadoc-style text, so Doxygen's `WARN_IF_DOC_ERROR` has nothing to report from this slice.

## Exact commands and results (tool evidence)

All commands run from `/__w/Aurevia-Care/Aurevia-Care`, branch `swarm/S-G3-09-114`.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test -Dtest=ServiceProfileServiceTest` (before fix) | **RED**: 24 tests, 1 error — `aBrandNewUnitCanPublishItsFirstProfileWithVersionZero … ApiException: expectedVersion must be 1-2147483647 minutes` |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (after fix) | **GREEN**, exit 0. Service package: 65 tests, 0 failures/errors (`InMemoryServiceProfileStoreTest` 3, `ServiceHoursCalculatorTest` 13, `ServiceProfileControllerTest` 6, `ServiceProfileDomainTest` 14, `ServiceProfileServiceTest` 24, `SupervisorSnapshotCalculatorTest` 5) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast clean test jacoco:report` | exit 0; JaCoCo XML for `com/aurevia/smartclinic/messaging/service`: INSTRUCTION 2272/2272, BRANCH 183/183, LINE 373/373, METHOD 100/100, CLASS 18/18 — **100%** (no missed counters) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test-compile` | exit 0 |
| `cd apps/patient-mobile && npm ci --no-audit --no-fund` | exit 0 |
| `cd apps/patient-mobile && npm run typecheck` | exit 0 |
| `cd apps/patient-mobile && npm run lint` | exit 0 |
| `cd apps/patient-mobile && npx jest src/features/s_g3_09 --coverage --collectCoverageFrom='src/features/s_g3_09/**/*.{ts,tsx}'` | exit 0; 1 suite, 12 tests; `serviceNotice.ts` and `ServiceProfileNotice.tsx` 100% statements/branches/functions/lines (`types.ts` is interfaces only) |
| `cd apps/patient-mobile && npm test` | exit 0; 26 suites, 278 tests |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (no new migration) |
| `python3 -I -m unittest discover -s scripts/tests -q` | local exit 1 — see "environment caveats" below; the failing cases are pre-existing/out-of-scope |

Evidence source for all of the above: **executed tool commands** on synthetic fixtures. No reviewer, simulated user or human participant evidence was used for acceptance; no patient data or credentials were used.

## Environment caveats (not introduced by this story)

- `python3 -I -m unittest discover -s scripts/tests -q` reports 6 failures / 4 errors locally, none touching this slice: `test_fast_tier` and `test_workflow_concurrency` cannot import `yaml` (PyYAML not installed in this worker image); `test_check_pack` cases fail because `opencode.json` is a stale CI profile copied under `skip-worktree` (`gen-opencode.py --check` and `check-pack.py` therefore judge that profile, per the delivery skill); `test_agent_run`/`test_swarm` cases depend on git refs absent in this shallow single-commit snapshot; `test_javadoc_links_are_fully_qualified` fails on a pre-existing `{@link #from(String)}` in `exchange/trend/Qualifier.java` (another story's package, outside this scope). The in-scope convention test `test_list_bearing_records_copy_on_store_and_read` passes.
- `Doxygen` is not installed in this worker image, so `scripts/docs.sh` could not be run locally; its inputs (`check-docs.py`) pass and the changed Javadoc was cleaned to avoid `WARN_IF_DOC_ERROR`.
- Per the worker contract, **database and integration tests were not run here**; the integrator runs `scripts/check.sh` / `integrator-gate.sh` on the merge result.

## Entry-point-to-test trace

- `GET /api/v1/care-units/{unitId}/service-profile` → `ServiceProfileController.view` → `ServiceProfileService.viewProfile` → `TenantContext` + `ServiceProfileActions` + `ServiceProfileStore.hasActiveMembership/activeRoles/hasConnectedChart` → `effectiveProfile` (approved + effective) → `MessagingAudit` → `ServiceProfileServiceTest.aConnectedPatientViewsOnlyTheEffectiveApprovedProfileOfThatUnit`.
- `GET …/availability` → `ServiceProfileService.availability` → `ServiceHoursCalculator.calculate` → `ServiceProfileServiceTest.availabilityIsServedFromThePublishedHoursAndReturnsTheNextHandlingInstant` and `ServiceHoursCalculatorTest`.
- `POST …/service-profiles` → `ServiceProfileController.publish` (`withUnit` overrides body unit id) → `ServiceProfileInputs.version/windows/zone/…` → `ServiceProfileService.publish` → `ServiceProfileServiceTest.aBrandNewUnitCanPublishItsFirstProfileWithVersionZero`; HTTP 422/404/412/409 denials in `ServiceProfileDomainTest` and `ServiceProfileServiceTest`.
- `GET …/supervisor-snapshot` → `ServiceProfileService.supervisorSnapshot` → `SupervisorSnapshotCalculator.compute` → `SupervisorSnapshotCalculatorTest` and `ServiceProfileServiceTest.aSupervisorSeesDerivedCountersOnlyForAnAssignedUnit`.
- Patient app: `ServiceProfileNotice` + `createSyntheticServiceInfoClient.notice` → `serviceNotice.noticeLines` → `apps/patient-mobile/src/features/s_g3_09/index.test.tsx`.

## Files changed (all inside the declared scope)

- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileInputs.java`
- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileService.java`
- `apps/api/src/main/java/com/aurevia/smartclinic/messaging/service/ServiceProfileContract.java`
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/service/ServiceProfileDomainTest.java`
- `apps/api/src/test/java/com/aurevia/smartclinic/messaging/service/ServiceProfileServiceTest.java`
- `.agent/pr-notes/S-G3-09.md` (this file)
