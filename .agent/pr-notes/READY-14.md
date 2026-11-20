# READY-14 — List staff (GET /api/v1/staff)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).

## Acceptance mapping

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/staff`; `cursor`, `limit` (default 50, max 200, else 422 `INVALID_LIMIT`), `status` (`active`/`deactivated`, else 422) | `web/StaffController.list`, `StaffService.list/parseCursor/parseLimit/parseStatus` | `StaffListServiceTest`, `StaffListControllerTest`, `StaffListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. Order by subject asc, id tie-break; cursor `<subject>:<id>`; `nextCursor` null on last page; fetch `limit+1` | `StaffRepository.query` (`ORDER BY u.subject, u.id LIMIT :limit`), `StaffService.list` | `StaffListServiceTest.extraRowProducesCursorFromTheLastReturnedRow`, `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-record read action; add no role | `authorize(context, "STAFF_READ", "ALL")` — same action as `StaffService.get` | `StaffListServiceTest.inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited`; `StaffListIntegrationTest.unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real migration column names | `StaffRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `user_identity`, `membership`, `role_assignment`, `current_membership()` from V1/V4; no manual org predicate | `StaffListIntegrationTest.organizationBNeverSeesOrganizationARows`, `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 5. `StaffPage(List<StaffMember> items, String nextCursor)` in view package | new `staff/StaffPage.java` | `StaffListServiceTest.pageAndRowFreezeTheirPartsAndExposeValueSemantics` |
| 6. Unit + controller + 2-org integration tests | 3 new test classes, 21 new tests | commands below |
| 7. Contract | `contracts/openapi.yaml`: `get` on `/api/v1/staff` + `StaffPage` schema; staff-web generated d.ts regenerated with pinned generator | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check`, `bash apps/staff-web/check.sh` |

No existing endpoint, response shape or migration was changed. No dependency added. Synthetic data only.

## Entry point to test trace

`GET /api/v1/staff?cursor=&limit=&status=` → `TenantContextInterceptor.preHandle` (401 without subject) → `StaffController.list` → `StaffService.list` (validate then guarded `TransactionTemplate`) → `authorize` (`STAFF_READ`, re-derived membership/roles) → `StaffRepository.query` (forced-RLS, subject/id ordered, `limit+1`) → `appendAudit(STAFF_READ, ALL, SUCCESS)` → `StaffPage`.
Success path: `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder`.
Failure paths: `StaffListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `StaffListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`.

## Exact commands and results (executed by this worker)

All commands run from the repository root unless noted. Exit codes are from the tool.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (before implementation) | RED — compile errors: `StaffPage`, `StaffRepository.StaffRow`, `StaffService.list`, `StaffController.list` do not exist |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test` | **BUILD SUCCESS — Tests run: 1875, Failures: 0, Errors: 0, Skipped: 0** (includes 11 `StaffListServiceTest`, 5 `StaffListControllerTest`) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=StaffListIntegrationTest -DfailIfNoSpecifiedTests=false test` | **BUILD SUCCESS — Tests run: 5, Failures: 0, Errors: 0** against real PostgreSQL |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | **BUILD SUCCESS — Tests run: 2123, Failures: 0, Errors: 0, Skipped: 0; `jacoco:check`: "All coverage checks have been met."** |
| `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false spotbugs:check` | **BUILD SUCCESS — BugInstance size is 0** |
| `SMARTCLINIC_PG_ADMIN=postgres bash db/check.sh` | exit 0 — migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `npm run gen:api:check` (in `apps/staff-web`) | `gen:api: generated client matches the contract` after `npm run gen:api` regeneration |
| `bash check.sh` (in `apps/staff-web`) | exit 0 — 33 test files / 235 tests passed, 100% statements/branches/functions/lines (46 files) |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline` | all exit 0 |

### Worker-gate result and environment caveats

`SMARTCLINIC_PG_ADMIN=postgres bash scripts/worker-check.sh` was run with the committed HEAD `opencode.json` temporarily restored (see below). It exited **1** on the pre-existing Python tooling suite, not on this change:

- `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier`, `test_swarm`, `test_workflow_concurrency` (PyYAML absent from this worker's Python 3.11).
- `test_agent_run.py` failures/errors from sandbox git/gh behaviour (`git branch -D opencode/issue-5-9` nonzero, push rejected by the temp bare remote, `gh pr create: permission denied`).

The gate passed every stage relevant to this story before that point: `check-pack` ("Delivery tooling validated: 33 agents, 33 skills"), docs/secrets/names/migrations policy, and the full Java unit tier. The Java and database portions were additionally run directly as shown above (full `mvn verify` with JaCoCo, the new integration class, and `db/check.sh`) and are green. No Python, workflow or tooling file was changed by this story.

`check-pack.py` reports "opencode.json is stale" when run against this worker's worktree: `git ls-files -v opencode.json` shows flag `S`, and the worktree file equals `.github/opencode.ci.json`, not `HEAD:opencode.json`. This is the documented OPS-CW-003-01 `agent-run.sh activate_ci_config` measurement artifact. For the worker-gate run only, the HEAD profile was restored with `git update-index --no-skip-worktree` + `git checkout -- opencode.json`, then the CI profile and its `skip-worktree` flag were restored byte-for-byte. No committed config was modified (`git status` shows only this story's files).

## Branch-to-test mapping (JaCoCo 100% requirement)

Every new/changed branch is named with the test that takes each side. Full `mvn verify` reported all coverage checks met.

| Production branch | Test covering each side |
|---|---|
| `StaffPage` compact ctor + `items()` copy/override | `StaffListServiceTest.pageAndRowFreezeTheirPartsAndExposeValueSemantics` (caller mutation + unmodifiable result) |
| `StaffRepository.query`: `cursorSubject != null` true/false | `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (page 1 false, pages 2–3 true) |
| `StaffRepository.query`: `membershipStatus != null` true/false | `StaffListIntegrationTest.statusFilterSelectsOnlyMatchingMembers` (true) and `pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (false) |
| `StaffRepository.mapRow` and `StaffRow` accessors/value methods | `StaffListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (row mapping), `StaffListServiceTest.extraRowProducesCursorFromTheLastReturnedRow`, `pageAndRowFreezeTheirPartsAndExposeValueSemantics` |
| `StaffService.list`: cursor present/absent | `extraRowProducesCursorFromTheLastReturnedRow` (present) vs `absentAndBlankParametersUseDefaults`, `emptyPageHasNoNextCursor` (absent) |
| `StaffService.list`: `denial != null` true/false and `failure.get() != null` | `inactiveMembershipIsDeniedAndAudited`, `missingPermissionIsDeniedAndAudited` (true) vs all success tests (false) |
| `StaffService.list`: `hasMore` true/false | `extraRowProducesCursorFromTheLastReturnedRow` (true) vs `emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor` (false) |
| `parseCursor`: null / blank / malformed / valid | `absentAndBlankParametersUseDefaults` (null+blank), `invalidCursorsAreRejectedBeforeAnyQuery` (6 shapes), `extraRowProducesCursorFromTheLastReturnedRow` (valid) |
| `parseLimit`: null / blank / `<1` / `>200` / non-numeric / valid | `absentAndBlankParametersUseDefaults`, `invalidLimitsAreRejectedBeforeAnyQuery` (`0`, `201`, `abc`), success tests |
| `parseStatus`: null / blank / `active` / `deactivated` / invalid | `absentAndBlankParametersUseDefaults`, `statusFiltersMapToDatabaseMembershipStatus`, `invalidStatusesAreRejectedBeforeAnyQuery` |
| `StaffController.list` parameter binding | `StaffListControllerTest` (5 tests: 401, 403, 422, 200, passthrough) |

