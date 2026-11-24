# FIN-01 clause 2 — invoice lifecycle states

## Clause

Support draft, issued, paid, partially paid and void.

## Status

Proven by tests

## Evidence

Test class `com.aurevia.finance.clauses.SupportDraftIssuedPaidPartiallyPaidVoidTest`
(`apps/api/src/test/java/com/aurevia/finance/clauses/SupportDraftIssuedPaidPartiallyPaidVoidTest.java`),
an HTTP-level integration test on real PostgreSQL with two synthetic tenants and
synthetic finance, tenant-admin, reception and patient fixtures. It proves the
clause through the real REST entry point, not just the domain enum:

- `anInvoiceMovesDraftIssuedPartiallyPaidPaidAndVoid` — one invoice is created as
  `draft` (1300 minor units, balance 1300, version 1), issued (`issued`, v2), paid
  300 (`partially_paid`, balance 1000, paid 300, v3) and paid 1000 (`paid`, balance
  0, paid 1300, v4); a fresh GET read-back and the `?status=paid` filter confirm the
  committed state. A second invoice is voided with a recorded reason and an
  independent approver (`void`, v2), read back and found by `?status=void`; a
  second void and a payment to that void invoice are refused with 409
  `INVALID_TRANSITION`, proving VOID terminal.
- `aStaleVersionCannotChangeInvoiceState` — a stale `If-Match: "99"` is refused
  with 412 `STALE_VERSION`; after a successful issue at "1", stale payments and
  voids at "1" are also refused with 412 and the state does not move.
- `aReceptionSubjectCannotPostPayment` — a fully valid payment and issue by a
  reception subject, and a void signed by a tenant_admin actor, are refused with
  403 `FORBIDDEN`; the invoice stays issued with no money posted.
- `aPeerTenantCannotReadOrChangeAnotherTenantsInvoice` — a finance actor in the
  peer tenant receives 404 `NOT_FOUND` on both GET and state-changing requests
  for another tenant's invoice; the source invoice is unchanged.
- `voidingAfterPostingIsRefused` — a PAID invoice void is refused with 409
  `INVALID_TRANSITION`; after a full 1300 refund restores `issued` with two ledger
  entries, voiding is refused with 422 `VOID_AFTER_POSTING` and the invoice stays
  issued with its entries intact.
- `voidRequiresAnApproverDistinctFromTheActor` — self-approval is refused with 403
  `SEPARATION_OF_DUTIES` and an active but unauthorised approver with 403
  `INVALID_APPROVER`; only an independent tenant_admin approver completes the void.
- `aDraftOrVoidInvoiceCannotAcceptEntries` — entries on draft or void invoices are
  refused with 409 `INVALID_TRANSITION`, so money only moves through issued states.
- `aMissingOrMalformedPreconditionIsRejectedBeforeAnyStateChange` — a missing
  precondition is refused with 428 `PRECONDITION_REQUIRED` and a malformed one with
  422 `INVALID_IF_MATCH`, leaving the draft untouched.

Production classes exercised through `com.aurevia.finance.FinanceController`
(`POST /api/v1/finance/invoices`, `/{id}/issue`, `/{id}/payments`, `/{id}/refunds`,
`/{id}/void`, `GET /invoices`, `/invoices/{id}`):

- `com.aurevia.finance.FinanceService` — `create`, `issue`, `postEntry`,
  `voidInvoice`, `get`, `list`, `targetState`, version and state guards, and the
  403/404/409/412/422 refusal codes.
- `com.aurevia.finance.FinanceContract.InvoiceState` — the five wire
  states asserted above, the production `canTransitionTo` matrix (DRAFT ->
  ISSUED/VOID, ISSUED -> PARTIALLY_PAID/PAID/VOID, PARTIALLY_PAID -> ISSUED/PAID,
  PAID -> PARTIALLY_PAID/ISSUED, VOID terminal; this suite exercises the subset
  named in the test bullets) and `targetState` balance arithmetic.
- `com.aurevia.finance.FinanceActions` and `FinanceInputs` — the
  finance/tenant_admin/reception action matrix, approval separation and bounded
  entry inputs.
- `com.aurevia.api.IfMatch` and `ApiException` — precondition parsing
  and structured error codes.
- `db/migrations/V45__finance.sql` — the five-state CHECK, RLS/FORCE RLS, the
  version+1 and append-only money guards and the transition trigger that mirrors
  the Java state machine.

Verified locally: `mvn -B -f apps/api/pom.xml -Pno-security -Dgroups=integration -Dtest=SupportDraftIssuedPaidPartiallyPaidVoidTest test`
— `Tests run: 8, Failures: 0, Errors: 0, Skipped: 0`, `BUILD SUCCESS`; see
`.agent/pr-notes/SPEC-FIN-01-C02-20261122-212917.md` for the exact final lines.

## Gap

None.
