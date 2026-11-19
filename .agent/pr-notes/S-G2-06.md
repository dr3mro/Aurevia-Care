# S-G2-06 — Patient release authorization, timed/hold policies and withdrawn releases (XCH-06)

## Requirement IDs

Primary: **XCH-06**. Supporting: **PAT-02**, **IAM-01**, **ARC-01/02/03/04/05**, **OPS-02**, **PRD-06**, and G3's "withdrawn releases stop future access" outcome.

## Scope and path note

Implementation lives in `apps/api/src/main/java/com/aurevia/smartclinic/exchange/release` and `apps/api/src/test/java/com/aurevia/smartclinic/it/exchangerelease`, with the reserved migration `db/migrations/V24__patient_release.sql`. The dispatch text named `.../g2/s_g2_06` and `.../it/s_g2_06`, but a repository-wide refactor (`.agent/pr-notes/REFACTOR-PACKAGES-20261119-201815.md`) renamed all story-ID Java packages to business-domain packages; `g2/s_g2_06` → `exchange/release`. CODING_STYLE.md forbids story IDs in Java package names, so the refactored path is the correct in-scope location. No other story's files, trackers, or migration versions were touched.

## Acceptance mapping (requirement → change → test)

| XCH-06 behaviour | Implementation | Evidence |
|---|---|---|
| Separate authorization referencing immutable revision id/version, policy version, effective time; issuer/author derived from context | `AuthorizeRelease`, `PatientRelease`, `ReleaseService.authorize`, V24 triggers | `ReleaseServiceTest.authorizeImmediateIsVisibleAndNotifies`; `PatientReleaseIsolationTest.immediateReleaseIsAuthorizedAuditedAndNotified`, `forgedAuthorAndIssuerAreDerivedFromContext` |
| IMMEDIATE now / TIMED from effective instant / HOLD only after authorized review | `ReleaseKind`, `ReleaseState.visibleAt`, `PatientRelease.visibleNow`, `ReleaseService` | `ReleasePolicyTest.visibilityRequiresAuthorizedAndReachedEffectiveTime`; `ReleaseServiceTest.authorizeTimedFutureIsNotVisibleYet`, `authorizeHoldIsNotVisibleUntilReviewed`; `PatientReleaseIsolationTest.timedReleaseIsNotVisibleBeforeEffectiveTime`, `holdStaysInvisibleUntilAuthorizedReview` |
| Hold carries reason + future expiry + reviewer; a hold never opens by elapsed time alone | `ReleaseService.authorize/liftHold`, V24 HOLD `CHECK` | `ReleaseServiceTest.rejectsHoldWithoutReason/...Expiry/...InPast`, `liftHoldMakesReleaseVisible` |
| Withdrawal stops future access, retains append-only history, notifies prior recipients | `ReleaseService.withdraw`, `ReleaseState`, V24 ledgers | `ReleaseServiceTest.withdrawStopsAccessAndNotifiesPriorRecipients`; `PatientReleaseIsolationTest.withdrawalStopsAccessRetainsHistoryAndNotifies` |
| Correction updates current access, supersedes old revision, retains history, notifies recipients | `ReleaseService.correct` | `ReleaseServiceTest.correctSupersedesAndCreatesNewRevision`; `PatientReleaseIsolationTest.correctionSupersedesRevisionAndRedirectsAccess` |
| 401/403/404/409/412/422, active-membership recheck, version precondition | `ReleaseActionPolicy`, `ReleaseService`, `TenantContextInterceptor`, `IfMatch` | `ReleaseServiceTest.*Denies*`, `correctRejectsStaleVersion`, `withdrawRejectsStaleVersion`; `PatientReleaseIsolationTest.permissionAndInputBoundaries` |
| Two tenants with identical local ids stay isolated; forced RLS; append-only ledgers | V24 forced RLS, grants, immutability triggers; guarded repository | `PatientReleaseIsolationTest.identicalLocalIdentifiersStayIsolatedAcrossTenants`, `historyIsAppendOnlyAndRuntimeRoleCannotBypassRls` |

### Entry-point-to-test trace

