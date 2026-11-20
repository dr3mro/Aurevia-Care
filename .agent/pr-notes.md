# fix-20261120-155558 — READY-16 recurring integrator merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.

Classification: cross-boundary **tracker-hygiene repair** of the recurring integrator merge-conflict / failing-check loop on branch `swarm/READY-16-275`. This segment changes **no production code, test, migration, contract, generated client, dependency or configuration**. It removes the conflict source on the integrator-owned shared notes file, re-verifies the already-committed READY-16 implementation on the exact merged tree, and records evidence only in this worker-owned file.

Branch: `swarm/READY-16-275`. HEAD before this change: `09387ce Agent changes for READY-16`. `origin/main` = merge base = `742f2bb1f5d78ffa1bcced9620c9f282af99d452`; the branch is `0 45` commits vs main (main is an ancestor, no main commit missing). The merge of main and this branch is therefore the branch tree itself.

Evidence discipline: every command below was executed locally this run (**tool evidence**). Specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build` and `smartclinic-software-engineer` were loaded and their rules applied (**simulated reviewer evidence**); no separate reviewer subagent was dispatched for this bounded tracker fix. Simulated-user, human-participant and clinical evidence: **none** (this is a backend list endpoint, not a clinical decision workflow). All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage. Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Root cause reproduced (tool evidence)

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3." `git log --oneline origin/main..HEAD` alternates between `fix-<ts>: restore shared pr-notes ...` commits and `Agent changes for READY-16` commits. The starting HEAD `09387ce Agent changes for READY-16` touched **only** the tracked, integrator-owned `.agent/pr-notes.md` (71 insertions / 91 deletions), overwriting main's `AUD-20261120-13` note with a worker note — the two-sided modification that drives the integrator's conflict-resolution path and records `merged but checks failed` when the post-merge `check.sh` is red. Prior fix notes (`fix-20261120-*` in this folder) document the same loop.

| Forensics command | Result |
|---|---|
| `git status --short` (before fix) | clean worktree on `swarm/READY-16-275` |
| `git rev-parse origin/main`; `git merge-base HEAD origin/main` | both `742f2bb1f5d78ffa1bcced9620c9f282af99d452` (main is an ancestor) |
| `git rev-list --count origin/main..HEAD` / `HEAD..origin/main` | `45` / `0` |
| `git ls-files -u` | no unmerged entries (no live in-tree conflict markers) |
| `git show --stat --oneline 09387ce` | `.agent/pr-notes.md` only (71 +/91 -) |
| `git rev-parse HEAD:.agent/pr-notes.md` vs `origin/main:.agent/pr-notes.md` | `7e04964` vs `b5eae15` — the conflict source |
| `git diff --numstat origin/main..HEAD -- .agent/pr-notes.md` (before fix) | `71 91 .agent/pr-notes.md` |

## Reproduction with `scripts/check.sh` (tool evidence, this run)

- `bash scripts/check.sh` on the branch as-is → **exit 1** at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. Diagnosed as the known skip-worktree CI-profile artifact (MEMORY.md, OPS-CW-003-01), not a failure caused by this branch:
  - `git ls-files -v opencode.json` → `S` (skip-worktree).
  - `cmp opencode.json .github/opencode.ci.json` → identical (the worktree file is the CI profile copied by `agent-run.sh`).
  - `git show HEAD:opencode.json` differs from the CI profile (HEAD holds the local profile) and differs from the worktree file.
  - Rendered both outputs from `scripts/gen-opencode.py` and compared to the committed blobs: `opencode.json ci=False: HEAD matches fresh render: True`; `.github/opencode.ci.json ci=True: HEAD matches fresh render: True`. The committed configuration is correct; `check-pack` judges the masked worktree file. No config file was edited by this segment.
- Earlier fix notes additionally document that, with the committed HEAD `opencode.json` swapped in, `check.sh` passes all repository stages and fails only in unchanged `scripts/tests/` under this sandbox (PyYAML absent). This container is Python 3.11.2 without `PyYAML`/`pip`, so the full Python unit discovery in `check.sh` cannot pass here; that is an environment-only limitation, not a story defect, and no Python/tooling file was changed.

## Fix applied (in scope)

1. Restored the shared file to main's exact blob with `git checkout origin/main -- .agent/pr-notes.md`. Verified afterward: `git hash-object .agent/pr-notes.md` = `b5eae15b899cdb6690b7fca7a602b99cb80d41ac` = `git rev-parse origin/main:.agent/pr-notes.md`. Both `git diff --cached --numstat origin/main -- .agent/pr-notes.md` and `git diff --numstat origin/main -- .agent/pr-notes.md` are empty — main's `AUD-20261120-13` note is preserved and the recurring conflict source is removed from this committed tree. No worker content was destroyed: prior worker notes remain in their worker-owned `.agent/pr-notes/*.md` files (including the full `READY-16-20261120-090706.md` mapping and prior fix notes).
2. Wrote this attempt's evidence **only** to the worker-owned `.agent/pr-notes/fix-20261120-155558.md` — never back to the shared path.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/*`, `scripts/`, any migration, any contract, any generated client or any production/test file.

**Durable blocker (owner action required, outside this task's scope):** the branch keeps re-acquiring the bad change because an `Agent changes for READY-16` end-of-session auto-commit (`scripts/agent-run.sh`, `git add -A && git commit -qm "Agent changes for ${STORY_ID}"`) re-writes the tracked shared path whenever worker notes land there. Until the harness stops writing worker notes to the tracked `.agent/pr-notes.md` (write only to the untracked artifact path, or untrack/ignore that path), a later READY-16 session can re-introduce this exact conflict. The orchestrator owns that harness change; this worker cannot patch it without leaving its declared scope.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged on this exact tree

| Story step | Implementation | Evidence (executed this run or carried from the verified full run) |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the workflow states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` (`awaiting_local_patient`, `ready`) | `OrderListServiceTest` 11, `OrderListControllerTest` 5 — green this run |
| 2. `created_at DESC, id DESC`; cursor `<createdAt>:<id>` of last returned row; null on last page; `limit+1` fetch | `OrderRepository.query`, `OrderService.list` | service green this run; `OrderListIntegrationTest` owned by the integrator |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, identical to `OrderService.get` | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real `exchange_order` column names, no manual org predicate | `OrderRepository.query` over `public.exchange_order`, `exchange_order_snapshot`, `exchange_work_item` (V19) | integration `organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` | `exchange/order/OrderPage.java`, frozen with `List.copyOf` | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | `OrderListServiceTest` (11), `OrderListControllerTest` (5), `it/exchange/order/OrderListIntegrationTest` | unit classes green this run; integration tier owned by the integrator |
| 7. Contract | `contracts/openapi.yaml` `get /api/v1/orders` + `OrderPage` schema; staff-web `openapi.d.ts` regenerated | `python3 -I contracts/openapi/check.py` → `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0, this run) |

Entry point → test trace: `GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor` (401 without subject) → `OrderController.list` → `OrderService.list` (validate, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership/roles re-derived each request) → `OrderRepository.query` (forced RLS, `created_at/id DESC`, `limit+1`) → audit `ORDER_READ/ALL/SUCCESS` → `OrderPage`. Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`; failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed this run)

All commands run from the repository root; results are tool output. Java 21.0.13, Maven 3.10.0, Node 22.23.5, Python 3.11.2 (no PyYAML).

| Command | Result |
|---|---|
| `bash scripts/check.sh` (branch as-is) | **exit 1** at `check-pack: opencode.json is stale` — diagnosed as skip-worktree CI-profile artifact (above); no code defect |
| `git ls-files -v opencode.json`; `cmp opencode.json .github/opencode.ci.json`; `git show HEAD:opencode.json` | `S`; worktree == CI profile; HEAD == local profile; both HEAD blobs match a fresh generator render → environment-only |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OrderListServiceTest,OrderListControllerTest test` | **exit 0** (targeted order-list unit tests green) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **exit 0**; Surefire XML parse: 171 report files, **1930 tests, 0 failures, 0 errors, 0 skipped**; `OrderListServiceTest` 11/0/0/0, `OrderListControllerTest` 5/0/0/0 |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| `git checkout origin/main -- .agent/pr-notes.md`; `git hash-object` + `git diff --cached/--numstat origin/main -- .agent/pr-notes.md` | blob restored to `b5eae15...`; both diffs empty — conflict source removed |

Step-6 check named in TODO.md "Instructions for every agent": `bash scripts/worker-check.sh` for API work. It is not runnable to green in this sandbox for the two environment-only reasons above (`check-pack` skip-worktree artifact, missing PyYAML). The Java compile + fast unit tier, contract gate, Java conventions and the policy scripts that do not need PyYAML were run directly and are green. The database and integration tier is owned by the integrator per this task's instructions.

## Coverage (100% gate)

This segment added **no production code**, so no new branch or line needs coverage and no coverage threshold, exclusion or skip was changed. The READY-16 implementation's 100% was verified in the original story notes (`.agent/pr-notes/READY-16-20261120-090706.md`): JaCoCo 0.8.16 at bundle level reported 0 missed INSTRUCTION/BRANCH/LINE/METHOD/CLASS, with 0 missed on every touched file (`OrderPage.java`, `OrderService.java`, `OrderRepository.java`, `OrderController.java`). The full `bash apps/api/check.sh` gate (including JaCoCo) is owned by the integrator/Delivery; the fast unit tier was re-run green this run (1930 tests). No coverage result is fabricated or pending-as-covered.

## Defects found and decisions

- Decision: restore the shared `.agent/pr-notes.md` to main's exact blob rather than merge the two versions, because AGENTS.md forbids any agent editing that shared file and main's `AUD-20261120-13` note is the authoritative version. Worker evidence stays in worker-owned `*.md` files.
- Decision: do not edit `opencode.json` to make `check.sh` green. The committed blobs match a fresh generator render; the worktree file is the harness's CI profile under skip-worktree. Changing it would alter a protected file and mask the real harness issue.
- Defect (durable, owner-owned, out of scope): the `agent-run.sh` end-of-session auto-commit writes worker notes to the tracked shared path, re-creating this conflict on every READY-16 session. Recorded above; not patched here because `.github/`, `scripts/` and shared trackers are outside this task's scope.
- No patient data, credential, real domain name or PHI-shaped string appears in this note or in any file changed by this segment.

## Evidence sources

Tool evidence: all commands above were executed locally; results come from Git, Maven/Surefire, the contract gate, the generator render comparison and the repository policy scripts. Reviewer evidence: the named specialist skills were loaded and their rules applied; no separate reviewer run was dispatched. Simulated-user evidence: none. Human/clinical evidence: none. All fixtures are synthetic.
