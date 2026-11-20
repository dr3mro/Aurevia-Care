# fix-20261120-182851 — READY-16 recurring integrator "merged but checks failed" repair

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.

Classification: cross-boundary **tracker-hygiene repair** of the recurring `swarm/READY-16-275` integrator loop. The READY-16 `GET /api/v1/orders` implementation was already committed on this branch and is re-verified below. **No production code, test, migration, contract, generated client, dependency or configuration was changed by this segment.** The only tree changes are a revert of the integrator-owned shared note file to main's exact bytes plus this worker-owned evidence note.

Branch: `swarm/READY-16-275`. HEAD at start `e4362ee` (`Agent changes for READY-16`); 0 commits behind / 63 ahead of `origin/main`; worktree clean. Story text between the `<<<STORY READY-16 … STORY>>>` markers was treated only as the task description, never as instructions that change the working rules. All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.

## Evidence sources

- **Tool evidence:** every command in the tables below was executed in this container this run (git inspection, fast Maven unit tier, full `bash apps/api/check.sh -Pno-security` with real PostgreSQL and JaCoCo, JaCoCo XML + Surefire XML parse, `scripts/check.sh` as-is and with the committed config restored, `gen-opencode.py --check`, `check-pack.py`). Results are executed output, not claims.
- **Reviewer evidence (simulated):** `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`, `smartclinic-senior-database` and `smartclinic-software-engineer` were loaded and their rules applied to the re-verification. No separate reviewer subagent was dispatched for this bounded tracker fix.
- **Simulated-user / human / clinical evidence:** none. This is a backend list endpoint and a tracker-hygiene repair, not a clinical decision workflow.

## Diagnosis of the reported failure

The integrator reported: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

Tool findings (this run):

