# PRD-04 clause 2 — boundary evidence note

## Clause

No claims adjudication, payer integrations, card data storage or accounting ledger.

## Status

Not built

This is a prohibition, so "Not built" is the healthy state: no code, workflow,
infrastructure file, contract or document in this repository implements claims
adjudication, a payer/insurance integration, payment-card data storage or an
accounting (general) ledger. There is also no automated guard that asserts this
absence, so the clause is not claimed as "Built and verified".

## Evidence

Source of the clause: the PRD-04 boundary cell in
[PROJECT_V1_SPEC.md](../../../../.opencode/config/PROJECT_V1_SPEC.md) and the associated
self-pay requirement FIN-01 in the same file.

Repository survey (tool evidence, executed on this branch; tracker files,
archived history and this evidence note itself excluded so the words in the
clause and this table are not counted as implementation):

| Prohibited capability | Sweep | Result |
|---|---|---|
| Claims adjudication | case-insensitive search for `adjudicat`, `claims adjudication` across `apps/`, `contracts/`, `db/`, `infra/`, `.github/`, `docs/` | no match |
| Payer integrations | `payer`, `insurance`, `clearinghouse`, `X12`, `837`, `835` | no match (only hash/hex false positives in lockfiles and SHA constants) |
| Card data storage | `credit card`, `debit card`, `card number`, `payment card`, `PCI`, `stripe`, `braintree` | no match (only `zebra stripe` in [token-contract.md](../../../../docs/design/token-contract.md) and hash fragments in lockfiles) |
| Accounting ledger | `general ledger`, `accounting ledger`, `double entry`, `chart of accounts` | no match |

Files that could be mistaken for the prohibited capabilities, examined and
ruled out:

- [web/ClaimsTenantResolver.java](../../../../apps/api/src/main/java/com/aurevia/web/ClaimsTenantResolver.java)
  and its test
  [ClaimsTenantResolverTest.java](../../../../apps/api/src/test/java/com/aurevia/web/ClaimsTenantResolverTest.java):
  "claims" are already-verified JWT token claims (`sub`, `orgs`, `iss`) used to
  derive the active tenant under IAM-01/ARC-02. It parses no token and has no
  payer or insurance meaning.
- [finance/FinanceContract.java](../../../../apps/api/src/main/java/com/aurevia/finance/FinanceContract.java):
  `LedgerEntry` is an append-only self-pay invoice entry with `EntryType`
  `PAYMENT`, `REFUND` or `CREDIT`. Its Javadoc states item descriptions are
  non-clinical catalogue-style labels and no clinical table is joined into a
  finance read. It is not a double-entry account ledger: there is no chart of
  accounts, debit/credit account posting, period close or general-ledger
  account.
- [api/contract/IdempotencyLedger.java](../../../../apps/api/src/main/java/com/aurevia/api/contract/IdempotencyLedger.java),
  [messaging/evidence/EvidenceLedger.java](../../../../apps/api/src/main/java/com/aurevia/messaging/evidence/EvidenceLedger.java)
  and [exchange/conformance/ConformanceLedger.java](../../../../apps/api/src/main/java/com/aurevia/exchange/conformance/ConformanceLedger.java):
  technical decision, evidence and conformance records, not accounting.

Implementation that respects the boundary (self-pay FIN-01 only):

- [finance/FinanceService.java](../../../../apps/api/src/main/java/com/aurevia/finance/FinanceService.java),
  [finance/FinanceController.java](../../../../apps/api/src/main/java/com/aurevia/finance/FinanceController.java),
  [finance/FinanceActions.java](../../../../apps/api/src/main/java/com/aurevia/finance/FinanceActions.java),
  [finance/FinanceInputs.java](../../../../apps/api/src/main/java/com/aurevia/finance/FinanceInputs.java)
  and [finance/FinanceRepository.java](../../../../apps/api/src/main/java/com/aurevia/finance/FinanceRepository.java):
  the only money workflow. Routes are invoice create/list/issue/payment/credit/
  refund/void/get and reconciliation; amounts are integer minor units in exactly
  one currency per invoice. No route accepts a payer, an insurance claim or card
  data.
- [V45__finance.sql](../../../../db/migrations/V45__finance.sql): stores only
  finance columns, with forced RLS inherited from V2, fixed-precision `bigint`
  minor-unit columns, a database `CHECK` that derives the balance as
  `total - paid + refunded - credited`, and an explicit comment that no clinical
  table is referenced so a finance role can reconcile without reading a chart.
  No table or column for a payer, claim, card, account or ledger account exists.
