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
