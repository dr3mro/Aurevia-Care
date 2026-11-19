# S-G4-04 — Prescriber attestation, approved routing channel, transmission events and ambiguity reconciliation (RX-02)

## Story, scope and requirements

- Story: S-G4-04 (swarm worker, this branch), reserved migration **V44** only.
- Inherited a prior-attempt implementation in the declared scope; reviewed it, found and fixed two CI-gating defects and one defense-in-depth gap, and added two synthetic tests. No tracker, workflow, script, agent/skill or out-of-scope file was edited. No push, tag or `gh` call was made.
- Primary: **RX-02** (prescriber attestation and market entitlement; approved electronic or locally printable channel; separate submitted / delivery-confirmed / pharmacy accept/reject / clarification / renewal / cancellation facts; dispensing evidence distinct from acceptance; stable submission IDs; verify partner status before retrying an ambiguous transmission). G4 gate: an ambiguous transmission reconciles **without a duplicate send**.
- Supporting: **IAM-01** (fresh membership/role re-derivation each command, domain authorization for the resource), **ARC-01/ARC-02** (tenant-owned composite keys, forced RLS, non-owner runtime role), **ARC-03/ARC-04** (version precondition via `If-Match`, machine-readable errors), **ARC-05** (append-only fact ledger committed atomically with state and audit), **OPS-02** (transactional, append-only audit of successes and authorization denials).

## Acceptance mapping (requirement → entry point → evidence)

- RX-02 attestation + entitlement: `POST /api/v1/prescriptions/{id}/attest` → `RxController.attest` → `RxService.attest` → `requirePrescriber` + `requireEntitlement` (approved `rx02.market` and `rx02.entitlement.<subject>` config fold). `RxTransmissionFlowTest.authorizationEntitlementAndChannelPoliciesFailClosed` proves wrong-actor, no-policy, disabled-entitlement, missing-entitlement and stale-version denials.
- RX-02 channel + actual-channel recording: `transmit` parses `E_PRESCRIBE` / `PRINTABLE_LOCAL`, checks `rx02.approved_channels`, stamps `channel` once (immutable in V44). Tests cover both channels, unapproved/missing channel policy and the printable path that never becomes electronically delivered.
- RX-02 separate facts + dispensing≠acceptance: append-only `rx02_event` ledger and distinct `EventType` values; `electronicWorkflowTracksEveryFactSeparately` asserts the exact six-event order and that acceptance does not create dispensing evidence.
- RX-02 stable submission IDs + ambiguity: `rx02_submission` keyed by stable `submission_id`, `ON CONFLICT DO NOTHING`; `verifyPartnerStatus` sets `retry_allowed` only for `absent`; `retry` reuses the same id with a bounded attempt count. `ambiguousRetryIsBlockedUntilPartnerStatusIsVerifiedAbsent` and `retryAttemptsAreBoundedAndReuseOneSubmission` assert one submission row despite repeated send facts; G4 no-duplicate is additionally enforced by a new DB uniqueness constraint (below).
- ARC-02 isolation: four synthetic tenants share identical local subject/prescription ids; `tenantIsolationConcealsIdenticalLocalIdentifiers` asserts cross-tenant 404 concealment.
- ARC-05/OPS-02: state change, fact append and audit append commit in one tenant-guarded transaction; `authorize` commits `DENIED` audit rows for membership/role failures.

## Defects found and fixed in this attempt

