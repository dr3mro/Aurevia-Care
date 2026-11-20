# fix-20261120-112705 — READY-16 merge-conflict repair (List orders, GET /api/v1/orders)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary repair of an integrator merge conflict. Branch: `swarm/READY-16-275`.
Base: branch HEAD `aef15ea` with `origin/main` `b495e96` already an ancestor. Synthetic fixtures only; no patient data.

## Root cause of the reported failure

The integrator reported: *"Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3. Reproduce with `scripts/check.sh` on the merge of main and this branch."*

Reproduction and diagnosis (tool evidence, all commands executed locally):

- `git diff --name-status origin/main HEAD` showed the branch's net changes included the **shared, integrator-owned file `.agent/pr-notes.md`** (modified), in addition to the ten legitimate READY-16 files. Main had replaced that shared file with the AUD-20261120-11 note in `b495e96`; the branch carried a READY-16 fix note there. That is the file both sides write, so an integrator merge conflicts on it — exactly the reported "merge conflict ... could not be resolved automatically".
- The READY-16 implementation itself did not conflict: all ten Java/test/contract files auto-merged with main cleanly. The Java fast tier (`mvn -Pno-security,fast test`) and the full integration+coverage tier (`mvn -Pno-security verify`) are green on the merge result (below).
- A prior fix commit (`4dce18d`) had taken main's version of `.agent/pr-notes.md`, but the following commit (`aef15ea`, "Agent changes for READY-16") rewrote the shared file again with the fix note, reintroducing the conflict. It also stored its note under a different task's file name (`fix-20261120-105859.md`). Per AGENTS.md and the TODO "Instructions for every agent", a worker must write notes **only** to its own `.agent/pr-notes/<task-id>.md` and must never edit the shared `.agent/pr-notes.md`.

## Fix applied

1. Restored the shared file to main's version: `git show origin/main:.agent/pr-notes.md > .agent/pr-notes.md`. `git diff origin/main -- .agent/pr-notes.md` is now empty — the branch no longer changes any shared tracker.
2. Put this attempt's notes in the only file this worker owns: `.agent/pr-notes/fix-20261120-112705.md` (this file).
3. Changed **no production code, test, migration, contract or configuration**. The READY-16 implementation from the earlier commits is unchanged and verified below.

After the fix the net diff against `origin/main` contains only: the nine READY-16 Java files (four production, three test classes including the `it/exchange/order` integration test), the regenerated staff-web `openapi.d.ts`, `contracts/openapi.yaml`, and the per-story note files (`.agent/pr-notes/READY-16-20261120-090706.md`, the prior attempt's `.agent/pr-notes/fix-20261120-105859.md`, and this file). No shared tracker (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `.agent/pr-notes.md`, `opencode.json`, `.github/`) is modified. `git merge-base --is-ancestor origin/main HEAD` is true, so the integrator's merge is a clean fast-forward with no conflict.

## Acceptance mapping (ARC-03) — unchanged from the implementation

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` (existing workflow values) else 422 | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`; `OrderListControllerTest.badLimitReturns422`; `OrderListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. `created_at DESC, id DESC`, `<createdAt>:<id>` cursor of last row, null `nextCursor` on last page, fetch `limit+1` | `OrderRepository.query`, `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-read action/role; add no role | `ORDER_READ` / `clinician` unchanged | `OrderListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited` |
| 4. Parameterised SQL, RLS isolation, real V19 column names | `OrderRepository.query` (no manual org predicate) | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView>, String)` | new `OrderPage.java` | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`, `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service, controller and two-org integration tests | three classes | commands below |
| 7. Contract | `contracts/openapi.yaml` + regenerated staff-web `openapi.d.ts` | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check` |

