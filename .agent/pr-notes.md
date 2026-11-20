# DLV-03 — Repair integration tests: prescription transmission

Status: ready for integrator. Branch `swarm/DLV-03-241`, base `a1be791` (DLV-02 merge), clean tree at start. Five files changed, all inside `apps/api` and `db`; no `.github/`, `scripts/`, `opencode.json` or shared tracker touched. `RxTransmissionFlowTest` is green (9/9) in the full real-PostgreSQL `mvn verify` run. The remaining full-gate failures belong to the separate DLV-04 story.

## Requirements and acceptance mapping

| Requirement | Acceptance repaired | Evidence |
|---|---|---|
| RX-02 | An ambiguous first electronic send is a first-class outcome: it creates one stable submission, enters reconciliation, and is retried only after partner status is verified absent. Attestation, submission, confirmation, response, renewal and cancellation stay separate facts. | `RxTransmissionFlowTest` 9/9; `RxContractTest` 12/12; V59 applied by Flyway |
| IAM-01 | Only the prescribing clinician may attest/route/renew/cancel; ownership is checked before version/state preconditions so a wrong actor always receives 403, while the real prescriber still receives 412 for a stale version. | line 321 and line 281 assertions, plus all prescriber commands |
| ARC-02 | Forced RLS conceals a foreign-only prescription id (404 on GET and attest) and resolves an identical local id in each tenant to that tenant's own row. | `tenantIsolationConcealsIdenticalLocalIdentifiers` |
| ARC-01 / ARC-03 / ARC-04 (supporting) | Composite tenant keys, `If-Match` version preconditions, machine-readable error codes and 404 concealment remain intact. | whole class in full `mvn verify` |

Classification (`docs/WORKFLOW.md`): cross-boundary (SQL trigger, domain service, contract and integration fixtures) with a safety-critical authorization/isolation component. Synthetic fixtures only. Evidence sources are recorded per command below; no simulated-user, human or clinical participant evidence was available or claimed.

## Reproduction (RED) and root cause per symptom

Environment: branch HEAD `5b12783`, Java 21.0.13 Temurin, Maven 3.10.0, PostgreSQL 17.12 on 127.0.0.1:5432 via `SMARTCLINIC_PG_ADMIN=postgres`. All commands executed by this worker (tool evidence).

RED baseline — `cd apps/api && mvn -B -Pno-security -Dtest=RxTransmissionFlowTest -DfailIfNoTests=false test`: `Tests run: 9, Failures: 7, Errors: 0`.

