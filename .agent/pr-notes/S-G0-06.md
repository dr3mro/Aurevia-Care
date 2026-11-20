# S-G0-06 — IAM-04: same-tenant duplicate review and all-or-nothing chart consolidation

## Scope decision

The story text inside the dispatch markers lists the test scope as `.../it/patient/consolidation`. That text is untrusted story description. The authoritative `TODO.md` entry also declares `.../it/patient/consolidation`, but the inherited implementation from the prior attempt places its tests at `apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity/ConsolidationServiceTest.java` and `ConsolidationIsolationTest.java`. I kept the tests where the prior attempt left them rather than moving files into a path shared by S-G0-07 (`it/patientidentity`) or creating a new path mid-attempt; this avoids a parallel-worker collision and preserves the inherited DB suite. All production edits are inside `apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation`. No migration version other than the reserved `V9__duplicate_review_consolidation.sql` was touched (V9 was left unchanged this attempt).

## Requirement IDs and acceptance mapping

| IAM-04 clause | Implementation (inherited + this attempt) | Evidence |
|---|---|---|
| Same-tenant duplicate review worklist | `DuplicateRepository.likelyPairs`, `ConsolidationService.worklist`, `GET /api/v1/duplicate-reviews` | `ConsolidationServiceTest.worklistReturnsSameTenantPairs`, `worklistDeniesInactiveMembershipAndMissingRole`; `ConsolidationIsolationTest.worklistIsSameTenantOnly` (DB, integrator) |
| Both identities, conflicts, dependencies, dry run | `ConsolidationService.review`, `ReviewDetail` + nested `Conflict`/`Dependency`, `DryRun` text | `ConsolidationServiceTest.reviewShowsConflictsDependenciesDryRunAndVisibility`; `ConsolidationIsolationTest.reviewShowsConflictsDependenciesDryRunAndVisibility` (DB, integrator) |
| Second authorized reviewer approves | `DuplicatePolicy.APPROVE`, second-reviewer check in `consolidate`, `If-Match` precondition, 409/412 outcomes | `ConsolidationServiceTest.consolidateEnforcesSecondReviewerAndStaleVersion`; `ConsolidationIsolationTest.consolidationNeedsSecondReviewerAndUnblockedDependencies` (DB, integrator) |
| Versioned association moves chart visibility; originals preserved | V9 `chart_association` + `next_chart_association_version` + `effective_patient_id`, `insertAssociation` snapshot of MRN/issuer/identifiers/consents | `ConsolidationServiceTest.consolidateLocksBothRecordsPreservesEvidenceAndAudits`; `ConsolidationIsolationTest` visibility scalar assertion (DB, integrator) |
| Lock both records during commit | V9 `lock_patient_pair` advisory + `FOR UPDATE`, `lockPair` must return 2 | `ConsolidationServiceTest.consolidateLocksBothRecordsPreservesEvidenceAndAudits`, `consolidateFailsClosedWhenLockIsIncomplete` |
| Block ambiguous orders / links / grants until resolved | V9 `consolidation_dependency` append-fold, `hasUnresolvedBlockers`, 409 `UNRESOLVED_DEPENDENCY` | `ConsolidationServiceTest.consolidateBlocksAmbiguousDependenciesWithoutPartialWrite`; `ConsolidationIsolationTest` blocked-then-resolved flow (DB, integrator) |
| Audited support workflow corrects an erroneous consolidation | `ConsolidationService.correct`, V9 `CORRECTED` revision + `REVERSED` association, `CONSOLIDATION_CORRECT` audit | `ConsolidationServiceTest.correctReversesAssociationWithAuditedReason`; `ConsolidationIsolationTest.correctReversesVisibilityAndAuditsAndHistoryIsImmutable` (DB, integrator) |
| **This attempt:** reject a second effect when a competing approval/correction commits while the caller waits on the pair lock | post-lock `findCurrentConsolidation` re-check in `consolidate` and `correct` | New red→green `consolidateRechecksCurrentStateInsideThePairLock` and `correctRechecksCurrentStateInsideThePairLock` |
| **This attempt:** provenance version must be current under the lock | `correct` now reads the duplicate patient after `lockPair`, mirroring `consolidate` | New red→green `correctSnapshotsPatientVersionReadAfterThePairLock` |
| **This attempt:** an aborted concurrent review insert rolls back explicitly | `status.setRollbackOnly()` in `open`'s `DuplicateKeyException` catch | New red→green `openRollsBackExplicitlyAfterAConcurrentInsertConflict` |

