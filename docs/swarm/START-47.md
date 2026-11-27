# START-47 · API: negative-path and tenant-isolation tests for the package `chart`

PR #1785, tested branch tip e1fbbf931883848e4680841461cee9589a9f902b.

Task: START-47

Swarm worker 4. Checks: passed. Local checks: failed. Agent result: ok.

# START-47 — API: negative-path and tenant-isolation tests for the package `chart`

## Summary

One new HTTP-entry test class proves the chart slice fails closed on invalid input, wrong role, cross-tenant identifiers and stale versions. Test-only change: no production code, existing test, contract, migration, dependency or threshold edit.

## Affected requirements

CLN-07, ARC-02 (context: ARC-03, ARC-04). CLN-07's PDF/image association has no route in `com.aurevia.chart.record`; see Findings.

## Risk class

Safety-critical (tenant isolation, clinical chart access) with a test-only blast radius.

## Review owner

Primary agent; read-only specialists named by the brief (plan, code, coverage, security).

## Data changes

None. No migration, RLS change, backfill, lock or volume impact.

## Authorization boundaries and audit events

No new authorization rule or audit event. The tests assert the existing `ChartActionPolicy` role matrix and the existing `AuditAppender` calls reached through the real `ChartService`.

## Migration and rollout considerations

None. Test-only change on branch `swarm/START-47-1733`.

## Skills loaded

- `aurevia-delivery`: every story — swarm worker loop, checkpoint and verdicts.
- `aurevia-senior-backend`: chart controller/service/repository test slice.
- `aurevia-senior-java-build`: targeted Maven and standalone SpotBugs commands.
- `aurevia-context-optimizer`: chart entry points, symbols and requirement IDs.
- `aurevia-test-engineer`: branch-to-test matrix for negative paths.
- `aurevia-plan-reviewer`: approve test-only plan before first test.
- `aurevia-code-reviewer`: review change after coding.
- `aurevia-coverage-auditor`: coverage evidence after coding.
- `aurevia-devsecops`: tenant isolation, PHI-free assertions and headers.

### Pre-coding plan review — NO-GO, Required gaps resolved before code

- **Scope/package confirmation (Required).** `apps/api/src/test/java/com/aurevia/chart/ChartNegativePathsTest.java` is confirmed as the planned path, not a stale reference: the task `scope:` names it exactly and the senior developer brief step 1 names both path and `package com.aurevia.chart`. `com.aurevia.chart` is an existing business-domain test package root (`chart/clauses`, `chart/record`); a cross-cutting negative-path class legitimately lives there, as `CatalogNegativePathsTest` lives in `com.aurevia.catalog` while production lives in `com.aurevia.web`. No maintainer is reachable in this worker run; the brief is the maintainer-supplied confirmation. Path never existed in Git (`git log`/`git cat-file` check recorded below).
- **Repository stubs (Required).** Every case names the exact `ChartRepository` stub: `hasActiveMembership`/`activeRoles` for clinician, clinical assistant, wrong role, inactive membership and peer-tenant clinician; `patientExists`, `find`, `chart`, `verify`, `lookupCatalog`, `insert` as each path needs. `AuditAppender` is mocked so no SQL runs; `TransactionTemplate` runs the callback.
- **Non-tautological tenant concealment (Required).** Seed one real `ChartEntry`; `entries.find(id)` and `entries.chart(patientId)` answer from `TenantContext.current().organizationId()`: the owner sees the row, a peer tenant gets the forced-RLS empty fold. Assert the peer 404 body contains neither the foreign text nor the owner organization id, and the peer list is `$.entries == []`.
- **Idempotency resolution (Required).** The senior brief explicitly authorizes asserting today's standalone behavior (two 201s, distinct ids, two `insert` calls) and recording the real-filter discrepancy. The real pipeline freezes the first 2xx and a replay reaches `DurableReplayAuthorization`, which returns 404 because `CommandCatalog` has no chart route; that is recorded under Findings/Why-this-is-not-finished with a task-request, never as a failing test.
- **Ordering and preconditions (Required).** Wrong-role/inactive/cross-tenant verify calls carry a valid `If-Match: "1"`; wrong-role record bodies are otherwise valid; unreadable JSON is a separate 400 `MALFORMED_REQUEST_BODY` case; a named empty-list test asserts `$.entries == []`.
- **Two 412 paths and 409 precedence (Required).** Named tests for stale version (`existing.version() != expected`), CAS false (`entries.verify(...)` stubbed to `false`), already verified at the expected version (409), and stale+VERIFIED still 412 (service documents that precedence).
- **Exact gates and verdicts (Required).** Exact commands recorded under Test evidence; `## Review verdicts` lists plan, code, coverage and security after fixes.