1. **CI docs gate (blocker):** `RxContract.View` compact constructor repeated `@param prescription` / `@param events` already present on the record declaration. Doxygen (`WARN_NO_PARAMDOC=YES`, `WARN_AS_ERROR=FAIL_ON_WARNINGS`) folds compact-constructor docs into the record's canonical constructor and fails on duplicate parameter sections — the exact failure previously fixed in S-G3-06. Fix: keep component documentation once on the record declaration; the compact constructor keeps prose only.
2. **JaCoCo 100% METHOD gate (blocker risk):** the `orElseThrow` lambda for a *missing* entitlement setting in `RxService.requireEntitlement` was never invoked (the existing `NO_ENTITLEMENT` case has a setting present but `"disabled"`, hitting the `if` branch). JaCoCo treats lambdas as methods under the enforced 1.00 METHOD ratio. Fix: added a synthetic test with a clinician who has no entitlement setting at all.
3. **G4 no-duplicate defense in depth:** `rx02_submission` had only `PRIMARY KEY (org, submission_id)`, so a direct insert with a fresh id for the same prescription was accepted, and the V44 header overstated the guard. Fix: added `UNIQUE (organization_id, prescription_id)` (one downstream submission per RX-02 record), rewrote the header to state accurately what is enforced where, and added a negative DB test that inserts a fresh submission id for the same prescription and expects rejection.

## Exact commands run and results (all on synthetic data)

| Command | Result |
|---|---|
| `cd apps/api && mvn -q -B -Pno-security,fast test` | exit 0; 1491 tests, 0 failures/errors/skipped; `RxContractTest` 12/12 OK; test sources including `RxTransmissionFlowTest` compile (exit 0) |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V44 change-safety gate still green after edit) |
| `python3 -I scripts/check-docs.py` | exit 0 |
| `python3 -I scripts/check-secrets.py` | exit 0 |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_check_migrations.py' -q` | exit 0; 35 tests OK |
| `python3 -I -m unittest discover -s scripts/tests -p 'test_java_conventions.py' -q` | 2 tests, 1 **pre-existing, out-of-scope** failure: `PatientFlowContract.java` (messaging s_g3_11) has a `List<`-bearing record with only one `List.copyOf`; not touched (outside this task's scope). The fully-qualified-`{@link}` test passed. |
| `bash scripts/frontend-check.sh` | exit 0 |
| `git status --short` | only the three in-scope files above are modified |

Doxygen is not installed in this worker, so `scripts/docs.sh` could not be executed locally; the Doxygen fix follows the verified S-G3-06 convention. PostgreSQL/`psql` is not installed here, so the integration suite and `db/check.sh` were not run locally — per worker rules the integrator runs database and integration tests.

## Decisions and reviewed-but-declined findings

- A senior-backend review confirmed all RX-02 clauses, the Java/SQL state-machine alignment (all 11 states) and the observable G4 no-duplicate guarantee. Its MAJOR "audit every business denial" suggestion was **declined**: this service already follows the repo-wide convention (identical to sibling RX-01 `PrescriptionService`) of committing `DENIED` audits for membership/role authorization, while state, version and input failures throw. Diverging would be inconsistent and unverifiable without a local DB.
- A MAJOR suggestion to add a `retry_allowed` column guard to the V44 trigger was **not implemented**: both `verifyPartnerStatus(present)` and `retry` perform an `ambiguous → submitted` transition ending with `retry_allowed = false`, so a naive trigger guard would block the legitimate partner-holds-the-prescription reconciliation path. The verified-absent gate remains enforced in the domain service and evidenced by the append-only `partner_status_verified` event ledger; the one-submission-row-per-prescription constraint now backs the no-duplicate guarantee at the DB layer.
- Low-risk hardening noted but not changed: `create` defers authoritative prescriber binding to `attest` (CLN-03 allows a distinct data-entry actor); no `Idempotency-Key` (stable submission id + `If-Match` CAS already prevents duplicate downstream sends).

## Evidence provenance

Results are from executed local tools (Maven/Surefire, Python offline guards, git) on synthetic fixtures, plus two simulated reviewer reports (senior backend, test engineer). The independent test-coverage review enumerated every method, constructor, lambda and branch in the seven production files and confirmed the line-446 entitlement lambda was the only slice-specific gap (resolved by the added test), with no other uncovered production code. No real patient data, credentials or raw logs are recorded. No human or authorized clinical feedback was used. Database, integration, JaCoCo-100%, SpotBugs, OWASP and Doxygen gates are the integrator's responsibility and were not run in this worker.
