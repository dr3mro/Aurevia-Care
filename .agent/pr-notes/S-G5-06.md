# S-G5-06 — Recovery targets, restore into a clean environment and operational runbooks

## Story / scope

- Story: S-G5-06 (attempt 4/resume), branch `swarm/S-G5-06-112`, base SHA `ddbd816`.
- Requirements: **OPS-04**, **OPS-05**.
- Scope honoured: `infra/s_g5_06/**`, `docs/operations/s_g5_06/**` and this PR-notes file. No tracker,
  CI, application, schema or shared file touched. Reserved migration **V48 deliberately unused**
  (no schema change; version gaps are allowed by `scripts/check-migrations.py`).
- Synthetic data only. No real identifier, credential, patient data or log is present.

## Resume state / honesty about authorship

The complete implementation and its test suite were already present at the resume checkpoint
(`ddbd816`): 12 scoped files (~1,440 lines). I re-read every scoped file, re-ran all fast checks,
verified coverage and the policy gates, and obtained two independent specialist reviews. The reviews
raised no correctness defect; they did raise two low-severity improvements (documentation
requirement-ID traceability and contract-consistency test completeness), both fixed test-first or by
citation-only edits as recorded below. This note is the pull-request description committed with the
work.

## Acceptance mapping (specification clause -> implementation -> evidence)

| Spec clause | Enforced as | Evidence |
|---|---|---|
| OPS-04 RPO <= 15 min | `targets.rpo_minutes` number in `[0,15]`; unit pinned by field name; non-finite/boolean/string rejected | `recovery_evidence.py::_check_targets`; tests `TargetsTest` (limit, above-limit, 0, negative, NaN/inf, bool, string) |
| OPS-04 RTO <= 4 h | `targets.rto_hours` number in `(0,4]` | same; `TargetsTest` rto cases |
| OPS-04 recover core chart, order data, documents **and their associations** | `scope` must contain all four named classes, no duplicates, extras allowed; exact (not substring) membership | `REQUIRED_SCOPE`, `_check_required_members`; `CoverageListTest`, incl. `documentation` near-miss |
| OPS-04 restore database, objects, IdP dependencies | `restore.targets` must name all three | `_check_restore`; `CoverageListTest` restore cases |
| OPS-04 into a clean environment | `restore.clean_environment` must be literal `true` (not truthy) | `_check_restore`; boolean/string negative tests |
| OPS-04 reconcile hashes, source versions, in-flight deliveries, audit coverage before writes reopen | four named gates plus `completed_before_writes_reopened`, each literal `true` | `REQUIRED_RECONCILIATION`, `_check_reconciliation`; `ReconciliationTest` |
| OPS-04 verify before pilot and quarterly | `verification.before_pilot` and `quarterly_cadence` literal `true` | `_check_verification`; `VerificationTest` |
| OPS-05 13 monitoring signals | `monitoring.signals` must name all 13, no duplicates | `REQUIRED_MONITORING_SIGNALS`, `_check_monitoring`; `MonitoringTest` |
| OPS-05 operational on-call + site clinical coverage owners | both non-blank, non-placeholder strings | `_check_ownership`, `OWNER_PLACEHOLDERS`; `OwnershipTest` |
| OPS-05 four runbooks (paper/manual capture, patient identification, critical contacts, supervised back-entry) | `runbooks` must name all four, no duplicates | `REQUIRED_RUNBOOKS`, `RunbooksTest` (incl. `downtime_drills` near-miss); topic bodies in `operational-runbooks.md` |
| OPS-05 prevent duplicates when recovering | `duplicate_prevention` literal `true`; procedure detail in restore runbook | `_check_duplicate_prevention`; `DuplicatePreventionTest`; `restore-procedure.md` §5 |
| Evidence honesty | synthetic rehearsal is never final evidence; conducted drill with synthetic-labelled source => `evidence_conflict` | `_verdict`, `evaluate`; `ConformingArtifactTest`, `StructureTest` |

Cross-references (IAM-02/IAM-05 revocation reapplication, ARC-03 idempotency and durable
uniqueness, ARC-05 event dedup, XCH-05 acknowledgment, ADM-01 approved rosters, PAT-01/MSG-05
generic notifications) are named in `README.md` and at the point of use in the runbooks; they add
no new threshold.

## Exact commands run and results (tool evidence)

All run from the repository root, `python3 -I` isolated mode, working tree clean at start.

| Command | Result |
|---|---|
| `python3 -I -m unittest discover -s infra/s_g5_06/tests -q` | **80 tests OK** (0.007s) after the review fixes (was 78 at checkpoint) |
| `bash infra/s_g5_06/check.sh` | **exit 0**; 80 tests OK; synthetic fixture PASS; violation fixture FAIL (all expected checks); final banner confirms |
| RED then GREEN for the contract-pin fix | added `ContractConsistencyTest` assertions for `schema_version`, `verification`, `ownership_roles` and target min bounds plus two new behavior tests → `Ran 80 tests ... FAILED (errors=1)` (`AttributeError: REQUIRED_VERIFICATION_FLAGS`); added `REQUIRED_VERIFICATION_FLAGS` / `REQUIRED_OWNERSHIP_ROLES` constants and used them in the evaluator → `Ran 80 tests ... OK` |
| `python3 -I infra/s_g5_06/recovery_evidence.py infra/s_g5_06/fixtures/synthetic_recovery_evidence.json` | exit 0; `verdict: pass`, `classification: rehearsal`, `final_evidence: false`, all 20 checks PASS |
| `python3 -I infra/s_g5_06/recovery_evidence.py infra/s_g5_06/fixtures/violation_recovery_evidence.json` | exit 1; failed checks for both targets, missing scope/restore members, clean_environment, two reconciliation gates, before_pilot, signals, clinical owner, runbooks, duplicate_prevention |
| `python3 -I -m trace --count --coverdir /tmp/opencode/s_g5_06_cov infra/s_g5_06/tests/run_coverage.py` | exit 0; `recovery_evidence.cover` has **0 uncovered lines (`>>>>>>` marks)** |
| `python3 -I scripts/check-docs.py` | exit 0 (all relative links resolve; all requirement IDs defined) |
| `python3 -I scripts/check-secrets.py` | exit 0 (no credential/PHI-shaped string introduced) |
| `python3 -I scripts/check-migrations.py --require-baseline` | exit 0 (V48 gap allowed; no schema change) |
| `bash -n infra/s_g5_06/check.sh` | exit 0 |