`POST /api/v1/releases` → `PatientReleaseController.authorize` → `ReleaseService.authorize` → guarded `TransactionTemplate` → `ReleaseRepository.findAnyByPatientAndRevision` / `insertRelease` / `insertEvent` / `insertNotification` / `appendAudit` → 201. `GET /api/v1/patients/{id}/released-revisions` → `PatientReleasedRevisionsController.visibleRevisions` → `ReleaseService.visibleRevisions` → `findAuthorizedByPatient` + `PatientRelease.visibleNow(clock)`. `POST /api/v1/releases/{id}/{withdraw|correct|lift-hold}` → controller → `ReleaseService.transition`/`correct` → CAS `UPDATE` + append-only ledgers. `PatientReleaseIsolationTest` exercises every route end-to-end against real PostgreSQL with two synthetic tenants; `ReleaseServiceTest`/`ReleasePolicyTest` cover the domain branches.

## Defects fixed in this change (all test-first, red → green)

The received base already contained the slice and three earlier fixes (lifted-HOLD correction kind; duplicate-key 500 in `authorize`; TIMED effective-time collapse) — I verified those fixes are present and their tests exist, but did not re-run their original red steps. This change adds five further fixes:

1. **Correcting onto a revision that already has a live release lost the CONFLICT audit.** `correct()` inserted the replacement without the pre-insert check used by `authorize()`; a unique violation rolled back the whole transaction including the audit row. Fix: pre-check `findAnyByPatientAndRevision` before the CAS update, append a `CONFLICT` audit and return 409. Red: `NeverWantedButInvoked: releaseRepository.correct("rel-1", 1)`; green after fix. New tests: `ReleaseServiceTest.correctRejectsAlreadyReleasedReplacementRevisionAndAuditsConflict` and `PatientReleaseIsolationTest.correctionOntoAnAlreadyReleasedRevisionIsAuditedAsConflict`.
2. **Correcting a lifted HOLD with a future effective instant opened access early.** The replacement hardcoded `clock.instant()`, discarding a configured future `effectiveFrom`. Fix: every replacement inherits `current.effectiveFrom()` (kind still converts to IMMEDIATE for a lifted hold). Red: `expected: <2026-06-01T11:00:00Z> but was: <2026-06-01T10:00:00Z>`; green after fix. New test: `ReleaseServiceTest.correctOfReviewedHoldWithFutureEffectiveTimeKeepsIt`.
3. **`HELD → CORRECTED` disagreed across the enum, service and V24 trigger.** The service blocked it but the enum and DB allowed it. Fix: removed the transition from `ReleaseState.canTransitionTo` and the V24 guard trigger, matching the service's stricter invariant that an unreviewed hold must be lifted first. Red: `expected: <false> but was: <true>`; green after fix. Updated: `ReleasePolicyTest.heldCanGoToAuthorizedOrWithdrawnOnly`.
4. **A withdrawn or corrected revision could be re-authorized, re-opening retracted access.** The repository lookup and the V24 uniqueness guard only covered live states, so a terminal `(patient, revision)` looked free. Fix: `findAnyByPatientAndRevision` now queries all states; the V24 unique index now covers all states; `correct()` rejects a replacement equal to the current revision. New tests: `ReleaseServiceTest.correctRejectsReplacingARevisionWithItself` (red: `NeverWantedButInvoked: releaseRepository.correct("rel-1", 1)`), plus integration tests `withdrawnRevisionCannotBeReauthorized` and `supersededRevisionCannotBeReauthorizedOrUsedAsItsOwnCorrection`.
5. **Coverage/consistency:** renamed the repository method and updated all callers/stubs so no stale "active-only" assumption remains.

## Commands run and exact results

- `mvn -q -B -Pno-security,fast -Dtest=ReleaseServiceTest#correctRejectsAlreadyReleasedReplacementRevisionAndAuditsConflict test` → red (exit 1, 1 failure, `NeverWantedButInvoked`), then green (exit 0) after fix.
- `mvn -q -B -Pno-security,fast -Dtest=ReleaseServiceTest#correctOfReviewedHoldWithFutureEffectiveTimeKeepsIt test` → red (exit 1, `expected: <…11:00:00Z> but was: <…10:00:00Z>`), then green.
- `mvn -q -B -Pno-security,fast -Dtest=ReleasePolicyTest#heldCanGoToAuthorizedOrWithdrawnOnly test` → red (exit 1, `expected: <false> but was: <true>`), then green.
- `mvn -q -B -Pno-security,fast -Dtest=ReleaseServiceTest#correctRejectsReplacingARevisionWithItself test` → red (exit 1, `NeverWantedButInvoked`), then green after fix.
- `mvn -q -B -Pno-security,fast test` → **green**, exit 0; aggregated Surefire XML: 143 suites, **1645 tests, 0 failures, 0 errors, 0 skipped**; release slices: `ReleaseServiceTest` 53 tests, `ReleasePolicyTest` 9 tests, all green. (Integration-tagged suites compile but are excluded from this worker tier, per repo policy.)
- `python3 -I scripts/check-migrations.py --require-baseline` → **exit 0** (reserved V24 still valid; `--require-baseline` satisfied).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 1 pre-existing out-of-scope failure in `exchange/trend/Qualifier.java` (S-G2-07, `{@link #from(String)}` not fully qualified); no release-related failures.
- `bash scripts/quick-check.sh` → stops at `check-pack: opencode.json is stale` — the documented CI-profile artifact (worktree `opencode.json` is the `skip-worktree` CI profile and differs from HEAD; `gen-opencode.py --check` therefore fails in this worker). Unrelated to this story.

