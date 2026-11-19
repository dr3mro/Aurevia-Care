# S-G2-06 — Patient release authorization, timed/hold policies and withdrawn releases (XCH-06)

## Requirement IDs

Primary: **XCH-06** (patient release authorization; immediate/timed/hold policies; corrected and withdrawn releases update current access, retain history and notify recipients).
Supporting: **PAT-02** (released report revision and access), **IAM-01** (role/action policy, active membership), **ARC-01/ARC-02** (tenant-scoped keys, forced RLS), **ARC-03/ARC-04** (version precondition and 401/403/404/409/412/422 semantics), **OPS-02** (append-only audit and outbox), **PRD-06** (a hold does not open access by elapsed time alone).

## State of the base snapshot

The worker base (`ddbd816`) already contained the full S-G2-06 slice from earlier attempts: ten production classes in `g2/s_g2_06`, the reserved `V24__patient_release.sql` migration, two fast test classes and one `@Tag("integration")` HTTP/PostgreSQL test. This PR keeps that implementation and fixes three genuine defects found in review (test first, red then green). No other story's files, shared trackers, migration versions other than the reserved V24, commands or environment settings were changed.

## Acceptance mapping (requirement → change → test)

| Requirement / acceptance | Where | Evidence |
|---|---|---|
| Separate authorization references immutable revision id/version, policy version and effective time | `AuthorizeRelease`, `PatientRelease`, `ReleaseService.authorize`, V24 | `ReleaseServiceTest.authorizeImmediateIsVisibleAndNotifies`; `PatientReleaseIsolationTest.immediateReleaseIsAuthorizedAuditedAndNotified`, `forgedAuthorAndIssuerAreDerivedFromContext` |
| IMMEDIATE visible now; TIMED visible only from effective instant; HOLD invisible until authorized review | `PatientRelease.visibleNow`, `ReleaseState.visibleAt`, `ReleaseKind`, `ReleaseService` | `ReleasePolicyTest.visibilityRequiresAuthorizedAndReachedEffectiveTime`; `ReleaseServiceTest.authorizeTimedFutureIsNotVisibleYet`, `authorizeHoldIsNotVisibleUntilReviewed`; `PatientReleaseIsolationTest.timedReleaseIsNotVisibleBeforeEffectiveTime`, `holdStaysInvisibleUntilAuthorizedReview` |
| Hold requires reason + future expiry + reviewer; reviewer/author derived server-side | `ReleaseService.authorize`, `liftHold`; V24 actor triggers | `ReleaseServiceTest.rejectsHoldWithoutReason/Expiry/…InPast`, `liftHoldMakesReleaseVisible`; `PatientReleaseIsolationTest.holdStaysInvisibleUntilAuthorizedReview` |
| Withdrawal stops future access, retains history, notifies prior recipients | `ReleaseService.withdraw`, `ReleaseState` | `ReleaseServiceTest.withdrawStopsAccessAndNotifiesPriorRecipients`; `PatientReleaseIsolationTest.withdrawalStopsAccessRetainsHistoryAndNotifies` |
| Correction updates current access, supersedes old revision, retains history, notifies | `ReleaseService.correct` | `ReleaseServiceTest.correctSupersedesAndCreatesNewRevision`; `PatientReleaseIsolationTest.correctionSupersedesRevisionAndRedirectsAccess` |
| Corrected HOLD publishes a visible replacement (defect fix) | `ReleaseService.correct` | **new** `ReleaseServiceTest.correctOfReviewedHoldPublishesAnImmediatelyVisibleReplacement`; **new** `PatientReleaseIsolationTest.correctionOfReviewedHoldPublishesAnImmediatelyVisibleReplacement` |
| Correction does not silently open a TIMED release early (defect fix) | `ReleaseService.correct` | **new** `ReleaseServiceTest.correctPreservesTimedReleasePolicy`; **new** `PatientReleaseIsolationTest.correctionOfTimedReleaseKeepsItsEffectiveTime` |
| Duplicate live revision returns 409, not a 500 on an aborted transaction (defect fix) | `ReleaseService.authorize` | amended `ReleaseServiceTest.authorizeRejectsConcurrentDuplicateWithoutFurtherSqlOnAbortedTransaction` |
| Role authorization, active-membership recheck and machine-readable outcomes | `ReleaseActionPolicy`, `ReleaseService.authorize` | `ReleasePolicyTest.deciderActionsRequireClinicianOrTenantAdmin`; `ReleaseServiceTest.authorizeDeniesInactiveMembershipAndAudits`; `PatientReleaseIsolationTest.permissionAndInputBoundaries` |
| Tenant isolation with identical local ids; append-only ledgers; RLS cannot be bypassed | V24 forced RLS, immutability triggers, grants; guarded repository | `PatientReleaseIsolationTest.identicalLocalIdentifiersStayIsolatedAcrossTenants`, `historyIsAppendOnlyAndRuntimeRoleCannotBypassRls` |

## Defects found and fixed (all test-first, red → green)

