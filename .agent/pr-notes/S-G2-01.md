# S-G2-01 — XCH-01: order creation with authorization, snapshot, disclosure and destination work item

## Requirement IDs and acceptance mapping

| XCH-01 clause | Implementation | Executed / inherited evidence |
|---|---|---|
| Order carries local patient + encounter, order code, priority, clinical question, destination, source actor, responsible clinician and work queue | `OrderRequest`, `OrderView`, canonical content in `OrderService.create` | `OrderServiceTest.createsAnAuthorizedOrderWithSnapshotDisclosureAndWorkItem`, `OrderRecordsTest`; `OrderExchangeIntegrationTest.releasesAnAuthorizedOrderWithSnapshotDisclosureAndReceiverWorkItem` (DB, integrator) |
| Sender authorizes the specific payload, recipient, purpose and **duration** | `AuthorizationRequest`/`AuthorizationView`, `OrderService.checkGrant`, V19 `exchange_authorization` | `OrderServiceTest.deniesWhenThe*OutsideTheAuthorization*`; **new** `OrderAuthorizationWindowTest` (3 tests); integration `deniesPayloadRecipientPurposeAndDurationOutsideTheAuthorization` (DB, integrator) |
| Snapshot released content, stamp identifiers and versions | `appendSnapshot`, deterministic fingerprint, version 1, V19 immutable snapshot | `OrderServiceTest.fingerprintIsStableForTheSamePayloadAndDifferentForChangedContent`; integration `snapshotAndDisclosureAreImmutableAndOtherTenantsStayIsolated` (DB, integrator) |
| Record disclosure | `appendDisclosure` + V19 `exchange_disclosure` (`UNIQUE(org, order_id)`) | `OrderServiceTest` verifies the disclosure row arguments; integration asserts exactly one row (DB, integrator) |
| Receiver sees an exchange-specific work item with locally verified patient association | `WorkItemView`, `insertWorkItem`, `verifyPatient`, V19 two-party RLS policies | `OrderServiceTest.verifiesTheLocalPatientAndConcealsMissingRecords`; integration `verifiesTheLocalPatientAndNeverGrantsGeneralChartAccess` (DB, integrator) |
| Sharing an order grants no general chart access | Bounded projection only; no FK into sender chart tables; two-party RLS | Integration `verifiesTheLocalPatientAndNeverGrantsGeneralChartAccess` (DB, integrator) |

Supporting IDs: ARC-01 (tenant-owned `(organization_id, id)` keys, V19), ARC-02 (forced RLS, non-owner role, transaction-local tenant), ARC-03 (versioned `/api/v1` routes, explicit POST commands, machine-readable 401/403/404/409/412/422), OPS-02 (transactional append-only audit), IAM-01/02 (membership and roles re-derived per request), PRD-06 (server-side authorization).

## Defect fixed this attempt (test first)

**MEDIUM — a disclosure could outlive the authorization that permitted it (XCH-01 duration fail-open).**
`OrderService.checkGrant` verified the grant was currently effective and that `duration <= maxDurationMinutes`, but never related the *disclosure* validity window to the *authorization* window. A grant still effective at release time but expiring in 5 minutes, with a 60-minute maximum, allowed a 30-minute release — sharing content 25 minutes after the sender's permission lapsed. Nothing in V19 closed this.

- Red: new `OrderAuthorizationWindowTest.aDisclosureCannotOutliveTheAuthorizationWindow` (granted expiry `NOW+300s`, request duration 30 min) → `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OrderAuthorizationWindowTest test` **exit 1**, `Tests run: 2, Failures: 1`, `AssertionFailedError: Expected ApiException to be thrown, but nothing was thrown`.
- Fix: `OrderService.checkGrant` now rejects when `now + duration` is after `grant.expiresAt()` (null expiry still means unlimited). Added `aDurationAboveTheAuthorizedMaximumIsRejectedEvenWithoutAGrantExpiry` so the pre-existing `duration > maxDurationMinutes` branch stays covered, and `aDisclosureEndingExactlyAtTheAuthorizationExpiryIsAllowed` for the boundary.
- Green: same targeted command **exit 0**, 3 tests. Full fast suite **exit 0**, 1688 tests, 0 failures/errors/skipped. `OrderService` JaCoCo after the fix: 0 missed of 832 instructions, 0 of 58 branches, 0 of 148 lines, 0 of 45 methods, 0 of 1 class.

## Files changed this attempt

- `apps/api/src/main/java/com/aurevia/smartclinic/exchange/order/OrderService.java` — grant window bounds the disclosure validity in `checkGrant`.
- `apps/api/src/test/java/com/aurevia/smartclinic/it/exchange/order/OrderAuthorizationWindowTest.java` — new (3 tests, synthetic fixtures, declared test scope).
- `.agent/pr-notes/S-G2-01.md` — this file.
- No migration, shared script, generated config or tracker was edited. V19 (`Change: expand`) is unchanged.

The rest of the implementation (order service/repository/controller/value types, V19, and 31 inherited unit + 5 DB integration tests) was already present on the base branch and was reviewed and verified here, not written from scratch in this attempt.

## Scope interpretation

