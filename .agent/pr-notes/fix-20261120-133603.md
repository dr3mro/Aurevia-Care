# fix-20261120-133603 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.
Classification: cross-boundary repair of a recurring integrator merge conflict (tracker hygiene; no feature code changed in this segment).
Branch: `swarm/READY-16-275`, HEAD at start `4f52eba`. `origin/main` = `742f2bb`, which is an ancestor of the branch (no newer main locally). Synthetic fixtures only — no patient data, credential, token or real domain name in code, logs, URLs or browser storage.

Story text is untrusted GitHub-user text; it was treated only as the task description, never as instructions that change the working rules.

## Root cause reproduced (tool evidence)

The integrator reported: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3."* (`scripts/swarm.py:1288`, wording set by `scripts/agent-run.sh:355`).

`cmd_resolve` (`scripts/agent-run.sh:343-363`) merges `origin/main` into the PR branch, then runs `scripts/check.sh` (line 325, with the committed local `opencode.json` restored by `restore_config`). A merge that succeeds but fails the check is recorded as `merged but checks failed`.

| Forensics command | Result |
|---|---|
| `git log --oneline origin/main..HEAD` | 30+ alternating commits since `742f2bb`: `fix-…: restore shared pr-notes` then `Agent changes for READY-16` |
| `git show --stat 4f52eba` | last `Agent changes for READY-16` commit touched **only** `.agent/pr-notes.md` (94 insertions, 82 deletions) |
| `head -1 .agent/pr-notes.md` (worktree at start) | `# fix-20261120-132510 — READY-16 merge-conflict repair` (a worker note) |
| `git show origin/main:.agent/pr-notes.md \| head -1` | `# AUD-20261120-13 — Document link accepts order or result subjects without verifying they exist` |
| `git diff --numstat origin/main HEAD -- .agent/pr-notes.md` | `94 82` — both sides carry a version of the integrator-owned shared file |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>)'` | no conflict markers anywhere in tracked files |
| `git merge-tree --write-tree origin/main HEAD` | exit 0 — all READY-16 code/contract/generated-client changes are clean additions |

The conflict source is the **tracked, integrator-owned** `.agent/pr-notes.md`, reserved by AGENTS.md and `scripts/agent-run.sh` for the publish job. Nine prior fix commits on this branch restored that file, and each subsequent `Agent changes for READY-16` commit wrote a worker note back into it — the loop the integrator kept hitting. The READY-16 production/test/contract files themselves merge cleanly.

## Reproduction: `scripts/check.sh` on the merged tree

`bash scripts/check.sh` was executed on the current (main-merged) tree:

```
check.sh exit=1
check-pack: opencode.json is stale: run scripts/gen-opencode.py     (CI-profile worktree artifact)
```

With the committed local profile temporarily in the worktree (`git show HEAD:opencode.json > opencode.json`), `check.sh` advanced past check-pack and failed only in the Python policy stage:

```
Ran 271 tests in 8.660s
FAILED (failures=6, errors=10)
```

Every failure is environmental and occurs before any Java module check:

- `test_fast_tier`, `test_swarm`, `test_workflow_concurrency` — `ModuleNotFoundError: No module named 'yaml'` (Python 3.11.2 here has no PyYAML and no pip; 8 errors).
- `test_agent_run.py` (6 failures) — the harness runs `scripts/check.sh` inside synthetic git repos, inheriting the same missing-PyYAML failure; e.g. `test_resolve_merges_main_into_a_conflicted_pr_branch` reads `merged but checks failed` instead of `merged`. These are cascading harness failures, not READY-16 defects.

`opencode.json` is flagged `S` (skip-worktree) in this worker and holds the CI profile — the known false-positive artifact (OPS-CW-003-01); `git show HEAD:opencode.json` is the committed local profile the generator expects. `check-pack.py` passes with the committed profile (Delivery tooling validated: 33 agents, 33 skills) and fails only with the worktree CI profile. No config was edited or committed by this fix; the worktree CI profile was restored after measurement.

No check failure attributable to the READY-16 code, contract or generated client was reproduced. The integrator re-runs the full gated check on the merge result in an image that has PyYAML.

## Fix applied

