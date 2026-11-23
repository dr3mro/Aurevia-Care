# FIN-01 clause 1 — API test evidence

## Clause

Generate itemized self-pay invoices in one currency per invoice using fixed precision.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.finance.clauses.GenerateItemizedSelfPayInvoicesCurrencyInvoiceTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/finance/clauses/GenerateItemizedSelfPayInvoicesCurrencyInvoiceTest.java`).
All tests are active (none are disabled) and use synthetic fixtures only. They drive the normal
HTTP entry point `POST /api/v1/finance/invoices` against real PostgreSQL and read the durable
rows back under forced row-level security.

- `createsItemizedDraftInvoiceInOneCurrencyWithExactMinorUnits` — positive proof. An active
  `finance` member posts a two-line invoice (3 × 333 and 7 × 143 minor units) with lowercase
  `usd`. The response is HTTP 201 with a `Location` matching the returned invoice id, uppercase
  `USD` on the invoice, the two exact item amounts 999 and 1001, total and balance 2000 and
  state `draft`. The durable rows are read back under the active tenant: the invoice row holds
  one `USD` currency, while each item row holds its own integer quantity, unit amount and
  computed amount; a second `EUR` invoice in the same tenant carries only `EUR`. This proves
  the currency belongs to one invoice, not to a global constant, and that minor-unit
  multiplication never rounds.
- `rejectsInvalidCurrencyBeforeWritingAnyInvoice` — negative proof of the currency rule. A
  two-letter currency is refused with HTTP 422 `INVALID_CURRENCY` and no invoice, item or ledger
  row is written.
- `rejectsNonPositiveAndOverflowingMinorUnits` — negative proof of fixed precision. A negative
  unit amount is refused with HTTP 422 `INVALID_UNITAMOUNTMINOR`; a quantity of 2 with
  `Long.MAX_VALUE` minor units is refused with HTTP 422 `AMOUNT_OVERFLOW` before any row is
  written.
- `refusesCreationWithoutFinanceRoleAndForPeerTenantPatient` — negative proofs of role and
  tenant. A `reception` member is refused with HTTP 403 `FORBIDDEN`; an active `finance` member
  of another organization posting a patient id owned by the first organization is refused with
  HTTP 404 `NOT_FOUND`, because forced row-level security makes that patient invisible to the
  active tenant. No finance row is written by either refusal.

Production classes exercised through the normal command entry point:

- `com.aurevia.smartclinic.finance.FinanceController` — thin HTTP adapter; `create` returns 201
  and the `Location` header for `POST /api/v1/finance/invoices`.
- `com.aurevia.smartclinic.finance.FinanceService` — `create` validates and normalizes input,
  computes each line and the total in integer minor units, checks role authorization and
  patient visibility inside one tenant-guarded transaction.
- `com.aurevia.smartclinic.finance.FinanceInputs` — `currency`, `positiveQuantity`,
  `nonNegativeAmount`, `multiply` and `add` reject non-fixed-precision input and overflow before
  any SQL runs.
- `com.aurevia.smartclinic.finance.FinanceRepository` and `FinanceActions` — the SQL boundary
  and the bounded action matrix that grants `FINANCE_INVOICE_CREATE` only to `finance`.
- `db/migrations/V45__finance.sql` — `CHECK (currency ~ '^[A-Z]{3}$')`,
  `amount_minor = quantity * unit_amount_minor`, the balance identity, forced RLS and the
  one-currency-per-entry trigger.
- `com.aurevia.smartclinic.web.TenantContextInterceptor` — binds `X-Synthetic-Subject` and
  `X-Active-Organization` only under the `synthetic-auth` profile used by this test.

Existing proof not duplicated here: `FinanceLedgerTest.fixedPrecisionMinorUnitsNeverRoundLineAmountsOrTotals`
already unit-tests the same arithmetic at the service boundary; `FinanceFlowTest` and
`FinanceHttpSecurityTest` already cover the wider invoice lifecycle and the per-route
authorization matrix. This clause test adds the HTTP-level, durable-row and per-invoice-currency
evidence those tests do not provide.

The primary contract `contracts/openapi.yaml` documents the finance list route; the create
route used by this test appears in `contracts/openapi/missing-routes.txt` (a separately tracked
traceability discrepancy). No contract change is in this task's scope.

## Gap

None.

The clause is implemented and proven by active tests. This task added proof only; it did not
change production code, an existing test, the contract or a migration.

## How to verify

- Clause test (synthetic fixtures, real PostgreSQL): `SMARTCLINIC_PG_ADMIN=postgres mvn -B -f apps/api/pom.xml -Pno-security -Dtest=GenerateItemizedSelfPayInvoicesCurrencyInvoiceTest test` — expected `EXIT:0`, with the Surefire report showing all four tests run and none skipped.
- Documentation check: `python3 -I scripts/check-docs.py` — expected exit 0.
- Tooling unit tests: `python3 -I -m unittest discover -s scripts/tests` — expected 0 failures.
- Worker gate: `bash scripts/worker-check.sh` — expected 0.
- Static analysis after compile: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` — expected no findings.
- Full JaCoCo coverage and image smoke gates are owned by Delivery/integrator, not this proof-only task.
