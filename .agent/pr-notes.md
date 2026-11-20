# READY-16 — List orders (GET /api/v1/orders)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).
Branch: `swarm/READY-16-275` at HEAD `a0ee6f4` before this change. Synthetic fixtures only.

## Acceptance mapping

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/orders`; `cursor` optional, `limit` default 50 / max 200 else 422 `INVALID_LIMIT`, `status` one of the order-domain workflow state values else 422 `INVALID_STATUS` | `exchange/order/OrderController.list`, `OrderService.list/parseCursor/parseLimit/parseStatus` | `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidStatusesAreRejectedBeforeAnyQuery`, `statusFiltersArePassedThroughToTheRepository`; `OrderListControllerTest.badLimitReturns422`; `OrderListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. Order by `created_at DESC` with unique `id` tie-break; cursor `<createdAt>:<id>` of the last row; `nextCursor` null on the last page; fetch `limit + 1` | `OrderRepository.query` (`ORDER BY o.created_at DESC, o.id DESC LIMIT :limit`), `OrderService.list` | `OrderListServiceTest.emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor`, `extraRowProducesCursorFromTheLastReturnedRow`; `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse the single-read action; add no role | `deny(context, READ_ACTION, "ALL", ORDERING_ROLE)` — `READ_ACTION = ORDER_READ` and `ORDERING_ROLE = clinician` are unchanged, exactly as `OrderService.get` | `OrderListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListIntegrationTest.unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real migration column names | `OrderRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `exchange_order`, `exchange_order_snapshot` and `exchange_work_item` from V19; no manual organization predicate | `OrderListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `OrderPage(List<OrderView> items, String nextCursor)` in the view package | new `exchange/order/OrderPage.java` with a frozen list | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation`; `OrderListControllerTest.validRequestReturnsPage` |
| 6. Service unit, controller and two-organization integration tests | 3 new test classes, 21 new tests | commands below |
| 7. Contract | `contracts/openapi.yaml`: `get` on `/api/v1/orders` plus `OrderView`/`OrderPage` schemas; staff-web generated `openapi.d.ts` regenerated with the pinned generator | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check` |

No existing endpoint, response shape or migration was changed. No dependency was added. No reserved migration version was used (schema unchanged).

## Entry point to test trace

`GET /api/v1/orders?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `OrderController.list` → `OrderService.list` (validate before transaction, then guarded `TransactionTemplate`) → `deny` (`ORDER_READ`, membership and roles re-derived each request) → `OrderRepository.query` (forced RLS, `created_at`/`id` descending, `limit+1`) → `audit.append(ORDER_READ, ALL, SUCCESS)` → `OrderPage`.

- Happy path: `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
- Failure paths: `OrderListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `OrderListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Exact commands and results (executed by this worker)

All commands run from the repository root unless noted; results are tool output.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (before implementation) | **RED** — compile errors: `OrderPage`, `OrderService.list`, `OrderRepository.query`, `OrderController.list` do not exist |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` | **GREEN** — Surefire: `OrderListServiceTest` 11 tests / 0 failures, `OrderListControllerTest` 5 tests / 0 failures |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=OrderListIntegrationTest -DfailIfNoSpecifiedTests=false test` | **GREEN** — `Tests run: 5, Failures: 0, Errors: 0, Skipped: 0` against real PostgreSQL 17.12 with 30 Flyway migrations |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `npm run gen:api:check` (in `apps/staff-web`) | `gen:api: generated client matches the contract` after `npm run gen:api` regeneration (exit 0) |
| `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` | all exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | `Ran 2 tests ... OK` (exit 0) |
| `SMARTCLINIC_PG_ADMIN=postgres bash apps/api/check.sh` | full Java gate incl. JaCoCo — see coverage result below |

### Coverage result (mandatory)

