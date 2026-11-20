# S-G1-02 — Identity verification, check-in, organization/facility worklist, assignment and handoff (CLN-02)

## Requirement IDs

Primary: **CLN-02**. Supporting: **IAM-02**, **IAM-03**, **ARC-01**, **ARC-02**, **ARC-03**, **OPS-02**, **PRD-01**, **IAM-01** (role table). Gate: G1 (clinic).

## Authoritative scope and path note

- Production package `com.aurevia.smartclinic.scheduling.checkin` — matches the dispatch scope exactly and follows CODING_STYLE.md (business-domain name, no story/task ID).
- The dispatch text named the test scope `.../it/scheduling/checkin`, but this repository flattens integration-test domain packages (`it/schedulingcheckin`, like `it/schedulingfollowup`, `it/exchangetask`, `it/chart/record`, …). All CLN-02 tests live in `it.schedulingcheckin`; **no rename was attempted** (it would break the established convention and touch files outside the minimal change). Integrator: expect `it/schedulingcheckin`.
- Reserved migration **V12** (`db/migrations/V12__checkin_worklist.sql`) is the only migration touched in the received base; this worker run changed **no migration**. No tracker, shared doc, config or `.github` file was edited.

## State of the received base — read this first

The worker branch `swarm/S-G1-02-121` was checked out clean at HEAD `48a6e39` (equal to local `main` and `origin/main`). The **complete CLN-02 slice was already present in that base commit**: nine production files in `scheduling/checkin`, six test classes in `it/schedulingcheckin`, and V12. I did not duplicate or relocate that implementation. I reviewed it, ran the fast unit gate for real (the prior attempt could not), routed bounded specialist reviews, and **fixed two genuine defects found in this run**, each test-first.

## Defects found and fixed in this run (test-first, synthetic fixtures)

### D1 — `tenant_admin` over-permissioned for clinical actions (IAM-01) — MEDIUM, fixed

- `WorklistPolicy.ADMIN_ACTIONS` included `ENCOUNTER_OPEN` and `VITALS_RECORD`, contradicting the class Javadoc ("Only a clinician may open the encounter") and the spec role table: "Tenant admin / privacy auditor … Clinical access follows separate clinical permissions."
- **RED:** rewrote `WorklistPolicyTest.tenantAdminMayDoEverythingAndAuditorNothing` → `tenantAdminMayRunClericalWorklistButNotClinicalActions`, asserting admin is denied both clinical actions. `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=WorklistPolicyTest test` → 1 failure: `expected: <false> but was: <true>` for `ENCOUNTER_OPEN`.
- **Fix:** removed `VITALS_RECORD, ENCOUNTER_OPEN` from `ADMIN_ACTIONS` with a rationale comment; clinical actions remain clinician (and assistant for vitals) only.
- **GREEN:** 5/5 `WorklistPolicyTest` pass; full slice 46/46; full fast suite 1762/1762.

### D2 — DENIED authorization audit rows were rolled back (OPS-02) — MEDIUM, fixed

- `CheckInService.authorize` appended a `DENIED` audit row and then **threw** `ApiException` inside the guarded `TransactionTemplate` callback. `ApiException` is a `RuntimeException`, so Spring rolled the callback back and the denial row was lost — contradicting `AuditAppender`'s documented contract and the established `AppointmentService`/`ClinicalNoteService` pattern.
- **RED:** added `CheckInServiceTest.denialAuditIsCommittedEvenThoughTheCommandFails`, using a transaction mock that only treats audits from callbacks returning normally as committed (models rollback on `RuntimeException`). → failure: `expected: <[DENIED]> but was: <[]>`.
- **Fix:** `authorize` now **returns** the `ApiException` (nullable) instead of throwing; all 7 guarded commands capture it in an `AtomicReference`, return from the callback so the `DENIED` row commits, then throw after `transactions.execute`. Added `CheckInServiceTest.everyGuardedCommandDeniesWithoutActiveMembership` to cover all 7 derived denial branches (100% coverage restored). Added integration assertion `CheckInWorklistIntegrationTest.denialAuditRowIsCommittedNotRolledBack` against real PostgreSQL for the integrator.
- **GREEN:** 21/21 `CheckInServiceTest` pass; `CheckInService` 1044/1044 instructions, 70/70 branches.

