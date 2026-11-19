# S-G3-12 — Restore, downtime, load, accessibility and audit evidence (G3)

## Requirement IDs and acceptance mapping

Primary gate: **G3** (restore, downtime, load, accessibility and audit evidence).
Requirements evidenced: **OPS-04** (restore RPO/RTO/reconciliation), **OPS-05** (downtime runbooks, idempotent replay), **OPS-03** (load/capacity p95 + availability), **UX-01** (English/Arabic, RTL, native screen-reader, dynamic type), **OPS-02** (audit completeness and append-only chain), **ARC-03/ARC-05** (idempotency, durable events and replay). Specification: `SMARTCLINIC_V1_SPEC.md` §8 OPS-02/03/04/05, §7 ARC-03/05, §6 UX-01, §9 G3 gate row.

| G3 evidence dimension | Implementation (synthetic only) | Test evidence |
|---|---|---|
| Restore — snapshot/journal replay reconciles, RPO ≤ 15 min, RTO ≤ 4 h, no duplicate/unknown/incomplete events | `GateEvidenceEvaluator.evaluateRestore`; immutable `Snapshot`/`JournalEvent`/`RestoreResult` values | `GateEvidenceTest` (restore: 6 tests) |
| Downtime — four runbook elements present; idempotent replay has no conflicting digest, mismatched payload, duplicate, unknown or incomplete effect | `GateEvidenceEvaluator.evaluateDowntime`; `DowntimeRunbook`/`IdempotentCommand` values | `GateEvidenceTest` (downtime: 3 tests) + standalone red/green driver (below) |
| Load — pilot profile 20/100000/200/50/30, read p95 ≤ 500 ms, command p95 ≤ 900 ms, availability ≥ 99.9% | `GateEvidenceEvaluator.evaluateLoad`; `LoadProfile.PILOT`, `LatencySample`, `Availability` | `GateEvidenceTest` (load: 4 tests, incl. boundary values) |
| Accessibility — LABEL/ROLE/SCREEN_READER/RTL checks for `en` and `ar`, named owner, correct RTL; native roles/labels and RTL row mirroring in the app | `GateEvidenceEvaluator.evaluateAccessibility` (server); `evaluateAccessibility`/`evidenceSummaryLabel` + `AccessibilityEvidenceSummary` (patient app) | `GateEvidenceTest` (accessibility: 3 tests); mobile `index.test.tsx` (18 tests) |
| Audit — all OPS-02 mandatory fields on every event; chain validity folds into the verdict | `GateEvidenceEvaluator.evaluateAudit` + `EvidenceLedger.verifyChain` | `GateEvidenceTest` (audit: 2 tests + ledger chain/tamper tests) |
| Composite report — one immutable record per category, tamper-evident chained ledger, PASS only when all five records in the call pass; tenant ID validated, never inferred from fixtures | `G3EvidenceService.produce`, `EvidenceLedger`, `EvidenceHash` | `GateEvidenceTest` (service: 4 tests) + standalone red/green driver |

Entry-point-to-test trace: `G3EvidenceService.produce(organizationId, EvidenceInput)` → reject blank org (422 `INVALID_ORGANIZATION`) → fresh per-call `EvidenceLedger` → `evaluateRestore/Downtime/Load/Accessibility` → append 4 records → `evaluateAudit(input.audit(), current.verifyChain())` → append audit record → composite `GateEvidenceReport` (PASS iff all five records in this call PASS). Asserted by `GateEvidenceTest.serviceProducesAllFiveRecordsAndAValidChain`, `serviceReportsFailVerdictWhenAnyCategoryFails`, `serviceRejectsMissingOrganization`. Patient-app trace: `AccessibilityEvidenceSummary` ← `evaluateAccessibility(CONFORMING_ELEMENTS)` ← synthetic bilingual `UiElementDescriptor` fixtures → React Native Testing Library assertions on accessibility roles, labels and RTL `flexDirection`.

## Defects found and fixed this cycle (TDD red → green)

Two independent read-only specialists (test-engineer and software-architect lenses) reviewed the slice. Both independently found **F1**; all three verdict-corrupting defects below were reproduced **red** against the original code with a standalone synthetic driver (`/tmp/opencode/G3FixCheckAll.java`, not committed) and **green** after the fix, then the full fast suite and 100% coverage were re-run.

- **F1 (HIGH, both reviewers) — shared mutable ledger on a singleton service.** `G3EvidenceService` is a `@Service` singleton that owned one `EvidenceLedger`; each `produce()` call appended to the previous chain and built the report/verdict from **all** entries. Second call returned 10 records and stayed FAIL after an earlier failing call, and records from a different call/organization leaked into the report (ARC-02/OPS-02). Fix: rebuild an `EvidenceLedger` per call, make `produce()` `synchronized`, expose only the most recent chain via `ledger()`, and scope the verdict to the current call. Red driver: `F1b 5 records (got 10)`, `F1c second PASS` failed; green: both pass.
- **F2 (HIGH, test-engineer) — replay effect digests never checked.** `evaluateDowntime` read only `effect.key()`; an effect with the right key but a different digest PASSED, violating ARC-03/ARC-05 "different payloads using the same key yield conflict." Fix: each effect must match both the original key **and** its payload digest, else `DIGEST_MISMATCH`. Red: `F2 wrong-digest replay FAIL` failed; green: passes.
- **F3 (MEDIUM, test-engineer) — RPO/RTO boundary truncation.** `Duration.toMinutes()` truncates, so 15 m 59 s RPO and 4 h 59 m RTO PASSED. Fix: compare full `Duration` values to the 15-min / 240-min limits. Red: `F3 15m59s RPO FAIL` failed; green: passes.
- **F4 (MEDIUM, both reviewers) — chain validity measured before append.** `evaluateAudit` received `ledger.verifyChain()` before the current records were appended, so the audit record and `chainHead` described a different chain. Fix: append the four non-audit records first, verify that chain, then build and append the audit record. No new branches; coverage preserved.