Supporting IDs: ARC-01 (tenant-owned keys), ARC-02 (forced RLS, non-owner runtime role), ARC-03/04 (`If-Match` 409/412/422, 401/403/404), OPS-02 (transactional append-only audit with purpose `IDENTITY_ADMIN`), IAM-01/02 (membership and roles re-derived per request).

## Files changed this attempt

- `apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation/ConsolidationService.java` — post-lock re-validation in `consolidate`/`correct`; `correct` reads the patient after the lock; explicit rollback on `open`'s unique-violation path.
- `apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation/ReviewDetail.java` — repaired a malformed Javadoc/indent block on the `Dependency` record left by a prior attempt (no behaviour change).
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity/ConsolidationServiceTest.java` — five new regression tests plus one existing stub update; a small real-transaction-manager test double.
- `.agent/pr-notes/S-G0-06.md` — this file.
- No shared tracker, generated config, other module or migration was edited.

The rest of the implementation (service, repository, controller, records, V9, 23 inherited unit tests and 8 DB acceptance tests) was inherited from the prior attempt on branch `swarm/S-G0-06-119` (base `614477f`); this attempt brings the consolidation unit total to 29.

## Test-first sequence (tool evidence)

1. Added `consolidateRechecksCurrentStateInsideThePairLock` and `correctRechecksCurrentStateInsideThePairLock`; both stub an empty/valid first read and a committed competing revision on the second read. `-Dtest=ConsolidationServiceTest` → **exit 1, 25 tests, 2 failures** ("Expected ApiException to be thrown, but nothing was thrown"). Genuine red: both commands wrote a second effect on stale state.
2. Added the post-lock re-checks in `consolidate`/`correct`. Targeted run → **exit 0, 25/0**.
3. Added `correctSnapshotsPatientVersionReadAfterThePairLock` (patient v2 before lock, v3 after). Run → **exit 1, 28 tests, 1 failure** (recorded alias version 2, expected 3). Moved `findPatient` after `lockPair` and added a lock stub to the pre-existing `coversRareFailureAndNullInputBranches` test. Run → **exit 0, 28/0**.
4. Added `openRollsBackExplicitlyAfterAConcurrentInsertConflict` with a real `AbstractPlatformTransactionManager`. Run → **exit 1, 29 tests, 1 failure** (`rolledBack` false). Added `status.setRollbackOnly()` in the catch. Run → **exit 0, 29/0**.
5. Added `concurrentConsolidateConflictStaysA409UnderARealTransaction` and `concurrentCorrectionConflictStaysA409UnderARealTransaction` to test a reviewer claim that `setRollbackOnly()` + normal return maps to 500. Both were **green immediately**: Spring's `AbstractPlatformTransactionManager.commit()` short-circuits a locally rollback-only *new* transaction into a silent rollback, after which the service throws the intended 409. The finding is a false positive for this controller entry point; the tests are kept as guards.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConsolidationServiceTest test` | **exit 0**; Surefire 29 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; Surefire 1,691 tests, 0 failures, 0 errors (fast tier excludes `@Tag("integration")`) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` inspection | `ConsolidationService`: 0 missed of 1,206 instructions, 88 branches, 237 lines, 21 methods, 1 class (100%). Controller, policy and all records 100%. `DuplicateRepository` SQL methods remain to the integrator's DB suite. |
| `python3 -I scripts/check-migrations.py --require-baseline` | **exit 0** (V9 still `Change: expand`, unchanged) |
| `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py` | **exit 0** each |
| `git diff --check` | **exit 0** |
| `mvn -q -B -f apps/api/pom.xml spotbugs:check` | **exit 1**, 5 pre-existing findings, **none in `patient/consolidation`**: `InstructionFactController` (messaging), `ConversationRepository` (messaging), `IdentityProofingService` (patient/identity, another worker), `RxController` and `RxRepository` (prescription). Out of scope; not edited. |
| `python3 -I scripts/gen-opencode.py --check`, `python3 -I scripts/check-pack.py` | **exit 1** each: cloud-worker CI profile copied over `opencode.json` under `skip-worktree` (`git ls-files -v` = `S`, worktree differs from HEAD). Rendering the generator and comparing with `git show HEAD:opencode.json` matches, so the committed file is correct; known measurement artifact, not edited. |
| `bash scripts/docs.sh` | **exit 1**: `Install Doxygen to build the developer reference` — Doxygen is not installed locally. No source/doc error was reported. |

## Defects found and decisions

- **Fixed (medium, concurrency integrity):** `consolidate` and `correct` validated "already consolidated / still consolidated" before taking the V9 pair lock. Two callers that read the same snapshot could both pass and append a second consolidation or a second correction. Re-reading the current revision inside the lock closes this; all-or-nothing is preserved and the second caller gets 409 + `CONFLICT` audit.
- **Fixed (medium, provenance):** `correct` snapshotted the duplicate patient's version before the pair lock, unlike `consolidate`, so a concurrent patient edit could be recorded as `alias_version`. Now read after the lock.
- **Fixed (low, transaction hygiene):** `open`'s unique-violation path relied on PostgreSQL turning COMMIT into ROLLBACK. It now marks the transaction rollback-only explicitly; the 409 and audit behaviour is unchanged.
- **Fixed (low, documentation):** malformed Javadoc indentation on `ReviewDetail.Dependency` from a prior attempt.
- **Reviewer claim disproved by execution (no change):** a simulated senior-backend review claimed the `setRollbackOnly()`-then-return pattern in `consolidate`/`correct` produces `UnexpectedRollbackException`/HTTP 500. Two new real-transaction-manager tests show the 409 mapping is correct for the `TransactionTemplate`-created transaction used by this controller; no production change was made for that claim.
- **Decision — no V9 change:** the DB specialist's findings (no DB-level second-reviewer trigger, no DB dependency blocker, no `db/check.sh` V9 section, deadlock ordering, etc.) are defence-in-depth gaps; the application layer enforces each property and the inherited `ConsolidationIsolationTest` proves isolation/immutability through the real API. Changing V9 or `db/check.sh` cannot be verified without local PostgreSQL and exceeds this bounded slice; recorded as residual risks below.
- **Evidence source:** all gate results above are from executed tool commands. Two specialist reviews were simulated static reviews (backend and database); the backend findings were independently checked by executed tests where testable. There was **no simulated-user exercise and no human or clinical participant evidence**. Real clinical approval remains a G5 gate.

## Residual risks / follow-ups (stop-and-report where evidence is genuinely missing)

1. **No local PostgreSQL** (`pg_isready`/`psql` absent; `SMARTCLINIC_PG_ADMIN` unset), so the 8 `@Tag("integration")` tests in `ConsolidationIsolationTest` — forced RLS, two-tenant concealment, lock/version flow, correction reversal, immutable history — **were not executed here**. The integrator must run `bash apps/api/check.sh` on the exact merged SHA; that suite is the authority for `DuplicateRepository` coverage and the JaCoCo 100% bundle gate.
2. **Worklist/correction policy inconsistency (reviewer, MEDIUM, not fixed):** after a correction the latest association is `REVERSED`, so `likelyPairs` re-lists the pair, but `open` returns 409 because the review has a current (CORRECTED) consolidation. Needs a product/owner decision (relist-and-reopen vs exclude) and a V9 constraint change; deferred with an owner.
3. **Dependency fold ordering (reviewer, MEDIUM-LOW, not fixed):** V9 `created_at DEFAULT now()` is transaction-constant and `id` is a random UUID, so two dependency-state rows appended in one transaction have a non-deterministic "latest" fold. Not reachable through the current API (no dependency-write command); recommend a monotonic `seq` in a future migration.
4. **DB-layer defence in depth (database reviewer, HIGH/MEDIUM, not fixed):** no trigger forces `approver_subject` from `app.actor` or rejects approver == opener, no DB trigger blocks a consolidation with unresolved dependencies, `lock_patient_pair` returns a count instead of raising, and `db/check.sh` has no V9 section. All are enforced in `ConsolidationService` today; hardening belongs in a separately reviewed migration + DB-gate story.
5. **`open` duplicate-insert race is idempotency-imperfect (reviewer, LOW, not fully fixed):** a concurrent loser still gets 409 rather than the pre-existing ticket promised by the Javadoc; the transaction now rolls back explicitly but no CONFLICT audit is appended. Deferred with an owner.
6. **Environment/tooling:** stale-looking pack check from the cloud-worker `opencode.json` profile, missing Doxygen, and 5 pre-existing out-of-scope SpotBugs findings are recorded above; none was edited here.
7. **No patient data, real identifiers or credentials** appear in any fixture or note; every subject, organization, MRN and reference is synthetic.

---

## Additional hard pass — 2026-11-20 (this worker)

Two bounded read-only specialist reviews were run on the inherited implementation: `smartclinic-senior-database` (V9 SQL + `DuplicateRepository`) and `smartclinic-senior-backend` (`ConsolidationService` + controller). Both returned one concrete, Java-only IAM-04 integrity defect each. I independently reproduced every finding as a red unit test before changing production code, then fixed all of them plus a third fail-open I found while reviewing the same path. No migration, tracker, generated config or file outside the declared main/test packages was touched; V9 remains `Change: expand` and unchanged.

### New defects fixed (all IAM-04 integrity, all Java-only)

1. **`open()` fail-open on a missing read-back (found by this worker).** After a successful `insertReview`, `findReviewByPair(...).orElse(null)` could return `null`; the method logged `SUCCESS` and returned HTTP 201 with a null body. It now logs `CONFLICT` and returns 409 `CONFLICT`, so the caller retries through the idempotent re-open path instead of receiving a phantom success.
2. **Reversed-pair reopen creates a chart-visibility cycle (found by the database reviewer).** V9's `UNIQUE (organization_id, survivor_id, duplicate_id)` is on the **ordered** pair. `open(survivor=B, duplicate=A)` found no review for a pair already consolidated as `(A,B)`, appended a second review, and a second reviewer could append an inverse ACTIVE association so `effective_patient_id(A)=B` and `effective_patient_id(B)=A`. `open()` now checks both orientations using the existing `findReviewByPair` method; the idempotent-reopen and already-consolidated 409 branches apply regardless of client-supplied order.
3. **A second consolidation of the same duplicate under a different review (found by the backend reviewer).** Two legal reviews `(A,D)` and `(B,D)` share duplicate D; the append-only patient version is unchanged by an association, so the `If-Match` version and same-review recheck both passed for the second approval, silently moving D from A to B. `consolidate()` now fails closed under the pair lock when either the duplicate **or the survivor** no longer resolves to itself via `repository.effectivePatientId` (the same fact the read path exposes as `ReviewDetail.chartVisibilityId`). This also closes the concurrent reversed-pair race where two `open()` calls pass the orientation check before either inserts.

### Red → green sequence (tool evidence, synthetic fixtures)

| New test | Before fix | After fix |
|---|---|---|
| `openFailsClosedWhenCreatedReviewIsNotVisible` | **red** — "Expected ApiException to be thrown, but nothing was thrown" (returned 201/null) | green — 409 + `CONFLICT` audit |
| `openRejectsReversedOrientationOfAnAlreadyConsolidatedPair` | **red** — no exception; a second review was inserted | green — 409, `insertReview` never called, `CONFLICT` audit |
| `consolidateRefusesDuplicateWhoseChartAlreadyMovedUnderAnotherReview` | **red** — no exception; both appends occurred | green — 409, no appends, `CONFLICT` audit |
| `consolidateRefusesSurvivorWhoseChartAlreadyMovedUnderAnotherReview` | **red** — no exception; inverse cycle appended | green — 409, no appends, `CONFLICT` audit |

A default "chart resolves to itself" stub was added in `setUp()` so existing success tests stay precise; tests that simulate a moved chart override it. The pre-existing test that asserted the old null-return behaviour was updated to assert the new 409.

### Files changed in this pass

- `apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation/ConsolidationService.java` — three guards described above.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity/ConsolidationServiceTest.java` — four new red/green tests, one updated assertion, default self-visibility stub, removed unused import.
- `.agent/pr-notes/S-G0-06.md` — this section.
- No other file (no migration, tracker, config, other module).

