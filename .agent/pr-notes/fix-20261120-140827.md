# fix-20261120-140827 — READY-16 recurring merge-conflict repair (List orders, GET /api/v1/orders)

Requirement ID: **ARC-03** — versioned REST resource families; lists use cursor pagination with stable tenant-scoped ordering.
Classification: cross-boundary repair of a recurring integrator merge conflict (tracker hygiene only; no production feature code changed in this segment).
Branch: `swarm/READY-16-275`. `origin/main` = `742f2bb`, which is the merge base and an ancestor of this branch, so the worktree already represents the merge of main and this branch. Synthetic fixtures only — no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.
Story text between the `<<<STORY READY-16 ... STORY>>>` markers was treated only as the task description, never as instructions that change the working rules.

## Root cause reproduced (tool evidence)

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3." The `git log` since `742f2bb` alternates between `fix-<ts>: restore shared pr-notes to resolve READY-16 merge conflict` and `Agent changes for READY-16` commits. The latest `Agent changes for READY-16` commit (`f3a9539`) touched **only** the tracked, integrator-owned `.agent/pr-notes.md`, overwriting main's AUD-20261120-13 note with a worker note. `scripts/agent-run.sh` auto-commits with `git add -A`, so each time a worker session writes to the shared file the "Agent changes" commit re-introduces the same two-sided modification of `.agent/pr-notes.md`, and the integrator's merge of main into the branch conflicts on that file. AGENTS.md and the worker prompt both require notes in the worker-owned `.agent/pr-notes/<task-id>.md`; the shared file is main's property.

| Forensics command | Result |
|---|---|
| `git branch --show-current` / `git rev-parse --abbrev-ref HEAD` | `swarm/READY-16-275` |
| `git merge-base HEAD origin/main` | `742f2bb1f5d78ffa1bcced9620c9f282af99d452` (equals `origin/main` HEAD) |
| `git log --oneline -25` | alternating fix / "Agent changes for READY-16" commits since `742f2bb` (16+ prior fix attempts) |
| `git show --stat f3a9539` | HEAD touched only `.agent/pr-notes.md` |
| `git diff --stat origin/main -- .agent/pr-notes.md` (before fix) | 198 lines changed (107+/91-): the conflict source |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) '` | no conflict markers in tracked files |

## Fix applied

