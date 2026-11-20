# fix-20261120-134632 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.
Classification: cross-boundary repair of a recurring integrator merge conflict (tracker hygiene; no production feature code changed in this segment).
Branch: `swarm/READY-16-275`, HEAD at start `de573745`. `origin/main` = `742f2bb`, an ancestor of the branch. Synthetic fixtures only — no patient data, credential, token or real domain name in code, logs, URLs or browser storage.
Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Root cause reproduced (tool evidence)

The integrator reported: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3."* (`scripts/swarm.py:1288`; wording produced by `scripts/agent-run.sh:343-363` `cmd_resolve` and `scripts/agent-run.sh:354-355`).

| Forensics command | Result |
|---|---|
| `git log --oneline -15` | Alternating `fix-…: restore shared pr-notes` and `Agent changes for READY-16` commits since `742f2bb` (16 prior fix attempts). |
| `git show --stat HEAD` | `de57374 Agent changes for READY-16` touched **only** `.agent/pr-notes.md` (95 insertions, 80 deletions). |
| `head -3 .agent/pr-notes.md` (at start) | `# fix-20261120-133603 — READY-16 merge-conflict repair` (a worker note). |
| `git show origin/main:.agent/pr-notes.md \| head -1` | `# AUD-20261120-13 — Document link accepts order or result subjects without verifying they exist`. |
| `git diff --numstat origin/main HEAD -- .agent/pr-notes.md` | `95 80` — both sides carry a version of the integrator-owned shared file. |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) '` | no conflict markers in tracked files. |
| `git merge-tree --write-tree origin/main HEAD` | exit 0 before the fix — the feature code/contract merges cleanly; only the shared file diverges. |

Root cause: successive worker sessions wrote their notes into the **tracked, integrator-owned** `.agent/pr-notes.md`. `git add -A` in `scripts/agent-run.sh:201-202` committed that edit as `Agent changes for READY-16`, producing a recurring conflict with main. The independent secondary cause of the *"checks failed"* wording is the resolve worker's image lacking PyYAML (below); removing the shared-file conflict lets the integrator merge the PR directly and avoids that path.

## Reproduction: `scripts/check.sh` on the merged tree

The current branch already contains main (`742f2bb` is an ancestor), so the worktree is the merged tree. Executed:

| Command | Result |
|---|---|
| `bash scripts/check.sh` | exit 1 at `check-pack.py`: `opencode.json is stale: run scripts/gen-opencode.py` — worktree `opencode.json` is the CI profile under `skip-worktree` (OPS-CW-003-01 false positive). |
| `bash scripts/check.sh` with committed local profile (`git show HEAD:opencode.json > opencode.json`) | exit 1 at `scripts/tests`: `Ran 271 tests … FAILED (failures=6, errors=10)`; 10 errors are `ModuleNotFoundError: No module named 'yaml'` (`test_fast_tier`, `test_swarm`, `test_workflow_concurrency`), the 6 failures are cascading `test_agent_run.py` harness failures (e.g. `'merged but checks failed' != 'merged'`). Python 3.11.2 here has no PyYAML and no pip; `apt-get install python3-yaml` → `E: Unable to locate package`. All failures are before any module check and none is attributable to READY-16 code, contract or generated client. |
| `bash scripts/worker-check.sh` with committed local profile | exit 1 at the same Python policy stage (identical PyYAML failures), before the PostgreSQL integration and db policy steps. |

`opencode.json` was restored to its original CI profile after measurement; it is `S` (skip-worktree) and was never committed by this fix. `gen-opencode.py --check` and `check-pack.py` pass with the committed local profile (33 agents, 33 skills). The integrator image carries PyYAML and re-runs the full gate on its own merge result.

## Fix applied

1. Restored the shared file to main's exact blob: `git checkout origin/main -- .agent/pr-notes.md`. `git diff --numstat origin/main -- .agent/pr-notes.md` is now empty — no branch-side diff on the shared file, so main's newer AUD note is preserved by a plain merge and the recurring conflict source is removed.
2. Wrote this attempt's evidence **only** to the worker-owned file `.agent/pr-notes/fix-20261120-134632.md`.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/*`, `scripts/` or any migration.
4. Changed **no** production code, test, migration, contract, generated client, dependency or configuration. The READY-16 implementation from the earlier commits is untouched and re-verified below.

Post-fix verification: `git grep` → no conflict markers; `git merge-tree --write-tree origin/main HEAD` → exit 0; `git status --short` shows only the intended shared-file restore plus this notes file.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` (`STATUSES = awaiting_local_patient, ready`) | `OrderListServiceTest` (11), `OrderListControllerTest` (5), `OrderListIntegrationTest` (5) |
| 2. `created_at DESC, id DESC`; cursor `<createdAt>:<id>` of the last returned row; null on last page; `limit+1` fetch | `OrderRepository.query` (`SELECT_ORDER` + keyset predicate + `ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`), `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, identical to `OrderService.get` (line 199) | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real V19 column names, no manual org predicate | `OrderRepository.query` over `public.exchange_order` joined to `exchange_order_snapshot` (and `exchange_work_item` only for the status filter); states confirmed against `V19__exchange_orders.sql` | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows` |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` | `exchange/order/OrderPage.java`, frozen via `List.copyOf` | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | 3 test classes, 21 tests | commands below |
| 7. Contract | `contracts/openapi.yaml` `get /api/v1/orders` + `OrderView`/`OrderPage` schemas; staff-web `openapi.d.ts` regenerated | `python3 -I contracts/openapi/check.py` green |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used.

## Entry point to test trace

`GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate inputs before any transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and role re-derived each request) → `OrderRepository.query` (forced RLS, `created_at DESC, id DESC`, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.

- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed by this worker, tool evidence)

| Command | Result |
|---|---|
| `git fetch origin main` (via refs already present) | `origin/main` = `742f2bb`, ancestor of HEAD — no newer main locally |
| `git checkout origin/main -- .agent/pr-notes.md` | exit 0; shared file restored to main's AUD-20261120-13 note |
| `git diff --numstat origin/main -- .agent/pr-notes.md` | empty — no net diff on the shared file |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) '` | no output — no conflict markers |
| `git merge-tree --write-tree origin/main HEAD` | exit 0 — clean merge |
| `bash scripts/check.sh` | exit 1 at check-pack (`opencode.json is stale` — worktree CI-profile artifact) |
| `bash scripts/check.sh` with committed local `opencode.json` | exit 1 at `scripts/tests`: 6 failures / 10 errors, all `No module named 'yaml'` or cascading `test_agent_run.py` harness failures — environment-only, before any Java module check |
| `bash scripts/worker-check.sh` with committed local `opencode.json` | exit 1 at the same Python policy stage (identical PyYAML failures) |
| `python3 -I scripts/check-pack.py` with committed local profile | `Delivery tooling validated: 33 agents, 33 skills` (exit 0) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='OrderListServiceTest,OrderListControllerTest' test` | **exit 0**; `OrderListControllerTest` 5/0/0, `OrderListServiceTest` 11/0/0 (fast unit tier) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | **exit 0 — BUILD SUCCESS**; `Tests run: 2381, Failures: 0, Errors: 0, Skipped: 0`; `OrderListIntegrationTest` 5/0/0; `[INFO] All coverage checks have been met.` |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |

`verify` used `-Pno-security` only to skip OWASP/SpotBugs, whose NVD update stalls in this container without `NVD_API_KEY`; those scans are owned by the integrator/Delivery gate and were not altered here. The integrator image has PyYAML and PostgreSQL and re-runs the full gated check on its merge result.

### Coverage result (mandatory)

`SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` executed JaCoCo 0.8.16 `report` + `check` (BUNDLE rule, 1.00 minimum for INSTRUCTION, LINE, BRANCH, METHOD, CLASS) and reported `All coverage checks have been met.` `apps/api/target/site/jacoco/jacoco.xml` counters:

| Counter | Missed | Covered |
|---|---|---|
| INSTRUCTION | 0 | 91024 |
| BRANCH | 0 | 6449 |
| LINE | 0 | 15803 |
| COMPLEXITY | 0 | 7101 |
| METHOD | 0 | 3825 |
| CLASS | 0 | 787 |

Per touched production file (0 missed on every counter): `OrderService.java` — 200 lines, 94 branches, 24 methods; `OrderRepository.java` — 106 lines, 6 branches, 24 methods; `OrderPage.java` — 4 lines, 2 methods; `OrderController.java` — 12 lines, 7 methods. **100% on every file changed.** The team's 100% thresholds were not lowered and no exclusion, `@Disabled`, `.skip` or `xit` was added.

This fix segment changed **no production code**, so it added no new branch to cover; the 100% result above is the unchanged READY-16 implementation on the exact committed tree.

## Branch-to-test mapping (no new branches in this fix)

No production branch was added in this segment. The READY-16 implementation's branch-to-test mapping is unchanged; every side was re-confirmed by the fast unit tests and the full `verify` above. The detailed per-branch table is in `.agent/pr-notes/READY-16-20261120-090706.md`.

## Defects found, decisions and deviations

- **Recurring defect:** 16 prior worker sessions on this branch committed edits to the integrator-owned shared `.agent/pr-notes.md`, reintroducing the same merge conflict after each restore (latest `de57374`). This fix removes the shared-file diff so the branch merges cleanly while preserving main's note. Self-improvement: the rule — never write the shared file, write only the worker-owned `.agent/pr-notes/<task-id>.md` — is recorded here and in the prior fix notes; the durable guard belongs to `scripts/agent-run.sh` (`git add -A` should exclude `.agent/pr-notes.md`), which is outside this task's scope and left to the orchestrator.
- **Decision:** restore the shared file to main's exact blob rather than merging worker and AUD notes; the shared file is main's property and any worker content there is invalid under AGENTS.md.
- **Decision:** no production/test/contract/config change; the READY-16 implementation is complete and re-verified, so this segment is tracker hygiene only.
- **Deviation (transparent):** none in this segment. The earlier READY-16 segment documented that the `it/` integration test and regenerated staff-web d.ts sit outside the literal `scope` but are required by the story's explicit steps; unchanged here.
- **Environment-only findings (no repo change):** Python 3.11.2 here lacks PyYAML/pip, so `scripts/check.sh` and `scripts/worker-check.sh` fail at the Python policy stage; `opencode.json` is `S` (skip-worktree, CI profile) in this worker, so check-pack fails on the worktree copy but passes with the committed profile; OWASP/NVD update stalls without an API key. None is caused by this fix.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Git, Maven/Surefire/JaCoCo, the contract gate and the repository policy scripts. Reviewer evidence: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer` and `smartclinic-senior-java-build` were loaded and their rules applied (simulated review); no separate reviewer subagent was run for this bounded merge-conflict fix. Simulated-user evidence: none. Human/clinical/patient evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.

**Status:** fix applied — shared file restored to main, notes written only to this worker's file, fast unit tier green (16 READY-16 tests, 0 failures), full JaCoCo gate green at 100% (2381 tests), contract gate green, no production code changed. Ready for the integrator to re-run the full merge gate.
