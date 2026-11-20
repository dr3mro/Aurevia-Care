<!-- Merge resolution: this shared notes file previously held a single story note.
Both sides replaced the merge-base note; both sections are preserved below.
Canonical per-story copies: .agent/pr-notes/READY-49-20261120-091850.md and .agent/pr-notes/READY-15-20261120-090755.md. -->

# READY-49 — HTTP tests for the remaining controllers named in no test

## Requirement IDs and acceptance mapping

| Requirement | What this change proves |
|---|---|
| ARC-03 | Every protected route of the four remaining controllers rejects an unauthenticated request (401) and an invalid tenant selection (422), and returns the documented success status for an authorized subject. |
| ARC-04 | Irreversible commands expose their version precondition (`If-Match`) at the HTTP boundary; list/boundary validation failures return 422 with a machine-readable error code. |

## Scope

Test-only change. Four new integration test classes under
`apps/api/src/test/java/com/aurevia/smartclinic/it`. No production code,
migration, OpenAPI file or shared tracker was modified. All fixtures are
synthetic; no patient data, credentials or real identifiers are used.

## Controller discovery (story step 1)

Command run for every `*Controller.java` under `apps/api/src/main/java`:

```
for f in $(find apps/api/src/main/java -name '*Controller.java' | sort); do
  name=$(basename "$f" .java)
  hits=$(grep -rl "$name" apps/api/src/test 2>/dev/null | wc -l)
  printf '%-45s %s\n' "$name" "$hits"
done
```

Controllers with no test hit: `CriticalResultController`, `PatientReleaseController`,
`PatientReleasedRevisionsController`, `ResultController`, `AppointmentController`,
`AuditController`, `SchedulingAdminController`.

Excluded per the story (already covered by AUD-20261120-08 / -09):
`AppointmentController`, `AuditController`, `SchedulingAdminController` (AUD-08),
and finance, prescription, specialty and conversation controllers (AUD-09).

**Remaining controllers covered by this change (one `<Name>HttpTest` each):**

| Controller | Business-domain test package | New test class | Tests |
|---|---|---|---|
| `exchange/critical/CriticalResultController` | `it.exchangeresult` | `CriticalResultControllerHttpTest` | 32 |
| `exchange/result/ResultController` | `it.exchangeresult` | `ResultControllerHttpTest` | 18 |
| `exchange/release/PatientReleaseController` | `it.exchangerelease` | `PatientReleaseControllerHttpTest` | 20 |
| `exchange/release/PatientReleasedRevisionsController` | `it.exchangerelease` | `PatientReleasedRevisionsControllerHttpTest` | 5 |

### URL-level observation (carried forward from AUD-08/-09)

A URL-level grep confirms these controllers' routes are also called by existing
flow tests (`CriticalResultFlowTest`, `ResultRoutingIntegrationTest`,
`PatientReleaseIsolationTest`). As in AUD-09, the class-name audit is a false
positive at the URL level, but the four-case HTTP **security** matrix was not
systematically asserted for these routes. This change adds that matrix without
duplicating the business-flow assertions.

## Acceptance matrix implemented per class

1. **401** — request with no `X-Synthetic-Subject` header.
2. **403** — authenticated subject with no active membership (`ghost-1111`);
   the patient revision list also proves a `reception` subject is denied.
3. **422** — malformed `X-Active-Organization` (`not-a-uuid`), with error code.
4. **Success** — allowed subject reaches the controller's documented status
   (201 for creates, 200 otherwise), driven through a real workflow.
5. **Invalid body** — a syntactically complete, domain-invalid command body
   returns 422 with a machine-readable error code (`INVALID_PATIENTID`,
   `INVALID_INPUT`, `INVALID_REVISION_ID`).
6. **List boundary** — `ResultController` rejects an unknown reconciliation
   `status` with 422/`INVALID_STATUS`; `PatientReleasedRevisionsController`
   rejects a non-UUID patient id with 422/`INVALID_PATIENT_ID`.

