# BUILD-13 · Finance: immutable payment, refund and credit ledger with fixed precision

PR #295, tested branch tip a1cb8456aa4b012331c0d64e0d0d5674137f9e49.

Task: BUILD-13

Swarm worker 4. Local checks: passed. Agent result: ok.

## Summary

BUILD-13 (FIN-01). The FIN-01 finance domain — immutable invoice lines and payment/refund/credit ledger, fixed-precision integer minor units, state machine, replay dedup, approvals and reconciliation — was already implemented on `main` by S-G4-05 (`V45__finance.sql`, `FinanceService`, `FinanceRepository`, `FinanceController`, `FinanceContract`, `FinanceInputs`, `FinanceActions`) and is already proven end-to-end over HTTP/PostgreSQL by the out-of-scope `it/finance` suite. This story adds the missing **in-scope, database-free acceptance tests** for the four requested bullets (rounding, partial payments in any order, void after payment refused, plus the two stated rejections) in `apps/api/src/test/java/com/aurevia/finance/FinanceLedgerTest.java` (10 tests). An independent Senior Java/Spring Boot review found four assertion gaps; all four were fixed and the suite re-run green. No production code, migration, dependency or contract was changed.

## Affected requirements

FIN-01, PRD-04 (self-pay only). Supporting contracts: ARC-01 (`PaymentEntry`/`Invoice` domain keys), ARC-03 (explicit commands, `If-Match` version), ARC-04 (422/409/412 error semantics), OPS-02 (append-only ledger, audit). No clinical requirement is touched.

## Risk class

Routine — test-only addition inside the finance domain. No production line changed, so no new runtime, migration, contract or authorization risk. Financially sensitive invariants are nevertheless asserted with negative paths; the domain review was routed to Senior Java/Spring Boot and Test Engineer (skills loaded).

## Review owner

`orevia-senior-backend` and `orevia-test-engineer` skills loaded; one bounded, read-only specialist review of the new test file was completed. It confirmed criteria 1–4 were genuinely asserted and raised four gaps (F1 one-currency, F2 reason-for-reversal, F3 immutability, F4 add-overflow); all four were fixed in this commit and re-verified green. The reviewer made no edits and ran no checks, so its findings are simulated-review evidence, not tool evidence. No human clinician or finance owner involved.

## Data changes

None. No Flyway migration added or edited (no `reserved:` version on this task). The existing `V45__finance.sql` already enforces the balance identity, one-currency-per-entry, append-only ledger and forced RLS. All fixtures are synthetic UUIDs, subjects (`finance-1111`, `admin-1111`), currencies (USD/SAR) and minor-unit amounts; no patient data, credentials or real identifiers.

## Authorization boundaries and audit events

No new authorization rule or audit event. Tests use the existing matrix: `finance` actor may create/issue/post/void, `tenant_admin` is the independent approver, and separation of duties is asserted (`SEPARATION_OF_DUTIES` when the actor names themselves). `AuditAppender` is mocked in the fast tests; real audit rows are proven by `FinanceFlowTest`.

## Migration and rollout considerations

None — no schema or rollout change. Existing deployment remains valid; this is a test-only commit.

## Test evidence

All commands run from the repository root on branch `swarm/BUILD-13-417` (HEAD `c45f9b1`), synthetic data only, PostgreSQL 17.12 on 127.0.0.1:5432.

1. New fast tests (red→green while authoring; one wrong test expectation corrected, see Findings):
   `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='FinanceLedgerTest' test`
   Final line: `EXIT:0`; Surefire XML: `tests="10" errors="0" skipped="0" failures="0"`.
2. Full finance set, unit + PostgreSQL integration (all green):
   `OREVIA_PG_ADMIN=postgres mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest='FinanceLedgerTest,InvoiceListServiceTest,InvoiceListControllerTest,FinanceContractTest,FinanceServiceTest,FinanceFlowTest,FinanceHttpSecurityTest,InvoiceListIntegrationTest' test`
   Final line: `EXIT:0`.
3. Final gate (policy + Python tooling + compile + all unit tests): `bash scripts/quick-check.sh`
   Final lines: `quick check: policy, compile and unit tests passed` / `EXIT:0`.
   Run with the committed local `opencode.json` temporarily restored over the CI-profile overlay (see Findings); `python3 -I scripts/gen-opencode.py --check` returned `GEN_OK` under the same swap. The overlay was restored afterwards.
