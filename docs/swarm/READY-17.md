# READY-17 · List lab accessions (GET /api/v1/lab/accessions)

PR #227, tested branch tip fcf771889dcc66fad6b77c7a29c4c3b588dd6fab.

**Draft: the worker did not finish cleanly** (agent result: ok; local checks: failed). Its work is saved here so nothing is lost; a fix worker or a person continues from this branch.

**Why it failed** (end of the local check log):

```text

+ ('ok', '1', 'passed')
?              ^ ^^


======================================================================
FAIL: test_merge_main_without_conflicts_merges_and_rechecks (test_agent_run.AgentRunTests.test_merge_main_without_conflicts_merges_and_rechecks)
----------------------------------------------------------------------
Traceback (most recent call last):
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_agent_run.py", line 115, in test_merge_main_without_conflicts_merges_and_rechecks
    self.assertEqual((self.repo / ".agent/checks").read_text(), "passed")
AssertionError: 'failed' != 'passed'
- failed
+ passed


======================================================================
FAIL: test_push_token_overrides_gh_token_for_git_only (test_agent_run.AgentRunTests.test_push_token_overrides_gh_token_for_git_only)
----------------------------------------------------------------------
Traceback (most recent call last):
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_agent_run.py", line 216, in test_push_token_overrides_gh_token_for_git_only
    self.assertIn("refs/heads/opencode/issue-5-9", heads)
AssertionError: 'refs/heads/opencode/issue-5-9' not found in 'a04f7d7ab875509d1a10e49843250191e001c8cc\trefs/heads/main\nf43138b8dba73fca28a6c9a958e2367542e4c04f\trefs/heads/swarm/issue-5-9\n'

======================================================================
FAIL: test_resolve_merges_main_into_a_conflicted_pr_branch (test_agent_run.AgentRunTests.test_resolve_merges_main_into_a_conflicted_pr_branch)
----------------------------------------------------------------------
Traceback (most recent call last):
  File "/__w/Aurevia-Care/Aurevia-Care/scripts/tests/test_agent_run.py", line 151, in test_resolve_merges_main_into_a_conflicted_pr_branch
    self.assertEqual((self.repo / ".agent/resolve/5").read_text(), "merged")
AssertionError: 'merged but checks failed' != 'merged'
- merged but checks failed
+ merged


----------------------------------------------------------------------
Ran 279 tests in 13.091s

FAILED (failures=5, errors=10)
Added 1 audit task(s).
Added 1 audit task(s).
Estimated monthly swarm minutes 9900 of 10000: pausing waves.
Public repository: runner minutes are free; the budget gate is off.
publish: swarm/CW-001-1 moved while CW-001 worked; keeping the newer branch
publish: PR #193 is already merged; nothing to push for CW-001
publish: PR #193 is already closed; nothing to push for CW-001
slot images are missing from the registry
slot images are present in the registry
ok
A repair task is already open.
ok
ok
no
ok
The run is not a failed run on main; nothing to repair.
No failing pipeline run on main needs a repair task.
No failing pipeline run on main needs a repair task.
ok
ok
A repair task is already open.
```

Task: READY-17

Swarm worker 2. Local checks: failed. Agent result: ok.

# READY-17 — List lab accessions (GET /api/v1/lab/accessions)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering). Also touches the LAB-01 accession resource surface.
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).
Branch: `swarm/READY-17-349`, base HEAD `1c016fb`. Synthetic fixtures only — no patient data, credentials, tokens or real domains.

## Acceptance mapping

| Story step | Implementation | Evidence (tool commands below) |
|---|---|---|
| 1. `GET /api/v1/lab/accessions`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`; `state` one of the `AccessionState` wire values else 422 `INVALID_STATE` | `LabAccessionController.list`, `LabAccessionService.list/parseCursor/parseLimit/parseState` | `AccessionListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatesAreRejectedBeforeAnyQuery`, `stateFiltersArePassedThroughToTheRepository`; `AccessionListControllerTest.badLimitReturns422`; `AccessionListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. Order by `created_at DESC`, unique `id` tie-break; cursor `<createdAt>:<id>` of the last returned row; `nextCursor` null on the last page; fetch `limit + 1` | `LabAccessionRepository.query` (`ORDER BY created_at DESC, id DESC LIMIT :limit`), `LabAccessionService.list`, `AccessionPage` | `AccessionListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `AccessionListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (two rows share a creation instant, proving the id tie-break) |
| 3. Reuse the single-read action; add no role | `authorize(context, LabActionPolicy.ACCESSION_READ, "ALL")` — the exact action `LabAccessionService.get` uses; existing `lab_collector` / `lab_verifier` roles unchanged | `AccessionListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `AccessionListIntegrationTest.unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real V26 column names, no manual org filter | `LabAccessionRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `public.lab_accession`; column names read from `db/migrations/V26__laboratory_accession.sql` (`created_at`, `state`, `id`, …) | `AccessionListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `AccessionPage(List<AccessionView> items, String nextCursor)` in the view package | new `lab/accession/AccessionPage.java` with a frozen list | `AccessionListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `AccessionListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | 3 new test classes, 21 new tests | commands and Surefire counts below |
| 7. Contract: add GET path, regenerate, run `check.py` | `contracts/openapi.yaml` `get` on `/api/v1/lab/accessions` plus `LabAccession`/`AccessionEvent`/`AccessionView`/`AccessionPage` schemas; staff-web generated `openapi.d.ts` regenerated with the pinned generator (never hand-edited) | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check` |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used (schema unchanged).

## Entry point to test trace

`GET /api/v1/lab/accessions?cursor=&limit=&state=` → `TenantContextInterceptor.preHandle` (401 without subject) → `LabAccessionController.list` → `LabAccessionService.list` (validate input before the transaction, then guarded `TransactionTemplate`) → `authorize` (`ACCESSION_READ`; membership and roles re-derived each request; 403 + `DENIED` audit on denial) → `LabAccessionRepository.query` (forced RLS, `created_at`/`id` descending, `limit+1`, parameterised) → `audit.append(..., ACCESSION_READ, ACCESSION, LAB, ALL, SUCCESS)` → `AccessionPage`.

- Happy path: `AccessionListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `AccessionListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatesAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `AccessionListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`; `AccessionListIntegrationTest.organizationBNeverSeesOrganizationARows`, `invalidPagingInputsAreRejected`, `unauthorizedAndForbiddenActorsAreDenied`.

## Exact commands and results (executed by this worker)

All commands from the repository root unless noted; results are tool output.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=AccessionListServiceTest,AccessionList
