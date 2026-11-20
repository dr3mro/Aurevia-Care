# fix-20261120-175700 — READY-16 recurring integrator "merged but checks failed" repair

Requirement ID: **ARC-03** (versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering).

Classification: cross-boundary **tracker-hygiene repair** of the recurring `swarm/READY-16-275` integrator loop. **No production code, test, migration, contract, generated client, dependency or configuration was changed by this segment.** The READY-16 list-orders implementation was already committed on this branch and is re-verified below.

Branch: `swarm/READY-16-275`. HEAD at start: `6d123e1`; 0 commits behind / 59 ahead of `origin/main` (`2bb82e2`); worktree clean. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules. All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.

## Evidence sources

- **Tool evidence**: every command in the tables below was executed in this container this run (git inspection, `scripts/check.sh`, policy scripts, contract check, config freshness render, full Maven verify with JaCoCo and real PostgreSQL, JaCoCo XML parse). Results are executed output, not claims.
- **Reviewer evidence (simulated)**: `smartclinic-delivery` and the Java/build/test specialist rules were applied to the re-verification. No separate reviewer subagent was dispatched for this bounded tracker fix.
- **Simulated-user / human / clinical evidence**: none. This is a backend list endpoint and a tracker-hygiene repair, not a clinical decision workflow.

## Reproduction of the reported failure

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch." `scripts/agent-run.sh:cmd_resolve` emits this when the resolver merges main into the branch and the subsequent `scripts/check.sh` exits nonzero; `scripts/swarm.py:742 fail()` converts it into a worker fix dispatch.

Tool findings (this run):

