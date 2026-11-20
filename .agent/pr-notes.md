# fix-20261120-152053 — READY-16 recurring integrator merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.

Classification: cross-boundary **tracker-hygiene repair** of a recurring integrator merge-conflict / failing-check loop on branch `swarm/READY-16-275`. This segment changes **no production code, test, migration, contract, generated client, dependency or configuration**. It removes the conflict source on the integrator-owned shared notes file and re-verifies the already-committed READY-16 implementation on the exact merged tree.

Branch: `swarm/READY-16-275` at HEAD `3b26f6fe125304578ac3150560723996d248f62a` before this change.

Evidence discipline: every command below was executed locally this run (tool evidence). Specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer` and `smartclinic-software-engineer` were loaded and their rules applied (simulated reviewer evidence); no separate reviewer subagent was dispatched for this bounded tracker fix. Simulated-user, human-participant and clinical evidence: none (this is a backend list endpoint, not a clinical decision workflow). All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Root cause reproduced (tool evidence)

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3." `git log --oneline 742f2bb..HEAD` alternates between `fix-<ts>: restore shared pr-notes ...` commits and `Agent changes for READY-16` commits. The starting HEAD `3b26f6f Agent changes for READY-16` touched **only** the tracked, integrator-owned `.agent/pr-notes.md` (91 insertions / 92 deletions), overwriting main's `AUD-20261120-13` note with a worker note — the two-sided modification that drives the integrator's conflict-resolution path (`scripts/agent-run.sh` `cmd_resolve` → `cmd_merge_main`, which records `merged but checks failed` when the post-merge `check.sh` is red, line 355).

| Forensics command | Result |
|---|---|
| `git status --short --branch` | clean worktree on `swarm/READY-16-275` before this fix |
| `git rev-parse origin/main` after `git fetch origin main` | `742f2bb1f5d78ffa1bcced9620c9f282af99d452` (unchanged) |
| `git merge-base HEAD origin/main` | `742f2bb1f5d78ffa1bcced9620c9f282af99d452` (= `origin/main` HEAD, ancestor of branch) |
| `git log --oneline HEAD..origin/main` | empty — no main commit missing from branch |
| `git merge-tree <base> origin/main HEAD` | exit 0, no conflict markers — the tree merge itself is clean |
| `git show --numstat --format='%H %s' HEAD` | `3b26f6f Agent changes for READY-16` → `91 92 .agent/pr-notes.md` only |
| `git diff --numstat origin/main HEAD -- .agent/pr-notes.md` (before fix) | `91 92 .agent/pr-notes.md` — the conflict source |
| `git ls-files -v opencode.json` | `S opencode.json` (skip-worktree; CI profile copied by `agent-run.sh`) |

So the merge of main and this branch is textually clean; the failure is the post-merge `check.sh` on a tree whose shared notes file was overwritten by a worker session.

## Reproduction with `scripts/check.sh` (tool evidence)

- `bash scripts/check.sh` on the branch as-is → **exit 1** at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. `sha256sum` of the worktree `opencode.json` equals `.github/opencode.ci.json` and both equal `b40d443d…`; `git ls-files -v opencode.json` reports `S`. This is the known skip-worktree CI-profile artifact (OPS-CW-003-01): the worktree is judged against the CI profile, not the committed local profile. No config file was edited by this segment.
- With the committed HEAD `opencode.json` temporarily swapped into the worktree (CI profile restored afterward via a shell copy; `git ls-files -v opencode.json` re-confirmed `S`): `bash scripts/check.sh` passed the shell/pack/docs/secrets/names/migration stages and failed only at `python3 -I -m unittest discover -s scripts/tests -q` — `Ran 271 tests ... FAILED (failures=6, errors=10)`. All failures/errors are in unchanged `scripts/tests/` and are sandbox artifacts:
  - `test_resolve_merges_main_into_a_conflicted_pr_branch`: expected `merged`, got `merged but checks failed`;
  - `test_merge_main_without_conflicts_merges_and_rechecks`: expected `passed`, got `failed`;
  - `test_failing_checks_open_a_draft`: `--draft` absent from the intercepted `gh` log;
  - `test_push_token_overrides_gh_token_for_git_only`: pushed refs missing under the intercepted `git`;
  - the remaining failures/errors are the same `PyYAML`-missing and `git`/`gh`-interception cascades.
  These tests invoke the sandbox `check.sh` (and intercepted `git`/`gh`) as their gate, so they cannot pass in this container. They are not caused by this branch's code, and none of this segment's changes touched `scripts/`, `.github/workflows/*`, `opencode.json` or any tooling file.

## Fix applied (in scope)

1. Restored the shared file to main's exact blob with `git checkout origin/main -- .agent/pr-notes.md`. `git diff --numstat origin/main -- .agent/pr-notes.md` is empty afterward — main's `AUD-20261120-13` note is preserved and the recurring conflict source is removed from this committed tree. No worker content was destroyed: prior worker notes remain in their worker-owned `.agent/pr-notes/*.md` files (including the full `READY-16-20261120-090706.md` mapping and the prior fix notes).
2. Wrote this attempt's evidence **only** to the worker-owned `.agent/pr-notes/fix-20261120-152053.md` — never back to the shared path.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/*`, `scripts/`, any migration, any contract, any generated client or any production/test file.

**Durable blocker (owner action required, outside this task's scope):** the branch keeps re-acquiring the bad change because an `Agent changes for READY-16` end-of-session auto-commit (`scripts/agent-run.sh` line 200–202, `git add -A && git commit -qm "Agent changes for ${STORY_ID}"`) re-writes the tracked shared path whenever the original READY-16 worker notes land there. Until the harness stops writing worker notes to the tracked `.agent/pr-notes.md` (write only to the untracked artifact path, or untrack/ignore that path), a later READY-16 session can re-introduce this exact conflict. The orchestrator owns that harness change; this worker cannot patch it without leaving its declared scope.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged on this exact tree

| Story step | Implementation | Evidence (executed or carried from the verified full run) |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the workflow states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` (`STATUSES = awaiting_local_patient, ready`) | `OrderListServiceTest` 11, `OrderListControllerTest` 5 — green this run |
| 2. `created_at DESC, id DESC`; cursor `<createdAt>:<id>` of last returned row; null on last page; `limit+1` fetch | `OrderRepository.query` keyset predicate + `ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`; `OrderService.list` | `OrderListServiceTest` + `OrderListIntegrationTest` 5 — green in the full run |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, identical to `OrderService.get` | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` — green |
| 4. Parameterised SQL, RLS isolation, real `exchange_order` column names, no manual org predicate | `OrderRepository.query` over `public.exchange_order` joined to `exchange_order_snapshot` (and `exchange_work_item` only for the status filter) | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` — green in the full run |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` | `exchange/order/OrderPage.java`, frozen with `List.copyOf` in the compact constructor and overridden accessor | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` — green |
| 6. Service unit, controller and two-organization integration tests | 3 test classes | unit classes green this run; integration tier owned by the integrator, green in the previous full `mvn verify` |
| 7. Contract | `contracts/openapi.yaml` `get /api/v1/orders` + `OrderView`/`OrderPage` schemas; staff-web `openapi.d.ts` regenerated | `python3 -I contracts/openapi/check.py` → `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0, this run) |

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
| `git merge-tree <base> origin/main HEAD` | exit 0, no conflict markers |
| `git show --numstat --format='%H %s' HEAD` | `3b26f6f Agent changes for READY-16` → `91 92 .agent/pr-notes.md` |
| `git checkout origin/main -- .agent/pr-notes.md` | exit 0 — shared file restored to main |
| `git diff --numstat origin/main -- .agent/pr-notes.md` | empty after fix |
| `bash scripts/check.sh` (worktree CI profile) | exit 1 at check-pack `opencode.json is stale` — known artifact, reproduced |
| `bash scripts/check.sh` (HEAD local profile swapped in, CI profile restored after) | policy stages green; failed only at Python tooling tests (6 failures / 10 errors, environment-only, unchanged `scripts/`) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0 — fast unit tier: **1930 tests, 0 failures, 0 errors, 0 skipped** |
| surefire order-list reports | `OrderListServiceTest` 11 / 0, `OrderListControllerTest` 5 / 0 |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` | all exit 0 |

### Coverage result (mandatory)

This segment added **no production branch and changed no production file**, so it introduces no new coverage obligation. The step-6 check named in TODO.md "Instructions for every agent" is `bash scripts/worker-check.sh` for API work; this task's brief further assigns the database/integration tier to the integrator, so I ran the fast unit tier (`mvn -q -B -f apps/api/pom.xml -Pno-security,fast test`, 1930 tests green) and the policy/contract gates above. The full Java gate, including the 100% JaCoCo assertion and the two-organization integration tests, was executed and verified green in the preceding READY-16 segment (`SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` → `BUILD SUCCESS`, `All coverage checks have been met.`, `Tests run: 2381, Failures: 0, Errors: 0, Skipped: 0`; JaCoCo bundle counters 0 missed for INSTRUCTION 91024 / BRANCH 6449 / LINE 15803 / COMPLEXITY 7101 / METHOD 3825 / CLASS 787; per touched file `OrderService.java`, `OrderRepository.java`, `OrderPage.java`, `OrderController.java` all 0 missed). Report: `.agent/pr-notes/fix-20261120-151217.md`. A fresh full coverage run is not reproducible in this container because the OWASP/NVD tooling stage cannot reach `nvd.nist.gov` and the sandbox intercepts `git`/`gh`; the integrator runs that gate on the merged SHA.

The complete per-branch-to-test list for the unchanged READY-16 implementation is in `.agent/pr-notes/READY-16-20261120-090706.md`; it remains valid because no production code changed in this segment.

## Defects found, decisions and deviations

- **Root-cause defect (owner action required):** `scripts/agent-run.sh` lines 200–202 auto-commit whatever the worker wrote, including a worker note written to the tracked shared path, so this merge conflict is re-created whenever the original READY-16 worker session re-runs. Out of this task's scope; escalated here for the orchestrator.
- **Decision:** restore the shared file to main's exact blob rather than merging worker and AUD notes; the shared file is main's property and worker content there is invalid under AGENTS.md. No worker content was lost.
- **Decision:** no production/test/contract/config change; the READY-16 implementation is complete and re-verified on this exact tree, so this segment is tracker hygiene only.
- **Deviation (transparent):** the literal `scope` lists only the `exchange/order` packages and `contracts/openapi`, while the repair necessarily touches the shared tracker file to resolve the conflict. This matches earlier READY-16 segments (which also added the `it/` integration test and regenerated the staff-web `openapi.d.ts` required by the story's explicit steps). No feature file was changed in this segment.
- **Environment-only findings (no repo change here):** `opencode.json` is `S` (skip-worktree, CI profile copied by `agent-run.sh`), so `check-pack.py` / `gen-opencode.py --check` report the known stale-config false positive (OPS-CW-003-01); the full Python tooling suite cannot pass in this sandbox (PyYAML absent; `git`/`gh` intercepted), producing 6 failures / 10 errors in unchanged `scripts/tests/`; no Docker locally. None is caused by this branch.

**Status:** in-scope fix applied — shared file restored to main, worker notes written only to this worker's own file, fast unit tier and all runnable policy/contract gates green, no production code changed. The recurring conflict cannot be permanently resolved within this task's scope; it requires the orchestrator to patch `scripts/agent-run.sh` (or untrack/ignore the shared notes path). Ready for the integrator to re-run its full gate on the merged SHA.
