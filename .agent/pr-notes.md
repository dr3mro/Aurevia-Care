# fix-20261120-123138 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource family, cursor pagination with stable tenant-scoped ordering.
Classification: cross-boundary repair of a recurring integrator merge conflict (tracker hygiene, not a new feature).
Branch: `swarm/READY-16-275`, HEAD at start `1355ba6` (`Agent changes for READY-16`). `origin/main` = `742f2bb`; branch 14 ahead / 0 behind. Synthetic fixtures only — no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.

## Root cause reproduced (tool evidence)

Integrator report: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

This is the fourth recurrence of one root cause (`fix-20261120-112705`, `fix-20261120-115243`, `fix-20261120-121332`, this file). An auto-generated `Agent changes for READY-16` commit copies a per-task fix note into the **shared, integrator-owned** `.agent/pr-notes.md`. Both the branch and `main` therefore modify that file, so the integrator's merge hits a conflict on it; when it force-merges, the resulting shared file content fails the pack/markdown stage.

| Command (executed this session) | Result |
|---|---|
| `git fetch origin main` | main unchanged at `742f2bb`; branch 14 ahead / 0 behind |
| `git diff origin/main --stat -- .agent/pr-notes.md` | differs: 58 insertions / 93 deletions; branch head was the `fix-20261120-121332` note, main holds the `AUD-20261120-13` note |
| shared trackers check (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `opencode.json`, `.github/opencode.ci.json`, `docs/swarm`, `.github/workflows`) | all identical to `origin/main`: the conflict is isolated to the shared pr-notes file |
| `grep -rInE '^(<<<<<<<\|=======\|>>>>>>>)' apps contracts .agent docs` | no output — no conflict markers |
| `bash scripts/check.sh` (reproduction requested by the story) | exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — environment-only (see below) |
| `bash scripts/worker-check.sh` (step-6 check from TODO "Instructions for every agent") | exit 1 at the same `check-pack` stage — same environment-only cause |

The `check-pack` failure is the documented cloud-worker artifact (MEMORY.md, OPS-CW-003-01, closed as false positive): `git ls-files -v opencode.json` reports `S` (skip-worktree) because `agent-run.sh` copied the CI profile into the worktree. A stdlib render comparison executed this session proved **both committed HEAD files are correct and fresh**: `opencode.json` fresh==HEAD **True**, fresh==worktree **False**; `.github/opencode.ci.json` fresh==HEAD **True**. In GitHub Actions the committed local profile is checked out normally, so `check-pack` passes there. This story changed no agent, skill, `opencode.json` or workflow file.

## Fix applied

1. Restored the shared file to main's content: `git checkout origin/main -- .agent/pr-notes.md`. Post-fix `git diff origin/main -- .agent/pr-notes.md` is empty; every shared tracker above is again identical to main. This removes the conflict on the integrator's next merge; no notes were added to the shared file.
2. Wrote this attempt's notes only to the file this worker owns: `.agent/pr-notes/fix-20261120-123138.md`.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `.agent/pr-notes.md`, `opencode.json` or `.github/workflows/*`.
4. Changed **no** production code, test, migration, contract, generated client, dependency or configuration. The READY-16 implementation from the earlier commits is untouched and was re-verified below.

## Acceptance mapping (ARC-03) — implementation verified unchanged