4. Static analysis (no production change, run for completeness):
   `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check`
   Final line: `EXIT:0` (no findings).

Entry-point-to-test trace (happy and failure paths):
- `POST /api/v1/finance/invoices` → `FinanceController.create` → `FinanceService.create` → `FinanceLedgerTest.fixedPrecisionMinorUnitsNeverRoundLineAmountsOrTotals` (exact minor units; overflow 422).
- `POST .../issue` → `FinanceService.issue` → `newIssued` helper used by every ledger test.
- `POST .../payments` → `FinanceService.postEntry(PAYMENT)` → `partialPaymentsInAnyOrderConvergeToPaid`, `overpaymentIsRejectedWithoutPostingAnEntry`, `replayIsANoOpAndAChangedPayloadWithTheSameReferenceConflicts`.
- `POST .../credits` → `postEntry(CREDIT)` → `approvedCreditReducesBalanceAndRequiresReasonAndApprover`.
- `POST .../refunds` → `postEntry(REFUND)` → `refundAboveNetPaidIsRejected`, `voidAfterPaymentIsRefused`.
- `POST .../void` → `FinanceService.voidInvoice` → `voidAfterPaymentIsRefused`, `aDraftCanBeVoidedOnceWithAnIndependentApprover`.
- HTTP end-to-end proof (already on `main`, out of scope): `it/finance/FinanceFlowTest.fullLifecyclePostsItemizedPaymentsAndNumberedReceipts`, `refundReestablishesIssuedStateAndReplayIsANoOp`, `voidNeedsApprovalAndIsBlockedOnceMoneyIsPosted`, `reversalsRequireReasonAndAnIndependentApprover`.

Not run: full `mvn verify`, `scripts/check.sh`, `scripts/worker-check.sh` and OWASP — the story explicitly forbids any command that runs every integration test / the full verify, and no dependency changed. The trusted integrator and Delivery run the full gate and JaCoCo check on the merge result.

## Coverage

Coverage command (targeted, avoids the >10-minute full verify):
`mvn -q -B -f apps/api/pom.xml -Pno-security jacoco:report`
Source: `apps/api/target/site/jacoco/jacoco.csv`, produced by the full finance test run in evidence item 2.

Result: **all 21 `com.aurevia.finance` production classes show 0 missed instructions, 0 missed branches, 0 missed lines, 0 missed methods and 0 missed classes** — package totals: 2588/2588 instructions, 202/202 branches, 464/464 lines, 120/120 methods (e.g. `FinanceService` 1150/1150 instructions, 105/105 branches, 226/226 lines, 39/39 methods; `FinanceRepository` 571/571 instructions, 8/8 branches, 106/106 lines, 27/27 methods; `FinanceInputs` 282/282 instructions, 40/40 branches, 53/53 lines, 18/18 methods; `InvoicePage`, `FinanceActions`, `FinanceController` and every contract record likewise zero-missed). An `awk` filter over the CSV found no finance row with a positive missed counter. No `exclude`, threshold change, `@Disabled` or skip was introduced.

New production branches added: **none** — the change is test-only. Pre-existing production branches now asserted in the fast gate by the new file:
- PAYMENT reduces balance / `INSUFFICIENT_BALANCE` on amount > balance → `partialPaymentsInAnyOrderConvergeToPaid` / `overpaymentIsRejectedWithoutPostingAnEntry`.
- CREDIT reduces balance / over-credit → `approvedCreditReducesBalanceAndRequiresReasonAndApprover`.
- REFUND increases balance / `EXCESS_REFUND` above net collected → `refundAboveNetPaidIsRejected`.
- Replay with matching digest (no-op) / differing digest (`REFERENCE_CONFLICT`) → `replayIsANoOpAndAChangedPayloadWithTheSameReferenceConflicts`.
- Separation-of-duties denial → `approvedCreditReducesBalanceAndRequiresReasonAndApprover`.
- `targetState` → PAID / ISSUED / PARTIALLY_PAID → `partialPaymentsInAnyOrderConvergeToPaid`, `voidAfterPaymentIsRefused`, `approvedCreditReducesBalanceAndRequiresReasonAndApp
