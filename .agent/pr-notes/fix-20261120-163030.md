# fix-20261120-163030 — READY-16 recurring integrator merge-conflict / failing-check repair (attempt 1 of 3)

Requirement ID: **ARC-03** (versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary **tracker-hygiene repair** of the recurring integrator merge-conflict / failing-check loop on branch `swarm/READY-16-275`. This segment changes **no production code, test, migration, contract, generated client, dependency or configuration**. It removes the conflict source on the integrator-owned shared notes file and re-verifies the already-committed READY-16 implementation on the merged tree.

Branch: `swarm/READY-16-275`. HEAD before this change: `4b955c7 Agent changes for READY-16`. `origin/main` = `7316f9d`; merge base = `742f2bb1f5d78ffa1bcced9620c9f282af99d452`. Main advanced by 2 commits since the base (`b322476` daily audit, `7316f9d` take 2 tasks), both touching only `TODO.md`; the branch touches no tracker, so the three-way merge is mechanically clean (`git merge-tree` reports `merged`, no conflict hunks).

## Evidence sources

- **Tool evidence**: every command below was executed locally this run (git inspection, `scripts/check.sh`, policy scripts, contract check, Maven/Surefire, stdlib render comparison). Results are executed output, not claims.
- **Reviewer evidence (simulated)**: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build` and `smartclinic-test-engineer` were loaded and their rules applied. No separate reviewer subagent was dispatched for this bounded tracker fix.
- **Simulated-user / human / clinical evidence**: none. This is a backend list endpoint and a tracker-hygiene fix, not a clinical decision workflow.
- All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Reproduction of the reported failure (tool evidence)

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3."

1. `git diff --stat 742f2bb..HEAD -- TODO.md` → empty (branch never touches the tracker).
2. `git merge-tree $(git merge-base HEAD origin/main) origin/main HEAD` → `merged`; the only differing file is `.agent/pr-notes.md`.
3. `git show origin/main:.agent/pr-notes.md` starts with `# AUD-20261120-13 — ...` (the integrator-owned note). `git show HEAD:.agent/pr-notes.md` starts with `# fix-20261120-160626 — ...` (a worker note). The merge takes the branch's worker note, so every merge re-introduces a non-integrator note on that shared file.
4. `bash scripts/check.sh` (bounded 280 s) → **exit 1**, last lines:
   ```
   shellcheck not installed: skipping shell lint (CI runs it)
   check-pack: opencode.json is stale: run scripts/gen-opencode.py
   ```
5. `git log --oneline 742f2bb..HEAD` shows 49 commits oscillating between `Agent changes for READY-16` (rewrites `.agent/pr-notes.md`) and `fix-<timestamp>: restore shared pr-notes ...` (restores it). `git show --stat 4b955c7` confirms the current tip's "Agent changes" commit modifies **only** `.agent/pr-notes.md` (163 lines).

AGENTS.md and the shared-knowledge loop forbid any worker from writing `.agent/pr-notes.md` (parallel workers overwrite it and every PR then conflicts). A re-dispatched READY-16 story actor keeps violating that rule; each fix pass restores it and the actor re-corrupts it. That two-sided modification of an integrator-owned tracker is the source of the loop and of the "merged but checks failed" verdict.

### "checks failed" is environmental, not a READY-16 code defect

- `check-pack.py` failure is the documented cloud-worker artifact: `git ls-files -v opencode.json` reports `S` (skip-worktree); `agent-run.sh` copied the CI profile into the worktree and `check-pack.py` judges that file. A stdlib in-memory render comparison (`gen-opencode.py render()`) proved: `HEAD opencode.json matches fresh render (ci=False): True` and `HEAD .github/opencode.ci.json matches fresh render (ci=True): True`. No agent, skill, `opencode.json` or workflow file was changed by this fix. In the integrator's non-skip-worktree environment `check-pack.py` passes.
- `python3 -c 'import yaml'` → `ModuleNotFoundError: No module named 'yaml'` in this container, so the Python policy unit-test stage cannot fully run locally (pre-existing environment limitation; PyYAML is installed in CI). No Python/tooling file was changed here.
- Individually, on the current tree: `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline`, and `python3 -I contracts/openapi/check.py` all exit 0 (`x-04 drift check OK: 24 commands, contract and client match manifest`).

## Fix applied in this segment

- Restored the shared `.agent/pr-notes.md` to the exact `origin/main` bytes with `git checkout origin/main -- .agent/pr-notes.md`. Verified `diff <(git show origin/main:.agent/pr-notes.md) .agent/pr-notes.md` is empty (`RESTORED: .agent/pr-notes.md == origin/main`) and `git diff origin/main -- .agent/pr-notes.md` is empty. The branch tip now has no diff on the integrator-owned shared file.
- Wrote this segment's evidence only to its own worker-owned file, `.agent/pr-notes/fix-20261120-163030.md` (the file this prompt names).
- No production, test, contract, generated-client, migration, dependency or configuration file was changed by this segment.

## Verification commands and results (this run, tool evidence)

| Command | Result |
|---|---|
| `git merge-base HEAD origin/main` | `742f2bb1f5d78ffa1bcced9620c9f282af99d452` |
| `git merge-tree <base> origin/main HEAD` | `merged` (no conflict hunks) |
| `bash scripts/check.sh` (280 s bound) | exit 1 at `check-pack.py`: `opencode.json is stale` — skip-worktree CI-profile artifact; committed HEAD files verified fresh in-memory |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OrderListServiceTest,OrderListControllerTest -DfailIfNoSpecifiedTests=false test` | exit 0; Surefire reports: `OrderListServiceTest` 11 tests / 0 failures / 0 errors / 0 skipped; `OrderListControllerTest` 5 tests / 0 failures / 0 errors / 0 skipped |
| `grep -rnE '^(<<<<<<<\|=======\|>>>>>>>)'` over scoped sources + contract + generated client | no conflict markers; `/api/v1/orders` and `/api/v1/facilities` each appear once in `contracts/openapi.yaml` |
| `python3 -I` in-memory `gen-opencode.py render()` comparison | both committed config files match a fresh render (`ci=False`, `ci=True`) |

## READY-16 acceptance mapping (implementation already committed; unchanged this segment)

| Story step | Implementation | Evidence (from the READY-16 note; re-run in part above) |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the order-domain work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest` (11), `OrderListControllerTest.badLimitReturns422`, `OrderListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. `created_at DESC`, unique `id` tie-break, `<createdAt>:<id>` cursor, null `nextCursor` on last page, fetch `limit + 1` | `OrderRepository.query`, `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; integration paging test |
| 3. Reuse single-read action; no new role | `deny(context, ORDER_READ, "ALL", clinician)` in `OrderService.list` | service denial tests + integration denial test |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query` over `exchange_order` / `exchange_work_item`, no manual org predicate | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows` |
| 5. `OrderPage(List<OrderView>, String nextCursor)` | `exchange/order/OrderPage.java` (frozen list) | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation` |
| 6. Service unit, controller, two-organization integration tests | 3 classes / 21 tests | commands above + integrator runs the integration tier |
| 7. Contract | `contracts/openapi.yaml` `get` on `/api/v1/orders` + regenerated staff-web `openapi.d.ts` | `contracts/openapi/check.py` green (above) |

No existing endpoint, response shape or migration was changed; no dependency was added.

## Coverage (unchanged this segment)

This segment changed **no production code**, so the 100% JaCoCo result recorded in `.agent/pr-notes/READY-16-20261120-090706.md` still applies to the merged tree: all bundle counters 0 missed (INSTRUCTION 90998, BRANCH 6445, LINE 15797, COMPLEXITY 7098, METHOD 3824, CLASS 787), and per touched file `OrderPage.java`, `OrderService.java`, `OrderRepository.java`, `OrderController.java` all 0 missed. No threshold, exclusion or test skip was introduced. The fast unit tier was re-run green above; the database/integration tier and full `mvn verify` (JaCoCo, OWASP, SpotBugs) are owned by the integrator/Delivery gate and were not re-run locally (no Docker; NVD cache stalls offline).

## Defects found and decisions

- **Root-cause defect (recurring):** non-fix story actors keep writing notes to the integrator-owned shared `.agent/pr-notes.md`, the merge-conflict source. Corrected by restoring the file; the durable fix belongs to the orchestrator (recommendation below).
- **Decision:** restore the shared notes file to the exact `origin/main` bytes rather than editing it; keep this segment's evidence only in its own worker-owned file.
- **Decision (carried from READY-16, unchanged):** `status` accepts the order resource's projected work-item states `awaiting_local_patient` and `ready` (V19 `exchange_work_item.state CHECK` / `WorkItemView`), because `OrderView` and `exchange_order` have no status column. No state was invented and no migration changed.
- **Environment-only findings (no repo change):** `opencode.json` CI profile under skip-worktree (committed files verified fresh); PyYAML absent locally. Neither is caused by this fix.

## Recommendation to break the loop (outside this worker's scope)

This fix removes the conflict source on the branch tip. If the integrator's next merge fails again, the durable cause is external to these files: READY-16 story actors are being re-dispatched after each failed merge and rewrite `.agent/pr-notes.md`. The orchestrator should mark READY-16 `[x]` once this green merged tree is confirmed, or block its re-dispatch, so the restored tracker is not overwritten again. This worker cannot edit `TODO.md`, workflows, shared trackers or another actor's prompt.
