# S-G2-04 — Result routing, identifier matching, reconciliation queue and correction versions

## Requirements and acceptance mapping

Primary: **XCH-04**. Supporting: XCH-02, XCH-05, XCH-06, ARC-01, ARC-02, ARC-03, ARC-04, ARC-05, IAM-01, IAM-06, OPS-02.

| Acceptance | Implementation | Evidence |
|---|---|---|
| Five issuer-scoped identifiers (patient, source, accession, destination, order) matched before import | `ResultRules.match` compares all five; `ResultRoutingService.importGuarded` diverts on `UNMATCHED`/`CONFLICT` | `ResultRulesTest` (7), `ResultRoutingServiceTest`, `ResultRoutingIntegrationTest.unmatchedAndConflictingReportsEnterTheReconciliationQueue` |
| Unmatched/conflicting reports enter a reconciliation queue, no clinical review task | `xch04_reconciliation` + `XCH04_RESULT_RECONCILED` audit; no `insertReview` on diversion | service tests assert no report/review insert; integration asserts 202 + `open` status |
| Final, corrected and critical results routed to a named owner and coverage queue | `ResultRules.route` + `xch04_report_revision.owner_subject/coverage_queue`; critical audit `XCH04_CRITICAL_ROUTE` | `ResultRulesTest.everyDispositionRoutes...`, service test, `matchedResultIsImportedRoutedAndAudited`, `criticalResultIsRoutedWithItsCriticalOwnerAndQueue` |
| Corrections append an immutable revision, preserve the prior revision and its acknowledgment, notify prior recipients, reopen review | `xch04_report_revision` insert-only triggers; `xch04_review` separate task; `xch04_notification`; `XCH04_REVIEW_REOPENED` | `correctionAppendsANewRevisionReopensReviewAndNotifiesPriorRecipients`, `correctionPreservesTheOriginalReopensReviewAndNotifiesPriorRecipients` |
| Replay/concurrent duplicate produces one revision and one review task | natural unique key + `ON CONFLICT DO NOTHING`; version precondition on review | `replayOfTheSameReportDoesNotDuplicate...`, `aConcurrentInsertConflictReturnsTheWinningRevisionWithoutDuplicating`, `concurrentReplayProducesOneRevisionAndOneReview` |
| Tenant isolation, fail-closed authorization on next request, 401/403/404/409/412/422 | forced RLS on all five V22 tables, `(org,id)` FKs, per-request membership/role fold, guarded transactions | `authorizationAndTenantIsolationFailClosed`, `versionPreconditionsAndInvalidInputsAreEnforced`, service denial tests |
| Synthetic fixtures only | two synthetic orgs share local order/accession/MRN; no real identifiers | integration `@BeforeAll` seed; no PHI-shaped strings |

## Entry point → test trace

`POST /api/v1/xch04/orders` → `ResultController.registerOrder` → `ResultRoutingService.registerOrder` → guarded `TransactionTemplate` → `authorize` (membership + role fold) → `ResultRepository.insertOrder` (`ON CONFLICT` + forced RLS) → `appendAudit` → 201/409. `POST /api/v1/xch04/results` → `ResultController.importResult` → `ResultRoutingService.importResult` → `ResultRules.match` → reconcile or `ResultRules.route` → `replayOrConflict` → `ResultRepository.insertReport`/`insertReview`/`insertNotification`/`insertReconciliation` → `appendAudit` → 201/202/409. `GET /results/{id}`, `POST /results/{id}/acknowledge`, `GET /reconciliations` follow the same guarded path. Fast tests: `ResultRoutingServiceTest`, `ResultRulesTest`, `ResultActionPolicyTest`. DB tests: `ResultRoutingIntegrationTest` (11 HTTP scenarios, `@Tag("integration")`, real PostgreSQL).

## Commands and results (evidence source: executed tool commands)

