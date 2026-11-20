# fix-20261120-151217 — READY-16 recurring integrator merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.

Classification: cross-boundary **tracker-hygiene repair** of a recurring integrator merge conflict. This segment changes **no production code, test, migration, contract, generated client, dependency or configuration**. It removes the conflict source on the integrator-owned shared notes file and re-verifies the already-committed READY-16 implementation on the exact merged tree.

Branch: `swarm/READY-16-275`. `git merge-base HEAD origin/main` = `742f2bb1f5d78ffa1bcced9620c9f282af99d452` = `origin/main` HEAD and is an ancestor of this branch, so the worktree already represents the merge of main and this branch. Synthetic fixtures only — no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Root cause reproduced (tool evidence)

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3." `git log --oneline 742f2bb..HEAD` alternates between `fix-<ts>: restore shared pr-notes ...` commits and `Agent changes for READY-16` commits. The starting HEAD `4998608 Agent changes for READY-16` touched **only** the tracked, integrator-owned `.agent/pr-notes.md` (80 insertions / 92 deletions), overwriting main's `AUD-20261120-13` note with a worker note — exactly the two-sided modification each prior fix had restored.

The recurring cause is in **`scripts/agent-run.sh`** (out of this task's scope):

- line 198 `[[ "$notes_file" != .agent/pr-notes.md && -f "$notes_file" ]] && cp "$notes_file" "$out/pr-notes.md"` — copies the worker's own, correctly named notes file into the *tracked* shared path `.agent/pr-notes.md` (the file's own comment at line 33 claims that path is "ignored", but `git ls-files` confirms it is tracked);
- line 200-202 `if [[ -n "$(git status --porcelain ...)" ]]; then git add -A; git commit -qm "Agent changes for ${STORY_ID}"; fi` — commits that overwrite as `Agent changes for READY-16`.

So every worker session that writes its own notes file automatically re-introduces the shared-file conflict after it has been restored. No worker-side edit can prevent the end-of-session auto-commit from doing this.

| Forensics command | Result |
|---|---|
| `git status --short --branch` | `## swarm/READY-16-275...origin/swarm/READY-16-275` (0/0) |
| `git merge-base HEAD origin/main` | `742f2bb1f5d78ffa1bcced9620c9f282af99d452` (= `origin/main` HEAD) |
| `git show --numstat --format='' HEAD` (4998608) | `80 92 .agent/pr-notes.md` — only the shared file |
| `git diff --numstat origin/main -- .agent/pr-notes.md` (before fix) | `80 92 .agent/pr-notes.md` — the conflict source |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) '` (tracked files) | no conflict markers |

## Reproduction with `scripts/check.sh` (tool evidence)