Modified in scope: `apps/api/.../messaging/s_g3_12/G3EvidenceService.java`, `GateEvidenceEvaluator.java`. Corrected the service Javadoc's tenant-attribution overstatement (organizationId is validated nonblank, not bound to an authenticated subject — that binding belongs to the transport boundary, outside this slice).

### Findings recorded, not changed (disposition)

- **UX-01 under-coverage / empty-set semantics:** Java fails closed on no checks (`NO_CHECKS`), mobile passes an empty descriptor set (`index.test.tsx:19-21`), and neither represents staff-web WCAG 2.2 AA, per-platform native screen-reader, IANA/DST, local-name or export-unit checks. These dimensions are owned by S-G5-05/S-G5-06/S-G5-10 and S-G3-11; aligning the vocabulary and adding dimensions is a cross-story change outside this worker's scope. Recorded so S-G3-13 does not over-claim.
- **Evidence depth (OPS-02/03/04/05):** categories check field completeness and the four runbook elements but not object/IdP restore, monthly availability window, or monitoring/queue-age dimensions. Delegated to S-G5-05/S-G5-06 and recorded as evidence gaps for the G3 gate matrix, not silent PASS.
- **Ledger canonicalization (LOW):** `canonical()` omits sequence and concatenates with an unescaped `|`. Renumbering is already detected by the positional sequence check in `EvidenceLedger.verify`; detail strings are internally generated. Accepted risk; hardening would change hash preimages across the slice.
- **Value validation (LOW):** audit `outcome` accepts any nonblank value; blank command keys/digests and `successes > total` are not rejected. Synthetic fixtures are trusted here; fail-closed hardening is a follow-up.

## Commands run and results (evidence source: executed tool commands and standalone driver)

- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast -Dtest=GateEvidenceTest test` → EXIT 0; **27 tests, 0 failures/errors/skipped**.
- `mvn -q -B -f apps/api/pom.xml -Pno-security,fast test` (exact integrator fast command) → EXIT 0; Surefire aggregate **1463 tests, 0 failures/errors/skipped** across 134 suites (run before and after the fixes).
- JaCoCo report on the s_g3_12 slice → **INSTRUCTION 1542/1542, BRANCH 158/158, LINE 279/279, METHOD 50/50 (100% each)** after the fixes.
- Standalone red/green driver (synthetic): original code → `TOTAL_FAILURES=4`, exit 1 (`F1b`, `F1c`, `F2`, `F3` failed); fixed code → `TOTAL_FAILURES=0`, exit 0. Driver lives in `/tmp/opencode/` and is **not** part of this commit.
- `npm ci --no-audit --no-fund` (patient-mobile) → EXIT 0 (1272 packages). `npx jest src/features/s_g3_12 --ci` → EXIT 0, **18 tests**. `npm run test:coverage -- --ci` → EXIT 0, **25 suites, 230 tests**, 100% global branches/functions/lines/statements; `s_g3_12` executable files at 100%. `npm run typecheck` → EXIT 0; `npm run lint` → EXIT 0.
- `python3 -I scripts/check-docs.py` → EXIT 0; `python3 -I scripts/check-secrets.py` → EXIT 0; `python3 -I scripts/project-name.py displayName|repositorySlug` → `Aurevia Care` / `aurevia-care`.

Database/integration gates (`db/check.sh`, `mvn verify`, JaCoCo build-failing check, real-PostgreSQL isolation) were **not** run by this worker per the contract; the integrator runs them. Reserved version **V40** is intentionally unused: this is pure in-memory deterministic evidence evaluation with no schema change, and `db/migrations` is outside the declared scope.

## Decisions / notes for the integrator

- This is attempt 3; the slice was already present in the base commit. This worker's contribution is verification plus the three verdict-corrupting fixes above. No fabricated "red first" claim: red is demonstrated by the standalone driver reproduced against the unmodified base, not by an added test file.
- The Java test file (`apps/api/src/test/.../s_g3_12/GateEvidenceTest.java`) is outside this worker's editable scope, so dedicated regression tests for F1–F4 were **not** added. The fixes preserve all 27 existing tests and 100% coverage. **Recommended follow-up (integrator or next story):** add a `produce()`-twice assertion (5 records + PASS), a wrong-digest effect case, and a 15m59s RPO case to `GateEvidenceTest.java`. These are uncommitted recommendations only.
- All fixtures are synthetic; no patient data or credentials used or recorded. No human or clinical evidence; all evidence is tool-generated (executed commands, standalone driver) plus read-only specialist review.
- Scope respected: only the two declared paths were worked in. No shared tracker, `docs/swarm`, migration, workflow or root-level file was edited. No push, tag or `gh` call was made.
