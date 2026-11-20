# fix-20261120-172536 — READY-16 recurring integrator "merged but checks failed" repair

Requirement ID: **ARC-03** (versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary **tracker-hygiene repair** of the recurring `swarm/READY-16-275` integrator loop. **No production code, test, migration, contract, generated client, dependency or configuration was changed by this segment.** The READY-16 list-orders implementation was already committed on this branch and is re-verified below.

Branch: `swarm/READY-16-275`. HEAD before this segment: `b209ded` (a prior merge of `origin/main` into the branch). `origin/main` tip after reconciliation: `2bb82e2`; merge base `ccfbce4`; the branch was 6 commits behind and 55 ahead, and the worktree was clean. After this segment HEAD is `107b00a` (restore commit `33a728d` + clean merge of `origin/main`), the branch is **0 behind / 57 ahead** of main, the worktree is clean, and there is **no diff on any shared tracker** vs main.

All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Evidence sources

- **Tool evidence**: every command in the tables below was executed in this container this run (git inspection, `scripts/check.sh`, `scripts/worker-check.sh`, policy scripts, contract check, generator render comparison, Maven/Surefire). Results are executed output, not claims.
- **Reviewer evidence (simulated)**: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build` and `smartclinic-test-engineer` were loaded and their rules applied. No separate reviewer subagent was dispatched for this bounded tracker fix.
- **Simulated-user / human / clinical evidence**: none. This is a backend list endpoint and a tracker-hygiene repair, not a clinical decision workflow.

## Reproduction of the reported failure

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch." `scripts/agent-run.sh:cmd_resolve` emits this text when a resolver merges main into the branch and the subsequent `scripts/check.sh` exits nonzero.

Tool findings (this run):

1. `git diff --name-status origin/main HEAD` showed the branch modifying three integrator-owned shared trackers: `TODO.md` (M — stale `[-] agent: swarm-9` for `AUD-20261120-16` instead of main's `[x]` + `pr:` + `evidence:`), `.agent/pr-notes.md` (M — a worker note starting `# fix-20261120-170418 ...` instead of main's AUD-16 note), and `docs/swarm/AUD-20261120-16.md` (D — deleted on the branch, present on main). `scripts/swarm.py:54` lists `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/*` in `TRACKERS`, and `guard()` (`:1063-1067`) calls `fail()` on any PR touching them. No other shared tracker had a diff.
2. `bash scripts/check.sh` → **exit 1**, final lines: `shellcheck not installed: skipping shell lint (CI runs it)` then `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
3. `git ls-files -v opencode.json` → `S` (skip-worktree): `agent-run.sh` copied the CI profile into the worktree, so `check-pack.py` judges that file rather than the committed local profile — the documented OPS-CW-003-01 false positive.
4. `python3 -c 'import yaml'` → `ModuleNotFoundError: No module named 'yaml'` (Python 3.11.2; no pip), so the Python policy unit-test stage cannot fully run in this container. This is a pre-existing environment limitation; no Python/tooling file was changed here.
5. `bash scripts/worker-check.sh` (the TODO step-6 check for API work) → **exit 1** at the same check-pack skip-worktree artifact.
6. The 6 main commits beyond the merge base are the AUD-20261120-16 story (DocumentService subject verification + its note/test/tracker updates). They touch no READY-16 file, so re-merging main is mechanically clean once the trackers are restored.

**Diagnosis:** the reported failure is a tracker-hygiene loop plus two environment-only gates, not a READY-16 code defect. A re-dispatched story actor repeatedly wrote its notes to the integrator-owned shared `.agent/pr-notes.md` (and left a stale `[-]` claim in `TODO.md` and a deleted `docs/swarm` note through earlier merges); each fix pass restored them and the actor re-corrupted them. Two-sided modification of those trackers is the conflict/`guard()` source; `check-pack.py` and the missing `yaml` module are worker-container artifacts reproduced independently of this change.

## Fix applied in this segment

- Restored `.agent/pr-notes.md`, `TODO.md` and `docs/swarm/AUD-20261120-16.md` to the **exact `origin/main` bytes** (written via `git show origin/main:<file>`, staged and committed as `33a728d`). Verified with `diff <(git show origin/main:<file>) <file>` → `MATCH` for all three and `git diff origin/main --stat -- TODO.md .agent/pr-notes.md docs/swarm` → empty at the branch tip.
- This is a revert of a merge artifact to main's bytes, not authoring tracker content: the orchestrator's `[-]`/`[x]` marks and all integrator-merged trackers are exactly as on `origin/main`. No tracker entry was added, removed or re-worded.
- Re-merged `origin/main` into the branch (`107b00a`, `ort` strategy, no conflicts) so checks run on the exact tree the integrator will test; re-verified no tracker diff remains.
- Wrote this evidence only to its own worker-owned file, `.agent/pr-notes/fix-20261120-172536.md`, as the prompt names. No other note file was created, edited or deleted by this segment.

## Exact commands and results (this run, tool evidence)

| Command | Result |
|---|---|
| `git fetch` state / `git rev-parse origin/main`; `git merge-base HEAD origin/main` | `origin/main` `2bb82e2`; merge base `ccfbce4`; branch was 6 behind / 55 ahead before the fix |
| `git show origin/main:<file> > <file>` for the 3 shared trackers, then `git add` + `git commit` | `33a728d` "restore shared trackers to break READY-16 integrator loop"; 3 files changed |
| `diff <(git show origin/main:<file>) <file>` for each tracker | `MATCH TODO.md`, `MATCH .agent/pr-notes.md`, `MATCH docs/swarm/AUD-20261120-16.md` |
| `git merge origin/main -m ...` | clean `ort` merge, no conflicts; HEAD `107b00a`; **0 behind / 57 ahead** of main |
| `git diff origin/main --stat -- TODO.md .agent/pr-notes.md docs/swarm` at tip | empty (no tracker diff) |
| `git status --short` | clean |
| `bash scripts/check.sh` (300 s bound) | exit 1 at `check-pack: opencode.json is stale` — skip-worktree CI-profile artifact |
| `python3 -I - <<gen render comparison>>` | committed `opencode.json` (ci=False) matches fresh render: **True**; committed `.github/opencode.ci.json` (ci=True) matches fresh render: **True**; worktree `opencode.json` equals HEAD bytes: **False** (skip-worktree CI profile) |
| `python3 -I scripts/check-docs.py` / `check-secrets.py` / `check-names.py` / `check-migrations.py --require-baseline` | all exit 0 |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `bash scripts/worker-check.sh` (step-6 check) | exit 1 at the same `check-pack` skip-worktree artifact; no code-gate failure |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0; Surefire totals **1939 tests, 0 failures, 0 errors, 0 skipped**; `OrderListServiceTest` 11/11, `OrderListControllerTest` 5/5 |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) ' HEAD -- apps/api contracts apps/staff-web ':!*.md'` | no conflict markers |

The Java fast tier, all code-relevant policy scripts and the contract gate are green; the only failing gates are the two environment artifacts reproduced on clean main and unchanged by this segment. No threshold, exclusion or skip was changed.

## Step-6 check (TODO.md "Instructions for every agent", step 6)

Step 6 names `bash scripts/worker-check.sh` for API work. It was executed this run and **exits 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`** — the documented cloud-worker skip-worktree artifact (OPS-CW-003-01). Proof the committed configs are fresh is in the command table above. The remaining policy stage cannot fully run locally because `yaml` is absent, and the database/OWASP/SpotBugs/JaCoCo stages are integrator-owned in this container (no Docker; NVD feed unreachable). The directly-executed Java, code-policy and contract gates above are all green; this segment changed no production, test, SQL, contract, generated-client, agent, workflow or configuration file.

## Coverage (mandatory — unchanged this segment)

This segment changed **no production code**, so the 100% JaCoCo result recorded in `.agent/pr-notes/READY-16-20261120-090706.md` and re-confirmed in `fix-20261120-170418.md` still applies to the merged tree: all bundle counters 0 missed (INSTRUCTION 91024, BRANCH 6449, LINE 15803, COMPLEXITY 7101, METHOD 3825, CLASS 787), and per touched production file `OrderPage.java`, `OrderService.java`, `OrderRepository.java`, `OrderController.java` all 0 missed. The fast test profile used locally does not emit `apps/api/target/site/jacoco/jacoco.xml`; the full `mvn verify` JaCoCo gate is integrator-owned. No threshold was lowered, no exclusion or `@Disabled`/`.skip`/`xit` added, no test weakened or deleted.

Branch-to-test mapping for the READY-16 production branches is in `.agent/pr-notes/READY-16-20261120-090706.md` (service denial/success and `hasMore`, `parseCursor`/`parseLimit`/`parseStatus`, repository cursor/status and `OrderPage` defensive copies), each covered by a named service, controller or integration test.

## READY-16 acceptance mapping (implementation already committed; unchanged this segment)

| Story step | Implementation | Re-verified evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the order work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest` 11/11, `OrderListControllerTest` 5/5 |
| 2. `created_at DESC`, unique `id` tie-break, `<createdAt>:<id>` cursor, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query`, `OrderService.list` | service paging tests + `OrderListIntegrationTest` (integrator owns DB tier) |
| 3. Reuse single-read action, no new role | `deny(context, ORDER_READ, "ALL", clinician)` | service + integration denial tests |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query`, no manual org predicate | two-organization `OrderListIntegrationTest` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` | frozen-record `OrderPage.java` | `OrderListServiceTest`, `OrderListControllerTest` |
| 6. Service unit, controller, two-organization integration tests | 3 classes / 21 tests | fast tiers green above; integration tier owned by integrator |
| 7. Contract | `contracts/openapi.yaml` `get` on `/api/v1/orders` + regenerated staff-web `openapi.d.ts` | `contracts/openapi/check.py` green above |

No existing endpoint, response shape or migration was changed; no dependency was added; no reserved migration version was used.

## Defects found, decisions and blockers for the orchestrator

- **Root-cause defect (removed from the branch tip this segment):** shared integrator-owned trackers were modified on the branch (`.agent/pr-notes.md`, `TODO.md`, `docs/swarm/AUD-20261120-16.md`). Restored to exact `origin/main` bytes. A story actor re-writing `.agent/pr-notes.md` on re-dispatch is the durable cause; the current prompt forbids it, and this segment writes only its own named file.
- **Environment-only findings (no repo change):** skip-worktree CI `opencode.json` (committed configs verified fresh); PyYAML absent locally; OWASP/NVD offline. The durable resolver fix — run `restore_config` before `scripts/agent-run.sh`'s `scripts/check.sh`, and make the `test_agent_run` Python tests environment-independent — belongs to `scripts/`, which is outside this worker's scope.
- **File-count blocker (not changed here):** the branch diff vs main is **33 files** (34 with this note), mostly 24 duplicate `fix-20261120-*.md` notes left by earlier attempts of this same loop. `scripts/swarm.py:1068` `MAX_PR_FILES = 15` would label such a PR `needs-human`. Preserving other runs' evidence is required here, so this segment did not delete or rewrite them; the orchestrator should squash the duplicate fix notes (keeping the implementation's 9 files + one authoritative note) before the next integrate attempt if that guard fires. This worker cannot edit other workers' notes or shared trackers.
- **Loop recommendation:** mark READY-16 `[x]` (or block its re-dispatch) once this clean merged tree is confirmed green, so the restored trackers are not overwritten again. Only the orchestrator owns `TODO.md` and dispatch.
- No human, clinical or patient-participant evidence is claimed; this is a static backend list endpoint and tracker repair.

**Status:** the reported conflict source and tracker artifacts are removed; the branch is current with main, trackers match main exactly, the working tree is clean, and the READY-16 implementation passes the fast unit tier, code policy scripts, contract gate and (unchanged) 100%-coverage result. The remaining `scripts/check.sh` / step-6 failures are environment-only and reproduced independently of this change; the pipeline-level correction and the duplicate-note squash require the orchestrator's authority.
