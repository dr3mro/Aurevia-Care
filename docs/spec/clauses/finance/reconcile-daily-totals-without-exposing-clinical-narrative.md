# FIN-01 clause 5 — API test evidence

## Clause

Reconcile daily totals without exposing clinical narrative to finance roles.

## Status

Proven by tests

## Evidence

Test class: `com.aurevia.smartclinic.finance.clauses.ReconcileDailyTotalsWithoutExposingClinicalNarrativeTest`
(path: `apps/api/src/test/java/com/aurevia/smartclinic/finance/clauses/ReconcileDailyTotalsWithoutExposingClinicalNarrativeTest.java`).
All four tests are active (none are disabled) and use synthetic money-only data.

- `reconcileReturnsFinanceOnlyDailyTotalsForFinanceRole` — positive proof. An active
  `finance` member calls `FinanceService.reconcile`, which returns the synthetic
  `Reconciliation`; the test asserts the returned daily rows and, via reflection, that
  `DailyTotal` has exactly the components `day`, `currency`, `issuedMinor`, `paymentMinor`,
  `refundMinor`, `creditMinor`, `netMinor` and `entryCount`, and `Reconciliation` exactly
  `days`. No clinical field exists in the response shape. It also asserts the
  `SUCCESS` audit row and that the reconciliation query ran.
- `reconcileAllowsTenantAdminAndPrivacyAuditor` — positive role variants. Two separate
  invocations with single-element role lists prove that `tenant_admin` and
  `privacy_auditor` may reconcile, each with its own `SUCCESS` audit row; a separate
  action-matrix assertion covers the privacy-auditor operand directly.
- `reconcileIsDeniedForAClinicalRole` — negative proof of role. An active member whose
  only role is `clinician` is refused with HTTP 403 `FORBIDDEN`; the denial is audited
  and the reconciliation query never runs.
- `reconcileIsDeniedWithoutActiveMembership` — negative proof of membership. A subject
  with no active membership is refused with HTTP 403 `FORBIDDEN` on the short-circuit
  side of the authorization check; `activeRoles` and the reconciliation query are never
  called and the denial is audited.

Production classes exercised through the normal command entry point
(`FinanceService.reconcile`):

- `com.aurevia.smartclinic.finance.FinanceService` — owns the transactional
  `RECONCILE` command, the fresh membership and role check, and the `SUCCESS`/`DENIED`
  audit pair; a forbidden caller receives the failure after the denial audit commits.
- `com.aurevia.smartclinic.finance.FinanceActions` — grants `RECONCILE` to `finance`,
  `tenant_admin` and `privacy_auditor`, and withholds it from any other role.
- `com.aurevia.smartclinic.finance.FinanceContract` — `Reconciliation` and `DailyTotal`
  are immutable finance-only records with no clinical field.
- `com.aurevia.smartclinic.finance.FinanceRepository` — `reconcile` groups
  `finance_invoice` and `finance_ledger_entry` into per-day, per-currency money totals
  and selects no patient, item description, service reference or ledger reason.
- `com.aurevia.smartclinic.finance.FinanceController` — the thin HTTP adapter exposing
  `GET /api/v1/finance/reconciliation`; the existing HTTP proof that finance-only
  totals persist and replay stably is
  `com.aurevia.smartclinic.it.finance.FinanceFlowTest.reconciliationIsFinanceOnlyAndStableAcrossReplay`,
  with the reception-role denial covered by
  `com.aurevia.smartclinic.it.finance.FinanceHttpSecurityTest`.
- `db/migrations/V45__finance.sql` — creates the finance tables under RLS and FORCE RLS
  and the daily projection index; the reconciliation query references no clinical narrative
  table (the invoice keeps only a patient-ownership foreign key, which the report does not join).

This task added proof only. It did not change production code, an existing test, the
contract or a migration.

## Gap

None.
