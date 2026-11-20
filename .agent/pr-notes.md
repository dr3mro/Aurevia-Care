# fix-20261120-125115 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource family; lists use cursor pagination with stable tenant-scoped ordering.
Classification: cross-boundary repair of a recurring integrator merge conflict (tracker hygiene only; no new feature).
Branch: `swarm/READY-16-275`. HEAD at start `7b0f738` (`Agent changes for READY-16`); `origin/main` = `742f2bb`; branch 18 ahead / 0 behind. Synthetic fixtures only — no patient data, credential, token or real domain name in code, logs, URLs or browser storage.

## Root cause reproduced (tool evidence)

Integrator report (untrusted story text): *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

This is the **sixth** recurrence of one root cause (`fix-20261120-112705`, `-115243`, `-121332`, `-123138`, `-124114`, this file). The harness `scripts/agent-run.sh` (lines 198–202) copies a per-task note into the **tracked, integrator-owned** `.agent/pr-notes.md` and then commits with `git add -A` (`Agent changes for READY-16`). HEAD `7b0f738` is exactly such a commit: it replaced main's `AUD-20261120-13` note (blob `b5eae15b`) with the `fix-20261120-124114` note (blob `562556c`). Both main and the branch therefore modify the same shared file, so the integrator's merge conflicts on it; the READY-16 production/test/contract files themselves auto-merge cleanly. The harness is outside this worker's declared scope and was not changed.

| Command (executed this session) | Result |
|---|---|
| `git fetch origin main` | `742f2bb`, branch 18 ahead / 0 behind |
| `git rev-list --left-right --count origin/main...HEAD` | `0 18` |
| `git diff --stat origin/main..HEAD -- .agent/pr-notes.md` | 66 insertions / 92 deletions (shared file differs) |
| `git show --stat HEAD` | only `.agent/pr-notes.md` changed by `7b0f738` |
| `git hash-object .agent/pr-notes.md` before fix / `git rev-parse HEAD:.agent/pr-notes.md` / `git rev-parse origin/main:.agent/pr-notes.md` | `562556c` / `562556c` / `b5eae15b` |
| `for f in TODO.md MEMORY.md WORKSPACE.md BUGS.md PROJECT.md .github/opencode.ci.json docs/swarm; do git diff --quiet origin/main..HEAD -- "$f"` | every one identical to main — conflict isolated to the shared pr-notes file |
| `grep -rInE '^(<<<<<<<\|=======\|>>>>>>>)' apps contracts .agent docs` | no output — no conflict markers in tracked content |
| `git merge-tree $(git merge-base origin/main HEAD) origin/main <proposed-tree>` | exit 0; **no `changed in both`/conflict markers** for `.agent/pr-notes.md` — the fix removes the conflict |

## Fix applied

1. Restored the shared file to main's content: `git checkout origin/main -- .agent/pr-notes.md`. Working tree, index and `origin/main` now all hold blob `b5eae15b`; the staged change reverts HEAD's bad blob on commit, leaving no net diff on that file.
2. Wrote this attempt's notes **only** to the file this worker owns: `.agent/pr-notes/fix-20261120-125115.md`.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json` or `.github/workflows/*`.
4. Changed **no** production code, test, migration, contract, generated client, dependency or configuration. The READY-16 implementation from the earlier commits is untouched and re-verified below.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged

