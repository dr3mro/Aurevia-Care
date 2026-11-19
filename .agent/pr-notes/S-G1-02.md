# S-G1-02 — Identity verification, check-in, organization/facility worklist, assignment and handoff (CLN-02)

## Requirement IDs

Primary: **CLN-02**. Supporting: **IAM-02**, **IAM-03**, **ARC-01**, **ARC-02**, **ARC-03**, **OPS-02**, **PRD-01**. Gate: G1 (clinic).

## State of the received base — read this first

The worker branch `swarm/S-G1-02-119` was checked out at `8ce56f9` with HEAD equal to local `main` and `origin/main`. The **complete CLN-02 slice was already present in that base commit**: nine production files in `apps/api/src/main/java/com/aurevia/smartclinic/scheduling/checkin`, six test classes (53 `@Test` methods) in `apps/api/src/test/java/com/aurevia/smartclinic/it/schedulingcheckin`, and the reserved migration `db/migrations/V12__checkin_worklist.sql`. There were no uncommitted changes and no prior commits on the worker branch.

I did **not** duplicate, rewrite or move that implementation. I reviewed every file in the slice against the specification, verified the code compiles, ran the offline checks available in this container, and wrote these PR notes as the deliverable attached to the work. No source, test or migration file was modified in this worker run.

## Scope and path note

- Production package `com.aurevia.smartclinic.scheduling.checkin` matches the dispatch scope exactly and follows CODING_STYLE.md (business-domain name, no story ID).
- The dispatch text named the test scope `.../it/scheduling/checkin`, but the repository convention for integration tests is flattened domain packages (`it/schedulingcheckin`, like `it/schedulingfollowup`, `it/exchangetask`, etc.). The existing tests are in `it/schedulingcheckin`. I did not rename them: a rename would break the established convention and touch files outside the minimal change. Integrator: expect `it/schedulingcheckin`.
- Reserved migration **V12** was used (`V12__checkin_worklist.sql`), and only V12. No other migration or tracker file was touched. I did not edit TODO.md, MEMORY.md, WORKSPACE.md, BUGS.md, PROJECT.md or docs/swarm.

## Acceptance mapping (requirement → implementation → test)

