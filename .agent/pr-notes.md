# READY-16 fix attempt 1 — List orders (GET /api/v1/orders)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).
Branch: `swarm/READY-16-275`. Base for this fix: branch HEAD `5ee5949` merged with `origin/main` `b495e96`. Synthetic fixtures only; no patient data.

## Root cause of the integrator failure and the fix

The integrator reported: "Merge conflict with main could not be resolved automatically (merged but checks failed). Fix attempt 1 of 3."

Reproduction and diagnosis (tool evidence):

- `git merge origin/main` on the branch produced a single conflict, in the shared file `.agent/pr-notes.md`. Main had replaced that file with the AUD-11 audit note (`7693785`/`b495e96`), while the branch still carried the older READY-49/READY-15/READY-16 combined note. That is a protected, integrator-owned file: a worker must not author changes to it.
- The READY-16 implementation itself is intact and green: no Java, test, contract or config file conflicted with main (`git merge` auto-merged all ten implementation files with no conflict).
- Resolution: `git checkout --theirs .agent/pr-notes.md` (take main's AUD-11 version) and stage it. After the resolution, `git diff origin/main -- .agent/pr-notes.md` is empty, so the branch no longer changes any shared tracker.

Post-resolution, the branch's net diff against `origin/main` is exactly the ten READY-16 files and nothing else:

```
 .agent/pr-notes/READY-16-20261120-090706.md        (own notes only)
 .../exchange/order/OrderController.java
 .../exchange/order/OrderPage.java
 .../exchange/order/OrderRepository.java
 .../exchange/order/OrderService.java
 .../exchange/order/OrderListControllerTest.java
 .../exchange/order/OrderListServiceTest.java
 .../exchange/order/OrderListIntegrationTest.java
 apps/staff-web/src/api/generated/openapi.d.ts
 contracts/openapi.yaml
```

A grep of the net-diff file list for shared-tracker paths (`TODO.md`, `MEMORY.md`, `WORKSPACE.md`, `BUGS.md`, `PROJECT.md`, `docs/swarm/`, `.agent/pr-notes.md`, `opencode.json`, `.github/`) returned none. The corrected branch contains `origin/main` as an ancestor, so the integrator's merge of this branch is a clean fast-forward with no conflict.

No production code was changed in this fix attempt: every applicable gate was re-run on the merged tree and is green. The acceptance mapping, entry-point-to-test trace, branch-to-test mapping and contract-generation note from the first READY-16 note (`.agent/pr-notes/READY-16-20261120-090706.md`) still apply unchanged.

## Acceptance mapping (ARC-03) — unchanged from segment 1

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor`/`limit`/`status` validation (422 on bad input) | `OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest`, `OrderListControllerTest`, `OrderListIntegrationTest` |
| 2. `created_at DESC, id DESC`, `<createdAt>:<id>` cursor, `limit+1` fetch, null `nextCursor` on last page | `OrderRepository.query`, `OrderService.list` | `OrderListServiceTest`, `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-read action/role | `ORDER_READ`/`clinician` unchanged | `OrderListServiceTest` denial tests |
| 4. Parameterised SQL, forced RLS isolation, real V19 columns | `OrderRepository.query` | `OrderListIntegrationTest` two-org tests |
| 5. `OrderPage(List<OrderView>, String)` | new `OrderPage.java` | service + controller tests |
| 6. Service, controller and two-org integration tests | 3 classes | commands below |
| 7. Contract | `contracts/openapi.yaml` + regenerated staff-web d.ts | contract + gen checks below |

Entry point to test trace: `GET /api/v1/orders` -> `TenantContextInterceptor.preHandle` -> `OrderController.list` -> `OrderService.list` (validate, guarded transaction, `deny`) -> `OrderRepository.query` (forced RLS, `created_at/id DESC`, `limit+1`) -> audit append -> `OrderPage`.

## Exact commands and results (all executed by this worker on the merged tree; tool evidence)

| Command | Result |
|---|---|
| `git merge origin/main --no-edit` | conflict only in `.agent/pr-notes.md`; resolved with `git checkout --theirs` (main's version) |
| `git diff origin/main -- .agent/pr-notes.md` | empty (shared file now identical to main) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify` | exit 0; full unit + integration build |
| `mvn -q -B -f apps/api/pom.xml -Psecurity -Dowasp.skip=true -DskipTests verify` (SpotBugs) | exit 0 |
| `cd db && SMARTCLINIC_PG_ADMIN=postgres bash check.sh` | exit 0: "db: migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified" |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `cd apps/staff-web && npm run gen:api:check` | `gen:api: generated client matches the contract` (exit 0) |
| `bash scripts/frontend-check.sh` | exit 0: typecheck, lint, prettier, Vitest all pass |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-names.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 |
| fresh render of `gen-opencode.py` vs committed config | `opencode.json committed == fresh render: YES`; `opencode.ci.json committed == fresh render: YES` (so config is not genuinely stale) |
| merged contract sanity | both `/api/v1/facilities` and `/api/v1/orders` present; `OrderView`, `OrderPage`, `Facility`, `FacilityPage` schemas present; both paths in generated d.ts |

### Coverage result (mandatory)

`SMARTCLINIC_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security verify` ran the full Surefire suite including PostgreSQL integration tests and regenerated `apps/api/target/site/jacoco/jacoco.xml`. Parsed directly with a stdlib XML reader:

- Bundle counters (all 0 missed): INSTRUCTION 0/90998, BRANCH 0/6445, LINE 0/15797, COMPLEXITY 0/7098, METHOD 0/3824, CLASS 0/787.
- Touched files (all 0 missed): `OrderPage.java` (LINE 4, METHOD 2), `OrderService.java` (LINE 200, BRANCH 94, METHOD 24), `OrderRepository.java` (LINE 106, BRANCH 6, METHOD 24), `OrderController.java` (LINE 12, METHOD 7).
- Surefire across all reports: `tests=2370 failures=0 errors=0 skipped=0`.

100% line, branch, method and class coverage on every file changed. The build-failing JaCoCo gate is satisfied. No threshold, exclusion or skip was introduced; no test was disabled.

## Step-6 check result (TODO.md "Instructions for every agent")

The step-6 check for API work is `bash scripts/worker-check.sh`. On this worker it exits 1 at `check-pack: opencode.json is stale: run scripts/gen-opencode.py`. This is the documented cloud-worker artifact, not a story defect: `git ls-files -v opencode.json` reports `S` (skip-worktree) because `agent-run.sh` copied the CI profile into the worktree, and `check-pack.py` judges that worktree file rather than the committed local profile. Verified above that the committed `opencode.json` and `.github/opencode.ci.json` both match a fresh render exactly, so a fresh integrator checkout passes this gate. This worker additionally lacks PyYAML, pip and Doxygen, so the Python policy unit tests and `scripts/docs.sh` cannot run here; those are pre-existing environment limitations and no Python, workflow or documentation file was changed by this story. The Java compile + unit + integration + JaCoCo gates, SpotBugs, the database policy gate, the contract gates and the frontend gate were all run directly and are green.

## Defects found, decisions and deviations

- Root cause (decision): the integrator failure was a merge conflict in the protected shared `.agent/pr-notes.md`, not a failing implementation. Fixed by resolving that conflict in main's favour; no shared tracker was edited.
- No new defect was found in the READY-16 code during re-verification.
- No production file, migration, dependency, response shape or existing endpoint was changed in this fix attempt. No reserved migration version was used (schema unchanged).
- The integration test intentionally lives under `apps/api/src/test/java/com/aurevia/smartclinic/it/` and the generated staff-web d.ts was regenerated; these are required by the story's explicit steps even though the planner `scope` field lists only the exchange/order packages and `contracts/openapi` (scope is planner collision metadata, not an enforced allowlist).

## Evidence sources

Tool evidence: every command above was executed locally (`git`, Maven/Surefire/JaCoCo, SpotBugs, `db/check.sh`, contract and frontend gates, stdlib XML/renders). Reviewer evidence: specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`, `smartclinic-senior-database` and `smartclinic-software-engineer` were applied in segment 1; no separate reviewer run was dispatched for this bounded merge-resolution fix. Simulated-user evidence: none. Human or clinical evidence: none (backend list endpoint, not a clinical decision workflow). All fixtures are synthetic; no patient data, credential, token or real domain name appears in code, logs, URLs or browser storage.
