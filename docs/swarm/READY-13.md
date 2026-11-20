# READY-13 · List appointments by date range (GET /api/v1/appointments)

PR #226, tested branch tip be6928dc401879c52f289c0f1ee62368a26d536d.

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
AssertionError: 'refs/heads/opencode/issue-5-9' not found in '9dc0559f44d68fbe4dd59edbe45656b14c1941cd\trefs/heads/main\n2427277f7e35f4f32c3f3b12af946f5c73192073\trefs/heads/swarm/issue-5-9\n'

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
Ran 279 tests in 14.324s

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

Task: READY-13

Swarm worker 6. Local checks: failed. Agent result: ok.

# READY-13 — List appointments by date range (GET /api/v1/appointments)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).
Branch: `swarm/READY-13-347` at `e73a3a6` before this change. Synthetic fixtures only; no patient data, credential or real domain name in code, logs, URLs or storage.

## Acceptance mapping

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/appointments`; `cursor`, `limit` (default 50, max 200, else 422 `INVALID_LIMIT`), required `from`/`to` ISO instants (422 `INVALID_RANGE` when malformed, `to <= from` or `to > from + 31d`), optional `resource` and `state` | `web/AppointmentController.list`, `AppointmentService.list/parseCursor/parseLimit/requireInstant/validateRange/parseResource/parseState` | `AppointmentListServiceTest` (16 tests), `AppointmentListControllerTest` (5 tests), `AppointmentListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. Order by scheduled start ascending with unique-id tie-break; cursor `<startsAt>:<id>`; null `nextCursor` on last page; fetch `limit + 1` | `AppointmentRepository.query` (`ORDER BY a.starts_at, a.id LIMIT :limit`), cursor predicate, `AppointmentService.list` | `AppointmentListServiceTest.extraRowProducesCursorFromTheLastReturnedRow`, `exactlyLimitRowsHaveNoNextCursor`, `emptyPageHasNoNextCursor`; `AppointmentListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse the single-record read action; add no role | `authorize(context, ActionPolicy.APPOINTMENT_READ, "ALL")` — the action used by `AppointmentService.get`; audit under `APPOINTMENT_READ`/`APPOINTMENT` | `AppointmentListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `AppointmentListIntegrationTest.unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL only; RLS isolation; real V8 column names; no manual org predicate | `AppointmentRepository.query` uses `MapSqlParameterSource` over `appointment`, `facility`, `scheduling_resource` columns from `V8__scheduling.sql`; forced RLS from V2 supplies isolation | `AppointmentListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `AppointmentPage(List<AppointmentView> items, String nextCursor)` in the view package | new `scheduling/AppointmentPage.java` with immutable list | `AppointmentListServiceTest.pageAndRowFreezeTheirPartsAndExposeValueSemantics` |
| 6. Service unit + controller + 2-org integration tests | 3 new test classes, 21 new tests (16 + 5 + 5 integration methods) | commands below |
| 7. Contract | `contracts/openapi.yaml`: new `GET /api/v1/appointments` with six query parameters, plus `Appointment`, `AppointmentEvent`, `AppointmentView`, `AppointmentPage` schemas; staff-web typed client regenerated with the pinned `openapi-typescript` CLI; catalog-generated X-04 artifacts unchanged | `python3 -I contracts/openapi/check.py`, X-04 `test_check.py`, `npm run gen:api:check`, `bash apps/staff-web/check.sh` |

No existing endpoint, response shape or migration was changed. No dependency added. No reserved migration version used (no schema change).

## Entry point to test trace

`GET /api/v1/appointments?cursor=&limit=&from=&to=&resource=&state=` → `TenantContextInterceptor.preHandle` (401 without subject) → `AppointmentController.list` → `AppointmentService.list` (validate limit/cursor/range/filters before any transaction) → guarded `TransactionTemplate` → `authorize` (`APPOINTMENT_READ`, membership and roles re-derived from the store) → `AppointmentRepository.query` (forced-RLS, `starts_at`/`id` ordered, `limit+1`) → per-row `appointments.history(id)` → `AuditAppender.append(APPOINTMENT_READ, ALL, SUCCESS)` → `AppointmentPage`.

Success path: `AppointmentListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
Failure paths: `AppointmentListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `missingRangeIsRejected`, `malformedRangeIsRejected`, `invalidRangeOrderingIsRejected`, `invalidResourceIsRejectedBeforeAnyQuery`, `invalidStateIsRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `AppointmentListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed by this worker)

All commands run from the repository root unless noted. Exit codes are from the tool.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (before implementation) | **RED** — compile errors: `AppointmentPage`, `AppointmentRepository.AppointmentRow`, `AppointmentService.list`, `AppointmentController.list` do not exist |
| `mvn -B -f apps/api/pom.xml -Pn