- `bash scripts/check.sh` on the branch as-is → **exit 1** at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`.
- `git ls-files -v opencode.json` → `S opencode.json`; `sha256sum` of the worktree file equals `.github/opencode.ci.json` and differs from `git show HEAD:opencode.json`. This is the known skip-worktree CI-profile artifact (OPS-CW-003-01): `agent-run.sh` copies the CI profile into the worktree and marks it skip-worktree, so `check-pack.py` judges the CI profile rather than the committed local profile.
- With the committed HEAD `opencode.json` temporarily swapped into the worktree (CI profile restored afterward via an `EXIT` trap; `git ls-files -v opencode.json` re-confirmed `S`): `python3 -I scripts/gen-opencode.py --check` → **exit 0**, proving the committed profile is fresh. `bash scripts/check.sh` then passed the shell/pack/docs/secrets/names/migration stages and failed only at `python3 -I -m unittest discover -s scripts/tests -q` (`Ran 271 tests ... FAILED (failures=6, errors=10)`). All failures/errors are in unchanged `scripts/tests/` and are environment artifacts (PyYAML absent; `git`/`gh` intercepted in this sandbox — e.g. `test_resolve_merges_main_into_a_conflicted_pr_branch` saw `merged but checks failed` because the sandbox `check.sh` fails). None is caused by this story.

## Fix applied (in scope)

1. Restored the shared file to main's exact blob with `git checkout origin/main -- .agent/pr-notes.md`. `git diff --numstat origin/main -- .agent/pr-notes.md` is empty afterward — main's `AUD-20261120-13` note is preserved and the recurring conflict source is removed from this committed tree. No worker content was destroyed: prior worker notes remain in their worker-owned `.agent/pr-notes/*.md` files.
2. Wrote this attempt's evidence **only** to the worker-owned `.agent/pr-notes/fix-20261120-151217.md`. The shared file is not touched again after restoration.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/*`, `scripts/` or any migration.
4. No production/test/contract/config change; the READY-16 implementation from the earlier commits is untouched.

**Durable blocker (owner action required, outside this task's scope):** until `scripts/agent-run.sh` stops copying the worker notes file into the tracked shared path (line 198) — e.g. write it only to the untracked artifact path, or untrack/ignore `.agent/pr-notes.md` — each end-of-session auto-commit (line 202) will re-introduce this exact conflict on every subsequent fix attempt. The orchestrator owns that harness change.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged on this exact tree

| Story step | Implementation | Evidence (executed this run) |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the workflow states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` (`STATUSES = awaiting_local_patient, ready`) | `OrderListServiceTest` 11, `OrderListControllerTest` 5 — green |
| 2. `created_at DESC, id DESC`; cursor `<createdAt>:<id>` of the last returned row; null on last page; `limit+1` fetch | `OrderRepository.query` keyset predicate + `ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`; `OrderService.list` | `OrderListServiceTest` + `OrderListIntegrationTest` 5 — green |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, identical to `OrderService.get` | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` — green |
| 4. Parameterised SQL, RLS isolation, real `exchange_order` column names, no manual org predicate | `OrderRepository.query` over `public.exchange_order` joined to `exchange_order_snapshot` (and `exchange_work_item` only for the status filter) | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` — green |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` | `exchange/order/OrderPage.java`, frozen with `List.copyOf` in the compact constructor and overridden accessor | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` — green |
| 6. Service unit, controller and two-organization integration tests | 3 test classes | all green in `mvn verify` below |
| 7. Contract | `contracts/openapi.yaml` `get /api/v1/orders` + `OrderView`/`OrderPage` schemas; staff-web `openapi.d.ts` regenerated | `python3 -I contracts/openapi/check.py` green |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used. The full per-branch mapping is in `.agent/pr-notes/READY-16-20261120-090706.md`.

## Entry point → test trace

`GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate inputs before any transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and role re-derived each request) → `OrderRepository.query` (forced RLS, `created_at DESC, id DESC`, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.
- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed this run, tool evidence)

All commands run from the repository root unless noted.

| Command | Result |
|---|---|
| `git checkout origin/main -- .agent/pr-notes.md` | exit 0 — shared file restored to main |
| `git diff --numstat origin/main -- .agent/pr-notes.md` | empty after fix |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) '` | no output — no conflict markers |
| `bash scripts/check.sh` (worktree CI profile) | exit 1 at check-pack `opencode.json is stale` — known artifact, reproduced |
| `bash scripts/worker-check.sh` (worktree CI profile) | exit 1 at check-pack `opencode.json is stale` — same artifact; step-6 check for API work |
| `python3 -I scripts/gen-opencode.py --check` (HEAD local profile swapped in, CI profile restored after) | exit 0 — committed config is fresh |
| `bash scripts/check.sh` (HEAD local profile swapped in) | all policy stages green; failed only at Python tooling tests (6 failures / 10 errors, environment-only, unchanged scripts) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0 — fast unit tier: **1930 tests, 0 failures, 0 errors, 0 skipped** |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | exit 0 — `BUILD SUCCESS`; `All coverage checks have been met.`; **Tests run: 2381, Failures: 0, Errors: 0, Skipped: 0** (real PostgreSQL) |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` | all exit 0 |

Order-list test classes in the full run: `OrderListServiceTest` 11, `OrderListControllerTest` 5, `OrderListIntegrationTest` 5 — all 0 failures/errors/skips.

### Coverage result (mandatory)

Coverage command: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` → exit 0, `All coverage checks have been met.` JaCoCo 0.8.16 enforces 1.00 INSTRUCTION/LINE/BRANCH/METHOD/CLASS at bundle level. `apps/api/target/site/jacoco/jacoco.xml` read with a stdlib XML parse:

| Counter | Missed | Covered |
|---|---|---|
| INSTRUCTION | 0 | 91024 |
| BRANCH | 0 | 6449 |
| LINE | 0 | 15803 |
| COMPLEXITY | 0 | 7101 |
| METHOD | 0 | 3825 |
| CLASS | 0 | 787 |

Per touched production file (all counters 0 missed): `OrderService.java` (200 lines, 94 branches, 24 methods, 2 classes), `OrderRepository.java` (106 lines, 6 branches, 24 methods, 2 classes), `OrderPage.java` (4 lines, 2 methods, 1 class), `OrderController.java` (12 lines, 7 methods, 1 class). **100% on every file changed by the READY-16 implementation.** This segment added no production branch; every branch in the unchanged implementation is covered by the executed unit + integration suites. The new-branch-to-test list for the implementation is in `.agent/pr-notes/READY-16-20261120-090706.md`.

## Defects found, decisions and deviations

- **Root-cause defect (owner action required):** `scripts/agent-run.sh` lines 198/202 copy the worker note into the tracked shared path and auto-commit it, so this merge conflict is re-created every session. Out of this task's scope; escalated here for the orchestrator.
- **Decision:** restore the shared file to main's exact blob rather than merging worker and AUD notes; the shared file is main's property and any worker content there is invalid under AGENTS.md. No worker content was lost.
- **Decision:** no production/test/contract/config change; the READY-16 implementation is complete and re-verified at 100% on this exact tree, so this segment is tracker hygiene only.
- **Deviation (transparent):** the literal `scope` lists only the `exchange/order` packages and `contracts/openapi`, while the repair necessarily touches the shared tracker file to resolve the conflict. This matches earlier READY-16 segments (which also added the `it/` integration test and regenerated the staff-web `openapi.d.ts` required by the story's explicit steps). No feature file was changed in this segment.
- **Environment-only findings (no repo change here):**
  - `opencode.json` is `S` (skip-worktree, CI profile copied by `agent-run.sh`), so `check-pack.py` / `gen-opencode.py --check` report the known stale-config false positive (OPS-CW-003-01). Confirmed the HEAD blob matches a fresh local render (`--check` exit 0) and no config file was edited by this segment.
  - The full Python tooling suite cannot pass in this sandbox: `PyYAML` is absent and `git`/`gh` are intercepted. All 6 failures and 10 errors are in unchanged `scripts/tests/` and are environment artifacts, not caused by this branch; the Java compile, 2381 tests, integration tier and full JaCoCo gate all ran green locally.
  - No Docker locally; image smoke runs in GitHub Actions only.

## Evidence sources

Tool evidence: every command above was executed locally this run; results come from Git, Maven/Surefire/JaCoCo, the contract gate and the policy scripts. Reviewer evidence: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer` and `smartclinic-software-engineer` were loaded and their rules applied (simulated review); no separate reviewer subagent was run for this bounded tracker fix. Simulated-user evidence: none. Human/clinical/patient evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.

**Status:** in-scope fix applied — shared file restored to main, worker notes written only to this worker's own file, fast unit tier and full integration + 100% coverage gate green, no production code changed. The recurring conflict cannot be permanently resolved within this task's scope; it requires the orchestrator to patch `scripts/agent-run.sh` (or untrack/ignore the shared notes path). Ready for the integrator to re-run its full gate on the merged SHA; if the conflict recurs, the harness fix is the required next action.
