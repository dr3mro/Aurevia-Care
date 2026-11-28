# START-54 · API: negative-path and tenant-isolation tests for the package `finance`

PR #1824, tested branch tip 058d2c309c9cb68b1c9ae090f3f6d71ad4678523.

Task: START-54

Swarm worker 2. Checks: passed. Local checks: failed. Agent result: ok.

# START-54 — API: negative-path and tenant-isolation tests for the package `finance`

Skill evidence (loaded at task start):
- `aurevia-delivery` — every story; drives the swarm worker loop and notes hand-off.
- `aurevia-senior-backend` — Java/Spring finance service and controller behaviour.
- `aurevia-senior-java-build` — SpotBugs/JaCoCo tooling and the targeted Maven command.
- `aurevia-context-optimizer` — bounded discovery of finance entry points and requirement IDs.
- `aurevia-test-engineer` — test-first negative-path matrix.
- `aurevia-plan-reviewer` — plan gate before code.
- `aurevia-code-reviewer` — post-code review.
- `aurevia-coverage-auditor` — branch-to-test mapping.
- `aurevia-devsecops` — tenant-isolation assertion shape and defensive review.

## Scope and requirement mapping
- Task: `tasks/00942000-START-54.md` (already `[-]`, agent `swarm-2`); worker does not edit trackers.
- Scope (single new file): `apps/api/src/test/java/com/aurevia/finance/FinanceNegativePathsTest.java`.
- Requirements: FIN-01 (self-pay finance), ARC-02 (tenant isolation); contract statuses from ARC-04 (404/409/412/422/428).
- Test-only change: no production code, existing tests, OpenAPI contract or migration is modified.

## Existing coverage inventory (from source reading, tool evidence)
- `FinanceContractTest` (fast): pure `FinanceInputs` validation — UUID, currency, text length, subject, money/quantity, reason, reference, overflow, digest, action matrix.
- `FinanceLedgerTest` (fast): overflow, overpayment 422, excess refund 422, void-after-posting 409/422, credit reason+approver, separation of duties 403, replay no-op and 409 REFERENCE_CONFLICT.
- `ApplyBalanceChangesTransactionallyDeduplicatePaymentReferencesTest` (fast): atomic payment, replay, conflict, over-balance 422, stale 412, outsider 403.
- `InvoiceListServiceTest` / `InvoiceListControllerTest` (fast): list validation, 403/401/422, denied audit.
- `ReconcileDailyTotalsWithoutExposingClinicalNarrativeTest` (fast): reconcile role matrix, denied audit.
- `FinanceServiceTest` (fast): already-void rejection 409.
- Integration (`FinanceFlowTest`, clause tests, `FinanceHttpSecurityTest`): full lifecycle, peer-tenant 404, wrong role 403, stale 412, missing/malformed If-Match 428/422, invalid inputs, replay/conflict.
- Gap pinned here: fast, mocked-repository assertions that malformed input writes nothing; reception-role denial audit on create/issue/payment; empty-lookup 404 concealment on get/issue/payment/void; stale version + If-Match parse at the controller; replay/conflict with explicit no-write verification.

## Test evidence
Evidence source labels: commands below are **tool evidence**; named subagent returns are **reviewer evidence**; no simulated-user walkthrough or authorized human feedback was used.

Tested revision: e3824145dbff812d560cf742736638e2b12bbf64