`bash apps/api/check.sh` runs `mvn verify`; JaCoCo 0.8.16 enforces 100% INSTRUCTION, LINE, BRANCH, METHOD and CLASS at bundle level. The generated `apps/api/target/site/jacoco/jacoco.xml` reports:

| Counter | Missed | Covered |
|---|---|---|
| INSTRUCTION | 0 | 90741 |
| BRANCH | 0 | 6419 |
| LINE | 0 | 15750 |
| METHOD | 0 | 3813 |
| CLASS | 0 | 784 |

Per touched production file (`sourcefile` counters): `OrderPage.java` 0 missed lines / 4 covered, 2 methods covered; `OrderService.java` 0 missed / 200 lines, 0 missed / 94 branches, 24 methods covered; `OrderRepository.java` 0 missed / 106 lines, 0 missed / 6 branches, 24 methods covered; `OrderController.java` 0 missed / 12 lines, 7 methods covered. **100% on every file changed.** Report timestamp 2026-11-20T09:23Z; fixtures are the executed unit + integration suites above.

### Worker-gate result and environment caveats

- The step-6 check named in TODO.md "Instructions for every agent" is `bash scripts/worker-check.sh` for API work. This container cannot run its Python policy stage fully: `python3` is 3.11 without `PyYAML` and without `pip`, so `test_fast_tier`, `test_swarm` and `test_workflow_concurrency` fail with `ModuleNotFoundError: No module named 'yaml'`. This is environment-only and pre-existing (the same caveat is recorded in `.agent/pr-notes/READY-14.md`); no Python, workflow or tooling file was changed by this story. The Java and database stages were run directly and are green (commands above), and the full Java coverage gate is green.
- `opencode.json` is flagged `S` (skip-worktree) in this worker, i.e. the CI profile. No config was edited.
- Evidence sources: all commands above were executed locally (tool evidence) from synthetic fixtures. Specialist skills `smartclinic-delivery`, `smartclinic-senior-backend`, `smartclinic-test-engineer`, `smartclinic-senior-java-build`, `smartclinic-software-engineer` and `smartclinic-senior-database` were loaded and their rules applied (reviewer evidence, simulated). No separate reviewer run was dispatched for this bounded slice. Simulated user, human-participant and clinical evidence: none (this is a backend list endpoint, not a clinical decision workflow).

## Branch-to-test mapping (JaCoCo 100% requirement)

| Production branch | Test covering each side |
|---|---|
| `OrderPage` compact ctor + `items()` copy/override | `OrderListServiceTest.pageFreezesItsItemsAgainstCallerMutation` (caller mutation + unmodifiable result); `OrderListControllerTest.validRequestReturnsPage` (JSON projection) |
| `OrderService.list`: `denial != null` true/false | `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited` (true) vs all success tests (false) |
| `OrderService.list`: `failure.get() != null` true/false | denial tests (true) vs success tests (false) |
| `OrderService.list`: `parsedCursor == null ? null : ...` (both ternaries) | `absentAndBlankParametersUseDefaults` / `emptyPageHasNoNextCursor` (absent) vs `extraRowProducesCursorFromTheLastReturnedRow` (present) |
| `OrderService.list`: `hasMore` true/false and `if (hasMore)` | `extraRowProducesCursorFromTheLastReturnedRow` (true) vs `emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor` (false) |
| `parseCursor`: null / blank / regex mismatch / `Instant.parse` exception / non-canonical / valid | `absentAndBlankParametersUseDefaults` (null + blank), `invalidCursorsAreRejectedBeforeAnyQuery` (regex mismatch, `not-a-time:uuid`, `.1Z` non-canonical), `extraRowProducesCursorFromTheLastReturnedRow` (valid) |
| `parseLimit`: null / blank / `<1` / `>200` / non-numeric / valid | `absentAndBlankParametersUseDefaults`, `invalidLimitsAreRejectedBeforeAnyQuery` (`0`, `201`, `abc`), `exactlyLimitRowsHaveNoNextCursor` (`2`) |
| `parseStatus`: null / blank / valid / invalid | `absentAndBlankParametersUseDefaults`, `statusFiltersArePassedThroughToTheRepository`, `invalidStatusesAreRejectedBeforeAnyQuery` |
| `OrderRepository.query`: `cursorCreatedAt != null` true/false | `OrderListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (page 1 false, pages 2–3 true) |
| `OrderRepository.query`: `status != null` true/false (both ifs) | `OrderListIntegrationTest.statusFilterSelectsOnlyMatchingOrders` (true) and `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (false) |
| `OrderRepository.mapOrder` and `OrderView` mapping | exercised by the integration list assertions and the existing single-record tests |
| `OrderController.list` parameter binding | `OrderListControllerTest` (5 tests: 401, 403, 422, 200, passthrough) |

