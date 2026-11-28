# START-53 · API: negative-path and tenant-isolation tests for the package `exchange`

PR #1820, tested branch tip 5dbb6b0115b474bce577bec955b5e60852bb14b5.

Task: START-53

Swarm worker 4. Checks: passed. Local checks: failed. Agent result: ok.

# START-53 — API: negative-path and tenant-isolation tests for the package `exchange`

## Summary

Test-only FIX/DOC slice. One new test class `apps/api/src/test/java/com/aurevia/exchange/ExchangeNegativePathsTest.java` (package `com.aurevia.exchange`) is the executable record of the exact status/code a caller sees for exchange refusal paths. No production code, migration, contract, route, dependency or config change. No real patient data.

## Affected requirements

- XCH-01 — order release, snapshot, disclosure and receiver work-item projection (`PROJECT_V1_SPEC.md:63`).
- ARC-02 — tenant isolation, forced RLS, fail closed when context is missing (`PROJECT_V1_SPEC.md:118`).
- Supporting contract semantics: ARC-03 (`PROJECT_V1_SPEC.md:120`) and ARC-04 (`PROJECT_V1_SPEC.md:122`) define 401/403/404/409/412/422.

## Risk class

Cross-boundary by subject (tenant concealment, roles, stale writes), but test-only: no runtime boundary changes. Authoritative style is the neighbour tests (`MockMvcBuilders.standaloneSetup` with `ApiErrorHandler` + `TenantContextInterceptor`, synthetic-auth headers) and mocked repositories, matching the in-scope package's fixture style. Real-PostgreSQL forced-RLS concealment is owned by `com.aurevia.it` and is out of scope.

## Review owner

Primary agent; read-only reviewers `plan-reviewer`, `code-reviewer`, `coverage-auditor`, `devsecops` (plus `qa-reviewer` per the active worker prompt). Named domain advisors: `context-optimizer`, `senior-backend`, `test-engineer`, `senior-java-build`, `devsecops`.

## Data changes

None. No Flyway migration, RLS policy, backfill, lock or volume impact.

## Authorization boundaries and audit events

Assertions only: inactive membership / wrong role → 403 `FORBIDDEN` plus `DENIED` audit and no write; foreign/absent id → 404 `NOT_FOUND` with no record fields in the body; stale version → 412 `STALE_VERSION`; repeated natural key with a different clinical payload → 409 `PAYLOAD_CONFLICT`; malformed input → 422 with no repository call. No audit event is added or changed.

## Migration and rollout considerations

None.

## Covered-vs-gap inventory (step 1 — before code)

All paths listed in the story were grepped against `apps/api/src/test/java/com/aurevia/exchange` and `apps/api/src/test/java/com/aurevia/it`.

