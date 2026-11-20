# fix-20261120-170418 — READY-16 recurring integrator repair (attempt 1 of 3)

Requirement ID: **ARC-03** (versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary **tracker-hygiene repair** of the recurring `swarm/READY-16-275` integrator loop. This segment changes **no production code, test, migration, contract, generated client, dependency or configuration**. The READY-16 implementation was already committed and is re-verified below.

Branch: `swarm/READY-16-275`. HEAD before this change: `c80c577` (merge of `origin/main` into the branch). `origin/main` after `git fetch origin main`: `ccfbce4`. All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Evidence sources

- **Tool evidence**: every command in the tables below was executed in this container this run (git inspection, `scripts/check.sh`, policy scripts, Maven/Surefire/JaCoCo/SpotBugs, PostgreSQL integration suite, contract check, staff-web generator/typecheck). Results are executed output, not claims.
- **Reviewer evidence (simulated)**: the specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`, `smartclinic-senior-database`, `smartclinic-software-engineer` and `smartclinic-qa-reviewer` were loaded and their rules applied. No separate reviewer subagent was dispatched for this bounded tracker fix.
- **Simulated-user / human / clinical evidence**: none. This is a backend list endpoint and a tracker-hygiene repair, not a clinical decision workflow.

## Reproduction of the reported failure

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch." `scripts/agent-run.sh:340-356` (`cmd_resolve`) shows that this text is produced when a resolver merges main into the branch and the subsequent `scripts/check.sh` (line 325) exits nonzero.

1. `git log --graph -30` shows 25+ commits oscillating between `Agent changes for READY-16` and `fix-<timestamp>: restore shared pr-notes ...` on this one branch — the same loop documented in `.agent/pr-notes/fix-20261120-163030.md`.
2. `git diff origin/main HEAD -- .agent/pr-notes.md` showed the branch's file starting with `# fix-20261120-163030 ...` while `origin/main` has `# AUD-20261120-13 ...`. The three-way merge takes the branch's worker-note bytes, so every merge re-introduces a non-integrator note on the shared, integrator-owned file. AGENTS.md and the shared-knowledge loop forbid any worker from writing that file.
3. `git diff origin/main HEAD -- TODO.md` showed one stale merge artifact: `AUD-20261120-15` was `[-] agent: swarm-8` on the branch while main has `[ ]`. `scripts/swarm.py:1063-1067` (`guard`) fails any PR that touches a shared tracker, including `TODO.md`, so this artifact blocks the next integrator attempt even though the merge itself is mechanically clean.
4. `bash scripts/check.sh` locally → **exit 1** at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` reports `S` (skip-worktree): `agent-run.sh` copied the CI profile into the worktree, and `check-pack.py` judges that worktree file rather than the committed local profile (the documented OPS-CW-003-01 false positive).
5. With a temporary, uncommitted local swap of `opencode.json` to the committed HEAD bytes (restored to the original CI-profile bytes immediately afterwards; `git status --short` is clean), `MAVEN_ARGS='-Dowasp.skip=true' SMARTCLINIC_PG_ADMIN=postgres bash scripts/check.sh` → **exit 1** at the Python policy stage: 6 failures + 2 errors, all in `test_agent_run.py`, none in code touched by this story. Re-running `python3 -I -m unittest discover -s scripts/tests -q` on a clean `origin/main` worktree (`.verify-main`, removed afterwards) produced the **identical** 6 failures + 2 errors, proving they are pre-existing in this container, not caused by this branch. OWASP was skipped only because this container has no `NVD_API_KEY` and no network access to nvd.nist.gov; no dependency or scan configuration was changed.

## Root cause and fix applied in this segment

The reported failure is a **tracker-hygiene loop plus two environment-only gates**, not a READY-16 code defect:

- A re-dispatched story actor repeatedly wrote its notes to the integrator-owned shared `.agent/pr-notes.md`; each fix pass restored it and the actor overwrote it again. The current prompt explicitly forbids that path, and this segment writes only to its own named file.
- The resolver's merge left a stale `[-]` claim for another task in `TODO.md`. Restoring it to main's bytes is a revert of a merge artifact, not authoring tracker content; the orchestrator's `[-]` mark for READY-16 and all other marks remain exactly as on `origin/main`.
- `check-pack.py` and the `test_agent_run` failures are environment artifacts reproduced on clean main. The durable pipeline fix (run `restore_config` before the resolver's `scripts/check.sh`, and make the Python tests environment-independent) belongs to `scripts/`, which is outside this worker's scope.

Changes made:

- Restored `.agent/pr-notes.md` and `TODO.md` to the exact `origin/main` bytes with `git checkout origin/main -- TODO.md .agent/pr-notes.md`. Verified `git diff --stat origin/main -- TODO.md .agent/pr-notes.md` is empty and `cmp <(git show origin/main:<file>) <file>` passes for both. The branch now touches neither shared file.
- Wrote this evidence only to its own worker-owned file, `.agent/pr-notes/fix-20261120-170418.md`, as the prompt names.
- No production, test, contract, generated-client, migration, dependency, agent, workflow or configuration file was changed by this segment.

## Verification commands and results (this run, tool evidence)

| Command | Result |
|---|---|
| `git fetch origin main` | `origin/main` = `ccfbce4` |
| `cmp <(git show origin/main:TODO.md) TODO.md` and the same for `.agent/pr-notes.md` | both identical; no diff on either shared file |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0; Surefire reports 171 files, **1930 tests, 0 failures, 0 errors, 0 skipped** (`OrderListServiceTest` 11, `OrderListControllerTest` 5) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=OrderListIntegrationTest -DfailIfNoSpecifiedTests=false test` | exit 0; `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` on real PostgreSQL 17.12 with all Flyway migrations |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml verify -Dowasp.skip=true -Dspotbugs.skip=false` | exit 0; **2381 tests, 0 failures/errors/skipped**; `jacoco:check` "All coverage checks have been met"; `spotbugs:check` "BugInstance size is 0" |
| `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh` equivalent (the `verify` command above) | full Java gate green with OWASP skipped for the offline reason stated above |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `cd apps/staff-web && npm run gen:api:check` | `gen:api: generated client matches the contract` (exit 0) |
| `cd apps/staff-web && npm run typecheck` | exit 0 |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` | all exit 0 |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) ' HEAD -- ':!*.md'` | no conflict markers |
| `python3 -I` in-memory `gen-opencode.py render()` comparison | `opencode.json` (ci=False) and `.github/opencode.ci.json` (ci=True) both match a fresh render from HEAD |

