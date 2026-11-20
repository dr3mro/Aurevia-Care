# READY-18 · List invoices (GET /api/v1/finance/invoices)

PR #228, tested branch tip 6b3f8404da59a3accabc756fd1ca47e886c37579.

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
AssertionError: 'refs/heads/opencode/issue-5-9' not found in 'e627fc4f8c9b33339644f8e3888f11cdba95131d\trefs/heads/main\n66dc18dbf84c3a44816bc3d5f38c9faff33b903e\trefs/heads/swarm/issue-5-9\n'

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
Ran 279 tests in 10.870s

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

Task: READY-18

Swarm worker 1. Local checks: failed. Agent result: ok.

# READY-18 — List invoices (GET /api/v1/finance/invoices)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering). Also touches the FIN-01 finance resource surface.
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).
Branch: `swarm/READY-18-350`, base HEAD `a59c906`. Synthetic fixtures only — no patient data, credentials, tokens or real domains.

## Acceptance mapping

| Story step | Implementation | Evidence (tool commands below) |
|---|---|---|
| 1. `GET /api/v1/finance/invoices`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`; `status` one of the existing invoice states else 422 `INVALID_STATUS`; `patientId` optional | `FinanceController.list` → `FinanceService.list/parseCursor/parseLimit/parseStatus/parsePatientId` | `InvoiceListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `invalidPatientIdsAreRejectedBeforeAnyQuery`, `statusAndPatientIdFiltersArePassedThroughToTheRepository`; `InvoiceListControllerTest.badLimitReturns422`; `InvoiceListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. Order by `created_at DESC`, unique `id` tie-break; cursor `<createdAt>:<id>` of the last returned row; `nextCursor` null on last page; fetch `limit + 1` | `FinanceRepository.query` (`ORDER BY created_at DESC, id DESC LIMIT :limit`), `FinanceService.list`, new `InvoicePage` | `InvoiceListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `InvoiceListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (two rows share a creation instant, proving the id tie-break) |
| 3. Reuse the single-read action; add no role | `execute(FinanceActions.READ, "ALL", ...)` — the exact action `FinanceService.get` uses; the existing `finance` / `tenant_admin` / `privacy_auditor` allow-list in `FinanceActions` is unchanged | `InvoiceListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `InvoiceListIntegrationTest.unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real V45 column names, no manual org filter | `FinanceRepository.query` uses `NamedParameterJdbcTemplate` / `MapSqlParameterSource` over `public.finance_invoice`; column names read from `db/migrations/V45__finance.sql` (`created_at`, `id`, `state`, `patient_id`, …). No organization predicate is added — forced RLS confines the read | `InvoiceListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder`, `statusAndPatientIdFiltersSelectOnlyMatchingInvoices` |
| 5. `InvoicePage(List<the existing invoice view record>, String nextCursor)` in the view's package | new `finance/InvoicePage.java` holding `List<FinanceContract.View>` with a frozen list and defensive accessor | `InvoiceListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `InvoiceListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | 3 new test classes, 22 new tests | Surefire counts and commands below |
| 7. Contract: add GET path, regenerate, run `check.py` | `contracts/openapi.yaml`: new `get` on `/api/v1/finance/invoices` plus `Invoice`, `InvoiceItem`, `LedgerEntry`, `InvoiceView`, `InvoicePage` schemas; staff-web generated `openapi.d.ts` regenerated with the pinned generator (never hand-edited). `contracts/openapi/generated/openapi.yaml` (catalog-generated X-04 artifacts) is unchanged by a GET route | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check --prefix apps/staff-web` |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used (schema unchanged).

## Entry point to test trace

`GET /api/v1/finance/invoices?cursor=&limit=&status=&patientId=` → `TenantContextInterceptor.preHandle` (401 without subject) → `FinanceController.list` → `FinanceService.list` (validate input before the transaction, then the existing guarded `TransactionTemplate`/`execute`) → `authorize` (`FINANCE_INVOICE_READ`; membership and roles re-derived each request; 403 + `DENIED` audit on denial) → `FinanceRepository.query` (forced RLS, `created_at`/`id` descending, `limit+1`, parameterised) → `success(..., "ALL")` audit → `InvoicePage`.

- Happy path: `InvoiceListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `InvoiceListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `invalidPatientIdsAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `InvoiceListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`,