| Refusal family | Producing code | Existing coverage | Gap candidate |
|---|---|---|---|
| Missing/blank/overlong/malformed input → 422 | `OrderInputs.text/uuid/priority/bounded`, `ExchangeResponseInputs.*`, `CriticalInputs.*`, `ExchangeTaskService.orderRef/reason/id`, `ResultRoutingService.code/uuid/kind/instant` | `it/exchangeorder/OrderInputsTest` (all four methods, both sides); `it/exchangetask/ExchangeResponseSupportTest`; `it/exchangeresult/CriticalContractTest`; `it/exchangeresult/ResultRoutingServiceTest.validatesEveryBoundaryInputBeforePersistence` + `rejectsNullInputsAtTheBoundary` + `rejectsBlankAndOversizedRegistrationFields`; `it/exchangetask/ExchangeTaskServiceTest.createRejectsBlankOversizedMalformedAndNullOrderReference` + `rejectAndCancelRequireAReasonAndOnlyTheyDo` + `malformedIdIsUnprocessable`; `OrderListServiceTest.invalidCursors/limits/statusesAreRejectedBeforeAnyQuery`; clause tests (`OrderIncludesLocalPatientEncounterOrderCodeTest`, `SnapshotReleasedContentStampIdentifiersVersionsRecordTest`) | Validator branches are fully covered. A single fast class in `com.aurevia.exchange` that asserts 422 **and no repository call** in one place is consolidation, not a new branch. |
| Wrong role / inactive membership → 403 + `DENIED` audit + no write | `OrderService.deny` (`OrderService.java:377-387`), `ExchangeTaskService.authorize` (`ExchangeTaskService.java:178-189`), `ResultRoutingService.authorize` (`:448-459`) | `it/exchangetask/ExchangeTaskServiceTest.createDeniesInactiveMembershipAndMissingRole` + `acknowledgeDeniesNonClinicianAndInactiveMembership` + `readAuditsSuccessAndIsDeniedWithoutPermission`; `OrderListServiceTest.inactiveMembershipIsDeniedAndAudited` + `missingPermissionIsDeniedAndAudited`; `it/exchangeresult/ResultRoutingServiceTest.registrationDeniesInactiveMembershipAndWrongRole` + `importDeniesInactiveMembershipAndWrongRole` + `getReportDeniesRolesWithoutClinicalContentAccess`; `ReceiverSeesExchangeSpecificWorkItemLocallyTest.receptionRoleCannotListOrVerify` | Covered at both sides of `authorize`/`deny`, with `DENIED` audit and `never()` write assertions. |
| Foreign/absent id → 404 `NOT_FOUND` | `OrderService.get` (`:202-203`), `ExchangeTaskService.get`/`execute` (`:117-118`, `:152-153`), `ResultRoutingService.getReport` (`:197-200`) | `it/exchangetask/ExchangeTaskServiceTest.illegalTransitionAndMissingRecordAreRejected` (both empty `loadForUpdate` and empty `find`); `it/exchangeresult/ResultRoutingServiceTest.concealsMissingRevisionsAndReviewTasks`; `ReceiverSeesExchangeSpecificWorkItemLocallyTest.otherTenantsWorkItemCannotBeVerified`; `SharingOrderGrantsGeneralChartAccessTest.sharingDoesNotExposeSenderOrderToReceiver` (standalone MockMvc, `$.code == NOT_FOUND`); `it/exchangetask/ExchangeTaskIsolationTest.authorizationPreconditionsAndTenantConcealment` (real PostgreSQL, forced RLS) | **Possible genuine gap:** no assertion that the concealed 404 *body contains only the error envelope* — no `id`, `patientRef`, `orderRef`, `fingerprint`, `workflowState`, etc. All existing 404 tests assert status and `$.code` only. |
| Stale version → 412 `STALE_VERSION` | `ExchangeTaskService.requireVersion`/`staleVersion` (`:191-199`) and `updateState == 0` (`:156-157`); `ResultRoutingService.acknowledge` (`:243-249`) | `it/exchangetask/ExchangeTaskServiceTest.staleVersionAndLostUpdateAreConflictsWithoutHistory` covers **both** sides deterministically; `it/exchangeresult/ResultRoutingServiceTest.refuseRepeatAndStaleAcknowledgments`; `AcknowledgmentMadeAuthorizedHumanTimestampRevisionTest` covers both CAS sides; multiple clause tests assert HTTP 412 | Covered. |
| Repeated natural key, different payload → 409 `PAYLOAD_CONFLICT` | `ResultRoutingService.replayOrConflict` (`:425-435`) | `it/exchangeresult/ResultRoutingServiceTest.aReplayWithDifferentContentIsRejectedInsteadOfSilentlyDiscarded` (different checksum and different effectiveAt), `aConcurrentWinnerWithDifferentContentIsRejectedInsteadOfReturned`, `aReplayWithDifferentPatientIsRejectedInsteadOfReturned`, `aConcurrentConflictWithNoVisibleWinnerFailsClosedWithAConflict`; `acceptance/exchange/FinalReportReplayIntegrityAcceptanceTest` | Covered on the replay, concurrent-winner and missing-winner sides. |
| `Idempotency-Key` HTTP replay | `com.aurevia.web.IdempotencyFilter` | `it/idempotency/IdempotencyLedgerHttpIntegrationTest` (plus clause replay tests) | Owned by `com.aurevia.web`; explicitly out of scope. |

**Conclusion after step 1:** every status/code branch the story lists is already exercised somewhere. The only candidate not asserted anywhere is the *shape of the concealed 404 error body* (no record fields), plus the value of a single fast, self-contained DOC-style class in the target package. Specialists were asked to confirm or reject this before any code is written.

## Clinical and architecture review

No signed clinical data, release, prescribing or live cross-organization payload was added or changed; the only artifact is a test class using synthetic identifiers and marker strings. Architecture evidence is bounded by design: a repository stub returning `Optional.empty()` models the control flow of tenant concealment but executes no SQL, sets no `app.current_org` and uses no `NOBYPASSRLS` role, so it cannot prove PostgreSQL `FORCE ROW LEVEL SECURITY`. That proof remains with the real-PostgreSQL tests `it/exchangetask/ExchangeTaskIsolationTest` and `it/exchangeorder/OrderExchangeIntegrationTest`, explicitly out of scope for this one-file slice. No clinical sign-off is required because no live behavior or patient data is in
