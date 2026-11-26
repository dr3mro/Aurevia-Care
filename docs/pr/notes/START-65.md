# START-65 — PT-01 negative-path and tenant-isolation tests

## What was done

Added `apps/api/src/test/java/com/aurevia/therapy/TherapyNegativePathsTest.java` (21 tests).

The class lives in the same package as the existing therapy unit tests and uses
`InMemoryTherapyStore` with `TherapyService` directly (no Spring context, no database).

Coverage per the task acceptance criteria:

| Scenario | Test methods |
|---|---|
| Missing / blank / too-long input | `acceptReferral_nullReferralIdIsRejected`, `_blankReferralIdIsRejected`, `_tooLongReferralIdIsRejected`, `_nullPatientIdIsRejected`, `_malformedPatientIdIsRejected`, `recordNote_nullKindIsRejected`, `_blankContentIsRejected`, `recordEvaluation_emptyProblemsIsRejected`, `_zeroFrequencyIsRejected`, `_zeroDurationIsRejected` |
| Wrong role | `acceptReferral_noRoleIsForbidden`, `_tenantAdminIsForbidden`, `recordEvaluation_coordinatorIsForbidden`, `getEpisode_tenantAdminFromSameTenantIsForbidden` |
| Identifier from another tenant (must look like NOT_FOUND) | `getEpisode_otherTenantActorSeesNotFound` |
| Same referral ID in different tenant (must succeed, not conflict) | `acceptReferral_sameReferralIdInDifferentTenantSucceeds` |
| Stale optimistic-lock version | `recordEvaluation_staleVersionIsRejected`, `_aheadVersionIsRejected` |
| Duplicate referral idempotency key (same tenant) | `acceptReferral_duplicateReferralIdIsConflict` |
| Unknown episode id | `getEpisode_unknownIdIsNotFound` |
| Patient mismatch | `recordEvaluation_wrongPatientIsRejected` |

No production code, existing tests, contracts or migrations were changed.

## Test evidence

```
$ cd apps/api && mvn test -Dtest=TherapyNegativePathsTest --no-transfer-progress
Tests run: 21, Failures: 0, Errors: 0, Skipped: 0 — com.aurevia.therapy.TherapyNegativePathsTest
BUILD SUCCESS

$ cd apps/api && mvn test -Dtest="TherapyNegativePathsTest,TherapyControllerHttpTest,JdbcTherapyStoreTest" --no-transfer-progress
Tests run: 21, Failures: 0, Errors: 0  — TherapyNegativePathsTest
Tests run: 18, Failures: 0, Errors: 0  — JdbcTherapyStoreTest
Tests run:  4, Failures: 0, Errors: 0  — TherapyControllerHttpTest
Tests run: 43, Failures: 0, Errors: 0
BUILD SUCCESS

$ bash scripts/worker-check.sh
# 1588/1589 tests passed; the single failure (test_committed_files_are_valid /
# PR-NOTES-PATH-MIGRATION) is pre-existing on main and unrelated to this change.
```

## Pre-existing test failure (not introduced by this PR)

`scripts/tests/test_swarm.py::PlanTests::test_committed_files_are_valid` fails on
`PR-NOTES-PATH-MIGRATION` on the current `main` HEAD before any change is staged.
Verified by stashing the new file and re-running the test: same result.

## Coverage

No `scripts/*.py` files were changed; `scripts/python-coverage.sh` does not apply.

## Lessons for workers

No `tasks/`, `.github/workflows/*`, `.opencode/*`, `opencode.json`, `AGENTS.md` or
`scripts/*` files were edited.
