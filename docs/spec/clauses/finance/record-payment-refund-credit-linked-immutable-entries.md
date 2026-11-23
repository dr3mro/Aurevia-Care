# FIN-01 clause 3 — record payment, refund and credit as linked immutable entries

## Clause

Record payment, refund and credit as linked immutable entries, requiring reason and approval according to policy.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.smartclinic.finance.clauses.RecordPaymentRefundCreditLinkedImmutableEntriesTest`
(`apps/api/src/test/java/com/aurevia/smartclinic/finance/clauses/RecordPaymentRefundCreditLinkedImmutableEntriesTest.java`),
an HTTP-level integration test against a fresh real-PostgreSQL database created from the production
Flyway migrations, with synthetic organizations, members and patients and the `synthetic-auth` profile:

- `paymentRefundAndCreditAreLinkedImmutableEntriesWithNumberedReceipts` — through
  `POST /api/v1/finance/invoices/{id}/payments`, `/credits` and `/refunds`, three separate entry types
  are posted to one issued invoice. Each returned entry links to the invoice id and snapshots the
  revision it was posted against (2, 3, 4) and the invoice's single currency; receipts are consecutive.
  The serialized first and second entries are unchanged after later posts and after a fresh `GET`,
  and a control `SELECT` followed by `UPDATE` and `DELETE` attempts as the migration owner are both
  rejected with SQLSTATE 44000 by the migration's append-only trigger.
- `refundAndCreditRequireReasonAndAnIndependentApprover` — a missing or blank reason is refused with
  422 `INVALID_REASON`, a missing approver with 422 `INVALID_APPROVERSUBJECT`, a self-approving actor
  with 403 `SEPARATION_OF_DUTIES`, a role without approval permission and a well-formed non-member
  subject with 403 `INVALID_APPROVER`. The same command succeeds with a reason and an independent
  authorized approver; the in-process defensive copy of `View.entries()` is separately asserted by
  `FinanceLedgerTest.partialPaymentsInAnyOrderConvergeToPaid`.
- `receptionRoleAndOtherTenantCannotPostAnEntry` — a `reception` actor and a revoked member are
  refused with 403 `FORBIDDEN`. An invoice that really exists in a second tenant is visible to that
  tenant's finance member (200) but returns 404 to the first tenant's finance actor under forced RLS.
  None of the denials posts an entry or changes the balance.
- `staleVersionAndForbiddenStatesRefuseEntries` — a stale `If-Match` returns 412 `STALE_VERSION`;
  draft and void invoices return 409 `INVALID_TRANSITION`; a reference reused with a changed payload
  returns 409 `REFERENCE_CONFLICT` with no second entry, receipt or balance change, while an identical
  replay returns the original outcome unchanged.

Production exercised: `FinanceService.postEntry` and `FinanceService.approvalFailure`,
`FinanceContract.EntryType`/`LedgerEntry`/`EntryRequest`/`View`, `FinanceController` payment, credit
and refund routes, `FinanceActions`, `FinanceRepository.insertEntry`/`nextReceiptNo`/`findByReference`,
and `db/migrations/V45__finance.sql` (forced RLS, append-only ledger triggers, currency guard and
per-tenant receipt counter). The requirement and its boundary are defined in
[SMARTCLINIC_V1_SPEC.md](../../../../SMARTCLINIC_V1_SPEC.md), FIN-01.

This note proves the HTTP behaviour only. The published OpenAPI contract documents the finance list
route; the POST entry routes are baselined in `contracts/openapi/missing-routes.txt` as pre-existing
debt outside this clause's scope.

## Gap

None