## Acceptance mapping (requirement → implementation → test)

| CLN-02 behaviour | Implementation | Evidence (test) |
|---|---|---|
| Reception verifies identity using two identifiers (≥1 issuer-scoped/verified) or an approved alternate; no phone/name/DOB-only linking (IAM-03) | `WorkflowInputs.identifiers/method/alternate`; `CheckInService.verify`; V12 `check_in_verification` CHECKs | `WorkflowInputsTest.identifiersNeedTwoDistinctCategoriesAndAtLeastOneStrong`, `alternatePathNeedsBothANamedCodeAndAReason`; `CheckInServiceTest.verifyStoresEvidenceAndAuditsSuccess`, `verifySupportsTheDocumentedAlternatePath`; `CheckInWorklistIntegrationTest.identityRulesAuthorizationAndGates` |
| Reception checks in a verified appointment; booked→checked-in stays owned by the V8 state machine | `CheckInService.checkIn` (verification gate → `AppointmentService.checkIn` → worklist entry → audit) | `CheckInServiceTest.checkInRequiresVerificationAndCreatesAWorklistItem`; `CheckInWorklistIntegrationTest.verifyThenCheckInCreatesWorklistEntryHistoryAndAudit` |
| Org/facility worklist ordered by scheduled time then configurable priority, bounded paging | `WorklistRepository.listWorklist` (`ORDER BY COALESCE(starts_at,created_at), priority DESC`), `priorityFor` from approved config | `CheckInWorklistIntegrationTest.worklistOrdersByScheduledTimeAndConfiguredPriority`, `configuredPriorityIsAppliedToWorklistItems`; `CheckInServiceTest.worklistIsBoundedAndOrderedByTheRepository` |
| Assignment + timestamp; handoff requires an active assignee and records from/to/reason; only active members assignable (IAM-02) | `CheckInService.assign/handoff`, V12 `guard_worklist_assignment`, append-only `handoff_event` | `CheckInServiceTest.assignSetsTheAssigneeTimestampAndAppendsAHandoff`, `handoffRequiresAnActiveAssigneeAndRecordsFromToAndReason`; `CheckInWorklistIntegrationTest.assignmentHandoffAndRevocation`, `concurrentAssignmentLeavesExactlyOneWinner` |
| Only eligible roles record vitals / open the encounter (IAM-01) | `WorklistPolicy` local matrix; **D1 fix** removes clinical actions from `tenant_admin` | `WorklistPolicyTest.tenantAdminMayRunClericalWorklistButNotClinicalActions` + the four role tests; `CheckInWorklistIntegrationTest.verificationLedgerIsAppendOnlyAndEncounterOpeningIsGuarded` |
| Link unscheduled arrivals to intake | `CheckInService.linkIntake`; V12 `worklist_entry` XOR check | `CheckInServiceTest.intakeLinksAnUnscheduledArrival`; `CheckInWorklistIntegrationTest.unscheduledIntakeAppearsInWorklistAndTenantsStayIsolated` |
| Tenant isolation, forced RLS, fail-closed context, append-only ledgers, committed audit incl. denials (ARC-01/02, OPS-02) | V12 forced RLS + actor/immutability guards; **D2 fix** commits DENIED audits | `CheckInServiceTest.verifyFailsClosedWithoutTenantContext`, `denialAuditIsCommittedEvenThoughTheCommandFails`; `CheckInWorklistIntegrationTest.denialAuditRowIsCommittedNotRolledBack`, `verificationLedgerIsAppendOnlyAndEncounterOpeningIsGuarded` |
| HTTP contract, `If-Match` preconditions, 401/403/404/409/412/422/428 (ARC-03/04) | `ClinicController` routes under `/api/v1/clinic`, `IfMatch.parse`, `ApiException` mapping | `ClinicControllerTest` (6); `CheckInWorklistIntegrationTest.identityRulesAuthorizationAndGates` |