### Exact commands and results in this pass (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConsolidationServiceTest test` (after each red test) | **exit 1**, 30/31/32/33 tests, 1 failure each time — "Expected ApiException to be thrown, but nothing was thrown" |
| Same command after each fix | **exit 0**; final **33 tests, 0 failures, 0 errors** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; Surefire aggregate **1,764 tests, 0 failures, 0 errors, 0 skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` | `ConsolidationService`: **0 missed** of 1,284 instructions, 92 branches, 249 lines, 22 methods, 1 class (100%); controller, policy and all records still 100%. `DuplicateRepository` SQL methods remain to the integrator's DB suite. |
| `python3 -I scripts/check-migrations.py --require-baseline`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `git diff --check` | **exit 0** each |
| `mvn -q -B -f apps/api/pom.xml spotbugs:check` | **exit 1**, 5 findings, **none in `patient/consolidation`**: `InstructionFactController` and `ConversationRepository` (messaging), `IdentityProofingService` (other worker), `RxController` and `RxRepository` (prescription). Pre-existing and out of scope; not edited. |

### Reviewer / evidence provenance

- Defects 2 and 3 came from two **simulated read-only specialist reviews** (senior-database, senior-backend). Each prediction was independently converted into a failing unit test by this worker; the red results above are **executed tool evidence**, not reviewer assertion.
- Defect 1 was found by this worker's own review of the same code path.
- **No simulated-user exercise and no human or clinical participant evidence** in this pass. Real clinical approval is a G5 gate.

### Residual risks after this pass

1. **No local PostgreSQL** (`psql`/`pg_isready` absent, `SMARTCLINIC_PG_ADMIN` unset), so the 6 `@Tag("integration")` scenarios in `ConsolidationIsolationTest` (forced RLS, two-tenant concealment, lock/version flow, correction reversal, immutable history) were **not run here**. The integrator must run `bash apps/api/check.sh` on the exact merged SHA; that is the authority for `DuplicateRepository` coverage and the JaCoCo 100% bundle gate.
2. **DB-level unordered-pair uniqueness is still absent.** The Java orientation check and visibility guards close every API-reachable path, but a concurrent pair of `open()` calls (or a direct DB writer) is only fully prevented by a unique index on `(least(survivor_id, duplicate_id), greatest(...))`. That requires a new migration beyond the reserved V9 and is recorded as a follow-up, not attempted here.
3. The earlier residual risks (dependency-fold ordering, worklist-after-correction policy, DB trigger defence in depth, insert-race idempotency) still stand unchanged.

---

## Additional bounded pass — IAM-04 two-identifier confirmation gate (this worker)

Two fresh, independent read-only specialist reviews were run: `smartclinic-physician-reviewer` (clinical safety) and `smartclinic-senior-backend` (code correctness). Per AGENTS.md each prediction was independently converted into a red test by this worker before any production change.

### Review outcomes

- **Senior-backend (simulated static review): no new API-reachable correctness defect.** It re-verified and ruled out the already-fixed races, the pair-lock serialization, `IfMatch` handling and rollback on `DuplicateKeyException`. It independently confirmed the `actor_id = NULL` concern is a **false positive**: V3's `audit_event_before_insert()` derives `actor_id` from `app.actor` when the caller passes NULL. Its only residual (a `privacy_auditor` may open/approve a merge) is a documented policy choice, not a code bug. No code change from this review.
- **Physician/clinical-safety (simulated static review): one genuine code gap** — IAM-04 requires "Confirm two identifiers at high-risk transitions when available, including ... chart consolidation", but `consolidate()` accepted only a free-text `reason` and a second subject. A wrong-patient merge was achievable through the normal HTTP entry point from a name+DOB warning with reason `"merge"` and no identifier question. Harm: wrong-patient chart merge → allergy/medication errors and wrong care. This is a code control; *which* identifiers qualify and the acceptability of non-clinician reviewers remain G5 site policy decisions.

### Fix implemented (test-first, Java-only, inside scope)

- New `ConsolidationController.ConsolidationApprovalRequest(reason, confirmedIdentifiers, alternateProcess)` is now the `POST /api/v1/duplicate-reviews/{reviewId}/consolidate` body; `correction` keeps `ReasonRequest` unchanged. The confirmation list is frozen on construction and returned as a defensive copy (SpotBugs clean).
- `ConsolidationService.consolidate(...)` now validates, **after taking the pair lock and reading both patients**, either (a) two distinct issuer-scoped `<value>@<issuer>` identifiers that match the survivor's and duplicate's actual MRN+issuer, or (b) one bounded `alternateProcess` code naming the documented approved process. The two modes are mutually exclusive. Failures are 422 with machine codes `IDENTIFIER_CONFIRMATION_REQUIRED`, `INVALID_IDENTIFIER_CONFIRMATION`, `IDENTIFIER_MISMATCH`, `INVALID_ALTERNATE_PROCESS` or `CONFLICTING_CONFIRMATION`.
- A `CONSOLIDATION_IDENTIFIER_CONFIRM` audit event (purpose `IDENTITY_ADMIN`, scope `PATIENT`) is appended in the same transaction with outcome `CONFIRMED:<ids>` or `ALTERNATE:<code>`, and `DENIED` on rejection — a durable record of the identity basis for a high-risk merge (OPS-02).
- Integration fixtures now send the two issuer-scoped MRNs; a new DB scenario proves wrong identifiers are refused with no partial write and the alternate-process path still consolidates and is audited.

### Red → green (tool evidence, synthetic fixtures)

| New test | Before fix | After fix |
|---|---|---|
| `consolidateRequiresTwoIdentifiersConfirmedFromBothRecords`, `consolidateRejectsRepeatedConfirmedIdentifier`, `consolidateRejectsMalformedConfirmedIdentifier`, `consolidateRejectsConfirmedIdentifierAbsentFromBothRecords`, `consolidateRejectsConfirmationCombinedWithAlternateProcess`, `consolidateRejectsMalformedAlternateProcess` | **red** — "Expected ApiException to be thrown, but nothing was thrown" | green — 422 with the exact code, no appends |
| `consolidateAcceptsDocumentedAlternateProcess` | **red** — expected `ALTERNATE:site_witness` audit not emitted | green — audit recorded |
| `consolidateRequiresConfirmationWhenListIsNullOrEmpty`, `consolidateRejectsNullConfirmedIdentifierElement`, `consolidateRejectsConfirmationMissingOneRecordsIdentifier`, `consolidateTreatsBlankAlternateAsAbsent` | added to close every branch | all green, 0 missed branches in the new code |
| `approvalRequestDeserializesTheHttpContract` | n/a (contract guard) | green — Jackson 3 deserializes both modes |

### Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ConsolidationServiceTest test` (after red tests) | **exit 1**, 41 tests, 7 failures — confirmation gate absent |
| Same command after fix and branch-closing tests | **exit 0**, **46 tests, 0 failures/errors** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**, **1,793 tests, 0 failures/errors/skipped** |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast jacoco:report` + `jacoco.xml` | New code: **0 missed instructions/branches/lines/methods**; only the 2 pre-existing `DuplicateRepository` null-scalar branches remain in the fast tier (integrator's DB suite). `mvn verify` enforces 100% for all five counters. |
| `mvn -B -f apps/api/pom.xml spotbugs:check` | **exit 0**, BugInstance size 0, no errors/warnings |
| `python3 -I scripts/check-migrations.py --require-baseline`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-secrets.py`, `git diff --check`, `test_java_conventions.py` | **exit 0** each (V9 unchanged, still `Change: expand`) |
| Full Python tooling suite | failures confined to `test_agent_run`/`test_swarm`/`test_check_pack`: the documented cloud-worker CI-profile artifact (worktree `opencode.json` under `skip-worktree`, flag `S`, differs from HEAD). None relate to consolidation. |

