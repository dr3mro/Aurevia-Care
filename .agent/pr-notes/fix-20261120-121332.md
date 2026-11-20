# fix-20261120-121332 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement IDs: **ARC-03** (versioned REST resource family, cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary repair of a recurring integrator merge conflict. No production code, test, contract, migration, dependency, generated client or configuration was changed by this fix; only the shared, integrator-owned `.agent/pr-notes.md` was restored to main's content and this per-task note was added.
Branch: `swarm/READY-16-275`. Base for this fix: branch HEAD `bb834e1`, `origin/main` `742f2bb` (`git fetch origin main` confirmed main had not advanced; the branch was 12 ahead, 0 behind).
Synthetic fixtures only; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.

## Root cause of the reported failure (tool evidence)

Integrator report: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

This is a recurrence of the defect already recorded in `fix-20261120-115243.md` and `fix-20261120-112705.md`: a later auto-generated "Agent changes for READY-16" commit rewrites the **shared**, integrator-owned `.agent/pr-notes.md` with a copy of a per-task fix note, reintroducing the merge conflict on every repair cycle.

Reproduction and diagnosis, all executed locally:

- `git diff origin/main --name-status` showed that the only shared tracker differing between main and this branch was `M .agent/pr-notes.md`. Its HEAD content was the **previous** fix's note (`fix-20261120-115243 ...`), while `origin/main` holds the AUD-20261120-13 note. All other changed files were the READY-16 implementation and the per-task note files.
- `git merge-base HEAD origin/main` = `742f2bb` = `origin/main`; no other shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/`) differs from main. The conflict is isolated to the shared pr-notes file, which both sides modify.
- `bash scripts/check.sh` on the branch exited 1 at `python3 -I scripts/check-pack.py`: `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented cloud-worker artifact (MEMORY.md OPS-CW-003-01, closed as a false positive): the worktree `opencode.json` is the CI profile copied under `skip-worktree` (`git ls-files -v opencode.json` reports `S`), so `check-pack.py` judges that profile rather than the committed local one.

## Fix applied

1. Restored the shared file to main's content: `git checkout origin/main -- .agent/pr-notes.md`. Post-fix `git diff origin/main -- .agent/pr-notes.md` is empty, and `git diff origin/main --name-status -- <all shared trackers>` is empty. A grep for conflict markers (`^<<<<<<<|^=======|^>>>>>>>`) over `apps`, `contracts`, `.agent`, `docs` returned none. The branch now differs from main only in the READY-16 implementation files, the generated staff-web client, the contract and the per-task note files, so the integrator's merge is conflict-free.
2. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `.agent/pr-notes.md`, `opencode.json` or `.github/workflows/*`.
3. Wrote this attempt's notes only to the file this worker owns: `.agent/pr-notes/fix-20261120-121332.md`.
4. Changed **no** production code, test, migration, contract, generated client or configuration. The READY-16 implementation from the earlier commits is unchanged and was re-verified (below).

## Acceptance mapping (ARC-03) — unchanged from the implementation

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the existing workflow values else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`; `OrderListControllerTest.badLimitReturns422` |
| 2. `created_at DESC, id DESC`, `<createdAt>:<id>` cursor of the last row, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query` (`ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`), `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-read action/role; add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` with `READ_ACTION = ORDER_READ`, `ORDERING_ROLE = clinician`, unchanged from `OrderService.get` | `OrderListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited` |
| 4. Parameterised SQL, forced-RLS isolation, real V19 column names | `OrderRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `exchange_order`/`exchange_work_item`; no manual organization predicate | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` in the view package | `exchange/order/OrderPage.java` with a frozen list | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | three test classes, 21 tests | commands below |
| 7. Contract | `contracts/openapi.yaml` GET path + `OrderView`/`OrderPage` schemas; regenerated staff-web `openapi.d.ts` | `python3 -I contracts/openapi/check.py` (exit 0) |

Entry point to test trace: `GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate before transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and roles re-derived per request) → `OrderRepository.query` (forced RLS, `created_at`/`id` DESC, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.
- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Exact commands and results (executed by this worker; tool evidence)

| Command | Result |
|---|---|
| `git fetch origin main` | main unchanged at `742f2bb`; branch 12 ahead / 0 behind |
| `git diff origin/main --name-status` (before fix) | only shared tracker differing: `M .agent/pr-notes.md` (held the `fix-20261120-115243` note) plus READY-16 per-task notes, implementation, contract and generated client |
| `bash scripts/check.sh` (reproduction) | exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — known cloud-worker artifact, not a story defect |
| fresh render vs committed config (`git show HEAD:opencode.json` vs `gen-opencode.py` output) | `HEAD == fresh local render: YES`; `worktree CI profile == fresh local render: NO` — committed config is correct; `check-pack` measures the CI-profile worktree file under `skip-worktree` |
| `git checkout origin/main -- .agent/pr-notes.md` | shared file restored; `git diff origin/main -- .agent/pr-notes.md` empty; all shared trackers identical to main |
| grep `^<<<<<<<\|^=======\|^>>>>>>>` in `apps contracts .agent docs` | no output (no conflict markers) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (fast unit tests) | exit 0; Surefire totals **1930 tests, 0 failures, 0 errors, 0 skipped**; `OrderListServiceTest` 11, `OrderListControllerTest` 5 |
| `bash scripts/worker-check.sh` (the step-6 check from TODO "Instructions for every agent") | exit 1 at the same `check-pack: opencode.json is stale` stage — same environment-only cause |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py` | all exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| contract/generated-client presence | `contracts/openapi.yaml` has `/api/v1/orders:` (line 340) and `OrderPage:` schema (line 559); `apps/staff-web/src/api/generated/openapi.d.ts` has `OrderPage` (line 447) |