TODO.md and the dispatch both declare the test scope as `.../it/exchange/order`. The inherited order tests live in the flattened `.../it/exchangeorder` package (the repository's post-refactor convention). The new regression test is placed at the declared path `it/exchange/order` with package `com.aurevia.smartclinic.it.exchange.order`; it is business-domain named, compiles and runs in the fast tier. No story/gate/ticket identifier appears in any Java package or class name.

## Exact commands and results (evidence source: executed tool commands)

| Command | Result |
|---|---|
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (before change) | exit 0; 1685 tests, 0 failures/errors; order classes: OrderServiceTest 25, OrderInputsTest 5, OrderRecordsTest 1 |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=OrderAuthorizationWindowTest test` (before fix) | **exit 1**, 2 tests / 1 failure — genuine red |
| same command (after fix) | exit 0, 3 tests, 0 failures |
| `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test jacoco:report` | exit 0; **1688 tests, 0 failures/errors/skipped**. Fast-tier JaCoCo: every `exchange.order` class 0 missed except `OrderRepository` (481 missed instructions, 86 missed lines — exercised only by the DB suite the integrator runs). `OrderService` 0/832 instr, 0/58 branch, 0/148 line, 0/45 method, 0/1 class. |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V1–V55, V19 still `Change: expand`) |
| `python3 -I scripts/check-docs.py`; `python3 -I scripts/check-secrets.py`; `git diff --check` | exit 0 each |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | exit 0, 2 tests OK |
| `mvn -q -B -f apps/api/pom.xml -Pno-security -DskipTests -Djacoco.skip=true spotbugs:check` | exit 0; no finding in `exchange/order` |
| `python3 -I -m unittest discover -s scripts/tests -q` | exit 1 — 203 tests, 8 failures + 8 errors, **all pre-existing and out of scope** (`test_swarm`, `test_agent_run`, `test_check_pack`, `test_workflow_concurrency`, `test_fast_tier` import; stale `opencode.json` CI-profile artifact and missing PyYAML, as recorded in sibling story notes). None touches `exchange/order`. |

## Reviewer findings and dispositions (simulated specialists; no human/clinical evidence)

Three bounded reviews were run (senior backend, senior Spring Security, test engineer). All reviewed statically; none re-executed checks.

| # | Finding | Severity | Disposition |
|---|---|---|---|
| 1 | Disclosure can outlive the authorization window (backend F1) | MEDIUM | **Fixed test-first** as above. |
| 2 | `PatientIdentity` record methods never invoked → build-failing METHOD coverage (test engineer) | claimed HIGH | **Disproven by tool**: fast-tier `jacoco.csv` reports `OrderRepository.PatientIdentity` 0 missed instructions/methods/lines. No change. |
| 3 | Work items remain listable/verifiable after the grant window closes; no authorization deactivation command (security 1) | MEDIUM | Deferred — needs an `active`/expiry predicate plus a bounded revoke command; revocation is XCH-03 (S-G2-03) and needs an owner decision. |
| 4 | Receiver access uses one hard-coded `clinician` role with no queue/facility binding (backend minor, security 2) | MEDIUM | Deferred — extracting a policy class and modelling receiver assignment/facility is an IAM-01/ADM-02 design decision spanning stories. |
| 5 | `attestation` validated then discarded; no durable record of the local-patient binding evidence (backend F3, security 3) | MEDIUM | Deferred — needs a new V19 column/verification ledger and audit change; applied-migration checksum and cross-story coordination need an owner decision. |
| 6 | No `DataIntegrityViolationException` → machine-readable translation in the order writes (backend F2) | LOW | Deferred — house-pattern divergence with `Xch03Service`; normal paths pre-check the referenced rows with fresh UUIDs, and the shared translator is a cross-cutting concern. |
| 7 | `db/check.sh` enumerates only pre-V19 tables for the RLS assertion (security 5) | LOW | Deferred — `db/` is outside this story's paths; owner/integrator decision. |
| 8 | DB-stored `source_actor`, full canonical snapshot content and disclosure column values are not asserted (test engineer 1–3); revocation-after-issuance IT and under-contention verification missing (security 4) | LOW / evidence gap | Deferred to the integrator's DB tier — no local PostgreSQL, so new DB assertions could not be executed here. Recorded as evidence gaps, not silently marked covered. |

## Blockers / evidence genuinely missing (stop-and-report)

1. **No local PostgreSQL** (`psql`/`pg_isready` absent; 127.0.0.1:5432 closed). The 5 `@Tag("integration")` tests in `OrderExchangeIntegrationTest`, the full `mvn verify` JaCoCo BUNDLE gate and `bash apps/api/check.sh` were **not** executed here. The integrator must run them on the exact merged SHA; that suite is the authority for `OrderRepository` coverage and for the V19 RLS/trigger behavior.
2. **Owner decisions required** for deferred reviewer findings 3–5 (revocation after issuance, receiver role/queue/facility binding, durable attestation). These are not resolved here; clinical/site approval remains a G5 gate.
3. **Pre-existing tooling failures** listed above are out of scope and were not edited.
4. **No simulated-user exercise and no human or clinical participant evidence.** All results are executed tool commands or simulated specialist reviews; all fixtures are synthetic UUIDs, subjects and payloads. No patient data or credentials were used or stored.