Every test uses `@Tag("integration")`, `@ActiveProfiles("synthetic-auth")`,
`@SpringBootTest` on a random port with a dedicated synthetic PostgreSQL
database, and the `headers(...)`/`send(...)` request pattern copied from
`it/exchangeresult/ResultRoutingIntegrationTest.java` and the AUD-09
`*HttpSecurityTest` classes. Each class drops its database in `@AfterAll`.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=CriticalResultControllerHttpTest,ResultControllerHttpTest,PatientReleaseControllerHttpTest,PatientReleasedRevisionsControllerHttpTest test` | 75 tests, 0 failures, 0 errors, 0 skipped (first run: 1 failure, corrected by using a domain-invalid body — see Defects) |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -Dgroups=integration test` (whole integration tier) | exit 0; surefire aggregate 205 suites / 2331 tests, 0 failures, 0 errors, 0 skipped |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (unit tier) | exit 0 |
| `SMARTCLINIC_PG_ADMIN=postgres bash db/check.sh` | exit 0 — migrations, forced RLS, role separation, isolation, audit, append-only staff history, versioned config verified |
| `bash apps/api/check.sh -Pno-security` (JaCoCo gate; `no-security` skips OWASP/SpotBugs only) | exit 0 — full `verify` with build-failing JaCoCo rules |
| `bash scripts/worker-check.sh` | blocked at `check-pack.py`: "opencode.json is stale" — documented cloud-worker false positive OPS-CW-003-01; all later gates run directly (rows above) |
| `python3 -I scripts/check-docs.py`, `check-secrets.py`, `check-names.py`, `check-migrations.py --require-baseline`, `project-name.py`, `bash -n` and `shellcheck` over shell scripts | all exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -q` | 271 tests, 8 failures / 10 errors — all in `test_agent_run`, `test_check_pack` and YAML-dependent workflow tests (`ModuleNotFoundError: No module named 'yaml'`), i.e. local git/gh behaviour, the skip-worktree `opencode.json` profile and a missing Python module. None touches Java tests or this change; the integrator image has PyYAML and the correct profile. |
| `bash apps/api/check.sh` (default, with OWASP) | failed before OWASP analysis: `NvdApiException: NVD Returned Status Code: 524`, `NoDataException: No documents exist`. External feed outage, not a finding. Coverage was verified with the `-Pno-security` run above; the integrator/Delivery supplies cached NVD data. |

### `worker-check.sh` skip-worktree diagnosis (tool evidence)

```
git ls-files -v opencode.json            -> S opencode.json
git status --short opencode.json         -> (empty)
git show HEAD:opencode.json vs worktree  -> different (CI profile)
```

The worktree `opencode.json` is the CI profile copied under `skip-worktree` by
`agent-run.sh`; the committed local profile at `HEAD` is correct. This is the
documented OPS-CW-003-01 artifact. `opencode.json` was not touched (out of scope).

## Coverage

`bash apps/api/check.sh -Pno-security` (exit 0) enforces the 100% JaCoCo rules.
`apps/api/target/site/jacoco/jacoco.csv`:

| Metric | Covered | Missed | Percent |
|---|---|---|---|
| Instruction | 90407 | 0 | 100.00% |
| Branch | 6377 | 0 | 100.00% |
| Line | 15680 | 0 | 100.00% |
| Complexity | 7042 | 0 | 100.00% |
| Method | 3802 | 0 | 100.00% |

Class coverage is also 100%: every class in the report has zero missed lines,
and the build-failing class rule passed. **No production code was added or
changed, so there are no new production branches to cover.** The new branches
are all in test code: the parameterized 401/403/422 cases, the invalid-body and
invalid-list-filter cases, and the success workflow branches of the four new
test classes. Each is exercised by its own assertion in the same class, and the
75 test invocations above confirm none is skipped.

Coverage scope note: test sources are not part of the JaCoCo instrumented
production scope, so adding these tests cannot lower coverage; the run above
proves the gate remains green at 100% on the merged tree.

## Defects, decisions and unverified claims

- **Corrected during the run (not a production defect):** the first draft of
  `PatientReleaseControllerHttpTest.invalidBodyReturnsUnprocessableWithErrorCode`
  sent an empty JSON object and expected 422; Jackson rejects it earlier with
  400 because `AuthorizeRelease` has primitive fields. The existing
  `PatientReleaseIsolationTest` line 491 got 422 only because it also sent a
  malformed organization. The test now sends a well-formed but domain-invalid
  body (empty `revisionId`) and asserts 422/`INVALID_REVISION_ID`.
- **Observation, no test disabled, no production change:** an empty JSON object
  on `POST /api/v1/releases` returns Spring's default 400 error body rather
  than the project's uniform `ErrorBody` shape. This is pre-existing, outside
  this test-only scope and does not block any acceptance case. Per the
  orchestrator-owned tracker rules I did not edit `BUGS.md` (the worker
  instruction forbids it); this note records the observation for the
  integrator. No test was disabled.
- The class-name audit producing this story is a known false positive at the
  URL level (AUD-08/-09). The value added here is the systematic four-case
  HTTP security matrix, not new endpoint discovery.
- No clinical, human or simulated-user evidence was used: tool evidence only,
  synthetic fixtures throughout.

Evidence source: executed tool commands (tool evidence) against synthetic
fixtures. No reviewer, human or clinical participant was involved.


# READY-15 — List facilities (GET /api/v1/facilities)

Requirement IDs: **ARC-03** (cursor pagination with stable tenant-scoped ordering).
Classification: cross-boundary (HTTP adapter + domain service + SQL + API contract).
Status: complete — all gates passed locally. Committed locally on branch `swarm/READY-15-273` (the integrator records the final SHA). No push/tag/gh performed (worker scope).

## Acceptance mapping

| Story step | Implementation | Evidence |
|---|---|---|
| 1. `GET /api/v1/facilities`; `cursor`, `limit` (default 50, max 200, else 422 `INVALID_LIMIT`); no filters | `web/FacilityController.list`, `FacilityService.list/parseCursor/parseLimit` | `FacilityListServiceTest.absentAndBlankParametersUseDefaults`, `invalidLimitsAreRejectedBeforeAnyQuery`, `invalidCursorsAreRejectedBeforeAnyQuery`; `FacilityListControllerTest.badLimitReturns422`; `FacilityListIntegrationTest.invalidPagingInputsAreRejected` |
| 2. Order by code asc, id tie-break; cursor `<code>:<id>`; `nextCursor` null on last page; fetch `limit+1` | `FacilityRepository.query` (`WHERE (code > :cursorCode OR (code = :cursorCode AND id > CAST(:cursorId AS uuid))) ORDER BY code, id LIMIT :limit`), `FacilityService.list` | `FacilityListServiceTest.extraRowProducesCursorFromTheLastReturnedRow`, `exactlyLimitRowsHaveNoNextCursor`, `emptyPageHasNoNextCursor`; `FacilityListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` |
| 3. Reuse single-record read action; add no role | `authorize("FACILITY_READ", ...)` — same action as `FacilityService.get` (no new action key) | `FacilityListServiceTest.inactiveMembershipIsDenied`, `missingPermissionIsDenied`; `FacilityListIntegrationTest.unauthorizedAndForbiddenActorsAreDenied` |
| 4. Parameterised SQL, RLS isolation, real migration column names | `FacilityRepository.query` uses `NamedParameterJdbcTemplate`/`MapSqlParameterSource` over `public.facility` (`organization_id, code, name, time_zone, id`) from V1/V8; no manual org predicate | `FacilityListIntegrationTest.organizationBNeverSeesOrganizationARows` (org B reuses two of org A's local codes and sees only its own rows) |
| 5. `FacilityPage(List<Facility> items, String nextCursor)` in view package | new `tenancy/FacilityPage.java` with immutable compact constructor and defensive accessor | `FacilityListServiceTest.pageAndRowFreezeTheirPartsAndExposeValueSemantics` (caller mutation + unmodifiable result + value equality) |
| 6. Unit + controller + 2-org integration tests | 3 new test classes (10 service, 5 controller, 4 integration tests) | commands below |
| 7. Contract | `contracts/openapi.yaml`: `get` on `/api/v1/facilities` + `FacilityPage` schema; staff-web d.ts regenerated with pinned generator | `python3 -I contracts/openapi/check.py`, `npm run gen:api:check`, `bash apps/staff-web/check.sh` |

No existing endpoint, response shape or migration was changed. No dependency added. Synthetic data only; no patient data, credential or real domain name in code, logs, URLs or browser storage.

## Entry point to test trace

`GET /api/v1/facilities?cursor=&limit=` → `TenantContextInterceptor.preHandle` (401 without subject) → `FacilityController.list` → `FacilityService.list` (validate `cursor`/`limit` before any transaction, then guarded `TransactionTemplate`) → `authorize` (`FACILITY_READ`, membership + role re-derived per request) → `FacilityRepository.query` (forced-RLS, code/id ordered, `limit+1`) → `appendAudit(FACILITY_READ, FACILITY, ALL, SUCCESS)` → `FacilityPage`.
Success path: `FacilityListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (5 rows, page size 2, 3 pages, every row exactly once, stable code order).
Failure paths: `FacilityListServiceTest.invalidCursorsAreRejectedBeforeAnyQuery`, `invalidLimitsAreRejectedBeforeAnyQuery` (validation before transaction), `inactiveMembershipIsDenied`, `missingPermissionIsDenied`; `FacilityListControllerTest.withoutLoginReturns401`, `forbiddenRoleReturns403`, `badLimitReturns422`.