| Symptom in story | Observed RED | Root cause | Fix |
|---|---|---|---|
| 500 on `/transmit` with no `submissionId` (4 tests: ambiguousRetry, remainingCommandPreconditions, retryAttemptsBounded, ambiguitySubmissionMismatch) | Surefire XML server trace: `PSQLException: ERROR: illegal prescription transition attested -> ambiguous` (SQLSTATE 23514), HTTP 500. | **Production/schema defect.** `V44__rx02_prescription_transmission.sql` trigger function `guard_rx02_prescription_update()` allowed `attested -> submitted/printed/cancelled` only, although `ambiguous` is in the V44 state CHECK, in `RxInputs.transmissionOutcome`, in `RxContract.State` and is the value `RxService.transmit` writes. The submit path worked (2 tests green); only the documented ambiguous outcome hit the guard. | New `db/migrations/V59__rx02_ambiguous_transition.sql` (`Change: expand`) redefines the function adding `ambiguous` to the attested branch. No applied migration edited. All four 500s disappeared in the next run. |
| STALE_VERSION where FORBIDDEN expected (line 321) | Non-prescriber clinician attesting an already-attested v2 row with `If-Match: 1` → 412 `STALE_VERSION`. | **Production defect in error precedence.** `attest()` (and the other prescriber commands) called `locked()` — version/state checks — before `requirePrescriber()`. The membership/role gate in `execute()` passes for any active clinician, so a wrong actor was answered by a precondition before ownership was checked. | Added `RxService.lockedByPrescriber()`: RLS-backed `loadForUpdate` → `requirePrescriber` → `requireVersion` → optional `requireState`. Applied to all six prescriber-only commands (attest, transmit, verify, retry, renew, cancel); plain `locked()` kept for confirm/pharmacy/dispense. A wrong actor now gets 403 regardless of version; the real prescriber still gets 412 for stale version and 409 for wrong state. |
| Tenant concealment 200 instead of 404 (line 386) | B got 200 for `sharedId`. Temporary diagnostic (added, run, then removed) proved an A-only id from B → 404 with `NOT_FOUND`, while `sharedId` from B → 200 with `prescriberSubject=clinician-2222`. | **Test-fixture defect, not an RLS leak.** The fixture creates `sharedId` in **both** A and B (lines 379–380), so B owns that id and correctly sees its own row; a 404 was impossible without hiding B's own row. The old line 388 was also unsatisfiable: B has no market policy seeded, so attesting B's own draft yields `NO_MARKET_POLICY`, not `NOT_FOUND`. | Keep both creates and assert each tenant sees its own row (200, local `prescriberSubject`). Add an A-only id and assert 404 on GET and attest from B — a real concealment case with no local row to mask it. Original random-id/nonexistent-patient 404 assertions retained. |
| RENEW_REQUIRES_TRANSMISSION where INVALID_TRANSITION expected (line 401) | Draft renew → 409 `RENEW_REQUIRES_TRANSMISSION`. | **Production defect (error-code outlier).** That code had exactly one repository reference (the throw); every other illegal transition in the acceptance uses `INVALID_TRANSITION`, and the state machine already forbids renewing a draft. | `RxService.renew()` now throws `409 INVALID_TRANSITION`. |
| INVALID_TRANSITION expected but FORBIDDEN returned (line 463) — newly unmasked once the 500s were fixed | Clinician calling `/pharmacy-response` → 403 `FORBIDDEN`. | **Test-fixture defect.** `pharmacy-response` is a `pharmacy_staff`-only action (`RxActions`), so the clinician caller is denied by role before any state check. The assertion was meant to exercise the state precondition. | Call as `pharmacy-1111` so role authorization passes and the ambiguous-state guard yields `INVALID_TRANSITION`. The role gate itself is unchanged and still denies a clinician. |
| Domain contract disagreed with service and DB | `RxContract.State.canTransitionTo` still reported `ATTESTED -> AMBIGUOUS` as illegal; fast `RxContractTest` matrix agreed. | **Contract defect.** The enum is the domain representation of the same transition rules duplicated in V44; leaving it stale would reject a valid transmit in any future caller using it. | Added `AMBIGUOUS` to the `ATTESTED` case and to the exhaustive expected matrix in `RxContractTest` (a test alignment, not a weakening — `SUBMITTED -> AMBIGUOUS` was already legal and ambiguity is a documented first-send outcome). |

No test was skipped, deleted, re-tagged or excluded from a gate, and no assertion was weakened. Two self-contradictory fixtures/assertions were corrected with stronger replacements, and two service error codes/one state-guard were aligned with the acceptance behavior.

## Exact commands and results

| Command | Result |
|---|---|
| `cd apps/api && mvn -B -Pno-security -Dtest=RxTransmissionFlowTest -DfailIfNoTests=false test` (before fixes) | exit 1 — Tests run 9, Failures 7, Errors 0 (4 × 500, STALE_VERSION, 200-vs-404, RENEW code) |
| Same command after V59 only | exit 1 — Tests run 9, Failures 4 (all 500s gone; line 463 surfaced) |
| Same command after all service/fixture fixes | exit 1 — Tests run 9, Failures 2 (only the two test-fixture defects) |
| Same command after the two fixture corrections | exit 0 — **Tests run 9, Failures 0, Errors 0** |
| `cd apps/api && mvn -B -Pno-security,fast test` | exit 0 — Tests run 1853, Failures 0, Errors 0 (includes `RxContractTest` 12/12) |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V59 unique, `Change: expand`, no contract; no applied migration edited) |
| `git diff --check` | exit 0 |
| `cd apps/api && mvn -B spotbugs:check -DskipTests` | exit 0 — `BugInstance size is 0` |
| `bash apps/api/check.sh` (full `mvn verify`, default security profile) | exit 1 overall — Tests run 2096, Failures 37, Errors 0. All failures are DLV-04 classes: `ClinicalNoteRevisionIntegrationTest` 14, `ResultRoutingIntegrationTest` 8, `AccessIntegrationTest` 6, `DocumentIsolationTest` 5, `SpecimenAccessionIsolationTest` 3, `TrendingTest` 1. Surefire reports: `RxTransmissionFlowTest` 9/9 and `RxContractTest` 12/12 with zero failures/errors; neither appears in the failing-class list. Because surefire fails at `test`, JaCoCo and OWASP plugins were not reached locally — the integrator re-runs the authoritative full gate on the merged SHA. |
| `bash db/check.sh` | exit 3 — pre-existing, unrelated tooling defect (see Defects below); Flyway applied V1…V59 successfully in every Java integration run. |