### Entry-point-to-test trace

`POST /api/v1/clinic/verifications` → `ClinicController.verify` → `CheckInService.verify` → guarded transaction → `authorize` (fresh membership + role, DENIED captured) → `AppointmentService.get` → `WorklistRepository.insertVerification` + `AuditAppender.append` → 201. `POST /api/v1/clinic/appointments/{id}/check-in` (`If-Match`) → verification gate → `AppointmentService.checkIn` → `insertItem` → audit. `GET /api/v1/clinic/worklist` → `listWorklist`. `POST /api/v1/clinic/worklist/{id}/assign|handoff` → guarded `FOR UPDATE` + version CAS + `insertHandoff` + audit. `POST /api/v1/clinic/worklist/intake` → `linkIntake`. `POST /api/v1/clinic/appointments/{id}/open-encounter` → clinician-only `ENCOUNTER_OPEN` → `AppointmentService.start`. The `@Tag("integration")` suite exercises all of these end to end against real PostgreSQL (integrator); the five fast classes cover the domain branches.

## Verification commands run and exact results

Evidence source: **executed tool commands**, all with synthetic data. Java 21.0.13 / Maven 3.10; the container's `/opt/m2` initially lacked `surefire-junit-platform:3.5.6`, so I provisioned that provider from Maven Central (Python `urllib`, network reachable) and ran Maven online. No fabricated results.

