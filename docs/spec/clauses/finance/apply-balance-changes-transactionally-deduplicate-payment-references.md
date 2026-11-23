# FIN-01 clause 4 — evidence note

## Clause

Apply balance changes transactionally, deduplicate payment references and issue numbered receipts.

## Status

Proven by tests

## Evidence

The clause is implemented by the FIN-01 finance slice and is proven here by an
active, database-free clause test plus the existing real-PostgreSQL HTTP proof.
All subjects, identifiers and amounts in the fixtures are synthetic.

- Test class
  [ApplyBalanceChangesTransactionallyDeduplicatePaymentReferencesTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/finance/clauses/ApplyBalanceChangesTransactionallyDeduplicatePaymentReferencesTest.java)
  (`com.aurevia.smartclinic.finance.clauses`) drives the real service through a
  fresh test-only repository and a mocked transaction template that runs the
  callback inline, so each assertion is deterministic:
  - `aPaymentUpdatesBalanceAndAllocatesTheNextReceiptAtomically` — one payment
    reduces the balance, moves the invoice to `partially_paid`, advances the
    version and allocates a positive receipt in the same callback; a second
    payment takes the next consecutive receipt number.
  - `anIdenticalReferenceReplaysWithoutASecondBalanceChangeOrReceipt` — the same
    reference and payload replays the committed outcome with no second balance
    change, ledger row, version bump or receipt number.
  - `aReusedReferenceWithADifferentPayloadIsRefused` — a changed payload on the
    same reference is refused with `409 REFERENCE_CONFLICT` and changes no money.
  - `anOverBalancePaymentIsRefusedWithoutChangingBalanceOrAllocatingAReceipt`
    and `aStaleVersionIsRefusedWithoutChangingBalanceOrAllocatingAReceipt` —
    `422 INSUFFICIENT_BALANCE` and `412 STALE_VERSION` leave the balance and
    ledger untouched, and the next valid posting receives receipt number one.
  - `aSubjectWithoutFinanceMembershipCannotChangeTheBalance` — a subject without
    an active finance membership is refused with `403 FORBIDDEN` and posts no
    entry.
- Production classes exercised:
  [FinanceService.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/finance/FinanceService.java)
  (`execute` transaction boundary, `postEntry` row lock, replay dedup, balance
  recompute and version compare-and-set),
  [FinanceRepository.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/finance/FinanceRepository.java)
  (`loadForUpdate`, `findByReference`, `nextReceiptNo`, `insertEntry`,
  `updateInvoice`),
  [FinanceInputs.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/finance/FinanceInputs.java)
  (`reference`, `digest`),
  [FinanceContract.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/finance/FinanceContract.java)
  (`Invoice`, `LedgerEntry`, `EntryRequest`),
  [FinanceActions.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/finance/FinanceActions.java)
  and
  [FinanceController.java](../../../../apps/api/src/main/java/com/aurevia/smartclinic/finance/FinanceController.java)
  (`POST /api/v1/finance/invoices/{id}/payments|credits|refunds`).
- Durable constraints and HTTP proof already present (not added by this task):
  [V45__finance.sql](../../../../db/migrations/V45__finance.sql) enforces forced
  row-level security, `UNIQUE (organization_id, reference)`,
  `UNIQUE (organization_id, receipt_no)`, the balance identity and a gap-free
  `finance_receipt_counter`;
  [FinanceFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/smartclinic/it/finance/FinanceFlowTest.java)
  proves the same rules end to end on a fresh PostgreSQL database in
  `fullLifecyclePostsItemizedPaymentsAndNumberedReceipts`,
  `refundReestablishesIssuedStateAndReplayIsANoOp`,
  `aReusedReferenceWithADifferentPayloadIsRejected` and
  `aReferenceCannotMoveToAnotherInvoice`. The routes are documented in
  [openapi.yaml](../../../../contracts/openapi.yaml).

## Gap

None.

The clause is built and proven at API level by active tests; the true database
rollback and concurrency guarantees are already covered by `FinanceFlowTest` and
the V45 constraints, so this task adds proof only. It changes no production code,
existing test, contract or migration and needs no follow-up task. Human finance
owner acceptance remains a separate G4/G5 gate and is not claimed here.