## Evidence not available in this worker (integrator must run)

- **Database / integration tests and JaCoCo 100% gates were NOT run here.** No PostgreSQL binaries/service and no Docker in this container (port 5432 closed; `pg_isready` absent). I did not fabricate a DB or coverage result. The three new integration tests plus the existing `PatientReleaseIsolationTest` must run against real PostgreSQL via `bash apps/api/check.sh` (`mvn verify`); that gate enforces 100% instruction/line/branch/method/class coverage.
- Out-of-scope pre-existing tooling failures observed on this base (not caused by this change): `scripts/tests` has unrelated `agent-run.sh`/swarm/CI-profile failures and the `Qualifier.java` convention finding above. I did not edit them (scope boundary).

## Decisions and owner questions still needed (outside this story's scope)

Three independent bounded reviews (simulated: senior-backend, physician-clinical-safety, DevSecOps) completed. In addition to the fixes above, they raised cross-cutting items that need a Product Owner / Architect / clinical owner decision before G3 acceptance; I did **not** implement them here because they require other modules, migrations or site policy:

1. **Patient self-access to released revisions (HIGH, all three reviewers).** `GET /api/v1/patients/{patientId}/released-revisions` and `ReleaseService.visibleRevisions` authorize staff roles only and take `patientId` from the path with no verified patient→chart-link binding. A real `patient` principal gets 403; conversely a staff reader could enumerate another patient's revision IDs. Implementing this needs the V10 `patient_account`/verified chart-link model and a decision on whether it belongs in this story or the G3 patient-access story. **Owner decision required.**
2. **Wrong-patient / unverified revision on authorize and correct (HIGH, physician/backend).** `patientId`, `revisionId` and `revisionVersion` are format-checked but not resolved against the authoritative XCH-04 report-revision store (`V22`), so a release can reference a nonexistent or unrelated revision; `issuingOrganizationId` defaults to the active tenant. Needs the revision store and a cross-module FK/validation, plus a decision on issuer provenance for partner results. **Architect decision required.**
3. **Resource-level authorization and release-decider policy (HIGH/MEDIUM, physician/DevSecOps).** Release decisions check active membership + role only (no care relationship / facility scope), and `tenant_admin` is currently a decider although PRD-06 reserves release decisions/attestation to clinical users. Changing the decider set is a product/clinical policy decision (G5 site approval), so I left the existing policy and its tests unchanged. **Owner decision required.**
4. **Notification recipient resolution and patient-report-store coupling (MEDIUM).** `release_notification.recipient_scope` is a generic string with no resolved recipients, and a withdrawal here does not mutate the separate in-memory store used by `messaging/reports`. Cross-slice integration; flag to architect. **Owner decision required.**
5. **Policy version validation (LOW).** `policyVersion` is only checked positive, not against an approved effective release-policy configuration (ADM-01/V5). Needs an ADM-01 configuration lookup. **Deferred with owner.**

These are recorded as decisions/evidence gaps rather than silently resolved, per the instruction to stop and say so when a decision is missing.

## Evidence sources

Implementation, fixes and all command results: executed tools (author) on synthetic fixtures. Defects 1–5: bounded simulated reviews by the `smartclinic-senior-backend`, `smartclinic-physician-reviewer` and `smartclinic-devsecops` specialists, each reproduced as a failing test before the fix (reviewers are advisory; they did not execute tests). No simulated-user exercise and no human/clinical participant evidence; real-site clinical approval remains a G5 human gate.