## Pre-existing baseline observations (not introduced by this story)

- `python3 -I scripts/gen-opencode.py --check` -> `opencode.json is out of date`. The worktree was
  clean at start and this story changes no agent, skill or `opencode.json` file. This is a
  prepared-image/CI-profile condition, left untouched as out of scope.
- `python3 -I -m unittest discover -s scripts/tests -q` -> 163 tests, 8 failures + 4 errors, all in
  unrelated areas (stale `opencode.json` pack tests, swarm/agent-run tests, Java-convention findings
  in `G3EvidenceService.java` and `PatientFlowContract.java`). None references `infra/s_g5_06` or
  `docs/operations/s_g5_06`. Not modified here.
- `shellcheck` and PyYAML are not installed in this worker image; `bash -n` was used for shell syntax.

## Defects found

No correctness defect in the scoped implementation. The prior checkpoint's evaluator, fixtures,
contract and docs are internally consistent (`ContractConsistencyTest` pins contract literals to
evaluator constants) and fail closed on wrong types, missing/duplicated members, truthy values and
contradictory evidence classes.

1. Low-severity **traceability** finding from the system-designer review: the duplicate-prevention
   procedure in `restore-procedure.md` reproduced ARC-03's idempotency rules and ARC-05's event-dedup
   rules without naming those IDs, and the README cross-reference example named ARC-05 but omitted
   ARC-03. **Fixed**: tagged the idempotency/durable-uniqueness bullets `(ARC-03)`, the event-dedup
   bullet and the `in_flight_deliveries` reconciliation row `(ARC-05)`, and listed both IDs in the
   README cross-reference note. No spec literal or evaluator behavior changed.

2. Low-severity **test completeness** finding from the senior-python review: `ContractConsistencyTest`
   did not pin `schema_version`, `verification`, `ownership_roles` or the target min bounds, so a
   future contract edit could drift silently while the test still passed. **Fixed test-first**
   (RED/GREEN evidence in the table above): introduced `REQUIRED_VERIFICATION_FLAGS` and
   `REQUIRED_OWNERSHIP_ROLES`, used them in `_check_verification` / `_check_ownership`, and extended
   the consistency test. Also added a `conducted_drill` + blank/non-string source test and an RTO=0
   rejection test to complete branch enumeration (78 → 80 tests).

3. Low-severity **process** finding from the same review: the canonical `check.sh` measures coverage
   but does not fail the build on it. **Reviewed and accepted as an exception**: this matches all
   sibling S-G5 evaluator modules (`s_g5_05`/`07`/`08`/`09` check.sh files run unit tests and fixture
   checks only), and this story changes no production service. Coverage is measured explicitly with
   stdlib `trace` (0 uncovered lines reported above); stdlib `trace` measures lines only, and branch
   completeness is addressed by the enumerated negative tests rather than a fabricated branch metric.

## Decisions

1. Enforce only the literals and named sets that OPS-04/OPS-05 actually specify; inventing extra thresholds would overstate the contract.
2. Units are carried by field names (`rpo_minutes`, `rto_hours`) to prevent unit fail-open.
3. RPO = 0 is accepted (zero data loss); RTO must be strictly positive.
4. Coverage lists use "must include, no duplicates, extras allowed" semantics.
5. All boolean gates use strict `is True`; a string `"false"` cannot pass.
6. `kind` is the authority for evidence class; a synthetic-labelled source on a conducted drill is a conflict.
7. Revocation/withdrawal reapplication is a mandatory *runbook* gate derived from IAM-02/IAM-05/§11, not a fabricated evaluator threshold.
8. V48 unused; no schema change.
9. The evaluator judges an artifact, never a running system; a real drill and human G5 acceptance remain open.
10. Coverage gate follows the sibling S-G5 evaluator pattern (unit + fixture gate; stdlib-trace line
    coverage measured separately, 0 uncovered lines) rather than a fabricated build-failing branch metric.

## Evidence-source labels

- Commands and coverage above: **executed tool** evidence (this worker).
- Code review: **simulated specialist reviewer** (senior-python, system-designer); all findings and dispositions are recorded in "Defects found" above.
- No **human participant**, clinician or real pilot feedback was used or implied. No patient data.

## Remaining gate (explicitly out of scope for this repository)

A real conducted restore drill with measured RPO/RTO, reconciliation results, named owners and a
dated drill record must be produced in an authorized environment; quarterly cadence and final human
G5 acceptance cannot be attested by a script. This story delivers the contract, evaluator, runbooks
and a synthetic rehearsal of the check only.
