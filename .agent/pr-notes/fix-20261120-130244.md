# fix-20261120-130244 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource family; lists use cursor pagination with stable tenant-scoped ordering.
Classification: cross-boundary repair of a recurring integrator merge conflict (tracker hygiene only; no new feature code).
Branch: `swarm/READY-16-275`. HEAD at start `f0adb4a` (`Agent changes for READY-16`); `origin/main` = `742f2bb`; branch ahead 20 / behind 0. Synthetic fixtures only — no patient data, credential, token or real domain name in code, logs, URLs or browser storage.

## Root cause reproduced (tool evidence)

Story text (untrusted): *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

The READY-16 production/test/contract files auto-merge cleanly. The conflict is isolated to the **tracked, integrator-owned** `.agent/pr-notes.md`, which AGENTS.md forbids editing:

| Command | Result |
|---|---|
| `git fetch origin main` | `742f2bb`; branch ahead 20 / behind 0 |
| `git merge-base origin/main HEAD` | `742f2bb` |
| `git merge-tree --write-tree --name-only origin/main HEAD` | exit 0 (main has not moved; conflict is created when merging the branch INTO a main whose `.agent/pr-notes.md` is the AUD-20261120-13 note) |
| `git hash-object .agent/pr-notes.md` / `git rev-parse origin/main:.agent/pr-notes.md` | `6c85e1d` (worker note) vs `b5eae15b` (main's AUD note) — both sides modified the shared file |
| `git show --stat HEAD` | `f0adb4a` changed only `.agent/pr-notes.md` (63 insertions / 93 deletions) |
| `for f in TODO.md MEMORY.md WORKSPACE.md BUGS.md PROJECT.md .github/opencode.ci.json docs/swarm; do git diff --quiet origin/main..HEAD -- "$f"` | every one identical to main |
| `grep -rInE '^(<<<<<<<\|=======\|>>>>>>>)' apps contracts .agent docs` | no output — no conflict markers |

## Fix applied

1. Restored the shared file to main's content: `git checkout origin/main -- .agent/pr-notes.md`. Worktree now holds blob `b5eae15b`, identical to `origin/main`; the staged change reverts HEAD's bad blob, leaving **no net diff on `.agent/pr-notes.md`** against main.
2. Wrote this attempt's notes **only** to the file this worker owns: `.agent/pr-notes/fix-20261120-130244.md`.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json` or `.github/workflows/*`.
4. Changed **no** production code, test, migration, contract, generated client, dependency or configuration. The READY-16 implementation from the earlier commits is untouched and re-verified below.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the projected work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest` (11), `OrderListControllerTest` (5), `OrderListIntegrationTest` (5) |
| 2. `created_at DESC, id DESC`, cursor `<createdAt>:<id>` of last returned row, null on last page, `limit+1` fetch | `OrderRepository.query`, `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, same as `OrderService.get` | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real V19 column names, no manual org predicate | `OrderRepository.query` over `exchange_order` joined to `exchange_work_item` | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows` |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` | new `exchange/order/OrderPage.java` | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | 3 test classes, 21 tests | commands below |
| 7. Contract | `contracts/openapi.yaml` `get /api/v1/orders` + `OrderView`/`OrderPage` schemas; staff-web `openapi.d.ts` regenerated | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check` (CI) |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used.

## Entry point to test trace

`GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate inputs before any transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and role re-derived each request) → `OrderRepository.query` (forced RLS, `created_at DESC, id DESC`, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.

- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed by this worker, tool evidence)

All commands run from the repository root; Java 21.0.13, Maven 3.10.0, PostgreSQL 18.6 on 127.0.0.1:5432, `SMARTCLINIC_PG_ADMIN=postgres`.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0; Surefire totals **1930 tests, 0 failures, 0 errors, 0 skipped**; `OrderListControllerTest` 5, `OrderListServiceTest` 11 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security verify` | exit 0 — full test tier incl. integration + JaCoCo `check` gate. `OrderListIntegrationTest` **5 tests, 0 failures, 0 errors** |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` | all exit 0 |
| `bash scripts/frontend-check.sh` | `frontend standards: policy and tool commands passed` (exit 0) |
| `git diff --exit-code origin/main -- .agent/pr-notes.md` | exit 0 (shared file matches main after fix) |

### Coverage result (mandatory)

`mvn -Pno-security verify` ran the JaCoCo 0.8.16 `check` goal bound to `verify` with 1.00 minimum for INSTRUCTION, LINE, BRANCH, METHOD and CLASS. `apps/api/target/site/jacoco/jacoco.xml` reports:

| Counter | Missed | Covered |
|---|---|---|
| INSTRUCTION | 0 | 91024 |
| BRANCH | 0 | 6449 |
| LINE | 0 | 15803 |
| COMPLEXITY | 0 | 7101 |
| METHOD | 0 | 3825 |
| CLASS | 0 | 787 |

Per touched production file (all 0 missed): `OrderService.java` (1081 instr, 94 branches, 200 lines, 24 methods), `OrderRepository.java` (563 instr, 6 branches, 106 lines, 24 methods), `OrderPage.java` (16 instr, 4 lines, 2 methods), `OrderController.java` (54 instr, 12 lines, 7 methods). **100% on every file changed.** `-Pno-security` skips only the OWASP Dependency-Check / SpotBugs stages (which stall on the NVD feed in this offline container); it does not alter JaCoCo or test scope. No dependency or scan configuration was changed.