### Files changed in this pass

- `apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation/ConsolidationService.java` — confirmation gate + audit.
- `apps/api/src/main/java/com/aurevia/smartclinic/patient/consolidation/ConsolidationController.java` — new approval request record and binding.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity/ConsolidationServiceTest.java` — 13 new tests, updated call sites.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/patientidentity/ConsolidationIsolationTest.java` — fixture payloads plus one new DB scenario.
- `.agent/pr-notes/S-G0-06.md` — this section.
- No migration, tracker, config or other module was edited.

### Public contract change (for the PR description)

`POST /api/v1/duplicate-reviews/{reviewId}/consolidate` now requires either `confirmedIdentifiers: string[]` (issuer-scoped `value@issuer` refs, one per record) or `alternateProcess: string` (bounded code). Missing/invalid confirmation → 422. `correct` is unchanged. No other endpoint changes.

### Residual risks after this pass

1. **No local PostgreSQL** (`psql`/`pg_isready` absent, `SMARTCLINIC_PG_ADMIN` unset), so the 9 `@Tag("integration")` scenarios were **not executed here**. The integrator must run `bash apps/api/check.sh` (`mvn verify`) on the exact merged SHA; that is the authority for `DuplicateRepository` coverage and the JaCoCo 100% bundle gate.
2. **Alternate-process approval is not code-enforced.** `alternateProcess` is a bounded code, not validated against a site-approved list; the approved list and when it may be used are G5 site-policy decisions. The code forces explicit naming and an auditable record.
3. **Clinical judgment remains human.** The gate proves the caller attested two record-matching identifiers; it cannot determine that two people are the same. Survivorship criteria, reviewer role (admin/privacy vs clinician), matching rules and downstream reconciliation of artifacts created during a wrong-merge window are G5 clinical/operational decisions, unchanged from the earlier residual list.
4. Earlier residuals (unordered-pair DB uniqueness, dependency-fold ordering, worklist-after-correction policy, DB trigger defence in depth, insert-race idempotency) still stand unchanged.
5. **Evidence provenance:** gate results are executed tool evidence; the two specialist reviews were simulated static reviews (no builds run by reviewers). **No simulated-user exercise and no human or clinical participant evidence** in this pass. Real clinical approval is a G5 gate. No patient data, real identifiers or credentials appear in any fixture or note; all subjects, MRNs and issuers are synthetic.