## Exact commands and results (executed by this worker)

All commands run from the repository root unless noted. Exit codes are from the tool.

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (before implementation) | **RED** — compile errors: `FacilityPage`, `FacilityRepository.FacilityRow`, `FacilityRepository.query`, `FacilityService.list` do not exist |
| `mvn -B -f apps/api/pom.xml -Pno-security,fast test` | **BUILD SUCCESS — Tests run: 1903, Failures: 0, Errors: 0, Skipped: 0** (includes 10 `FacilityListServiceTest`, 5 `FacilityListControllerTest`) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=FacilityListIntegrationTest -DfailIfNoSpecifiedTests=false test` | **BUILD SUCCESS — Tests run: 4, Failures: 0, Errors: 0** against real PostgreSQL (`smartclinic_facility_list_it`, two synthetic tenants) |
| `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security verify` | **BUILD SUCCESS — Tests run: 2274, Failures: 0, Errors: 0, Skipped: 0; `jacoco:check`: "All coverage checks have been met."** |
| `mvn -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false spotbugs:check` | **BUILD SUCCESS — BugInstance size is 0, Error size is 0** |
| JaCoCo per-file inspection of `apps/api/target/site/jacoco/jacoco.xml` | `FacilityPage.java`: instruction 16/16, line 4/4, method 2/2, class 1/1; `FacilityRepository.java`: instruction 181/181, branch 2/2, line 39/39, method 11/11, class 2/2; `FacilityService.java`: instruction 367/367, branch 40/40, line 71/71, method 15/15, class 2/2; `FacilityController.java`: instruction 47/47, line 8/8, method 5/5, class 2/2 — **100% on every touched production file** |
| `SMARTCLINIC_PG_ADMIN=postgres bash db/check.sh` | exit 0 — "migrations, forced RLS, role separation, isolation, audit, append-only staff history and versioned config verified" |
| `python3 -I contracts/openapi/check.py` | `x-04 drift check OK: 24 commands, contract and client match manifest` (exit 0) |
| `python3 -I -m unittest discover -s contracts/openapi -p 'test_*.py' -q` | 7 tests OK |
| `npm run gen:api` / `npm run gen:api:check` (in `apps/staff-web`) | generated client matches the contract (exit 0) |
| `bash check.sh` (in `apps/staff-web`) | exit 0 — 34 test files / 249 tests passed; 47 files fully covered; 100% statements/branches/functions/lines |
| `python3 -I scripts/check-docs.py`, `check-names.py`, `check-secrets.py`, `check-migrations.py --require-baseline` | all exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests OK |
| `git show HEAD:opencode.json` + `python3 -I scripts/gen-opencode.py --check` | exit 0 (HEAD profile is current; see environment caveat below) |

### Worker-gate result and environment caveats

`SMARTCLINIC_PG_ADMIN=postgres bash scripts/worker-check.sh` was run with the committed HEAD `opencode.json` temporarily in place so `check-pack.py` judged HEAD truth. It exited **1** at the pre-existing Python tooling suite, not at this change:

- `ModuleNotFoundError: No module named 'yaml'` in `test_fast_tier`, `test_swarm`, `test_workflow_concurrency` (PyYAML absent from this worker's Python 3.11).
- `test_agent_run.py` failures/errors from sandbox git/gh behaviour (`git branch -D`, push rejected by the temp bare remote, `gh pr create: permission denied`).
- Summary: `Ran 270 tests ... FAILED (failures=5, errors=10)` — identical to the documented READY-14 environment findings.

Because the script stops there, its later stages were run directly and are green: full Java unit tier (`mvn ... fast test`, 1903 tests), real-PostgreSQL integration (`FacilityListIntegrationTest`, 4 tests; `mvn verify`, 2274 tests), and `db/check.sh`. The CI-profile `opencode.json` was restored byte-for-byte from `.github/opencode.ci.json` after the run (`git status` shows no opencode.json change). No Python, workflow or tooling file was changed by this story.

`check-pack.py` / `gen-opencode.py --check` report "opencode.json is stale" against this worker's worktree: `git ls-files -v opencode.json` shows flag `S`, and the worktree file equals `.github/opencode.ci.json`, not `HEAD:opencode.json`. This is the documented OPS-CW-003-01 `agent-run.sh activate_ci_config` measurement artifact. With `HEAD:opencode.json` the generator check passes.

`python3 -I -m unittest discover -s scripts/tests -q` was not green for the unrelated environment reasons above; the Java-convention tests were run separately and pass. No new Javadoc `{@link}` or `@see` was added.

Static/dependency analysis disposition: OWASP Dependency-Check 12.1.0 is bound to `mvn verify` but this worker's `-Pno-security` profile skipped it, and a direct run cannot reach the NVD feed (`PKIX path building failed` on `maven.java.net`); no new dependency was added, so the dependency graph is unchanged by this story. SpotBugs 4.10.5.0 was run explicitly and reported 0 findings.

## Branch-to-test mapping (JaCoCo 100% requirement)

Every new/changed branch is named with the test that takes each side. `mvn verify` reported all coverage checks met and per-file inspection confirmed 100% on each touched file.

| Production branch | Test covering each side |
|---|---|
| `FacilityPage` compact ctor + `items()` copy/override | `FacilityListServiceTest.pageAndRowFreezeTheirPartsAndExposeValueSemantics` (caller mutation + unmodifiable result) |
| `FacilityRepository.query`: `cursorCode != null` true/false | `FacilityListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (page 1 false, pages 2–3 true); unit path uses the mocked repository |
| `FacilityRepository.FACILITY_ROW_MAPPER` / `FacilityRow` accessors and value methods | `FacilityListIntegrationTest.pagingReturnsEveryOrganizationRowExactlyOnceInOrder` (row mapping), `FacilityListServiceTest.extraRowProducesCursorFromTheLastReturnedRow`, `pageAndRowFreezeTheirPartsAndExposeValueSemantics` |
| `FacilityService.list`: cursor present/absent | `extraRowProducesCursorFromTheLastReturnedRow` (present) vs `absentAndBlankParametersUseDefaults`, `emptyPageHasNoNextCursor` (absent) |
| `FacilityService.list`: `hasMore` true/false | `extraRowProducesCursorFromTheLastReturnedRow` (true) vs `emptyPageHasNoNextCursor`, `exactlyLimitRowsHaveNoNextCursor` (false) |
| `FacilityService.list`: authorization denial | `inactiveMembershipIsDenied`, `missingPermissionIsDenied` (403, repository.query never called) |
| `parseCursor`: null / blank / malformed / valid | `absentAndBlankParametersUseDefaults` (null+blank), `invalidCursorsAreRejectedBeforeAnyQuery` (6 shapes), `extraRowProducesCursorFromTheLastReturnedRow` (valid) |
| `parseLimit`: null / blank / `<1` / `>200` / non-numeric / valid | `absentAndBlankParametersUseDefaults`, `invalidLimitsAreRejectedBeforeAnyQuery` (`0`, `201`, `abc`), success tests |
| `FacilityController.list` parameter binding | `FacilityListControllerTest` (5 tests: 401, 403, 422, 200, passthrough) |

