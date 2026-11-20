# fix-20261120-115243 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement IDs: **ARC-03** (versioned REST, cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary repair of an integrator merge conflict (HTTP adapter + domain service + SQL + contract are unchanged; only branch/main reconciliation is fixed).
Branch: `swarm/READY-16-275`. Base for this fix: branch HEAD `5310a62`, `origin/main` `742f2bb`.
Synthetic fixtures only; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.

## Root cause of the reported failure (tool evidence)

Integrator report: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

Reproduction and diagnosis, all executed locally:

- `git merge-base HEAD origin/main` = `b495e96`; the branch had diverged from current main. Commits on `origin/main` since the base are the AUD-20261120-13 integration (`742f2bb`, `d412082`, `508f6d4`, `bbff098`, `4efe237`, `db92ec6`, `7360239`); the branch had only READY-16 commits.
- `git diff --name-status origin/main HEAD` before the fix showed that the branch's net change included **shared, integrator-owned files**: `M .agent/pr-notes.md`, `M TODO.md`, `D .agent/pr-notes/AUD-20261120-13-20261120-101201.md`, `D docs/swarm/AUD-20261120-13.md`, plus stale (pre-AUD-13) versions of `documents/DocumentRepository.java`, `documents/DocumentService.java` and two deleted document test classes. Those stale versions are the merge result of an earlier `git merge origin/main` from before AUD-13 landed.
- `git merge --no-commit --no-ff origin/main` produced exactly one conflict, in `.agent/pr-notes.md`. All Java, test, contract and generated-client files auto-merged cleanly. This is the "merge conflict with main" the integrator reported: a worker had again written a per-task note into the shared file (HEAD's `.agent/pr-notes.md` is the `fix-20261120-112705` note), which AGENTS.md and the TODO "Instructions for every agent" forbid. A prior fix (`b1128ed`) had restored the shared file, but a later "Agent changes for READY-16" commit (`5310a62`) rewrote it again.
- `bash scripts/check.sh` on the merge reproduced a non-zero exit at `python3 -I scripts/check-pack.py`: `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented cloud-worker artifact (the worktree `opencode.json` is the CI profile copied under `skip-worktree`, and `git ls-files -v opencode.json` reports `S`), not a code, test or coverage defect of this story (MEMORY.md OPS-CW-003-01 closed as a false positive).

## Fix applied

1. Merged `origin/main` into the branch and resolved the sole conflict by taking main's version of the shared, integrator-owned file: `git checkout --theirs .agent/pr-notes.md && git add .agent/pr-notes.md`.
2. Did **not** author any edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `.agent/pr-notes.md`, `opencode.json` or `.github/workflows/*`. AUD-13's document code, its tests, its note and its `docs/swarm` record came back automatically from main during the merge; they are main's content, not this worker's.
3. Put this attempt's notes only in the file this worker owns: `.agent/pr-notes/fix-20261120-115243.md` (this file).
4. Changed **no production code, test, migration, contract, generated client or configuration**. The READY-16 implementation from the earlier commits is unchanged.

After the fix the net diff against `origin/main` contains only: four READY-16 production files (`OrderController.java`, `OrderPage.java`, `OrderRepository.java`, `OrderService.java`), three test classes (`OrderListControllerTest.java`, `OrderListServiceTest.java`, `it/exchange/order/OrderListIntegrationTest.java`), the regenerated staff-web `src/api/generated/openapi.d.ts`, `contracts/openapi.yaml`, and the per-task note files (`.agent/pr-notes/READY-16-20261120-090706.md`, the two earlier fix notes, and this file). A grep for conflict markers (`^<<<<<<<`, `^=======`, `^>>>>>>>`) over `apps`, `contracts`, `.agent` and `docs` returned none. No shared tracker differs from `origin/main`. The merge result has `origin/main` as an ancestor, so the integrator's merge of this branch is a clean fast-forward with no conflict.

## Acceptance mapping (ARC-03) — unchanged from the implementation

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` (existing workflow values) else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`; `OrderListControllerTest.badLimitReturns422`; `OrderListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. `created_at DESC, id DESC`, `<createdAt>:<id>` cursor of the last row, null `nextCursor` on last page, fetch `limit+1` | `OrderRepository.query` (`ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`), `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse the single-read action/role; add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` with `READ_ACTION = ORDER_READ`, `ORDERING_ROLE = clinician`, unchanged from `OrderService.get` | `OrderListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited` |
| 4. Parameterised SQL, forced RLS isolation, real V19 column names | `OrderRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource`; no manual organization predicate | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` in the view package | new `OrderPage.java` with a frozen list | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | three test classes, 21 tests | commands below |
| 7. Contract | `contracts/openapi.yaml` GET path + `OrderView`/`OrderPage` schemas; regenerated staff-web `openapi.d.ts` | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check` |

Entry point to test trace: `GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate before transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and roles re-derived per request) → `OrderRepository.query` (forced RLS, `created_at`/`id` DESC, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.
- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Exact commands and results (executed by this worker; tool evidence)

| Command | Result |
|---|---|
| `git diff --name-status origin/main HEAD` (before fix) | shared files `.agent/pr-notes.md`, `TODO.md`, `docs/swarm/AUD-20261120-13.md` and stale document code/tests in the net diff |
| `git merge --no-commit --no-ff origin/main` | single conflict in `.agent/pr-notes.md`; auto-merged everything else |
| `git checkout --theirs .agent/pr-notes.md && git add .agent/pr-notes.md` | conflict resolved; `git diff --name-only --diff-filter=U` empty |
| `git diff origin/main --name-status` (after fix) | only READY-16 production/test/contract/generated-client files and per-task notes; no shared tracker |
| `git diff origin/main -- .agent/pr-notes.md` | empty (shared file identical to main) |
| grep for `^<<<<<<<\|^=======\|^>>>>>>>` in `apps contracts .agent docs` | no output (no conflict markers) |
| `bash scripts/check.sh` (reproduction on the merge) | exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — known cloud-worker artifact, not a story defect |
| `bash scripts/worker-check.sh` (the step-6 check from TODO "Instructions for every agent") | exit 1 at the same `check-pack` stage; same environment-only cause |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0; Surefire totals **1930 tests, 0 failures, 0 errors, 0 skipped** (unit tier, synthetic fixtures) |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `npm run gen:api:check --prefix apps/staff-web` | `gen:api: dependencies missing; run npm ci in apps/staff-web` (module `node_modules` absent in this container; not a contract drift) |
| `bash scripts/frontend-check.sh` | exit 0 — `frontend standards: policy and tool commands passed`; Vitest 1/1 |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py` | all exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| fresh render of `scripts/gen-opencode.py` vs committed config | `HEAD opencode.json == fresh render: True`; `HEAD ci == fresh render(ci=True): True` — the committed config is correct; `check-pack` measures the CI-profile worktree file under `skip-worktree` |

## Coverage result (mandatory)

This repair added **no production line, branch, method or class**, so it introduces no new coverage obligation. The READY-16 implementation's 100% coverage was proven in the earlier attempt by `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh` (`mvn verify`, full unit + real-PostgreSQL integration tier), with JaCoCo 0.8.16 build-failing gates on INSTRUCTION, LINE, BRANCH, METHOD and CLASS. The recorded `jacoco.xml` counters were all `missed = 0`: INSTRUCTION 90998, BRANCH 6445, LINE 15797, COMPLEXITY 7098, METHOD 3824, CLASS 787; per touched file `OrderPage.java` (LINE 4, METHOD 2), `OrderService.java` (LINE 200, BRANCH 94, METHOD 24), `OrderRepository.java` (LINE 106, BRANCH 6, METHOD 24), `OrderController.java` (LINE 12, METHOD 7) — **100% on every file changed in the implementation**. That evidence is in `.agent/pr-notes/READY-16-20261120-090706.md`; the per-branch-to-test mapping there remains valid unchanged. The `mvn -Pno-security,fast test` re-run above regenerated `target/` and removed that report, so its counters are cited from the recorded evidence rather than re-read here. Per this worker's instructions the database and integration tests are the integrator's gate; no threshold, exclusion or skip was introduced and no test was disabled.

## Defects found, decisions and deviations

- **Defect (fixed):** a later "Agent changes for READY-16" commit rewrote the shared `.agent/pr-notes.md` after an earlier fix had restored it, reintroducing the integrator merge conflict; the branch was also stale relative to main (missing AUD-20261120-13). Fixed by merging main and taking main's version of the shared file. No production defect was found in the READY-16 implementation on re-verification.
- **Decision:** keep the two earlier per-task fix notes (`fix-20261120-105859.md`, `fix-20261120-112705.md`) rather than delete other runs' records; per-task files under `.agent/pr-notes/` are owned by their named task and do not affect any gate. Only the shared file had to be reverted.
- **Environment-only findings (no repo change):** `check-pack.py`/`check.sh` fail because `opencode.json` is the CI profile under `skip-worktree` (`S`), although both committed config files match a fresh render; PyYAML and pip are absent, so the full `scripts/tests` unittest discovery cannot run here; `apps/staff-web/node_modules` is absent, so `gen:api:check` cannot regenerate. None is caused by, or fixable inside, this story. The Java unit tier, policy scripts and frontend gate were run directly and are green.

## Evidence sources

Tool evidence: every command above was executed locally (`git`, Maven/Surefire, the contract gate, the frontend gate and the repository policy scripts). Reviewer evidence: the specialist skills `smartclinic-delivery`, `smartclinic-debugger`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`, `smartclinic-senior-database`, `smartclinic-software-engineer` and `smartclinic-qa-reviewer` were loaded and their rules applied; no separate reviewer run was dispatched for this bounded merge repair. Simulated-user evidence: none. Human/clinical evidence: none (backend list endpoint and tracker repair, not a clinical decision workflow). All fixtures are synthetic.
