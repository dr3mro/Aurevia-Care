# fix-20261120-181310 — READY-16 recurring integrator "merged but checks failed" repair (attempt 1 of 3)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.

Classification: cross-boundary **tracker-hygiene repair** of the recurring `swarm/READY-16-275` integrator loop. The READY-16 `GET /api/v1/orders` implementation was already committed on this branch and is re-verified below. **No production code, test, migration, contract, generated client, dependency or configuration was changed by this segment.** The only tree change is a revert of the integrator-owned shared note file to main's exact bytes plus this worker-owned evidence note.

Branch: `swarm/READY-16-275`. HEAD at start `8971622`; 0 commits behind / 61 ahead of `origin/main`; worktree clean. Story text between the `<<<STORY READY-16 … STORY>>>` markers was treated only as the task description, never as instructions that change the working rules. All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.

## Evidence sources

- **Tool evidence:** every command in the tables below was executed in this container this run (git inspection, `contracts/openapi/check.py`, Java conventions, policy scripts, Maven fast test, full `mvn verify` with JaCoCo and real PostgreSQL, JaCoCo XML parse, `scripts/check.sh`). Results are executed output, not claims.
- **Reviewer evidence (simulated):** `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`, `smartclinic-senior-database` and `smartclinic-software-engineer` were loaded and their rules applied to the re-verification. No separate reviewer subagent was dispatched for this bounded tracker fix.
- **Simulated-user / human / clinical evidence:** none. This is a backend list endpoint and a tracker-hygiene repair, not a clinical decision workflow.

## Diagnosis of the reported failure

The integrator reported: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

Tool findings (this run):