Javadoc uses `{@code ...}` only; no new `{@link}` target was added. `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py'` is green; the full Python suite is blocked by the missing `yaml` module (environment-only).

## Contract generation note

`contracts/openapi/README.md` states that `contracts/openapi/generated/openapi.yaml` is emitted from `CommandClientGenerator.java`/`CommandCatalog.java` and is distinct from the staff-API `contracts/openapi.yaml` consumed by `apps/staff-web/scripts/gen-api-types.sh`. This new staff GET changes only the latter; it adds no command to the Java catalog, so the generated X-04 artifacts are unchanged and `python3 -I contracts/openapi/check.py` stays green. The dependent staff-web `src/api/generated/openapi.d.ts` **was** regenerated with the pinned `openapi-typescript` CLI (never hand-edited) so `npm run gen:api:check` stays green.

## Defects found, decisions and deviations

- **Ambiguity resolved (decision):** the story says to use "the existing workflow state values you find in `OrderView` and the migration". `exchange/order/OrderView` has **no** status field, and `exchange_order` (V19) has **no** state column. The order resource's own view is `WorkItemView` (same package), whose Javadoc and V19 `exchange_work_item.state CHECK` define exactly two state values: `awaiting_local_patient` and `ready`. Those are the values accepted by `status`, and the filter joins `exchange_work_item` on `(organization_id, work_item_id)` — a real V19 composite key. No state was invented and no migration was changed. If the intended set was the XCH-02 `WorkflowState` values (`requested`…`cancelled`) or the XCH-03 `ExchangeState` values, that belongs to a different resource (`/api/v1/exchange/tasks`, `/api/v1/xch03/orders`) and was not silently substituted here.
- Decision: the list reuses `ORDER_READ` and the `clinician` role from the single-record read, and audits under `ORDER_READ`/`ALL`, adding no role and no action key (story step 3).
- Decision: cursor is the canonical `Instant.toString()` of `created_at` plus the immutable order id; a non-canonical or unparseable instant is rejected with `INVALID_CURSOR`. Descending order uses `(created_at DESC, id DESC)` so the tie-break is stable.
- Deviation (transparent, as in READY-14): the TODO `scope` field lists only the `exchange/order` packages and `contracts/openapi`, while the story's explicit steps require an `it/` integration test and the regenerated staff-web d.ts. Those files were changed because the explicit steps cannot be satisfied otherwise. Files changed outside the literal scope: `apps/api/src/test/java/com/aurevia/smartclinic/it/exchange/order/OrderListIntegrationTest.java` and `apps/staff-web/src/api/generated/openapi.d.ts`. The swarm `scope` is planner collision metadata, not an enforced file allowlist.
- Environment-only findings (no repo change): PyYAML/pip missing; `opencode.json` CI profile under skip-worktree. None is caused by this story.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Maven/Surefire/JaCoCo, the contract gate, the staff-web generator and the repository policy scripts. Reviewer evidence: the named specialist skills were loaded and their rules applied; no separate reviewer run was dispatched. Simulated-user evidence: none. Human/clinical evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.