### Step-6 gate and environment caveats

- Step 6 (`bash scripts/worker-check.sh`) **exits 1 at `check-pack: opencode.json is stale`**. This is the known cloud-worker artifact: `git ls-files -v opencode.json` = `S` (skip-worktree); `agent-run.sh` copied the CI profile into the worktree, and `check-pack.py`/`gen-opencode.py --check` judge that worktree file. Verified with a stdlib render comparison: **HEAD `opencode.json` matches a fresh local render = True**, **HEAD `.github/opencode.ci.json` matches a fresh CI render = True**, worktree `opencode.json` = CI profile. This story changed no agent, skill, `opencode.json` or workflow file.
- `npm run gen:api:check` cannot run here (`node_modules` absent; CI runs `npm ci`). The generated `openapi.d.ts` was regenerated by the original story worker and is unchanged by this fix; `contracts/openapi/check.py` is green and both files contain the new path.
- Full `python3 -I -m unittest discover -s scripts/tests -q` exits 1 from pre-existing environment gaps: `ModuleNotFoundError: No module named 'yaml'`, two `test_agent_run` git-branch setup errors, and the `opencode.json` profile check above. None is caused by this story; no Python/workflow/tooling file was changed.
- This container cannot complete `scripts/check.sh` locally (OWASP NVD feed unreachable, staff/mobile `node_modules` absent). Every applicable gate was therefore run directly with the results above; the integrator runs the exact `scripts/check.sh` on the merge result in CI.

## Branch-to-test mapping (JaCoCo 100% requirement)

| Production branch | Test covering each side |
|---|---|
| `OrderPage` compact ctor + `items()` copy/override | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `OrderListControllerTest.validRequestReturnsPage` |
| `OrderService.list`: `denial != null` true/false | `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited` (true) vs success tests (false) |
| `OrderService.list`: `failure.get() != null` true/false | denial tests (true) vs success tests (false) |
| `OrderService.list`: both `parsedCursor == null ? null : ...` ternaries | `absentAndBlankParametersUseDefaults`, `emptyPageHasNoNextCursor` (absent) vs `extraRowProducesCursorFromTheLastReturnedRow` (present) |
| `OrderService.list`: `hasMore` true/false and `if (hasMore)` | `extraRowProducesCursorFromTheLastReturnedRow` (true) vs `emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor` (false) |
| `parseCursor`: null / blank / regex mismatch / `Instant.parse` exception / non-canonical / valid | `absentAndBlankParametersUseDefaults`, `invalidCursorsAreRejectedBeforeAnyQuery`, `extraRowProducesCursorFromTheLastReturnedRow` |
| `parseLimit`: null / blank / `<1` / `>200` / non-numeric / valid | `absentAndBlankParametersUseDefaults`, `invalidLimitsAreRejectedBeforeAnyQuery`, `exactlyLimitRowsHaveNoNextCursor` |
| `parseStatus`: null / blank / valid / invalid | `absentAndBlankParametersUseDefaults`, `statusFiltersArePassedThroughToTheRepository`, `invalidStatusesAreRejectedBeforeAnyQuery` |
| `OrderRepository.query`: `cursorCreatedAt != null` true/false (both cursor and status ifs) | `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (cursor page 1 false, later pages true), `statusFilterSelectsOnlyMatchingOrders` |
| `OrderController.list` parameter binding | `OrderListControllerTest` (401, 403, 422, 200, passthrough) |

Javadoc uses `{@code ...}` only; no new `{@link}` target was added. `test_java_conventions.py` is green.

## Defects found, decisions and deviations

- **Ambiguity resolved earlier (decision, unchanged):** the story's "existing workflow state values in `OrderView` and the migration" — `OrderView` has no status field and `exchange_order` (V19) has no state column. The projected work-item states in `WorkItemView` and the V19 `exchange_work_item.state CHECK` are `awaiting_local_patient` and `ready`; those are the accepted `status` values. No state was invented and no migration changed.
- Decision: the list reuses `ORDER_READ` / `clinician` from the single-record read; audits under `ORDER_READ`/`ALL`; adds no role or action key (story step 3).
- Decision: cursor is canonical `Instant.toString()` of `created_at` plus the immutable order id; a non-canonical or unparseable instant is rejected `INVALID_CURSOR`. Descending `(created_at DESC, id DESC)` keeps paging stable.
- Deviation (transparent, from the original story): the TODO `scope` lists only the `exchange/order` packages and `contracts/openapi`, while explicit steps require an `it/` integration test and a regenerated staff-web d.ts; those files are part of the already-merged READY-16 implementation and were not touched by this repair.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Git, Maven/Surefire/JaCoCo, the contract gate and the repository policy scripts. Reviewer evidence: the `smartclinic-delivery` skill was loaded; no separate reviewer run was dispatched for this bounded tracker-hygiene repair. Simulated-user evidence: none. Human/clinical evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.

**Status:** fix applied. The only defect blocking the merge (the forbidden edit to the shared `.agent/pr-notes.md`) is removed; the READY-16 feature implementation, tests, 100% coverage and contract gates are re-verified green. No TODO/tracker, migration, dependency or out-of-scope file was changed by this repair.
