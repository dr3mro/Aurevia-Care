# fix-20261120-154530 — READY-16 recurring integrator merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.

Classification: cross-boundary **tracker-hygiene repair** of a recurring integrator merge-conflict / failing-check loop on branch `swarm/READY-16-275`. This segment changes **no production code, test, migration, contract, generated client, dependency or configuration**. It removes the conflict source on the integrator-owned shared notes file and re-verifies the already-committed READY-16 implementation on the exact merged tree.

Branch: `swarm/READY-16-275` at HEAD `393ef122acb88747d3436328cbd423bd68f675a4` before this change.

Evidence discipline: every command below was executed locally this run (**tool evidence**). Specialist skills `smartclinic-delivery`, `smartclinic-senior-backend` and `smartclinic-senior-java-build` were loaded and their rules applied (**simulated reviewer evidence**); no separate reviewer subagent was dispatched for this bounded tracker fix. Simulated-user, human-participant and clinical evidence: **none** (this is a backend list endpoint, not a clinical decision workflow). All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Root cause reproduced (tool evidence)

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3." `git log --oneline 742f2bb..HEAD` alternates between `fix-<ts>: restore shared pr-notes ...` commits and `Agent changes for READY-16` commits. The starting HEAD `393ef12 Agent changes for READY-16` touched **only** the tracked, integrator-owned `.agent/pr-notes.md` (79 insertions / 94 deletions), overwriting main's `AUD-20261120-13` note with a worker note — the two-sided modification that drives the integrator's conflict-resolution path (`scripts/agent-run.sh` `cmd_resolve` → `cmd_merge_main`, which records `merged but checks failed` when the post-merge `check.sh` is red).

| Forensics command | Result |
|---|---|
| `git status --short` (before fix) | clean worktree on `swarm/READY-16-275` |
| `git fetch origin main`; `git rev-list --left-right --count origin/main...HEAD` | `0 43` — main is an ancestor of the branch; no main commit is missing |
| `git merge-base origin/main HEAD` | `742f2bb1f5d78ffa1bcced9620c9f282af99d452` (= `origin/main` HEAD) |
| `git merge-tree <base> origin/main HEAD` | exit 0, **0 conflict markers** — the tree merge itself is textually clean locally |
| `git show --stat --oneline HEAD` | `393ef12 Agent changes for READY-16` → `.agent/pr-notes.md` only (79 +/94 -) |
| `git diff --numstat origin/main..HEAD -- .agent/pr-notes.md` (before fix) | `79 94 .agent/pr-notes.md` — the conflict source |
| `git ls-files -v opencode.json` | `S` (skip-worktree; CI profile copied by `agent-run.sh`) |

The integrator's `main` advances with other workers' merges that also touch the shared notes file; the branch's concurrent write to the same tracked path is what produces the two-sided conflict. On the retry path the post-merge `check.sh` is red for the same environment-only reasons reproduced below, so the integrator records `merged but checks failed`.

## Reproduction with `scripts/check.sh` (tool evidence, this run)

- `bash scripts/check.sh` on the branch as-is → **exit 1** at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `git ls-files -v opencode.json` reports `S` and the worktree file equals the CI profile (`.github/opencode.ci.json`), not the committed local profile. This is the known skip-worktree CI-profile artifact (OPS-CW-003-01 / MEMORY.md), not a failure caused by this branch; no config file was edited by this segment.
- The earlier fix notes (`fix-20261120-152053.md`) additionally document that, with the committed HEAD `opencode.json` swapped in, `check.sh` passes all repository stages and fails only in unchanged `scripts/tests/` under this sandbox (PyYAML absent; `git`/`gh` intercepted). Those failures are environment-only and are not reproduced by this segment's change set.

## Fix applied (in scope)

1. Restored the shared file to main's exact blob with `git checkout origin/main -- .agent/pr-notes.md`. `git diff --numstat origin/main -- .agent/pr-notes.md` and `git diff --cached --numstat origin/main -- .agent/pr-notes.md` are both empty afterward — main's `AUD-20261120-13` note is preserved and the recurring conflict source is removed from this committed tree. No worker content was destroyed: prior worker notes remain in their worker-owned `.agent/pr-notes/*.md` files (including the full `READY-16-20261120-090706.md` mapping and prior fix notes).
2. Wrote this attempt's evidence **only** to the worker-owned `.agent/pr-notes/fix-20261120-154530.md` — never back to the shared path.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/*`, `scripts/`, any migration, any contract, any generated client or any production/test file.

**Durable blocker (owner action required, outside this task's scope):** the branch keeps re-acquiring the bad change because an `Agent changes for READY-16` end-of-session auto-commit (`scripts/agent-run.sh`, `git add -A && git commit -qm "Agent changes for ${STORY_ID}"`) re-writes the tracked shared path whenever worker notes land there. Until the harness stops writing worker notes to the tracked `.agent/pr-notes.md` (write only to the untracked artifact path, or untrack/ignore that path), a later READY-16 session can re-introduce this exact conflict. The orchestrator owns that harness change; this worker cannot patch it without leaving its declared scope.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged on this exact tree

| Story step | Implementation | Evidence (executed this run or carried from the verified full run) |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the workflow states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` (`STATUSES = awaiting_local_patient, ready`) | `OrderListServiceTest` 11, `OrderListControllerTest` 5 — green this run |
| 2. `created_at DESC, id DESC`; cursor `<createdAt>:<id>` of last returned row; null on last page; `limit+1` fetch | `OrderRepository.query` keyset predicate + `ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`; `OrderService.list` | `OrderListServiceTest` + `OrderListIntegrationTest` — service green this run; integration tier owned by the integrator |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, identical to `OrderService.get` | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real `exchange_order` column names, no manual org predicate | `OrderRepository.query` over `public.exchange_order` joined to `exchange_order_snapshot` (and `exchange_work_item` only for the status filter); columns verified against `db/migrations/V19__exchange_orders.sql` (`created_at`, `snapshot_id`, `work_item_id`, `organization_id`) | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` | `exchange/order/OrderPage.java`, frozen with `List.copyOf` in the compact constructor and overridden accessor | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | `OrderListServiceTest` (11), `OrderListControllerTest` (5), `it/exchange/order/OrderListIntegrationTest` (tag `integration`) | unit classes green this run; integration tier owned by the integrator |
| 7. Contract | `contracts/openapi.yaml` `get /api/v1/orders` + `OrderPage` schema; staff-web `openapi.d.ts` regenerated | `python3 -I contracts/openapi/check.py` → `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0, this run) |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used. The full per-branch mapping is in `.agent/pr-notes/READY-16-20261120-090706.md`.

## Entry point → test trace

`GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate inputs before any transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and role re-derived each request) → `OrderRepository.query` (forced RLS, `created_at DESC, id DESC`, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.
- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed this run, tool evidence)