- [generated/openapi.yaml](../../../../contracts/openapi/generated/openapi.yaml),
  [generated/client.ts](../../../../contracts/openapi/generated/client.ts) and
  [generated/manifest.json](../../../../contracts/openapi/generated/manifest.json):
  the published contract has only the self-pay finance command at
  `/api/v1/invoices` (`FINANCE_INVOICE_CREATE`) and contains no payer, claim,
  card or accounting schema or path. See [contracts/openapi/README.md](../../../../contracts/openapi/README.md)
  for the generated-contract policy.
- [FinanceLedgerTest.java](../../../../apps/api/src/test/java/com/aurevia/finance/FinanceLedgerTest.java)
  (`fixedPrecisionMinorUnitsNeverRoundLineAmountsOrTotals`,
  `everyLinkedEntrySnapshotsTheInvoicesSingleCurrency`, `replayIsANoOpAndAChangedPayloadWithTheSameReferenceConflicts`),
  [FinanceFlowTest.java](../../../../apps/api/src/test/java/com/aurevia/it/finance/FinanceFlowTest.java)
  (`fullLifecyclePostsItemizedPaymentsAndNumberedReceipts`,
  `reconciliationIsFinanceOnlyAndStableAcrossReplay`) and
  [FinanceHttpSecurityTest.java](../../../../apps/api/src/test/java/com/aurevia/it/finance/FinanceHttpSecurityTest.java)
  (`unauthorizedRoleReturnsForbidden`, `allowedSubjectReachesSuccessStatusOnEveryRoute`)
  exercise only self-pay behavior. `reconciliationIsFinanceOnlyAndStableAcrossReplay`
  asserts a synthetic clinical marker is absent from finance responses. Contract
  coverage is in [FinanceContractTest.java](../../../../apps/api/src/test/java/com/aurevia/it/finance/FinanceContractTest.java)
  and [InvoiceListIntegrationTest.java](../../../../apps/api/src/test/java/com/aurevia/it/finance/InvoiceListIntegrationTest.java).

No workflow run, human approval, partner artifact or cloud account is cited as
evidence for this clause; none was inspected or found in the repository.
Review classification is in [docs/WORKFLOW.md](../../../../docs/WORKFLOW.md).

## Gap

No product implementation is missing — absence of the four capabilities is the
required state. What is missing is an automated, build-failing guard that would
detect a future reintroduction (for example a new payer route, a card-number
column or a general-ledger table). Today the boundary is held by design, the
finance-only schema and ordinary code review; a reviewer must repeat a manual
search to confirm it.

Closing that gap needs **one** bounded task: add a static/architecture check and
a test that fails when a prohibited package, route, column or dependency
appears. Files that task would change (none changed here): a new policy check
under `scripts/` plus its test under `scripts/tests/`, or a new Java
architecture test under `apps/api/src/test/java/com/aurevia/`, and
this note's `## Evidence` and `## Status` updated to point at the guard. The
`scripts/` and `.github/workflows/` paths are maintainer-owned; a worker puts
the proposal in its notes rather than editing them.

No human, partner, cloud account or signed decision is needed for this
boundary, so no G5 evidence supplier is named for it.

## How to verify

A reviewer runs these exact commands from the repository root and expects no
matches for the four capabilities (lockfile hash and hex-constant false
positives are acceptable; this evidence note names the words and is excluded):

```
grep -rniE 'adjudicat|payer|insurance|clearinghouse|credit card|debit card|card number|payment card|general ledger|accounting ledger|double.entry|chart of accounts|X12' apps contracts db infra .github docs --include='*.java' --include='*.ts' --include='*.tsx' --include='*.py' --include='*.sql' --include='*.yml' --include='*.yaml' --include='*.json' --include='*.md' | grep -vE './(TODO.md|tasks/|.agent/|docs/archive/)' | grep -v 'docs/spec/clauses/product/claims-adjudication-payer-integrations-card-data-storage.md'
```

Confirm the published money surface is self-pay only:

```
grep -nE '@(Get|Post)Mapping' apps/api/src/main/java/com/aurevia/finance/FinanceController.java
grep -n '/api/v1/invoices' contracts/openapi/generated/openapi.yaml
```

Confirm no clinical narrative leaks into finance reads (PostgreSQL required for
the HTTP integration tests; the integrator runs the full gate):

```
mvn -q -B -f apps/api/pom.xml test -Dtest=FinanceLedgerTest
mvn -q -B -f apps/api/pom.xml test -Dtest=FinanceFlowTest,FinanceHttpSecurityTest
```

Confirm the note's links and requirement IDs are valid:

```
python3 -I scripts/check-docs.py
```

No site-produced human evidence is required before G5 for this clause: the
G5 policy-pack and scope sign-offs cover site configuration, not this product
boundary.