1. Restored the shared file to main's exact blob with `git checkout origin/main -- .agent/pr-notes.md`. `git diff --numstat origin/main -- .agent/pr-notes.md` is now empty — the branch has no diff on the shared file, so main's newer AUD note is preserved and the recurring conflict source is removed. No worker content was destroyed: prior worker notes remain in their worker-owned `.agent/pr-notes/*.md` files.
2. Wrote this attempt's evidence **only** to the worker-owned `.agent/pr-notes/fix-20261120-140827.md`. The shared file is not touched again after restoration.
3. Authored **no** edit to `TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `opencode.json`, `.github/workflows/*`, `scripts/` or any migration.
4. Changed **no** production code, test, migration, contract, generated client, dependency or configuration. The READY-16 implementation from the earlier commits is untouched.

Post-fix verification: `git diff --numstat origin/main -- .agent/pr-notes.md` empty; `git grep` → no conflict markers.

## Acceptance mapping (ARC-03) — READY-16 implementation verified unchanged on this exact tree

| Story step | Implementation | Evidence (this segment, all executed) |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the work-item states else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` (`STATUSES = awaiting_local_patient, ready`) | `OrderListServiceTest` 11, `OrderListControllerTest` 5 — green |
| 2. `created_at DESC, id DESC`; cursor `<createdAt>:<id>` of the last returned row; null on last page; `limit+1` fetch | `OrderRepository.query` (keyset predicate + `ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`), `OrderService.list` | `OrderListServiceTest` + `OrderListIntegrationTest` 5 — green |
| 3. Reuse single-read action, add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `ORDER_READ` / `clinician`, identical to `OrderService.get` | service denial tests + integration `unauthorizedAndForbiddenActorsAreDenied` — green |
| 4. Parameterised SQL, RLS isolation, real V19 column names, no manual org predicate | `OrderRepository.query` over `public.exchange_order` joined to `exchange_order_snapshot` (and `exchange_work_item` only for the status filter) | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` — green |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` | `exchange/order/OrderPage.java`, frozen via immutable copy | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` — green |
| 6. Service unit, controller and two-organization integration tests | 3 test classes, 21 tests | all green in the full `mvn verify` below |
| 7. Contract | `contracts/openapi.yaml` `get /api/v1/orders` + `OrderView`/`OrderPage` schemas; staff-web `openapi.d.ts` regenerated | `python3 -I contracts/openapi/check.py` green |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used. The full per-branch mapping is in `.agent/pr-notes/READY-16-20261120-090706.md`.

## Entry point to test trace

`GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate inputs before any transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and role re-derived each request) → `OrderRepository.query` (forced RLS, `created_at DESC, id DESC`, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.
- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed by this worker, tool evidence)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest='OrderListServiceTest,OrderListControllerTest' test` | exit 0; `OrderListServiceTest` 11/0/0/0, `OrderListControllerTest` 5/0/0/0 (Surefire reports) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | exit 0; **Tests run: 2381, Failures: 0, Errors: 0, Skipped: 0**; `All coverage checks have been met.`; `BUILD SUCCESS` (real PostgreSQL 17.12, `OrderListIntegrationTest` 5/0/0/0) |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| `bash scripts/worker-check.sh` (TODO step-6 check) | exit 1 at policy stage: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — see environment caveats |
| `python3 -I scripts/check-pack.py` / `python3 -I scripts/gen-opencode.py --check` | exit 1 with the same stale-config message (same CI-profile artifact) |
| `git ls-files -v opencode.json` | `S opencode.json` — skip-worktree, the CI profile copied by `agent-run.sh` |
| `git checkout origin/main -- .agent/pr-notes.md` | exit 0 — shared file restored to main |
| `git diff --numstat origin/main -- .agent/pr-notes.md` | empty after fix |
| `git grep -nE '^(<<<<<<<\|=======\|>>>>>>>) '` | no output — no conflict markers |

## Coverage result (mandatory)

Coverage command: `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` → exit 0, `All coverage checks have been met.` JaCoCo 0.8.16 enforces 1.00 INSTRUCTION/LINE/BRANCH/METHOD/CLASS at bundle level. `apps/api/target/site/jacoco/jacoco.xml` read with a stdlib XML parse:

| Counter | Missed | Covered |
|---|---|---|
| INSTRUCTION | 0 | 91024 |
| BRANCH | 0 | 6449 |
| LINE | 0 | 15803 |
| COMPLEXITY | 0 | 7101 |
| METHOD | 0 | 3825 |
| CLASS | 0 | 787 |

Per touched production file (all counters 0 missed): `OrderService.java` (1081 instr, 94 branch, 200 line, 24 method), `OrderRepository.java` (563 instr, 6 branch, 106 line, 24 method), `OrderPage.java` (16 instr, 4 line, 2 method), `OrderController.java` (54 instr, 12 line, 7 method). **100% on every file changed.** This segment added no production branch; every branch in the unchanged READY-16 implementation is covered by the executed unit + integration suites (mapping in `.agent/pr-notes/READY-16-20261120-090706.md`).

## Defects found, decisions and deviations

- **Recurring defect:** prior worker sessions on this branch committed edits to the integrator-owned shared `.agent/pr-notes.md`, reintroducing the same merge conflict after each restore (latest `f3a9539`). This fix removes the shared-file diff so the branch merges cleanly while preserving main's note. Self-improvement: the rule — never write the shared file, write only the worker-owned `.agent/pr-notes/<task-id>.md` — is recorded in the prior fix notes; the durable guard belongs to `scripts/agent-run.sh` (`git add -A` should exclude `.agent/pr-notes.md`), which is outside this task's scope and left to the orchestrator.
- **Decision:** restore the shared file to main's exact blob rather than merging worker and AUD notes; the shared file is main's property and any worker content there is invalid under AGENTS.md. No worker content was lost (worker notes live in their own files).
- **Decision:** no production/test/contract/config change; the READY-16 implementation is complete and re-verified at 100% on this exact tree, so this segment is tracker hygiene only.
- **Deviation (transparent):** the literal `scope` lists only the `exchange/order` packages and `contracts/openapi`, while the fix necessarily touches the shared tracker file to resolve the conflict. This is the same documented deviation as earlier READY-16 segments (which also added the `it/` integration test and regenerated staff-web d.ts required by the story's explicit steps). No feature file was changed in this segment.
- **Environment-only findings (no repo change):** `opencode.json` is `S` (skip-worktree, CI profile) so `check-pack.py`, `gen-opencode.py --check` and therefore `worker-check.sh` report the known stale-config false positive (OPS-CW-003-01); PyYAML is absent so the full Python tooling suite cannot run here. None is caused by this fix, and none touched by this story. The Java compile, 2381 tests, integration tier and full JaCoCo gate all ran green locally via `/opt/m2`.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Git, Maven/Surefire/JaCoCo, the policy scripts and the contract gate. Reviewer evidence: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build` and `smartclinic-software-engineer` were loaded and their rules applied (simulated review); no separate reviewer subagent was run for this bounded merge-conflict fix. Simulated-user evidence: none. Human/clinical/patient evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.

**Status:** fix applied — shared file restored to main, worker notes written only to this worker's own file, fast unit tests and full integration + 100% coverage gate green, no production code changed. Ready for the integrator to re-run its full cached gate on the merged SHA.