1. `git diff --name-status origin/main HEAD` showed the branch modifying the integrator-owned shared file `.agent/pr-notes.md` (M — the `# fix-20261120-172536 ...` note text instead of main's `# AUD-20261120-16 ...` note). A story actor re-wrote this shared file on re-dispatch. `scripts/swarm.py:1063-1067 guard()` and the AGENTS.md note rule (`never .agent/pr-notes.md`) both forbid it.
2. The branch added **24 duplicate `fix-20261120-*.md` notes** (105859 through 172536), so the PR diff vs main was **35 files**. `scripts/swarm.py:26 MAX_PR_FILES = 15` and `guard()` at `:1068-1073` label any PR over 15 files `needs-human`, blocking automatic merge. This is the durable source of the repeated loop: each fix attempt added one more note, guaranteeing the next attempt also exceeded the limit. Prior fix note `fix-20261120-172536.md` flagged exactly this and asked the orchestrator to squash the duplicates; that never happened, so it is done here.
3. `bash scripts/check.sh` / `python3 -I scripts/check-pack.py` → exit 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` → `S` (skip-worktree): `agent-run.sh` copied the CI profile into the worktree, so `check-pack.py` judges that file instead of the committed local profile — the documented OPS-CW-003-01 false positive.
4. The remaining `scripts/tests` failures are environment artifacts: `ModuleNotFoundError: No module named 'yaml'` (no pip in this container) and pre-existing `test_agent_run.py` / `test_swarm.py` isolation failures. **No file under `scripts/` differs from `origin/main` on this branch** (`git diff --name-only origin/main HEAD -- scripts/ | wc -l` → `0`), so these are byte-identical on clean main and unrelated to READY-16.
5. The READY-16 implementation itself is correct and fully green (reproduction commands and results below). No code defect causes the reported failure.

**Diagnosis:** the reported failure is a tracker-hygiene loop (shared `.agent/pr-notes.md` rewritten + 24 duplicate fix notes over the file-count guard) plus two environment-only gates, not a READY-16 code defect.

## Fix applied in this segment

- Restored `.agent/pr-notes.md` to the **exact `origin/main` bytes** (`git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`). Worktree copy now equals main (`git diff origin/main --name-status` does not list it). This is a revert of a merge artifact to main's bytes, not authoring tracker content.
- Removed the **24 duplicate fix notes** added by this branch relative to main (`git rm` on the `--diff-filter=A` set). Kept the original implementation note `READY-16-20261120-090706.md` (the story's PR-description evidence) and wrote only this run's note. The branch diff vs main is now **11 files** (9 implementation files + 2 notes), below `MAX_PR_FILES = 15`.
- No other shared tracker had a diff: `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md` and `docs/swarm/*` were already restored to main on the prior fix commit; none was touched here.
- Wrote evidence only to this worker-owned file. No other note file was created or edited by this segment.

## Exact commands and results (this run, tool evidence)

| Command | Result |
|---|---|
| `git rev-list --left-right --count origin/main...HEAD` | `0 59` (0 behind, 59 ahead) |
| `git ls-files -v opencode.json .github/opencode.ci.json` | `S opencode.json`, `H .github/opencode.ci.json` |
| `python3 -I scripts/check-pack.py` | exit 1: `opencode.json is stale` (skip-worktree artifact) |
| importlib render comparison against `git show HEAD:<file>` | `opencode.json: committed_fresh=True, worktree==committed=False`; `.github/opencode.ci.json: committed_fresh=True, worktree==committed=True` |
| `python3 -I scripts/check-docs.py` / `check-secrets.py` / `check-names.py` / `check-migrations.py --require-baseline` | all exit 0 |
| `python3 -I contracts/openapi/check.py` | exit 0 (contract/client drift gate green) |
| `bash scripts/frontend-check.sh` | exit 0 (format, typecheck, Vitest green) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml verify -Pno-security` | **exit 0**; all 30 migrations applied to `smartclinic_order_list_it`, full unit + integration suite green, JaCoCo gate passed |
| `bash scripts/check.sh` with worktree `opencode.json` restored to HEAD bytes | exit 1 only in `scripts/tests` (yaml missing + pre-existing agent_run/swarm isolation failures); all code/content gates green; config restored after the run |
| `git diff --name-only origin/main HEAD -- scripts/` | `0` (failing-test scripts byte-identical to main) |
| worktree `.agent/pr-notes.md` vs main | `MATCH` (no diff) |

## Step-6 check (TODO.md "Instructions for every agent", step 6)

Step 6 names `bash scripts/worker-check.sh` for API work. Executed on the final committed tree with `SMARTCLINIC_PG_ADMIN=postgres` → **exit 1**, final line `check-pack: opencode.json is stale: run scripts/gen-opencode.py` (the skip-worktree CI-profile artifact). `scripts/check.sh` (its superset) was also executed this run. Both stop at environment artifacts reproduced independently of this change: the skip-worktree CI `opencode.json` under `check-pack`, and `scripts/tests` failures from the absent `yaml` module plus pre-existing `test_agent_run.py`/`test_swarm.py` isolation issues. `scripts/` has zero diff vs main, so none of those failures is caused by this change. The directly-executed Java verify (unit + integration + JaCoCo), every code policy script, the contract gate and the frontend gate are all green. The OWASP/NVD and SpotBugs stages are skipped locally by `-Pno-security` (NVD feed unreachable) and run integrator-side. No threshold, exclusion, `@Disabled`, `.skip` or `xit` was added or changed.

## Coverage (mandatory — re-measured this run)

`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml verify -Pno-security` → exit 0, and `apps/api/target/site/jacoco/jacoco.xml` parsed this run:

- BUNDLE: INSTRUCTION missed=0 covered=91060; BRANCH missed=0 covered=6455; LINE missed=0 covered=15809; COMPLEXITY missed=0 covered=7104; METHOD missed=0 covered=3825; CLASS missed=0 covered=787.
- Touched production files, all 0 missed: `OrderService.java` (line 200 covered, branch 94 covered), `OrderRepository.java` (line 106, branch 6), `OrderPage.java` (line 4, no branches), `OrderController.java` (line 12, no branches).

No threshold was lowered, no exclusion added, no test weakened or deleted. This segment changed no production code, so no new branch-to-test mapping is introduced; the mapping for the READY-16 production branches is in `.agent/pr-notes/READY-16-20261120-090706.md` (service denial/success and `hasMore`, `parseCursor`/`parseLimit`/`parseStatus`, repository cursor/status joins and `OrderPage` defensive copies), each covered by a named service, controller or integration test.

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

- **Root-cause defect (removed from the branch tip here):** shared `.agent/pr-notes.md` was rewritten on the branch, and 24 duplicate fix notes pushed the PR to 35 files (over `MAX_PR_FILES = 15`). Restored the shared file to main bytes and removed the 24 duplicates, leaving 11 files. A story actor re-writing `.agent/pr-notes.md` and adding a note on every re-dispatch is the durable cause; the current prompt forbids the former and this segment squashes the latter.
- **Environment findings (no repo change):** skip-worktree CI `opencode.json` (committed configs verified fresh); PyYAML absent locally; agent_run/swarm test isolation failures; OWASP/NVD offline. The durable resolver fix — run `restore_config` before `cmd_merge_main`'s `scripts/check.sh` in `scripts/agent-run.sh`, and make the `yaml`-dependent tests environment-independent — belongs to `scripts/`, which is outside this worker's scope.
- **Loop recommendation:** mark READY-16 `[x]` (or block its re-dispatch) once this 11-file, tracker-clean branch is confirmed green, so the restored trackers and squashed notes are not recreated. Only the orchestrator owns `TODO.md` and dispatch.
- No human, clinical or patient-participant evidence is claimed; this is a static backend list endpoint and tracker repair.

**Status:** the reported conflict source and tracker artifacts are removed, the PR is under the file-count guard, no shared tracker differs from main, the worktree is clean, and the READY-16 implementation passes the full Java unit + integration + 100%-coverage gate, all code policy scripts, the contract gate and the frontend gate. The remaining `scripts/check.sh` failures are environment-only, reproduced independently and byte-identical on clean main.