| CLN-02 behaviour | Implementation | Evidence (test) |
|---|---|---|
| Reception verifies identity using two identifiers or an approved alternate; at least one issuer-scoped/verified category; no shared phone/name/DOB-only linking (IAM-03) | `WorkflowInputs.identifiers/method/alternate`; `CheckInService.verify`; V12 `check_in_verification` with method/identifier CHECKs | `WorkflowInputsTest.identifiersNeedTwoDistinctCategoriesAndAtLeastOneStrong`, `alternatePathNeedsBothANamedCodeAndAReason`; `CheckInServiceTest.verifyStoresEvidenceAndAuditsSuccess`, `verifySupportsTheDocumentedAlternatePath`, `verifyRejectsBadInputBeforeAnyWrite`; `CheckInWorklistIntegrationTest.identityRulesAuthorizationAndGates` |
| Reception checks in a verified appointment; booked → checked-in stays owned by the V8 state machine | `CheckInService.checkIn` (requires verification, delegates `AppointmentService.checkIn`, creates worklist entry, appends audit) | `CheckInServiceTest.checkInRequiresVerificationAndCreatesAWorklistItem`, `checkInPropagatesStaleAndMissingFacility`; `CheckInWorklistIntegrationTest.verifyThenCheckInCreatesWorklistEntryHistoryAndAudit` |
| Organization/facility worklist ordered by scheduled time then configurable priority; bounded paging | `WorklistRepository.listWorklist` (`ORDER BY COALESCE(starts_at, created_at), priority DESC`), `CheckInService.worklist`, `priorityFor` reading approved configuration | `CheckInWorklistIntegrationTest.worklistOrdersByScheduledTimeAndConfiguredPriority`, `configuredPriorityIsAppliedToWorklistItems`; `CheckInServiceTest.worklistIsBoundedAndOrderedByTheRepository`, `worklistRejectsBadFacilityAndBounds` |
| Assignment with assignee + timestamp; transfer requires an active assignee and records from/to/reason; only active members assignable (IAM-02) | `CheckInService.assign/handoff`, V12 `guard_worklist_assignment`, `guard_worklist_entry_update`, append-only `handoff_event` | `CheckInServiceTest.assignSetsTheAssigneeTimestampAndAppendsAHandoff`, `handoffRequiresAnActiveAssigneeAndRecordsFromToAndReason`, `assignRejectsBadInputMissingStaleAssignedAndInactive`, `handoffRejectsBadInputMissingStaleAndInvalidTransitions`; `CheckInWorklistIntegrationTest.assignmentHandoffAndRevocation` |
| Only eligible roles record vitals or open the encounter; reception cannot open an encounter | `WorklistPolicy` (local action/role matrix), `CheckInService.authorize/openEncounter` | `WorklistPolicyTest.receptionMayRunTheClericalFlowButNotClinicalActions`, `clinicalAssistantMayRecordVitalsAndHandOffButNotOpenOrVerify`, `clinicianMayOpenAndRecordVitalsButNotRunClericalCommands`, `tenantAdminMayDoEverythingAndAuditorNothing`; `CheckInWorklistIntegrationTest.verificationLedgerIsAppendOnlyAndEncounterOpeningIsGuarded` |
| Link unscheduled arrivals to intake | `CheckInService.linkIntake`, V12 `worklist_entry` XOR check on appointment/intake reason | `CheckInServiceTest.intakeLinksAnUnscheduledArrival`, `intakeRejectsBadInputAndMissingReferences`; `CheckInWorklistIntegrationTest.unscheduledIntakeAppearsInWorklistAndTenantsStayIsolated` |
| Tenant isolation, forced RLS, fail-closed context, append-only ledgers, audit (ARC-01/02, OPS-02) | V12 forced RLS + actor triggers + immutability triggers; guarded repository derives org from `app_org()`; role/membership re-derived per request | `CheckInServiceTest.verifyFailsClosedWithoutTenantContext`; `CheckInWorklistIntegrationTest.unscheduledIntakeAppearsInWorklistAndTenantsStayIsolated`, `verificationLedgerIsAppendOnlyAndEncounterOpeningIsGuarded`, `concurrentAssignmentLeavesExactlyOneWinner` |
| HTTP contract, `If-Match` preconditions, 401/403/404/409/412/422 outcomes (ARC-03/04) | `ClinicController` routes under `/api/v1/clinic` using `IfMatch.parse`; `ApiException` mapping | `ClinicControllerTest.verifyReturnsCreated`, `checkInParsesIfMatch`, `openEncounterParsesIfMatch`, `assignAndHandoffParseIfMatch`, `intakeReturnsCreated`; `CheckInWorklistIntegrationTest.identityRulesAuthorizationAndGates` |

### Entry-point-to-test trace

`POST /api/v1/clinic/verifications` → `ClinicController.verify` → `CheckInService.verify` → guarded `TransactionTemplate` → `authorize` (fresh membership + role) → `AppointmentService.get` → `WorklistRepository.insertVerification` + `AuditAppender.append` → 201. `POST /api/v1/clinic/appointments/{id}/check-in` → `ClinicController.checkIn` (`If-Match`) → `CheckInService.checkIn` → verification gate → `AppointmentService.checkIn` → `WorklistRepository.insertItem` → audit. `GET /api/v1/clinic/worklist` → `CheckInService.worklist` → `WorklistRepository.listWorklist`. `POST /api/v1/clinic/worklist/{id}/assign|handoff` → guarded lock + version CAS + `insertHandoff` + audit. `POST /api/v1/clinic/worklist/intake` → `linkIntake`. `CheckInWorklistIntegrationTest` exercises all of these end to end against real PostgreSQL in CI; the five unit classes cover the domain branches.

## Verification commands run and exact results (all synthetic; no patient data)

- `git status --porcelain=v2` → clean; `git branch --show-current` → `swarm/S-G1-02-119`; `git rev-parse HEAD` → `8ce56f9…` (equals `main` and `origin/main`).
- `mvn -q -B -f apps/api/pom.xml -Pno-security test-compile` → **exit 0**. Main and test sources of the whole API module, including this slice, compile with `--release 21`.
- `python3 -I scripts/check-docs.py` → **exit 0**. `python3 -I scripts/check-secrets.py` → **exit 0**. No PHI-shaped or credential-shaped strings in the slice.
- `python3 -I scripts/check-migrations.py --require-baseline` → **exit 0**. The reserved V12 is an `expand` migration and passes the change-safety gate alongside the rest of V1–V55.
- `python3 -I scripts/project-name.py displayName` → `Aurevia Care`; `repositorySlug` → `aurevia-care`.
- Test inventory: 9 production files and 6 test classes / 53 `@Test` methods in the slice (20 service, 8 integration, 6 controller, 4 defensive-record, 10 input-guard, 5 policy). JaCoCo in `apps/api/pom.xml` enforces 100% COVEREDRATIO on instruction, line, branch, method and class at BUNDLE level during `verify`; the integration suite is `@Tag("integration")` and excluded from the fast tier.