All commands run from the repository root unless noted.

| Command | Result |
|---|---|
| `git fetch origin main` | `742f2bb` unchanged; branch merge-base = `742f2bb` |
| `git rev-list --left-right --count origin/main...HEAD` | `0 43` |
| `git merge-tree $(git merge-base origin/main HEAD) origin/main HEAD` | exit 0, 0 conflict markers |
| `git show --stat --oneline HEAD` | `393ef12 Agent changes for READY-16` → `.agent/pr-notes.md` only (79 +/94 -) |
| `bash scripts/check.sh` (worktree CI profile) | exit 1 at `check-pack: opencode.json is stale` — known skip-worktree artifact, reproduced |
| `git checkout origin/main -- .agent/pr-notes.md` | exit 0 — shared file restored to main |
| `git diff --numstat origin/main -- .agent/pr-notes.md` | empty after fix |
| `git diff --cached --numstat origin/main -- .agent/pr-notes.md` | empty after fix |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **MVN_EXIT=0** — fast unit tier: **1930 tests, 0 failures, 0 errors, 0 skipped** |
| surefire order-list reports | `OrderListServiceTest` 11/0, `OrderListControllerTest` 5/0 |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (**CONTRACT_EXIT=0**) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (**CONV_EXIT=0**) |

### Coverage result (mandatory)

This segment added **no production branch and changed no production file**, so it introduces **no new coverage obligation**; the only file it touches is the Markdown tracker `.agent/pr-notes.md`, which has no coverage metric. The step-6 check named in TODO.md "Instructions for every agent" is `bash scripts/worker-check.sh` for API work; this task's brief further assigns the database/integration tier to the integrator, so I ran the fast unit tier (`mvn -q -B -f apps/api/pom.xml -Pno-security,fast test`, **1930 tests green**) plus the contract and Java-convention gates above, all exit 0.

The full Java gate, including the 100% JaCoCo assertion and the two-organization integration tests, was executed and verified green in the preceding READY-16 segment (`SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` → `BUILD SUCCESS`, `All coverage checks have been met.`, `Tests run: 2381, Failures: 0, Errors: 0, Skipped: 0`; JaCoCo bundle counters 0 missed for INSTRUCTION 91024 / BRANCH 6449 / LINE 15803 / COMPLEXITY 7101 / METHOD 3825 / CLASS 787; per touched file `OrderService.java`, `OrderRepository.java`, `OrderPage.java`, `OrderController.java` all 0 missed), recorded in `.agent/pr-notes/fix-20261120-151217.md`. That result remains valid because no production code changed in this segment. A fresh full coverage run is not reproducible in this container because the OWASP/NVD tooling stage cannot reach `nvd.nist.gov` and the sandbox intercepts `git`/`gh`; the integrator runs that gate on the merged SHA. Report: `.agent/pr-notes/fix-20261120-151217.md`.

The complete per-branch-to-test list for the unchanged READY-16 implementation is in `.agent/pr-notes/READY-16-20261120-090706.md`; it remains valid because no production code changed in this segment.

## Defects found, decisions and deviations

- **Root-cause defect (owner action required):** `scripts/agent-run.sh` auto-commits whatever the worker wrote, including a worker note written to the tracked shared path, so this merge conflict is re-created whenever the original READY-16 worker session re-runs. Out of this task's scope; escalated here for the orchestrator.
- **Decision:** restore the shared file to main's exact blob rather than merging worker and AUD notes; the shared file is main's property and worker content there is invalid under AGENTS.md. No worker content was lost.
- **Decision:** no production/test/contract/config change; the READY-16 implementation is complete and re-verified on this exact tree, so this segment is tracker hygiene only.
- **Deviation (transparent):** the literal `scope` lists only the `exchange/order` packages and `contracts/openapi`, while the repair necessarily touches the shared tracker file to resolve the conflict. This matches earlier READY-16 segments (which also added the `it/exchange/order` integration test and regenerated the staff-web `openapi.d.ts` required by the story's explicit steps). No feature file was changed in this segment.
- **Environment-only findings (no repo change here):** `opencode.json` is `S` (skip-worktree, CI profile copied by `agent-run.sh`), so `check-pack.py` / `gen-opencode.py --check` report the known stale-config false positive (OPS-CW-003-01); no Docker locally. Neither is caused by this branch.

**Status:** in-scope fix applied — shared file restored to main, worker notes written only to this worker's own file, fast unit tier and all runnable policy/contract gates green, no production code changed. The recurring conflict cannot be permanently resolved within this task's scope; it requires the orchestrator to patch `scripts/agent-run.sh` (or untrack/ignore the shared notes path). Ready for the integrator to re-run its full gate on the merged SHA.