Entry point to test trace: `GET /api/v1/orders` -> `TenantContextInterceptor.preHandle` -> `OrderController.list` -> `OrderService.list` (validate, guarded transaction, `deny`) -> `OrderRepository.query` (forced RLS, `created_at/id DESC`, `limit+1`) -> audit append -> `OrderPage`. Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`. Failure paths: invalid-input and denial service/controller tests above.

## Exact commands and results (executed by this worker; tool evidence)

| Command | Result |
|---|---|
| `git diff --name-status origin/main HEAD` (before fix) | showed `.agent/pr-notes.md` modified — the conflicting shared file |
| `bash scripts/check.sh` (reproduction on the merge) | exit 1 at `python3 -I scripts/check-pack.py`: `check-pack: opencode.json is stale: run scripts/gen-opencode.py` — known cloud-worker artifact (see Defects); no code/contract failure |
| `bash scripts/worker-check.sh` (step-6 check from TODO "Instructions for every agent") | exit 1 at the same `check-pack` policy stage; same environment-only cause |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` | all exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1, `Ran 271 tests ... FAILED (failures=9, errors=10)` — all pre-existing/environment-only (`ModuleNotFoundError: No module named 'yaml'`; opencode.json CI profile; local git branch state in `AgentRunTests`), none in this branch's files |
| `bash scripts/frontend-check.sh` | exit 0 — Prettier clean; Vitest 1/1 passed |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `npm run gen:api:check --prefix apps/staff-web` | `gen:api: generated client matches the contract` (exit 0) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | exit 0 — Surefire 1919 tests, 0 failures, 0 errors, 0 skipped |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | **BUILD SUCCESS** — Surefire 2370 tests, 0 failures, 0 errors, 0 skipped; JaCoCo "All coverage checks have been met"; OWASP/SpotBugs skipped by `-Pno-security` (owned by the integrator) |

`opencode.json` environment artifact independently verified: importing `scripts/gen-opencode.py` and comparing with `git show HEAD:opencode.json` gave `HEAD opencode.json == render: True` and `HEAD ci == render(ci=True): True`. The worktree file is the CI profile under `skip-worktree` (`git ls-files -v opencode.json` = `S`), so `check-pack.py` judges that file rather than the committed, correct one (MEMORY.md: OPS-CW-003-01 closed as false positive). No config file was edited by this fix.

## Coverage result (mandatory)

`SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` ran the full test suite including the PostgreSQL integration tier; JaCoCo 0.8.16 enforces 100% INSTRUCTION/LINE/BRANCH/METHOD/CLASS at bundle level. `apps/api/target/site/jacoco/jacoco.xml` reports all counters `missed = 0`: INSTRUCTION 90998, BRANCH 6445, LINE 15797, METHOD 3824, CLASS 787. Per touched production file: `OrderPage.java` LINE 0/4, METHOD 0/2; `OrderService.java` LINE 0/200, BRANCH 0/94, METHOD 0/24; `OrderRepository.java` LINE 0/106, BRANCH 0/6, METHOD 0/24; `OrderController.java` LINE 0/12, METHOD 0/7. **100% on every file changed in the implementation.** This repair added no production line or branch, so it adds no new coverage obligation; the re-run confirms the merged tree still satisfies the build-failing gate.

The implementation's branch-to-test mapping is recorded in `.agent/pr-notes/READY-16-20261120-090706.md` (each new branch and its covering test is enumerated there) and remains valid unchanged.

## Defects found, decisions and evidence sources

- **Defect (fixed):** a worker wrote notes into the shared `.agent/pr-notes.md` and used another task's file name; this is what made the integrator merge conflict. Restored the shared file from main and moved this attempt's notes to `.agent/pr-notes/fix-20261120-112705.md`. No production defect was found in the READY-16 implementation.
- **Decision:** keep the prior attempt's `fix-20261120-105859.md` (a per-task notes file from an earlier run) rather than delete another worker's record; it does not affect any gate. The shared tracker is the only file that had to be reverted.
- **Environment-only findings (no repo change):** PyYAML and pip are absent, so `test_fast_tier`, `test_workflow_concurrency` and several `test_swarm`/`test_agent_run` cases cannot run here; `opencode.json` is the CI profile under skip-worktree. None is caused by, or fixable inside, this story.
- **Evidence sources:** all commands above were executed locally (tool evidence) from synthetic fixtures. Specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-senior-java-build`, `smartclinic-test-engineer`, `smartclinic-senior-database` and `smartclinic-software-engineer` were loaded and their rules applied (simulated reviewer evidence); no separate reviewer run was dispatched for this bounded merge repair. Simulated-user, human-participant and clinical evidence: none (backend list endpoint / tracker repair, not a clinical decision workflow). No patient data, credential or real domain name appears in code, logs, URLs or browser storage.