### Blocked checks — environment, not code

These could **not** be executed in this container. I did not fabricate results.

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` and `bash apps/api/check.sh` → **blocked**: the local Maven repo `/opt/m2` lacks `org.apache.maven.surefire:surefire-junit-platform:3.5.6.jar` (only `.lastUpdated` markers), and Maven Central is unreachable from this container (retry with `-U` and `dependency:get` also failed). `curl`/`wget` to Maven Central are denied by the container permission policy. The unit and integration tests therefore did **not** run here. **Integrator must run `bash apps/api/check.sh` with PostgreSQL to get the real test and JaCoCo verdict.**
- `bash scripts/docs.sh` → blocked: Doxygen is not installed in this container (the tool prints “Install Doxygen to build the developer reference.”). The Javadoc/record documentation is present and `check-docs.py` passes; Doxygen rendering is for CI.
- `python3 -I -m unittest discover -s scripts/tests -q` → 7 failures / 6 errors, all environment artifacts unrelated to this slice: `PyYAML` is not installed (`test_fast_tier`, `test_workflow_concurrency`, two `test_swarm` cases); the sandbox intercepts `git push`/`gh pr create` subprocess calls used by `test_agent_run.py`; `test_agent_run` branch setup fails in the temp repo. I did not edit these out-of-scope tests.
- `python3 -I scripts/check-pack.py` → “opencode.json is stale”. This is the documented cloud-worker artifact: the worktree `opencode.json` is the CI profile copied under `skip-worktree` and differs from `git show HEAD:opencode.json` (verified with `diff`). It is not a stale committed file and is unrelated to this story.

## Defects found

No defect was found in the CLN-02 slice during this review. The implementation is internally consistent with the V8 appointment state machine it delegates to, with the V12 database guards, and with the repository's audit/tenant conventions. The malformed-looking record Javadoc (`@return this record`) is the repository's deliberate Doxygen workaround recorded in `S-FIX-ACTIONS-01`, not a defect.

## Decisions recorded by this work

- **No code change in this worker run.** The received base already contained the complete, compiling slice; duplicating or reshaping it would add risk without adding behaviour. These notes are the committed deliverable.
- **Business-domain package naming** (`scheduling.checkin`) and flattened integration-test package (`it.schedulingcheckin`) are retained per repository convention and the package-refactor record.
- **V12 is additive (`Change: expand`)**, deliberately not altering the V8 appointment state machine or its history trigger; S-G1-01 owns `booked → checked_in` and the immutable appointment events, and this slice adds verification evidence and the worklist projection around that transition.
- **Identity evidence stores only identifier categories, never values**; the alternate path records an approved workflow code and reason — no unnecessary patient data or credentials.
- **Worklist priority is resolved from approved tenant configuration at check-in** and frozen on the entry so later configuration changes do not silently reorder history; unconfigured/malformed values fall back to 0.
- **WorklistPolicy is local to this slice** to avoid widening the shared `ActionPolicy`. Vitals recording permission is declared here but the vitals store itself belongs to CLN-03 (S-G1-03); no vitals store was created here.
- Audit purposes reuse the action keys, matching the convention already used by other slices (for example `exchange/order`, `exchange/critical`); this is not a new policy.

## Evidence source and remaining owner actions

- Evidence source in this run: **executed tool commands and direct source/test review**. No reviewer, simulated user or human participant evidence was produced in this worker run. All data is synthetic.
- **Integrator must supply the missing executable evidence:** run `bash apps/api/check.sh` (real PostgreSQL, JaCoCo 100% gate) and the full `scripts/check.sh` on the merge result, then mark the task `[x]` with the run SHA. The G1 gate additionally needs the competing-slot, stale-draft, correction-preservation and English/Arabic PDF evidence from the other G1 stories; this story contributes the verified check-in, worklist ordering, assignment/handoff and revocation evidence above.