Step-6 check named in TODO.md "Instructions for every agent" for API work is `bash scripts/worker-check.sh`. It was reproduced via `scripts/check.sh` above: it cannot go green in this container because `check-pack.py` judges the skip-worktree CI profile and because `test_agent_run.py` has 6 failures + 2 errors that also occur on clean `origin/main`. The Java, database, contract and policy stages were run directly and are green. No check threshold, exclusion or skip was changed.

## Coverage (mandatory)

`mvn verify` regenerated `apps/api/target/site/jacoco/jacoco.xml`. Bundle counters: **0 missed** INSTRUCTION 91024, BRANCH 6449, LINE 15803, COMPLEXITY 7101, METHOD 3825, CLASS 787. Per touched production file, every counter is 0 missed:

| File | Lines | Branches | Methods | Classes |
|---|---|---|---|---|
| `OrderPage.java` | 0/4 | n/a | 0/2 | 0/1 |
| `OrderService.java` | 0/200 | 0/94 | 0/24 | 0/2 |
| `OrderRepository.java` | 0/106 | 0/6 | 0/24 | 0/2 |
| `OrderController.java` | 0/12 | n/a | 0/7 | 0/1 |

This segment changed no production code, so the branch-to-test mapping in `.agent/pr-notes/READY-16-20261120-090706.md` still applies: `OrderService.list` denial/success and `hasMore` branches, `parseCursor`/`parseLimit`/`parseStatus` validation branches, `OrderRepository.query` cursor/status branches and `OrderPage` defensive copies are each covered by named service, controller and integration tests. No threshold was lowered, no exclusion or skip added, no test weakened or deleted.

## READY-16 acceptance mapping (implementation already committed; unchanged this segment)

| Story step | Implementation | Evidence re-run above |
|---|---|---|
| 1. `GET /api/v1/orders`; optional `cursor`, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the order work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest` 11, `OrderListControllerTest` 5, integration invalid-input test |
| 2. `created_at DESC` with unique `id` tie-break, `<createdAt>:<id>` cursor, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query`, `OrderService.list` | service paging tests + integration paging test (5/5) |
| 3. Reuse single-read action, no new role | `deny(context, ORDER_READ, "ALL", clinician)` | service and integration denial tests |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query`, no manual org predicate | two-organization integration test |
| 5. `OrderPage(List<OrderView>, String nextCursor)` in the view package | frozen-record `OrderPage.java` | service/controller tests |
| 6. Service unit, controller and two-organization integration tests | three test classes / 21 tests | commands above |
| 7. Contract | `contracts/openapi.yaml` `get` on `/api/v1/orders` + regenerated staff-web `openapi.d.ts` | `contracts/openapi/check.py` and `gen:api:check` green |

No existing endpoint, response shape or migration was changed; no dependency was added; no reserved migration version was used.

## Defects found, decisions and blockers for the orchestrator

- **Root-cause defect (recurring, now removed from the branch tip):** story actors also wrote notes to the integrator-owned shared `.agent/pr-notes.md`. Corrected by restoring it to `origin/main`; the durable guard is the explicit prompt rule in this run.
- **Merge artifact corrected:** the stale `[-] agent: swarm-8` claim on `AUD-20261120-15` in `TODO.md` was restored to main's `[ ]`, so the worker PR no longer touches a shared tracker (`scripts/swarm.py` `guard`).
- **Decision:** `status` keeps the order resource's projected work-item states `awaiting_local_patient` and `ready` (V19 `exchange_work_item.state` / `WorkItemView`), because `OrderView` and `exchange_order` have no status column. No state was invented and no migration changed.
- **Environment-only findings (no repo change):** skip-worktree CI `opencode.json`; missing local Doxygen; OWASP offline; 6 pre-existing `test_agent_run` failures + 2 errors reproduced on clean main. The durable fix for the resolver's `check-pack`/Python failures belongs to `scripts/agent-run.sh` and `scripts/tests/`, outside this worker's scope.
- **Orchestrator-level blocker (not changed here):** the PR diff still contains 25+ historical `.agent/pr-notes/fix-*.md` files from earlier attempts of this same loop. `scripts/swarm.py:1068` (`MAX_PR_FILES = 15`) would label a PR with more than 15 changed files `needs-human`. Preserving other runs' evidence is required here, so this worker did not delete them; the orchestrator should squash the duplicate fix notes on this branch before the next integrate attempt if the file-count guard fires. This worker cannot edit shared trackers, workflows or other workers' notes.
- No human, clinical or patient-participant evidence is claimed; this is a static backend list endpoint and tracker repair.

**Status:** the reported merge conflict source and the tracker artifact are removed; the READY-16 implementation passes unit, integration, contract, 100%-coverage and SpotBugs gates. The remaining check failures are environment-only and reproduced on clean main; the pipeline-level correction and the duplicate-note cleanup require the orchestrator's authority.