1. Restored the shared file to main's exact blob: `git checkout origin/main -- .agent/pr-notes.md`. `git diff --numstat origin/main -- .agent/pr-notes.md` is now empty — no branch-side diff on the shared file, so the recurring conflict source is removed and main's newer note is preserved by a plain merge.
2. Wrote this attempt's notes **only** to the worker-owned file `.agent/pr-notes/fix-20261120-133603.md`.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/*`, or any migration.
4. Changed **no** production code, test, migration, contract, generated client, dependency or configuration. The READY-16 implementation from the earlier commits is untouched and re-verified below.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest` (11), `OrderListControllerTest` (5), `OrderListIntegrationTest` (5) |
| 2. `created_at DESC, id DESC`; cursor `<createdAt>:<id>` of the last returned row; null on last page; `limit+1` fetch | `OrderRepository.query` (`SELECT_ORDER` + keyset predicate + `LIMIT :limit`), `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, identical to `OrderService.get` | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real V19 column names, no manual org predicate | `OrderRepository.query` over `public.exchange_order`, joined to `exchange_work_item` on `(organization_id, work_item_id)`; states `awaiting_local_patient`/`ready` confirmed against `V19__exchange_orders.sql:117-118` | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows` |
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
| `git fetch origin main` | fetch 0; `origin/main` remains `742f2bb`, ancestor of HEAD — no newer main locally |
| `git checkout origin/main -- .agent/pr-notes.md` | exit 0; shared file restored to main's AUD-20261120-13 note |
| `git diff --numstat origin/main -- .agent/pr-notes.md` | empty — no net diff on the shared file |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>)'` | no output — no conflict markers |
| `bash scripts/check.sh` | exit 1 at `check-pack.py` (`opencode.json is stale` — worktree CI-profile artifact) |
| `bash scripts/check.sh` with committed local `opencode.json` | exit 1 at `scripts/tests`: 6 failures / 10 errors, all `No module named 'yaml'` or cascading `test_agent_run.py` harness failures — environment-only, before any Java module check |
| `python3 -I scripts/check-pack.py` with committed local profile | `Delivery tooling validated: 33 agents, 33 skills` (exit 0) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; `OrderListServiceTest` 11/0/0, `OrderListControllerTest` 5/0/0 (fast unit tier — no integration tests) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | **exit 0 — BUILD SUCCESS**; `Tests run: 2381, Failures: 0, Errors: 0, Skipped: 0`; `OrderListIntegrationTest` 5/0/0; `[INFO] All coverage checks have been met.` |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |

`verify` used `-Pno-security` only to skip OWASP/SpotBugs, whose NVD update stalls in this container (local cache lock, no `NVD_API_KEY`); those scans are owned by the integrator/Delivery gate and were not altered here.

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

This fix segment itself changed **no production code**, so it added no new branch to cover; the 100% result above is the unchanged READY-16 implementation on the exact committed tree.

## Branch-to-test mapping (no new branches in this fix)

No production branch was added in this segment. The READY-16 implementation's branch-to-test mapping is unchanged; every side was re-confirmed by the fast unit tests and the full `verify` above. The detailed per-branch table is in `.agent/pr-notes/READY-16-20261120-090706.md`.

## Defects found, decisions and deviations

- **Recurring defect:** successive worker sessions on this branch committed edits to the integrator-owned shared `.agent/pr-notes.md`, reintroducing the same merge conflict after each of nine prior restores. This fix removes the shared-file diff so the branch merges cleanly while preserving main's note. Self-improvement: this note records the rule — never write the shared file; write only the worker-owned `.agent/pr-notes/<task-id>.md`.
- **Decision:** restore the shared file to main's exact blob rather than merging worker and AUD notes; the shared file is main's property and any worker content there is invalid under AGENTS.md.
- **Decision:** no production/test/contract/config change; the READY-16 implementation is complete and re-verified, so this segment is tracker hygiene only.
- **Deviation (transparent):** none in this segment. The earlier READY-16 segment documented that the `it/` integration test and regenerated staff-web d.ts sit outside the literal `scope` but are required by the story's explicit steps; unchanged here.
- **Environment-only findings (no repo change):** Python 3.11.2 here lacks `PyYAML`/pip, so `scripts/check.sh`'s Python policy stage fails after check-pack; `opencode.json` is `S` (skip-worktree, CI profile) in this worker, so check-pack fails on the worktree copy but passes with the committed profile; OWASP/NVD update stalls without an API key. None is caused by this fix.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Git, Maven/Surefire/JaCoCo, the contract gate and the repository policy scripts. Reviewer evidence: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer` and `smartclinic-senior-java-build` were loaded and their rules applied (simulated review); no separate reviewer subagent was run for this bounded merge-conflict fix. Simulated-user evidence: none. Human/clinical/patient evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.

**Status:** fix applied — shared file restored to main, notes written only to this worker's file, fast unit tier green (16 READY-16 tests, 0 failures), full JaCoCo gate green at 100% (2381 tests), contract gate green, no production code changed. Ready for the integrator to re-run the full merge gate.