The revision above is the exact `git rev-parse HEAD` output while the checks below ran; the new test file was uncommitted at that point.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=FinanceNegativePathsTest test` → exit 0. Surefire `TEST-com.aurevia.finance.FinanceNegativePathsTest.xml`: `tests="5" errors="0" skipped="0" failures="0" flakes="0"`. First run failed to compile (`unreported exception java.lang.Exception` at the MockMvc `perform` calls); fixed by declaring `throws Exception` on `staleVersionRejected`, matching `InvoiceListControllerTest`.
- `mvn -q -B -f apps/api/pom.xml -Pno-security -Dspotbugs.skip=false -DskipTests compile spotbugs:check` → exit 0, no findings (compile ran before the check, so it is not vacuous).
- `python3 -I -m unittest discover -s scripts/tests` → `Ran 2494 tests in 102.906s`, `FAILED (failures=5, skipped=1)`. The five failures are pre-existing worker-image artifacts, not caused by this change: three `test_check_pack` cases directly read the worktree `.opencode/opencode.json` (CI profile differs from committed blob), `test_daily_archives_keep_the_documented_dated_shape` needs `.agents/notes/archive/` (absent here), and `test_run_gate_reaps_a_started_background_descendant`. Proven by re-running those exact five with `-k` both with and without `FinanceNegativePathsTest.java` present: identical `Ran 5 ... FAILED (failures=5)` each time. No test was skipped, suppressed or weakened by this change.
- Policy checks (each exit 0): `check-pack.py` → `Delivery tooling validated: 39 agents, 39 skills`; `check-docs.py`, `check-secrets.py`, `check-names.py`, `check-wiring.py`, `check-constraints.py`, `check-migrations.py --require-baseline` → empty final lines with exit 0.
- `bash scripts/worker-check.sh` → exit 1 under a 300-second cap. It stopped inside `scripts/quick-check.sh` at `scripts/python-coverage.sh`, whose final line is `Ran 2494 tests in 121.149s` / `FAILED (failures=5, skipped=1)` — the same five pre-existing failures proven independent above. It therefore did not reach the frontend, SpotBugs or integration stages of that script; those were run individually here (SpotBugs exit 0, policy checks exit 0) and the finance integration suites remain the integrator's gate. No threshold or check was changed to make it pass.

## Coverage
No production file, migration or contract was changed: the only scoped file is the new test class, so no new production line or branch exists to cover. The targeted suite runs 5 tests, 0 skipped, and the full JaCoCo gate remains the integrator's. Every production branch this class pins, with its named test:

- `FinanceInputs.uuid` malformed value → `missingAndMalformedInputsFailBeforeAnyWrite` (INVALID_PATIENTID for patientId, INVALID_ID for route id; status and code). The null arm is already pinned by `FinanceContractTest.uuidValidationIsBounded`.
- `FinanceInputs.currency` non-three-letter → same test (INVALID_CURRENCY).
- `FinanceInputs.text` blank and 121-char description → same test (INVALID_DESCRIPTION).
- `positiveQuantity` null/0, `nonNegativeAmount` null/negative → same test.
- `create` `items == null` and `items.isEmpty()` operands → same test (INVALID_ITEMS, both sides).
- `multiply` overflow and `add` overflow → same test; distinct exact messages asserted for each.
- `positiveAmount` zero, `reference` blank/129-char, `requiredReason` blank/501-char, `subject` malformed → same test.
- Validation-before-transaction ordering → same test: `verify(transactions, never()).execute(any())`, `verifyNoInteractions(audit)`, and never-verify on every repository write.
- `authorize` role `noneMatch` false for CREATE/ISSUE/PAYMENT → `wrongRoleDeniedAndAudited` (403 + exact message + exact 7-arg DENIED audit with resource id "NEW" for create).
- `findInvoice`/`loadForUpdate` empty result on get/issue/payment/void → `otherTenantsIdLooksLikeNotFound` (404, constant message, no 403, no audit, no write).
- `requireVersion` mismatch on issue/void/payment → `staleVersionRejected` (412 + exact message + no write); `findByReference` before `requireVersion` is proven by `verify(repository).findByReference("STALE-NEG")`.
- `IfMatch.parse` absent header, non-numeric, non-positive and weak-validator parse → `staleVersionRejected` MockMvc block (428, 422, 422, 200 with `verify(service).issue(id, 2)`); `verifyNoInteractions(httpService)` for the three failures. The blank-header arm is already pinned by `IfMatchTest`.
- `findByReference` present with equal digest (replay) and with differing digest (conflict) → `duplicateReferenceReplaysAndChangedPayloadConflicts`; replay-before-version sequencing asserted with a stale caller version; REPLAY SUCCESS and CONFLICT audits asserted; no `insertEntry`/`updateInvoice`/`nextReceiptNo`.

Coverage command for the touched area: `mvn -q -B -f apps/api/pom.xml -Pno-security -Dtest=FinanceNegativePathsTest test` → 5 tests, 0 failures, 0 skipped. No threshold, exclusion, suppression or skip was added.

## Review verdicts
- plan-reviewer: GO
- code-reviewer: GO
- coverage-