Javadoc uses fully qualified `{@link}` targets only where links exist; no unqualified link was added. `python3 -I -m unittest discover -s scripts/tests` was not green in this container for the unrelated environment reasons above; the Java-convention tests inside that suite were not needed for this change (no new Javadoc links).

## Contract generation note

`contracts/openapi/README.md` states that `contracts/openapi/generated/openapi.yaml` is emitted from `CommandClientGenerator.java`/`CommandCatalog.java` and is distinct from the staff-API `contracts/openapi.yaml` consumed by `apps/staff-web/scripts/gen-api-types.sh`. The new staff GET changes only the latter; it does not change the command catalog, so the generated X-04 artifacts are unchanged. Freshness of all three X-04 artifacts is proven by `python3 -I contracts/openapi/check.py` and by `CommandClientGeneratorTest.committedArtifactsMatchAFreshGeneration` inside `mvn test`. The dependent staff-web `src/api/generated/openapi.d.ts` **was** regenerated with the pinned `openapi-typescript` CLI (not hand-edited) so `npm run gen:api:check` stays green.

## Defects found, decisions and deviations

- No product defect found. No migration version used (no schema change).
- Decision: the list reuses `STAFF_READ` (the single-read action) and audits under `STAFF_READ`/`ALL`, adding no role and no new action key, per story step 3.
- Decision: `status=deactivated` maps to the database's `revoked` membership state at the service boundary; the wire contract keeps the user-facing word `deactivated`.
- Deviation (transparent): the TODO `scope` field lists only the `staff` packages and `contracts/openapi`, while the story's explicit steps require `web/StaffController.java`, an `it/` integration test and the regenerated staff-web d.ts. Those files were changed because the explicit steps cannot be satisfied otherwise and the build is not green without them. The swarm `scope` is planner collision metadata, not an enforced file allowlist (`scripts/swarm.py:guard` checks protected infra files only). Files changed outside the literal scope: `apps/api/src/main/java/com/aurevia/smartclinic/web/StaffController.java`, `apps/api/src/test/java/com/aurevia/smartclinic/it/staff/StaffListIntegrationTest.java`, `apps/staff-web/src/api/generated/openapi.d.ts`.
- Environment-only findings (no repo change): PyYAML missing; sandbox git/gh in `test_agent_run.py`; CI-profile `opencode.json` under `skip-worktree`. None is caused by this story.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Maven, Surefire, JaCoCo, SpotBugs, `db/check.sh`, the contract gate and the staff-web gate. Reviewer evidence: specialist skills `smartclinic-senior-backend`, `smartclinic-test-engineer` and `smartclinic-senior-database` were loaded and their rules applied; no separate reviewer run was dispatched for this bounded slice. Simulated user evidence: none (no clinical/mobile workflow). Human participant evidence: none. All fixtures are synthetic; no patient data, credential or real domain name appears in code, logs, URLs or browser storage.