- `cd apps/api && mvn -q -B -Pno-security,fast test` → exit 0; Surefire 1518 tests, 0 failures, 0 errors, 0 skipped (whole fast tier); in-scope: `ResultRoutingServiceTest` 35, `ResultRulesTest` 7, `ResultActionPolicyTest` 6, all green.
- `cd apps/api && mvn -q -B -Pno-security,fast verify -Dtest=...` (JaCoCo) → fast-tier coverage of `ResultRoutingService`, `ResultRules`, `ResultActionPolicy` and all `ResultModel`/`ResultCommands` types is 100% instruction/line/branch/method/class. `ResultController` and `ResultRepository` are intentionally exercised only by the integration tier; the whole-bundle 100% gate is the integrator's `scripts/check.sh` run.
- `python3 -I scripts/check-migrations.py --require-baseline` → exit 0 (V22 unchanged in this session; no new migration version used).
- `bash scripts/quick-check.sh` → blocked at `check-pack` by the cloud-worker CI `opencode.json` (tracked with `skip-worktree`; regenerating would strip the worker's extra deny rules). This is the documented environment artifact, not a story defect; all other fast policy checks were run individually and passed except pre-existing issues listed below.
- `mvn -q -B -Dspotbugs.skip=false -Djacoco.skip=true -DskipTests ...spotbugs:check` → 6 Medium findings, **none** in `g2/s_g2_04` (all in `g0_07`, `g4_04`, `g3_05`, `g3_08`, `g3_10` — pre-existing, out of scope).
- `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` → 2 failures, both pre-existing and out of scope (`G3EvidenceService.java`, `PatientFlowContract.java`); no `s_g2_04` subtest failed.
- Local environment has **no PostgreSQL client/server and no Docker**, so the DB/integration suite and full JaCoCo bundle gate were not run here; the integrator runs them.

## Defects found and fixed (red → green)

1. **Blocking clinical integrity — divergent payload silently returned as replay.** The natural key `(order, source, accession, version)` alone decided replay; two different corrections of the same prior version, or a changed final, were discarded while the caller received 201 with the older report (ARC-03/ARC-04). Added `replayOrConflict`/`sameContent` fingerprint (identifiers, kind, effectiveAt, checksum) with `409 PAYLOAD_CONFLICT` + `XCH04_RESULT_CONFLICT` audit on divergence, applied to both the pre-insert and concurrent-winner paths. New tests failed before the fix (3 failures) and pass after.
2. **Out-of-order correction could replay an unrelated revision.** Added a lineage-tip guard: `supersedes` must equal the current tip, otherwise the result is diverted to reconciliation with `supersedesVersion` mismatch. New test failed before the fix.
3. **Concurrent order registration raced to an unmapped 500.** `insertOrder` now uses `ON CONFLICT (organization_id, order_ref) DO NOTHING RETURNING`; a losing insert maps to `409 ORDER_EXISTS` with a conflict audit. New unit test failed before the fix; added integration concurrency test for the DB path.
4. **Over-privileged import role.** `tenant_admin` could write clinical result content while being denied result reads; removed `RESULT_IMPORT` from tenant admin, restricted to `lab_verifier`. Policy test failed before the fix.
5. **Misleading test.** `aConcurrentInsertConflictReturnsTheWinningRevisionWithoutDuplicating` never reached the concurrent-insert branch because the duplicate lookup returned the winner first. Tightened the stub to consecutive returns and asserted the insert was attempted; the test failed before the fix and now covers the previously untested branch.

Findings 1 and 3 were independently raised by the senior-backend and software-architect reviewers (simulated review, not human or clinical evidence).

## Decisions and deferred items (owner input needed)

- **Expected-order source of truth (architect F2).** V22 deliberately registers a synthetic expectation (`xch04_expected_order`) because sibling exchange stories own their order tables and this branch keeps slices self-contained (V21 uses the same pattern). V19 `exchange_order` does exist, so before G2 acceptance a Product Owner/Architect decision is needed on linking the expectation to a released, non-revoked order; that requires a cross-story migration outside the V22 reservation. Not changed here to avoid cross-story coupling and applied-migration checksum churn.
- **`Idempotency-Key`/request digest (architect F3, backend #6), durable outbox (F4), reconciliation resolution (F6), cursor pagination (F7), `acknowledged_by` (F8), critical-as-flag (F9) and reconciliation dedup (backend #4)** belong to the separately scheduled cross-cutting stories X-01/X-02 and ADM-02, or to follow-on stories. In-scope compensating controls are the payload fingerprint, the unique natural key, the reconciliation queue and the audit trail.
- **Critical acknowledgment separation (architect F5).** `XCH04_REVIEW_ACK` is a result-review fact only; it never advances XCH-05's critical acknowledgment or due-time state. `lab_verifier` is retained as an authorized human reviewer for laboratory results per XCH-02; critical escalation stays in S-G2-05/V23.
- No clinical or human participant evidence was used. All fixtures are synthetic. No production deployment, push, tag or `gh` call was made.

## Environment caveats for the integrator

The local toolchain lacks PyYAML, Doxygen and PostgreSQL; full-policy Python tests that import `yaml`, `docs.sh`, and the DB tier could not be executed in this worker. Those checks, plus `check.sh` and the JaCoCo 100% bundle gate, must be judged by the integrator's prepared environment. The stale `opencode.json` finding is the known `skip-worktree` CI-profile artifact and was not modified (out of scope).