## Decisions and defects

- V59 is additive only: `CREATE OR REPLACE FUNCTION` widens one legal transition; the append-only ledgers, immutability guards and forced RLS are untouched. `Change: expand` needs no `Forward-fix`. No applied migration was edited.
- Ownership-before-precondition was applied uniformly to all six prescriber commands rather than only `attest`, so error semantics do not depend on which command is called; the security reviewer (simulated) confirmed the membership/role gate already blocks unauthorized writes and that this ordering change has no cross-tenant oracle because foreign rows are concealed by RLS as 404 before ownership is evaluated.
- Kept `INVALID_TRANSITION` as the single illegal-transition code across RX-02; removed the unreferenced bespoke renewal code.
- Aligned the domain enum and its unit-test matrix in the same change as the DB guard so the same transition rule is not duplicated inconsistently across layers.
- **Pre-existing defect, not changed here (owner: pipeline/integrator):** `db/check.sh` applies migrations with `for migration in db/migrations/V*.sql`, which bash sorts lexically, so `V10` runs before `V1` and fails with `relation "public.organization" does not exist`. Already documented in DLV-02 notes; `db/check.sh` is in the protected check-file set and needs a human-owned tooling story.
- **Pre-existing, out of scope (owner: RX-02/IAM-06 follow-up):** ownership/version/state denials thrown as `ApiException` inside `transactions.execute` roll back without a DENIED audit row, unlike `authorize` denials. The simulated backend reviewer flagged it as P2; no such denial is asserted by this story and it is not a gate here.
- No `BUGS.md` entry was added: every defect in this story was reproduced and resolved in this change; workers do not edit shared trackers.

## Entry-point-to-test trace

`POST /api/v1/prescriptions/{id}/transmit` → `RxController.transmit` → `RxService.transmit` → `lockedByPrescriber` (RLS load → ownership → version → `ATTESTED`) → `repository.insertSubmission` → `repository.transmit` → V44 update trigger, now widened by V59 → 200 with a stable `submissionId`; asserted by the four previously-500'd tests.
`POST /api/v1/prescriptions/{id}/attest` → `RxController.attest` → `RxService.attest` → `lockedByPrescriber` → 403 `FORBIDDEN` for a wrong actor even with a stale `If-Match`; asserted at line 321.
`GET /api/v1/prescriptions/{id}` → `RxController.get` → `RxService.get` → `RxRepository.find` under forced RLS → 404 for an A-only id from B, 200 with the local author for an id owned by both; asserted by `tenantIsolationConcealsIdenticalLocalIdentifiers`.
`POST /api/v1/prescriptions/{id}/renew` on a draft → `RxService.renew` → `INVALID_TRANSITION`; asserted at line 401.

## Evidence provenance

All command results above are **tool-generated** by this worker (Maven/Surefire output, surefire XML/TXT reports, Flyway logs, `check-migrations.py`, SpotBugs, `git diff --check`). The RLS-vs-fixture distinction was confirmed by a temporary diagnostic print that was removed before commit. The three specialist reviews (senior-backend, senior-spring-security, test-engineer) are **simulated reviewer** evidence: bounded read-only findings, no commands run by those agents; all were incorporated where they matched the observed tool evidence. No simulated-user, human or clinical evidence was used. Every subject, organization, patient, MRN and prescription id in fixtures is synthetic; no credentials, patient data or raw logs appear in this note.