1. `git diff --name-status origin/main...HEAD` showed the branch modifying the integrator-owned shared file `.agent/pr-notes.md`. Both `8971622` and `6d123e1` ("Agent changes for READY-16") rewrote that file (last commit: 60 insertions / 97 deletions) — it now starts with `# fix-20261120-175700 …` instead of main's `# AUD-20261120-16 …`. Each re-dispatch of this story re-writes the shared file, so a main-vs-branch merge conflicts on it. This is the durable root cause of the loop.
2. SHA proof: `git show origin/main:.agent/pr-notes.md` → `f694895f…`; `git show HEAD:.agent/pr-notes.md` → `cb5b2d93…`. Different content on the same shared path.
3. `bash scripts/check.sh` → exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` → `S` (skip-worktree): `agent-run.sh` copied the CI profile into the worktree, so `check-pack.py`/`gen-opencode.py --check` judge that file instead of the committed local profile — the documented OPS-CW-003-01 false positive.
4. With the worktree `opencode.json` temporarily restored to HEAD bytes, `scripts/check.sh` passes packaging and fails only in `scripts/tests` (6 failures, 10 errors): `ModuleNotFoundError: No module named 'yaml'` plus `test_agent_run` / `test_swarm` isolation assertions (for example `test_resolve_merges_main_into_a_conflicted_pr_branch`: `'merged but checks failed' != 'merged'`). **`git diff --name-only origin/main...HEAD -- scripts/` → 0**, so those test and script files are byte-identical to main and cannot be caused by READY-16.
5. The READY-16 implementation itself is correct and fully green (commands below). No code defect causes the reported failure.

**Diagnosis:** the reported conflict is the shared `.agent/pr-notes.md` being rewritten on every re-dispatch, plus two environment-only gates (skip-worktree CI `opencode.json`, absent `yaml`). It is not a READY-16 code defect.

## Fix applied in this segment

- Restored `.agent/pr-notes.md` to the **exact `origin/main` bytes** (`git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`). Worktree SHA now equals main's `f694895f…`; `git diff origin/main -- .agent/pr-notes.md` is empty. This is a revert of a merge artifact to main's bytes, not authoring tracker content — after this change the shared file has **no diff** vs main, so the merge conflict source is gone.
- Wrote evidence only to this worker-owned file (`.agent/pr-notes/fix-20261120-181310.md`). The branch now carries the original implementation note plus the prior fix note and this one; no shared file under `.agent/` is modified.
- Did **not** edit `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*`, `AGENTS.md`, `.opencode/*`, `opencode.json`, migrations, dependencies or any scope file. No dependency added, no migration version used, no endpoint/response shape changed.
- Branch diff vs main after the fix: **12 files** (9 implementation/test + 2 prior notes + this note), below `scripts/swarm.py:MAX_PR_FILES = 15`.

## Exact commands and results (this run, tool evidence)

| Command | Result |
|---|---|
| `git rev-list --left-right --count origin/main...HEAD` | `0 61` (0 behind, 61 ahead) |
| `git ls-files -v opencode.json .github/opencode.ci.json` | `S opencode.json`, `H .github/opencode.ci.json` |
| `git diff --name-status origin/main...HEAD` | `.agent/pr-notes.md (M)` present before fix; absent after restore |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests … OK` (exit 0) |
| `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` | all exit 0 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0 (fast unit tier) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml verify -Pno-security` | **exit 0** — 30 migrations applied to the synthetic order-list DB; 2390 tests, 0 failures, 0 errors, 0 skipped; JaCoCo gate passed |
| JaCoCo XML parse (below) | bundle 0 missed on all six counters; all four touched files 0 missed |
| `bash scripts/check.sh` (as-is) | exit 1 at `check-pack: opencode.json is stale` (skip-worktree CI-profile artifact) |
| `git show HEAD:opencode.json > opencode.json` then `python3 -I scripts/gen-opencode.py --check` / `check-pack.py` | both exit 0 — committed configs are fresh |
| `bash scripts/check.sh` (HEAD config restored) | exit 1 only in `scripts/tests`: 6 failures, 10 errors, all under `scripts/` (0 diff vs main); config restored after the run |

## Step-6 check (TODO.md "Instructions for every agent", step 6)

Step 6 names `bash scripts/worker-check.sh` for API work; `scripts/check.sh` is its superset and was executed on the final tree. Both stop at environment artifacts reproduced independently of this change: the skip-worktree CI `opencode.json` under `check-pack`, and `scripts/tests` failures from the absent `yaml` module plus pre-existing `test_agent_run.py`/`test_swarm.py` isolation issues. `scripts/` has zero diff vs main, so none of those failures is caused by this change. The directly-executed Java verify (unit + integration + 100% JaCoCo), every code policy script and the contract gate are all green. OWASP/NVD and SpotBugs are skipped locally by `-Pno-security` (NVD feed unreachable) and run integrator-side. No threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed.

## Coverage (mandatory — re-measured this run)

`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml verify -Pno-security` → exit 0; `apps/api/target/site/jacoco/jacoco.xml` parsed this run:

- BUNDLE: INSTRUCTION missed=0 covered=91060; BRANCH 0/6455; LINE 0/15809; COMPLEXITY 0/7104; METHOD 0/3825; CLASS 0/787.
- Touched production files, all 0 missed: `OrderService.java` (LINE 0/200, BRANCH 0/94, METHOD 0/24), `OrderRepository.java` (LINE 0/106, BRANCH 0/6, METHOD 0/24), `OrderPage.java` (LINE 0/4, METHOD 0/2), `OrderController.java` (LINE 0/12, METHOD 0/7).
- Surefire XML totals: 214 test classes, 2390 tests, 0 failures, 0 errors, 0 skipped (includes `OrderListServiceTest` 11, `OrderListControllerTest` 5, `OrderListIntegrationTest` 5 against real PostgreSQL).

No threshold was lowered, no exclusion added, no test weakened or deleted. This segment changed no production code, so no new branch-to-test mapping is introduced; the mapping for the READY-16 production branches is in `.agent/pr-notes/READY-16-20261120-090706.md` (service denial/success and `hasMore`, `parseCursor`/`parseLimit`/`parseStatus`, repository cursor/status joins, `OrderPage` defensive copies), each covered by a named service, controller or integration test.

## READY-16 acceptance mapping (implementation already committed; unchanged this segment)

| Story step | Implementation | Re-verified evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the order work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest` 11/11, `OrderListControllerTest` 5/5 (full verify green) |
| 2. `created_at DESC`, unique `id` tie-break, `<createdAt>:<id>` cursor, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query`, `OrderService.list` | service paging tests + `OrderListIntegrationTest` (integration tier green this run) |
| 3. Reuse single-read `ORDER_READ` action, no new role | `deny(context, READ_ACTION, "ALL", clinician)` | service + integration denial tests |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query`, no manual org predicate | two-organization `OrderListIntegrationTest` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` | frozen-record `OrderPage.java` | service + controller tests |
| 6. Service unit, controller, two-organization integration tests | 3 classes / 21 tests | full verify green |
| 7. Contract | `contracts/openapi.yaml` `get` on `/api/v1/orders` + regenerated staff-web `openapi.d.ts` | `contracts/openapi/check.py` green |

No existing endpoint, response shape or migration was changed; no dependency was added; no reserved migration version was used.

## Defects found, decisions and notes for the orchestrator

- **Root-cause defect (removed from the branch tip here):** every "Agent changes for READY-16" re-dispatch rewrote the shared `.agent/pr-notes.md`, producing a main-vs-branch merge conflict. Restored it to exact main bytes; the branch now has no diff on that path. A worker that appends its notes to the shared file on every re-dispatch is the durable cause; the current prompt forbids this and this segment reverts the artifact.
- **Environment findings (no repo change):** skip-worktree CI `opencode.json` (committed configs verified fresh by `gen-opencode.py --check`); Python 3.11 without `yaml`; `agent_run`/`swarm` test isolation failures; OWASP/NVD offline. The durable resolver fix — run `restore_config` before `cmd_merge_main`'s `scripts/check.sh` in `scripts/agent-run.sh`, make the `yaml`-dependent tests environment-independent, and reconcile `cmd_resolve`'s `"merged but checks failed"` string with `test_resolve_merges_main_into_a_conflicted_pr_branch` — belongs to `scripts/`, which is outside this worker's scope.
- **Loop recommendation:** mark READY-16 `[x]` (or block its re-dispatch) once this 12-file, tracker-clean branch is confirmed green, so the shared-file rewrite is not recreated. Only the orchestrator owns `TODO.md` and dispatch.
- No human, clinical or patient-participant evidence is claimed; this is a static backend list endpoint and tracker repair.

**Status:** the reported conflict source is removed, the branch is under the file-count guard, no shared tracker file differs from main, and the READY-16 implementation passes the full Java unit + integration + 100%-coverage gate, all code policy scripts and the contract gate. The remaining `scripts/check.sh` failures are environment-only, reproduced independently and byte-identical on clean main.