| Command | Result |
|---|---|
| `mvn -B -f apps/api/pom.xml -Pno-security,fast -Dtest=WorklistPolicyTest test` (before D1 fix) | **RED** — 1 failure, `expected <false> but was <true>` for admin `ENCOUNTER_OPEN` |
| same after D1 fix | **GREEN** — 5/5 |
| `mvn -B ... -Dtest=CheckInServiceTest#denialAuditIsCommittedEvenThoughTheCommandFails test` (before D2 fix) | **RED** — `expected <[DENIED]> but was <[]>` |
| `mvn -B ... -Dtest='CheckInServiceTest,WorklistPolicyTest,WorkflowInputsTest,ClinicControllerTest,DefensiveRecordsTest' test` (after both fixes) | **GREEN** — 46/46 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (full fast suite) | **exit 0**; Surefire totals **1762 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -o -q -B -f apps/api/pom.xml jacoco:report` + XML read | checkin non-DB classes all **100%** instruction/branch/line/method: `CheckInService` 1044/1044 instr & 70/70 branch, `WorkflowInputs` 275/275 & 54/54, `WorklistPolicy` 61/61 & 7/7, `ClinicController` 68/68, records 100%. `WorklistRepository` is DB-bound (covered by the integration suite the integrator runs). |
| `mvn -o -q -B -f apps/api/pom.xml -Pno-security spotbugs:check` | **exit 0**, no findings |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | **2/2 OK** (fully-qualified Javadoc links; list-bearing records copy on store/read) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -q` | **35/35 OK** |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-migrations.py --require-baseline` | all **exit 0**; V12 is `Change: expand` and passes the change-safety gate |
| `git diff --check` | **exit 0** |

### Blocked / not executed here (environment, not code)

- **`bash apps/api/check.sh` (full `mvn verify`, integration + JaCoCo BUNDLE 100% gate) — NOT run.** No PostgreSQL binaries or service in this container; the `@Tag("integration")` `CheckInWorklistIntegrationTest` is compiled but cannot execute here. **The integrator must run `bash apps/api/check.sh` with PostgreSQL on the exact merged SHA and record the JaCoCo ratios.** This suite is the authority for `WorklistRepository` coverage and the new DENIED-audit integration assertion.
- **`bash scripts/docs.sh` — NOT run.** Doxygen is absent locally. Javadoc/record docs are present and `check-docs.py` + `test_java_conventions.py` pass; rendering is CI's job.
- **`bash scripts/quick-check.sh` / `check-pack.py` — `opencode.json is stale`** in the worktree. Verified this is the documented cloud-worker CI-profile artifact: `git ls-files -v opencode.json` → `S` (skip-worktree), and the **committed** `git show HEAD:opencode.json` and `.github/opencode.ci.json` both exactly match `gen-opencode.py`'s render (local/ci profiles). No generator or agent/skill file was edited, so no regeneration was needed. Not a code defect.

## Specialist reviews (simulated, read-only) and dispositions

| Reviewer | Findings | Disposition |
|---|---|---|
| physician-clinical-safety | H-1 admin over-permission → **fixed (D1)**; H-2 alternate code not checked against an approved config catalog; H-3 categories-only evidence cannot detect wrong-patient attestation | H-2/H-3 deferred to G5 human approval (site must define/approve the alternate-process catalog and identity-proofing procedure). Categories-only attestation is a defensible PHI-minimizing V1 model (PRD-06, IAM-03), not a launch-blocking hazard. |
| senior-backend | DENIED audit rollback → **fixed (D2)**; legacy `/api/v1/appointments/{id}/check-in` and `/start` bypass CLN-02 verification; verify does not constrain appointment state | Bypass is in `web/AppointmentController.java`, **outside this worker's scope** — flagged for the integrator/owner (see below). State constraint is LOW and deferred; the V8 state machine still governs transitions. |
| senior-database | F1–F6, all MEDIUM/LOW defense-in-depth (handoff event not DB-bound to the update; CAS relies on app `WHERE version=?` + `FOR UPDATE`; `intake_reason` free text; priority mutable; facility scope enforced in app; deactivated-assignee coupling) | **Accepted/deferred.** No HIGH RLS-bypass, FK, trigger-ordering or expand-safety defect. Each gap mirrors the existing V4/V8/V11 app-layer discipline; the service already writes the handoff event in-transaction, uses `findItemForUpdate` + version CAS, and freezes priority by passing the stored value. Changing the DB contract would be a broader, separately-reviewed story. Reviewer executed no SQL (no PostgreSQL). |

No human or clinical participant evidence was produced; all reviews are simulated. Real-patient approval remains a G5 gate.

## Deferred items / owner actions (not blocking this story)

- **Legacy check-in bypass (backend Finding 2, out of scope):** `web/AppointmentController.java:126-143` exposes raw `AppointmentService.checkIn` / `start` without the CLN-02 verification gate or worklist insertion. The invariant is therefore enforced only on `/api/v1/clinic/**`. Integrator/owner should route those endpoints through `CheckInService` or gate them, and add a negative test. I could not touch this file under the declared scope.
- **H-2 / H-3 (G5):** site-approved alternate-process catalog and formal identity-proofing approval.
- **F1–F6 (DB):** optional defense-in-depth hardening in a separately scoped migration/service story.
- The G1 gate still needs the competing-slot, stale-draft, correction-preservation and English/Arabic PDF evidence from the other G1 stories; this story contributes the verified check-in, worklist ordering, assignment/handoff, revocation and committed-denial-audit evidence above.

## Evidence source and delivery

- Evidence source in this run: **executed tool commands and direct source/test review** (tool) plus **simulated specialist reviews** (no human/clinical/patient participant). All fixtures synthetic; no PHI, credentials, patient data or raw logs in any note.
- Only the five intended files were modified; no tracker, generated config or out-of-scope path was touched.
- Committed to the assigned branch only. **No push, tag or `gh` call.** The integrator runs the database/integration gate and marks the task `[x]` on the merge SHA.