1. **Correcting a reviewed HOLD failed with HTTP 500 and left no patient access.** A lift changes only `state`/`version`; `kind` stays `HOLD`. `correct()` copied `kind` into the replacement while passing null hold reason/expiry, violating V24's `CHECK (kind <> 'HOLD' OR (…hold_reason… AND hold_expires_at IS NOT NULL))`. Fix: a lifted-hold replacement is created as `IMMEDIATE` with a fresh effective instant (no second review, no hold metadata). Evidence: red `expected: <IMMEDIATE> but was: <HOLD>`; green after fix. Confirmed independently by the senior-backend reviewer.
2. **Duplicate-key race returned 500 instead of the documented 409.** `authorize()` caught `DuplicateKeyException` but then issued an audit INSERT inside the now-aborted PostgreSQL transaction (SQLSTATE 25P02), so `InFailedSqlTransaction` escaped. The unit test passed only because the repository was mocked. Fix: on duplicate key, set the 409 failure and issue no further SQL; detectable conflicts are still audited by the pre-insert live-release check. Evidence: red `Unexpected exception type thrown, expected: <ApiException> but was: <IllegalStateException>` (aborted-transaction simulation); green after fix. Found by the senior-backend reviewer.
3. **Correction silently collapsed TIMED policy.** `correct()` hardcoded `clock.instant()` as the replacement's effective time, making a not-yet-effective TIMED revision immediately visible. Fix: non-hold replacements keep the original `kind` and `effectiveFrom`; only a lifted hold converts to IMMEDIATE/now. Evidence: red `expected: <TIMED> but was: <IMMEDIATE>`; green after fix. Found by the senior-backend reviewer.

No production file was left uncovered by a unit or integration test; all three fixes have both a fast unit test and an end-to-end HTTP/PostgreSQL test.

## Commands run and results (all evidence from executed tools unless stated)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=ReleaseServiceTest test` — **red** after adding the hold-correct test: `Tests run: 49, Failures: 1` (`expected: <IMMEDIATE> but was: <HOLD>`).
- Same command after fix 1 — green (49 tests, 0 failures).
- Same command after adding tests for defects 2 and 3 — **red**: `Tests run: 50, Failures: 2` (`expected: <TIMED> but was: <IMMEDIATE>`; `Unexpected exception type … IllegalStateException`).
- Same command after fixes 2 and 3 — green (50 tests, 0 failures).
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` — **green**, full fast unit suite `tests=1514 errors=0 failures=0 skipped=0` (Surefire XML aggregated); includes 50 `ReleaseServiceTest`, 9 `ReleasePolicyTest`. Integration-tagged suites are compiled but excluded from execution in this tier, per repo policy.
- `python3 -I scripts/check-migrations.py --require-baseline` — exit 0 (reserved V24 unchanged, still valid).
- `bash scripts/docs.sh` — exit 0 ("Install Doxygen to build the developer reference"; Doxygen is not installed in this worker container, so the reference build is skipped here).
- `git status --short` — only the three in-scope files above plus this notes file are modified.

## Evidence not available in this worker (for the integrator)

- **Database / integration tests were NOT run here.** No PostgreSQL (`pg_isready`/port 5432 closed) and no Docker in this container. The two new integration tests and the existing `PatientReleaseIsolationTest` must be run by the integrator against real PostgreSQL (`bash apps/api/check.sh`, `mvn verify`, JaCoCo 100% gates). I did not fabricate an integration or coverage result.
- `python3 -I -m unittest discover -s scripts/tests -q` reports pre-existing, out-of-scope failures on this base snapshot (163 tests, 8 failures/4 errors): swarm plan open-task counts, `agent-run.sh` git/push tests, `check-pack.py` (CI-profile config artifact), and `test_java_conventions` findings in `messaging/s_g3_12/G3EvidenceService.java` (unresolved `{@link}`) and `PatientFlowContract.java` (missing `List.copyOf`). None are in `g2/s_g2_06`, none are caused by this change, and fixing them would breach this story's scope. Flagged for the integrator/owner.
- 100% JaCoCo coverage is enforced only by `mvn verify` with the integration suites; that gate is the integrator's. No coverage number is claimed here.

## Decisions

- A corrected revision inherits the original release policy; only a hold that a reviewer has already lifted is converted to IMMEDIATE (the hold requirement is satisfied, so no second review and no hold metadata). This preserves a configured TIMED release instant through correction instead of silently advancing it.
- On a duplicate-key race, the 409 wins over an in-transaction conflict audit: the aborted transaction cannot accept more SQL, and the pre-insert check already audits detectable conflicts. Kept minimal rather than introducing a savepoint.
- Kept all code inside the declared scope paths; class names remain business-domain names (`ReleaseService`, `PatientRelease`, …), matching the repo's existing per-story directory convention. No tracker files were edited.

## Evidence sources

Implementation and fixes: tool-verified code and executed Maven/Surefire commands (author). Defects 2 and 3 and confirmation of defect 1: bounded simulated review by the `smartclinic-senior-backend` specialist (reviewer evidence, reproduced as failing tests before the fix). No simulated-user exercise and no human/clinical participant evidence; real-patient approval remains a G5 gate.