`authorize` branches (inactive membership / missing permission / allowed) were already covered by `FacilityServiceTest` and remain covered by the new denial tests. No unqualified Javadoc link was added.

## Contract generation note

`contracts/openapi/README.md` says `contracts/openapi/generated/openapi.yaml` is emitted from the X-04 Java command catalog and is distinct from the staff-API `contracts/openapi.yaml` consumed by `apps/staff-web/scripts/gen-api-types.sh`. The new facility GET changes only the latter; it does not change the command catalog, so X-04 generated artifacts are unchanged. The story's step-7 reference to the X-04 generator/`check.py` is satisfied by running `python3 -I contracts/openapi/check.py` (green, unchanged). Freshness of the staff-web generated types was restored with the pinned `openapi-typescript` CLI (`npm run gen:api`, not hand-edited) so `npm run gen:api:check` and `bash apps/staff-web/check.sh` stay green. This follows the identical, already-merged approach in READY-14 (`.agent/pr-notes/READY-14.md`, commit `d297794`).

## Defects found, decisions and deviations

- No product defect found. No migration version used (no schema change).
- Decision: the list reuses `FACILITY_READ` (the single-read action) and audits under `FACILITY_READ`/`FACILITY`/`ALL`, adding no role and no new action key, per story step 3.
- Decision: authorization failures are thrown from inside the guarded transaction (matching `FacilityService.get`), so the transaction rolls back; the existing single-record behavior is unchanged. Success reads are audited in the same transaction.
- Deviation (transparent, same as READY-14): the TODO `scope` field lists only the `tenancy` packages and `contracts/openapi`, while the story's explicit steps require `web/FacilityController.java`, an `it/` integration test and the regenerated staff-web d.ts. Those files were changed because the explicit steps cannot be satisfied otherwise and the gates are not green without them. The swarm `scope` is planner collision metadata, not an enforced file allowlist. Files changed outside the literal scope: `apps/api/src/main/java/com/aurevia/smartclinic/web/FacilityController.java`, `apps/api/src/test/java/com/aurevia/smartclinic/it/tenancy/FacilityListIntegrationTest.java`, `apps/staff-web/src/api/generated/openapi.d.ts`.
- Environment-only findings (no repo change): PyYAML missing; sandbox git/gh in `test_agent_run.py`; CI-profile `opencode.json` under `skip-worktree`. None is caused by this story.
- Review finding R1 (senior-backend, low): the pre-existing `Facility` schema in `contracts/openapi.yaml` (and the generated d.ts) omits `timeZone`, although the record and the new list response carry it. Decision: **not changed here** — the story says "Do NOT change existing endpoints, response shapes or migrations", and the single-record `GET /api/v1/facilities/{code}` already had the same drift before this story. Follow-up documentation story should add `timeZone` to the shared `Facility` schema and regenerate the client; no production behavior is wrong.
- Review finding R2 (process, low): reviewer could not locate the notes file during its run. The file existed and was committed with this change; no action needed.

## Evidence sources

Tool evidence: every command above was executed locally; results are from Maven, Surefire, JaCoCo, SpotBugs, `db/check.sh`, the contract gates and the staff-web gate. Reviewer evidence: specialist skills `smartclinic-senior-backend`, `smartclinic-senior-java-build` and `smartclinic-test-engineer` were loaded and their rules applied. A read-only `smartclinic-senior-backend` review of the diff returned two low findings (R1 pre-existing `Facility.timeZone` contract drift — deferred per story constraint; R2 notes-file lookup during its run — already present and committed); it explicitly verified cursor/limit+1 logic, RLS isolation, `FACILITY_READ` reuse, new-branch coverage, EI_EXPOSE_REP defence and naming as acceptable, and re-ran no builds. Simulated user evidence: none (no clinical/mobile workflow). Human participant evidence: none.