| Story step | Implementation read this session | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; optional `cursor`; `limit` default 50 / max 200 else 422 `INVALID_LIMIT`; `status` one of the existing workflow values else 422 | `OrderController.list`; `OrderService.list/parseCursor/parseLimit/parseStatus`; `STATUSES = List.of("awaiting_local_patient", "ready")` | `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`; `OrderListControllerTest.badLimitReturns422` |
| 2. `created_at DESC` with unique `id` tie-break, `<createdAt>:<id>` cursor of last row, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query` — `ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`; `OrderService.list` (`hasMore`, `subList`, cursor from last returned row) | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse the single-read action/role; add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` with `READ_ACTION = ORDER_READ`, `ORDERING_ROLE = clinician`, identical to `OrderService.get` | `OrderListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.forbiddenRoleReturns403` |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `exchange_order`, `exchange_order_snapshot`, `exchange_work_item`; no manual organization predicate | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` in the view package | `exchange/order/OrderPage.java`, frozen list | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | `OrderListServiceTest` (11), `OrderListControllerTest` (5), `OrderListIntegrationTest` (5) | commands below |
| 7. Contract | `contracts/openapi.yaml` line 340 `/api/v1/orders:` with `get`, line 559 `OrderPage:`; regenerated `apps/staff-web/src/api/generated/openapi.d.ts` (line 447 `OrderPage`) | `python3 -I contracts/openapi/check.py` → `x-04 drift check OK: 24 commands, contract and client match manifest` |

Entry point → test trace: `GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate before transaction, guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and roles re-derived per request) → `OrderRepository.query` (forced RLS, `created_at`/`id` DESC, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.
Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`. Failure paths: the three `OrderListServiceTest` invalid-input/denial tests and `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Exact commands and results (executed this session; tool evidence)

| Command | Result |
|---|---|
| `git fetch origin main` | main unchanged `742f2bb`, branch 14/0 |
| `bash scripts/check.sh` | exit 1 at `check-pack: opencode.json is stale` (environment-only, reproduced as requested) |
| `bash scripts/worker-check.sh` | exit 1 at the same `check-pack` stage |
| fresh-render comparison (stdlib Python over `scripts/gen-opencode.py`) | `opencode.json` fresh==HEAD **True**, fresh==worktree **False**; `.github/opencode.ci.json` fresh==HEAD **True** |
| `python3 -I contracts/openapi/check.py` | exit 0 — `x-04 drift check OK: 24 commands, contract and client match manifest` |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1: `Ran 271 tests ... FAILED (failures=9, errors=10)` — all in `test_check_pack` (CI-profile `opencode.json` under skip-worktree), `test_agent_run` and `test_swarm` (sandbox git/gh behaviour differences, e.g. `gh pr create: permission denied`). Environment-only, pre-existing; no Python/workflow/tooling file was changed by this fix |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0; Surefire totals **1930 tests, 0 failures, 0 errors, 0 skipped**; `OrderListServiceTest` 11, `OrderListControllerTest` 5 |
| `git checkout origin/main -- .agent/pr-notes.md` | shared file restored; all shared trackers identical to main |
| conflict-marker grep over `apps contracts .agent docs` | no output |

## Coverage result (mandatory)

This repair added **no production line, branch, method or class** — only the shared file was restored to main's content and this per-task note was authored. It therefore introduces no new coverage obligation and did not lower, remove or bypass any threshold, add an exclusion or skip a test. The READY-16 implementation's 100% coverage was proven by `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh` (`mvn verify`, JaCoCo 0.8.16 build-failing INSTRUCTION/LINE/BRANCH/METHOD/CLASS gates) recorded in `.agent/pr-notes/READY-16-20261120-090706.md`: bundle counters all 0 missed (INSTRUCTION 90998, BRANCH 6445, LINE 15797, COMPLEXITY 7098, METHOD 3824, CLASS 787), and per touched file `OrderPage.java` (4 lines, 2 methods), `OrderService.java` (200 lines, 94 branches, 24 methods), `OrderRepository.java` (106 lines, 6 branches, 24 methods), `OrderController.java` (12 lines, 7 methods) — 100% on every file changed. That evidence is unchanged by this fix. Per this worker's instructions the database and integration tiers are run by the integrator; this session executed the fast unit tier (1930 tests, green) and the lightweight policy/contract gates (green).

## Defects found, decisions and deviations

- **Defect (fixed):** a later auto-generated `Agent changes for READY-16` commit (`1355ba6`, preceded by `6863b9e`) rewrote the shared `.agent/pr-notes.md` after the previous fix had restored it, reproducing the integrator merge conflict. Fixed by restoring main's version of the shared file.
- **Recurring pattern / follow-up for the orchestrator:** this is the fourth repair of the same root cause. The harness/handoff that generates `Agent changes for …` commits must write only to `.agent/pr-notes/<task-id>.md`, never to the shared `.agent/pr-notes.md`. This worker cannot change that harness from inside the scoped story; no repo change was made for it here.
- **Decision:** keep earlier per-task fix notes rather than delete other runs' records — per-task files under `.agent/pr-notes/` are owned by their named task and affect no gate; only the shared file had to be reverted.
- **No production defect found:** the READY-16 implementation was read directly and its fast unit tier re-run green (1930 tests); all lightweight gates passed.

## Evidence sources

Tool evidence: every command above was executed locally (`git`, Maven/Surefire, the contract gate and the repository policy scripts); results are from actual output. Reviewer evidence: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer` and `smartclinic-debugger` were loaded and their rules applied; no separate reviewer run was dispatched for this bounded merge repair. Simulated-user evidence: none. Human/clinical evidence: none (backend list endpoint and tracker repair, not a clinical decision workflow). All fixtures are synthetic.