| Story step | Implementation read from the branch | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; optional `cursor`; `limit` default 50 / max 200 else 422 `INVALID_LIMIT`; `status` one of the existing workflow values else 422 | `OrderController.list`; `OrderService.list/parseCursor/parseLimit/parseStatus`; `STATUSES = List.of("awaiting_local_patient", "ready")` | `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`; `OrderListControllerTest.badLimitReturns422` |
| 2. `created_at DESC` with unique `id` tie-break, `<createdAt>:<id>` cursor of last row, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query` (`ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`); `OrderService.list` (`hasMore`, `subList`, cursor from last returned row) | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse the single-read action/role; add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` with `READ_ACTION = ORDER_READ`, `ORDERING_ROLE = clinician`, identical to `OrderService.get` | `OrderListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.forbiddenRoleReturns403` |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `exchange_order`, `exchange_order_snapshot`, `exchange_work_item`; no manual organization predicate | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` in the view package | `exchange/order/OrderPage.java`, frozen list | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | `OrderListServiceTest` (11), `OrderListControllerTest` (5), `OrderListIntegrationTest` (5) | commands below |
| 7. Contract | `contracts/openapi.yaml` `/api/v1/orders` GET plus `OrderPage` schema; regenerated `apps/staff-web/src/api/generated/openapi.d.ts` | `python3 -I contracts/openapi/check.py` → `x-04 drift check OK: 24 commands, contract and client match manifest` |

Entry point → test trace: `GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate before transaction, guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and roles re-derived each request) → `OrderRepository.query` (forced RLS, `created_at`/`id` DESC, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.
Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`. Failure paths: the three `OrderListServiceTest` invalid-input/denial tests and `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Exact commands and results (executed this session; tool evidence)

| Command | Result |
|---|---|
| `git fetch origin main` | main `742f2bb`, branch 18/0 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0; Surefire totals **1930 tests, 0 failures, 0 errors, 0 skipped** |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I contracts/openapi/check.py` | exit 0 — `x-04 drift check OK: 24 commands, contract and client match manifest` |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| `python3 -I scripts/check-pack.py` | exit 1 — `opencode.json is stale` (worktree CI profile under skip-worktree; HEAD verified fresh below) |
| stdlib render comparison over `scripts/gen-opencode.py` | `HEAD opencode.json == render(ci=False)` **True**; `HEAD .github/opencode.ci.json == render(ci=True)` **True**; worktree `opencode.json == render(ci=True)` **True** (CI profile, not the committed local profile) |
| `git checkout origin/main -- .agent/pr-notes.md` | shared file restored; working tree/index/main blob all `b5eae15b`; every shared tracker identical to main |
| `git merge-tree <base> origin/main <proposed-tree>` | exit 0, no conflict markers — merge is clean |

## Coverage result (mandatory)

This repair added **no production line, branch, method or class** — only the shared file was restored to main's content and this per-task note was authored. It therefore introduces no new coverage obligation and did not lower, remove or bypass any threshold, add an exclusion or skip a test. The READY-16 implementation's 100% coverage was proven by the earlier implementation commit and re-proven in prior fix attempts with `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify` (JaCoCo 0.8.16, build-failing INSTRUCTION/LINE/BRANCH/METHOD/CLASS gates; `-Pno-security` skips only the offline-stalling OWASP NVD stage, not JaCoCo or tests): all bundle counters 0 missed; per touched production file `OrderService.java` (200 lines, 94 branches, 24 methods), `OrderRepository.java` (106 lines, 6 branches, 24 methods), `OrderPage.java` (4 lines, 2 methods), `OrderController.java` (12 lines, 7 methods), all 0 missed. **100% on every file changed.** Per this worker's instructions the database and integration tiers are run by the integrator; they are not repeated here.

## New/changed branches and their covering tests

None from this repair (no production code changed). The READY-16 branch-to-test mapping is recorded in `.agent/pr-notes/READY-16-20261120-090706.md`.

## Defects found, decisions and deviations

- **Defect (fixed):** the latest auto-generated `Agent changes for READY-16` commit (`7b0f738`) rewrote the shared `.agent/pr-notes.md` after the previous fix had restored it, reproducing the integrator merge conflict. Fixed by restoring main's version of the shared file.
- **Recurring pattern / follow-up for the orchestrator:** this is the sixth repair of the same root cause. The harness `scripts/agent-run.sh` lines 198–202 copies the per-task note into the tracked shared file and commits it with `git add -A`. It must never write the shared `.agent/pr-notes.md`. This worker cannot change that harness from inside the scoped story; no repo change was made for it here.
- **Decision:** keep earlier per-task fix notes rather than delete other runs' records — per-task files under `.agent/pr-notes/` are owned by their named task and affect no gate; only the shared file had to be reverted.
- **No production defect found:** the READY-16 implementation was read directly and its fast unit tier (1930 tests) re-run green; all lightweight policy and contract gates passed.
- **Environment-only findings (no repo change):** worktree `opencode.json` is the CI profile under skip-worktree, so `check-pack`/`worker-check.sh` fail locally though the committed HEAD files are fresh. Python 3.11 here lacks `PyYAML`, so the full `scripts/tests` suite cannot run (the targeted `test_java_conventions.py` does and is green). Neither is caused by this fix.

## Step-6 check result (TODO "Instructions for every agent")

The step-6 check for API work is `bash scripts/worker-check.sh`, which wraps `check-pack.py`; in this container it fails at that stage with `opencode.json is stale` — the documented cloud-worker CI-profile artifact (MEMORY.md, OPS-CW-003-01, closed as false positive). `git ls-files -v opencode.json` reports `S` (skip-worktree). The committed HEAD files were verified fresh by render comparison, so in GitHub Actions (which checks out the committed local profile) the gate passes. The constituent checks that can run here (`check-docs`, `check-secrets`, `check-names`, `check-migrations --require-baseline`, the fast Java unit tier, the contract check and `test_java_conventions.py`) are all green above.

## Evidence sources

Tool evidence: every command above was executed locally (`git`, Maven/Surefire, the contract gate and the repository policy scripts); results are from actual output. Reviewer evidence: specialist skills `smartclinic-delivery` and `smartclinic-debugger` were loaded and their rules applied; no separate reviewer run was dispatched for this bounded merge repair. Simulated-user evidence: none. Human/clinical evidence: none (backend list endpoint and tracker repair, not a clinical decision workflow). All fixtures are synthetic.