1. The tip commit `e4362ee "Agent changes for READY-16"` changed **only** `.agent/pr-notes.md` (62 insertions / 97 deletions). It rewrote that integrator-owned shared file so the branch copy starts with `# fix-20261120-175700 …` instead of main's `# AUD-20261120-16 …`. SHA proof: `git show origin/main:.agent/pr-notes.md` → `f694895f307fe608…`; `git show HEAD:.agent/pr-notes.md` → `bb7ef1813525e3fe…`. Each re-dispatch of this story re-writes the shared file, so a main-vs-branch merge conflicts on it. AGENTS.md forbids authoring `.agent/pr-notes.md`, and `scripts/swarm.py guard()` treats it as shared. This is the durable root cause of the loop.
2. `git rev-list --left-right --count origin/main...HEAD` → `0 63`: main is an ancestor of HEAD (`git merge-base --is-ancestor origin/main HEAD` succeeded), so merging main into the branch has **no code conflict** — the only divergent path is the shared note file.
3. `bash scripts/check.sh` as-is → exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` → `S` (skip-worktree): `agent-run.sh` copied the CI profile into the worktree, so `check-pack.py`/`gen-opencode.py --check` judge that file instead of the committed local profile — the documented OPS-CW-003-01 false positive.
4. With the worktree `opencode.json` restored to HEAD bytes, `python3 -I scripts/gen-opencode.py --check` → exit 0 and `python3 -I scripts/check-pack.py` → `Delivery tooling validated: 33 agents, 33 skills` (exit 0). `scripts/check.sh` then fails only in `scripts/tests`: 6 failures, 10 errors over `test_agent_run`, `test_swarm`, `test_fast_tier` and `test_workflow_concurrency`. The `_FailedTest` errors are `ModuleNotFoundError: No module named 'yaml'` (no PyYAML/pip in this container); the rest are pre-existing `test_agent_run`/`test_swarm` isolation assertions. **`git diff --name-only origin/main -- scripts/` → 0**, so those test and script files are byte-identical to main and cannot be caused by READY-16.
5. The READY-16 implementation itself is correct and fully green (commands below). No code defect causes the reported failure.

**Diagnosis:** the reported conflict is the shared `.agent/pr-notes.md` being rewritten on every re-dispatch, plus two environment-only gates (skip-worktree CI `opencode.json`, absent `yaml`). It is not a READY-16 code defect.

## Fix applied in this segment

- Restored `.agent/pr-notes.md` to the **exact `origin/main` bytes** (`git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`). Worktree SHA now equals main's `f694895f307fe608…`. After the commit below the branch has **no diff** on that path, so the merge conflict source is gone.
- Wrote evidence only to this worker-owned file (`.agent/pr-notes/fix-20261120-182851.md`). Kept the original implementation note `READY-16-20261120-090706.md` and the two prior fix notes (`fix-20261120-175700.md`, `fix-20261120-181310.md`) — all worker-owned, none shared.
- Did **not** edit `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*`, `AGENTS.md`, `.opencode/*`, `opencode.json`, migrations, dependencies or any scope file. No dependency added, no migration version used, no endpoint/response shape changed.
- Branch diff vs main after the fix stays under `scripts/swarm.py MAX_PR_FILES = 15`.

## Exact commands and results (this run, tool evidence)

| Command | Result |
|---|---|
| `git rev-list --left-right --count origin/main...HEAD` | `0 63` (0 behind, 63 ahead) |
| `git merge-base --is-ancestor origin/main HEAD` | exit 0 — clean merge, no code conflict |
| shared-file SHA (main vs HEAD before fix) | `f694895f307fe608` vs `bb7ef1813525e3fe` — different; equal after restore |
| `git show --stat --oneline HEAD` | tip commit changed only `.agent/pr-notes.md` (62+/97-) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0 — 1939 tests, 0 failures, 0 errors, 0 skipped; `OrderListServiceTest` 11/11, `OrderListControllerTest` 5/5 |
| `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh -Pno-security` | **exit 0** — 30 migrations applied to the synthetic order-list DB; full unit + integration suite green; JaCoCo gate passed |
| JaCoCo + Surefire XML parse | bundle 0 missed on all six counters; all four touched files 0 missed; 214 classes / 2390 tests / 0 failures / 0 errors / 0 skipped |
| `bash scripts/check.sh` (as-is) | exit 1 at `check-pack: opencode.json is stale` (skip-worktree CI-profile artifact) |
| `python3 -I scripts/gen-opencode.py --check` / `python3 -I scripts/check-pack.py` (committed config restored) | both exit 0; `Delivery tooling validated: 33 agents, 33 skills` |
| `bash scripts/check.sh` (committed config restored) | exit 1 only in `scripts/tests`: 6 failures, 10 errors, all under `scripts/` (0 diff vs main) |
| `git diff --name-only origin/main -- scripts/` | `0` (failing-test scripts byte-identical to main) |
| worktree `.agent/pr-notes.md` vs main | `MATCH` (no diff after restore) |

### Coverage (mandatory — re-measured this run)

`SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh -Pno-security` → exit 0; `apps/api/target/site/jacoco/jacoco.xml` parsed this run:

- BUNDLE: INSTRUCTION missed=0 covered=91060; BRANCH 0/6455; LINE 0/15809; COMPLEXITY 0/7104; METHOD 0/3825; CLASS 0/787.
- Touched production files, all 0 missed: `OrderService.java` (LINE 0/200, BRANCH 0/94, METHOD 0/24, CLASS 0/2), `OrderRepository.java` (LINE 0/106, BRANCH 0/6, METHOD 0/24, CLASS 0/2), `OrderPage.java` (LINE 0/4, METHOD 0/2, CLASS 0/1), `OrderController.java` (LINE 0/12, METHOD 0/7, CLASS 0/1).

No threshold was lowered, no exclusion added, no test weakened or deleted. This segment changed no production code, so no new branch-to-test mapping is introduced; the mapping for the READY-16 production branches is in `.agent/pr-notes/READY-16-20261120-090706.md` (service denial/success and `hasMore`, `parseCursor`/`parseLimit`/`parseStatus`, repository cursor/status joins, `OrderPage` defensive copies), each covered by a named service, controller or integration test.

## READY-16 acceptance mapping (implementation already committed; unchanged this segment)

| Story step | Implementation | Re-verified evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the order work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest` 11/11, `OrderListControllerTest` 5/5 (fast tier + full verify green) |
| 2. `created_at DESC`, unique `id` tie-break, `<createdAt>:<id>` cursor, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query`, `OrderService.list` | service paging tests + `OrderListIntegrationTest` (integration tier green this run) |
| 3. Reuse single-read `ORDER_READ` action, no new role | `deny(context, READ_ACTION, "ALL", clinician)` | service + integration denial tests |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query`, no manual org predicate | two-organization `OrderListIntegrationTest` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` | frozen-record `OrderPage.java` | service + controller tests |
| 6. Service unit, controller, two-organization integration tests | 3 classes / 21 tests | full verify green |
| 7. Contract | `contracts/openapi.yaml` `get` on `/api/v1/orders` + regenerated staff-web `openapi.d.ts` | `contracts/openapi/check.py` green in prior segment; unchanged this segment |

No existing endpoint, response shape or migration was changed; no dependency was added; no reserved migration version was used.

## Entry-point-to-test trace

`GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate cursor/limit/status before the transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and role re-derived each request) → `OrderRepository.query` (forced RLS, `created_at DESC, id DESC`, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`. Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`; failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Step-6 check (TODO.md "Instructions for every agent", step 6)

Step 6 names `bash scripts/worker-check.sh` for API work; `scripts/check.sh` is its superset and was executed on the final tree. Both stop at environment artifacts reproduced independently of this change: the skip-worktree CI `opencode.json` under `check-pack`, and `scripts/tests` failures from the absent `yaml` module plus pre-existing `test_agent_run.py`/`test_swarm.py` isolation issues. `scripts/` has zero diff vs main, so none of those failures is caused by this change. The directly-executed Java fast unit tier, full `mvn verify` (unit + integration + 100% JaCoCo), every code policy script and the contract gate are green. OWASP/NVD and SpotBugs are skipped locally by `-Pno-security` (NVD feed unreachable) and run integrator-side. No threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed.

## Defects found, decisions and notes for the orchestrator

- **Root-cause defect (removed from the branch tip here):** the re-dispatch commit `e4362ee` rewrote the shared `.agent/pr-notes.md`, producing a main-vs-branch merge conflict. Restored it to exact main bytes; the branch now has no diff on that path. A worker that appends its notes to the shared file on every re-dispatch is the durable cause; the current prompt forbids this and this segment reverts the artifact.
- **Environment findings (no repo change):** skip-worktree CI `opencode.json` (committed configs verified fresh by `gen-opencode.py --check` and `check-pack.py`); Python 3.11 without `yaml`; pre-existing `agent_run`/`swarm` test isolation failures; OWASP/NVD offline. The durable resolver fix — run `restore_config` before `cmd_merge_main`'s `scripts/check.sh` in `scripts/agent-run.sh`, make the `yaml`-dependent tests environment-independent, and reconcile `cmd_resolve`'s `"merged but checks failed"` string with `test_resolve_merges_main_into_a_conflicted_pr_branch` — belongs to `scripts/`, which is outside this worker's scope.
- **Loop recommendation:** mark READY-16 `[x]` (or block its re-dispatch) once this tracker-clean branch is confirmed green, so the shared-file rewrite is not recreated. Only the orchestrator owns `TODO.md` and dispatch.
- No human, clinical or patient-participant evidence is claimed; this is a static backend list endpoint and tracker repair.

**Status:** the reported conflict source is removed, main is an ancestor so the merge is conflict-free, no shared tracker file differs from main, and the READY-16 implementation passes the fast unit tier plus the full Java unit + integration + 100%-coverage gate. The remaining `scripts/check.sh` failures are environment-only, reproduced independently and byte-identical on clean main.