## Coverage result (mandatory)

This repair added **no production line, branch, method or class**, so it introduces no new coverage obligation; the only changed files are Markdown notes. It did not lower, remove or bypass any threshold, add an exclusion or skip a test.

The READY-16 implementation's 100% coverage was proven in the earlier attempts by `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh` (`mvn verify`, full unit + real-PostgreSQL integration tier), with JaCoCo 0.8.16 build-failing gates on INSTRUCTION, LINE, BRANCH, METHOD and CLASS. Recorded `jacoco.xml` counters were all `missed = 0`: INSTRUCTION 90998, BRANCH 6445, LINE 15797, COMPLEXITY 7098, METHOD 3824, CLASS 787; per touched file `OrderPage.java` (LINE 4, METHOD 2), `OrderService.java` (LINE 200, BRANCH 94, METHOD 24), `OrderRepository.java` (LINE 106, BRANCH 6, METHOD 24), `OrderController.java` (LINE 12, METHOD 7) — **100% on every file changed in the implementation**. That evidence and the full per-branch-to-test mapping are in `.agent/pr-notes/READY-16-20261120-090706.md` and remain valid unchanged. Per this worker's instructions the database and integration tests are the integrator's gate; this run executed the fast unit tier only.

## Contract generation note

`contracts/openapi/README.md` states that `contracts/openapi/generated/openapi.yaml` is emitted from `CommandClientGenerator.java`/`CommandCatalog.java` and is distinct from the staff-API `contracts/openapi.yaml` consumed by `apps/staff-web/scripts/gen-api-types.sh`. The staff GET changes only the latter; it adds no command to the Java catalog, so the generated X-04 artifacts are unchanged and `python3 -I contracts/openapi/check.py` stays green. The dependent staff-web `src/api/generated/openapi.d.ts` was regenerated with the pinned generator in the implementation and is unchanged by this fix.

## Defects found, decisions and deviations

- **Defect (fixed):** an auto-generated "Agent changes for READY-16" commit (`bb834e1`) rewrote the shared `.agent/pr-notes.md` after the previous fix (`862e061`) had restored it, reintroducing the integrator merge conflict. Fixed by restoring main's version of the shared file. No production defect was found in the READY-16 implementation on re-verification (1930 unit tests green, all lightweight gates green).
- **Recurring pattern / follow-up for the orchestrator:** this is the third repair of the same root cause (`fix-20261120-112705`, `fix-20261120-115243`, this file). Each time, an "Agent changes for READY-16" commit copies a per-task note into the shared file. The harness/handoff that generates those commits should write only to `.agent/pr-notes/<task-id>.md`; this worker cannot change that from inside the scoped story. No repo change was made for it here.
- **Decision:** keep the earlier per-task fix notes rather than delete other runs' records; per-task files under `.agent/pr-notes/` are owned by their named task and affect no gate. Only the shared file had to be reverted.
- **Environment-only findings (no repo change):** `check-pack.py`/`check.sh`/`worker-check.sh` fail because `opencode.json` is the CI profile under `skip-worktree` (`S`) although the committed HEAD file matches a fresh render; Python 3.11 here lacks `PyYAML`/`pip`, so the full `scripts/tests` unittest discovery cannot run; `apps/staff-web/node_modules` is absent, so `gen:api:check` cannot regenerate. None is caused by, or fixable inside, this story. The Java unit tier and the lightweight policy/contract gates were run directly and are green.

## Evidence sources

Tool evidence: every command above was executed locally (`git`, Maven/Surefire, the contract gate and the repository policy scripts); results are from their actual output. Reviewer evidence: the specialist skills `smartclinic-delivery`, `smartclinic-debugger`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`, `smartclinic-senior-database`, `smartclinic-software-engineer` and `smartclinic-qa-reviewer` were loaded and their rules applied; no separate reviewer run was dispatched for this bounded merge repair. Simulated-user evidence: none. Human/clinical evidence: none (backend list endpoint and tracker repair, not a clinical decision workflow). All fixtures are synthetic.