- **aurevia-senior-backend — GO.** Harness is faithful with four chart adaptations: `ChartService`'s 5th `Clock` argument fixed for determinism; `MockEnvironment.setActiveProfiles("synthetic-auth")` plus both synthetic headers on every request; `.setControllerAdvice(new ApiErrorHandler())`; raw JSON `RecordEntryCommand` bodies rather than relying on Jackson Java-time serialization. Exact stub matrix: `authorize` reads membership/roles on the mocked `ChartRepository` (`false` default silently turns 404 into 403); `record` additionally needs `patientExists=true` and optionally `lookupCatalog`; `verify` needs a valid path UUID and `If-Match` before any authorization; `get` needs `find`; `listForPatient` needs `chart` only (no `patientExists`, so a cross-tenant list is 200 with `{"entries":[]}`, never 404). `validate()` runs **before** `authorize()`, so every wrong-role record body must set `clinicalAuthor` to that caller's own subject or validation yields 422 instead of 403. Codes confirmed for every candidate path; two keyed POSTs produce two 201s, distinct ids and two `insert` calls in the standalone pipeline. No CLN-07 route in `com.aurevia.chart.record`.

- **aurevia-test-engineer — GO.** Branch matrix delivered T1–T6: T1 missing/oversize/malformed values at HTTP with `$.code` (includes `codeSystem >120` → `INVALID_CODE`, the only branch with no test anywhere); T2 UNKNOWN/pairing/period/VITAL/author branches at the HTTP contract; T3 wrong-role and inactive membership on all four routes plus `patientExists=false` → 404 and get/list denial with no HTTP test today; T4 both 412 paths, 409 precedence and both `IfMatch.parse` failures; T5 non-tautological tenant concealment body assertion; T6 repeated key documents append behavior. Do not re-add unit-only branches already covered by `ChartRecordServiceTest`, `IfMatchTest`, `ChartRecordActionPolicyTest`, `ChartRepositoryVerifyTest` or the integration/clause suites. No real bug found; no failing test warranted.

## Tested revision

Tested revision: dd0c6bd881c94af12d6807649f937b09335fd9bd

(HEAD at the time every command below ran; the scoped commit follows, so this is an ancestor of the published head.)

## Test evidence

Evidence source: **tool** (commands executed in this checkout) unless labelled reviewer. All commands run from the repository root; scratch output under `/tmp/aurevia-orca.*`.

- Baseline before the new file: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ChartRepositoryVerifyTest test` — EXIT=0; Surefire `tests="2" errors="0" skipped="0" failures="0"`.
- Red/acceptance gap: the scope file was absent (`git cat-file -e HEAD:apps/api/src/test/java/com/aurevia/chart/ChartNegativePathsTest.java` → `fatal: path … does not exist in 'HEAD'`; `git log --oneline -1 -- <path>` empty). First run of the new class: `Tests run: 12, Failures: 6, Errors: 0` — all six were my missing `"clinician"` role argument producing 403; corrected to `active(CLINICIAN_A, "clinician")`. Second run: `Tests run: 12, Failures: 1` — an audit count of 1 where both the 409 and 412 paths log `CONFLICT`; corrected to `times(2)`. No production behavior failed.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=ChartNegativePathsTest test` — EXIT=0. Surefire XML final line: `<testsuite … name="com.aurevia.chart.ChartNegativePathsTest" time="3.441"
