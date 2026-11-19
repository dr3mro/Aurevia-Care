# S-G5-06 — Recovery targets, restore into a clean environment and operational runbooks

## Story / scope

- Story: S-G5-06 (attempt 6/resume), branch `swarm/S-G5-06-114`, base SHA `2945531`.
- Requirements: **OPS-04**, **OPS-05**.
- Scope honoured: `infra/s_g5_06/**`, `docs/operations/s_g5_06/**` and this PR-notes file. No tracker,
  CI, application, schema or shared file touched. Reserved migration **V48 deliberately unused**
  (no schema change; version gaps are allowed by `scripts/check-migrations.py`).
- Synthetic data only. No real identifier, credential, patient data or log is present.

## Resume state / honesty about authorship

The complete implementation and its test suite were already present at the resume checkpoint
(`2945531`). I re-read every scoped file, re-ran all fast checks and the policy gates, then added a
**build-failing 100% line-coverage gate** (the quality bar requires one; the prior `check.sh` only
measured coverage and its documented `trace --count` command was vacuous). Three real defects were
found and fixed test-first: the vacuous coverage measurement (missing `--missing`), a cover-file
selection bug that measured the test module instead of the evaluator, and a stale documented test
count. A bounded senior-python review then raised seven robustness findings on the new gate, all
addressed before commit (F1–F7 below). This note is the pull-request description committed with the
work; earlier review findings from attempt 4 are retained below for continuity.

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
| `python3 -I -m unittest discover -s infra/s_g5_06/tests` | **97 tests OK** (0.09s): 80 evaluator tests from the prior checkpoint plus 17 coverage-gate tests added this resume |
| `bash infra/s_g5_06/check.sh` | **exit 0**; 97 tests OK; coverage gate PASS; synthetic fixture PASS; violation fixture FAIL (all expected checks); final banner confirms |
| `python3 -I infra/s_g5_06/tests/coverage_gate.py` | **exit 0**; `coverage gate: PASS - 100% line coverage (271 lines across 1 module(s), 0 uncovered)` — real `trace --count --missing` measurement |
| F1 regression proof (one-off, out of tree) | Running the same probe with `--count` only detected `[]` uncovered lines, so the pinned assertion `== [3]` would fail if `--missing` were removed from `run_traced_suite` — the gate's safety property is now tested, not just hard-coded |
| RED then GREEN for the coverage-gate helper | new `test_coverage_gate.py` importing the not-yet-written `coverage_gate.py` → `Ran 81 tests ... FAILED (errors=1)` (`ModuleNotFoundError`); after implementing the helper → `Ran 85 tests ... OK` |
| RED then GREEN for cover-file selection | two `FindCoverFileTest` cases → `FAILED (failures=2)` (test module's cover file selected); after excluding `test_recovery_evidence.cover` → OK |
| RED then GREEN for the coverage gate itself | gate with `--missing` first reported `FAIL - 1 uncovered line(s): [271]` (the `__main__` guard) and earlier the 554-line test cover file; after adding `#pragma NO COVER` to the CLI guard and fixing file selection → `PASS ... (271 lines, 0 uncovered)` |
| `TraceIntegrationTest.test_trace_marks_a_never_called_function_as_uncovered` | real `trace` run on a synthetic probe module with an unexecuted branch → gate detects exactly the uncovered line; proves the gate is not vacuous |
| `python3 -I infra/s_g5_06/recovery_evidence.py infra/s_g5_06/fixtures/synthetic_recovery_evidence.json` | exit 0; `verdict: pass`, `classification: rehearsal`, `final_evidence: false`, all 20 checks PASS |
| `python3 -I infra/s_g5_06/recovery_evidence.py infra/s_g5_06/fixtures/violation_recovery_evidence.json` | exit 1; failed checks for both targets, missing scope/restore members, clean_environment, two reconciliation gates, before_pilot, signals, clinical owner, runbooks, duplicate_prevention |
| `python3 -I -m trace --count --missing --coverdir <tmp> infra/s_g5_06/tests/run_coverage.py` | exit 0; evaluator cover report has **0 uncovered lines** (271 lines). `--missing` is mandatory: `--count` alone never annotates unexecuted lines |
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

No correctness defect in the evaluator's OPS-04/OPS-05 decision logic. The evaluator, fixtures and
contract are internally consistent (`ContractConsistencyTest` pins contract literals to evaluator
constants) and fail closed on wrong types, missing/duplicated members, truthy values and
contradictory evidence classes. The three defects found this resume are in the coverage tooling and
documentation, listed below.

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

3. **Build-failing coverage gap** (owner: this resume; supersedes the earlier "accepted exception"):
   the canonical `check.sh` measured coverage but could never fail on it, contrary to the quality
   bar's build-failing gate. **Fixed**: added `infra/s_g5_06/tests/coverage_gate.py`, wired into
   `check.sh`, that runs the suite under `trace --count --missing` and exits 1 on any uncovered
   evaluator line. Branch completeness remains covered by enumerated negative tests; no fabricated
   branch metric is claimed.

4. **Vacuous coverage measurement** (owner: this resume): every documented coverage command used
   `trace --count` without `--missing`. Verified from the stdlib `trace` source that `--count` alone
   never writes the `>>>>>>` marker, so the prior "0 uncovered lines" claim could not have detected
   an uncovered line. **Fixed test-first**: a `TraceIntegrationTest` now runs real `trace` on a
   synthetic probe module with an unexecuted branch and asserts the gate reports that line. All
   documented commands now include `--missing`, and the gate hard-codes it with an explanatory
   comment.

5. **Cover-file selection bug** (owner: this resume): `find_cover_file` matched any name ending in
   `recovery_evidence.cover`, which also matches `test_recovery_evidence.cover` (554 lines). The gate
   consequently measured the test module and reported a false uncovered line. **Fixed test-first**
   (two RED `FindCoverFileTest` cases → GREEN): exclude `test_recovery_evidence.cover`; gate now
   measures the 271-line evaluator.

6. **Stale documented test count** (owner: this resume): `README.md` and `recovery-evidence.md` said
   "78 unit tests" while the suite already had 80 (now 97). **Fixed** by citation-only doc edits.

7. **Senior-python review findings** (simulated reviewer, this resume). Bounded static review of the
   two new coverage-gate files raised seven findings; all were addressed before commit:
   - **F1 HIGH** — the `--missing` flag was hardcoded but no test exercised that path, so removing it
     would silently reintroduce a vacuous gate. **Fixed**: `run_traced_suite` now accepts a `target`
     and `TraceIntegrationTest` drives the exact production command on a probe module whose known
     uncovered line must be detected; one-off proof above shows the assertion fails without the flag.
   - **F2 MEDIUM** — cover-file selection was tested only with synthetic filenames. **Fixed**: the
     integration test now calls the production `find_cover_file` on a real trace artifact.
   - **F3 MEDIUM** — ambiguous suffix match could select a decoy cover file. **Fixed**:
     `find_cover_file` requires exactly one match and raises otherwise; RED/GREEN tests for zero and
     multiple matches.
   - **F4 MEDIUM** — a future production module could escape coverage. **Fixed**: `production_modules()`
     enumerates `*.py` in the story root and the gate requires a fully-covered report for each.
   - **F5 LOW/MEDIUM** — the `#pragma NO COVER` dependency on the CLI guard was untested. **Fixed**:
     the probe carries the same pragma and asserts trace honours it on this interpreter.
   - **F6 LOW** — no timeout and opaque subprocess failures. **Fixed**: 120 s timeout and return-code
     / stderr tail in the error.
   - **F7 LOW** — too-narrow exception handler. **Fixed**: `subprocess.SubprocessError` and `ValueError`
     are caught, with a RED/GREEN `TimeoutExpired` test.
   Reviewer executed no commands (no tool available in its session); it was static only. Its finding
   that recursion was safe was correct for the original design; a new full-suite integration test I
   added did recurse (123 s, one error), so I removed it — F2 is fully covered by the probe test.

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
10. Coverage is now build-failing with stdlib `trace --count --missing` (271 evaluator lines, 0
    uncovered). The CLI `__main__` guard is a documented `#pragma NO COVER` exception whose `main()`
    is covered directly by tests. No fabricated branch metric is claimed; branch completeness is
    addressed by the enumerated negative tests.
11. The root `scripts/check.sh` does not discover `infra/**/check.sh` (it only loops over app modules
    and `db`). This is a pre-existing repo-level wiring gap, out of this story's scope; the story's
    canonical gate remains `infra/s_g5_06/check.sh` and the integrator's environment may run it
    explicitly. No shared script was edited.

## Evidence-source labels

- Commands and coverage above: **executed tool** evidence (this worker): 97 tests, coverage gate,
  `check.sh`, docs/secrets/migrations gates, and the one-off F1 regression proof.
- Code review: **simulated specialist reviewers** — attempt 4 senior-python and system-designer; this
  resume's bounded static senior-python review of the new coverage gate. All findings and dispositions
  are recorded in "Defects found" above.
- No **human participant**, clinician or real pilot feedback was used or implied. No patient data.

## Remaining gate (explicitly out of scope for this repository)

A real conducted restore drill with measured RPO/RTO, reconciliation results, named owners and a
dated drill record must be produced in an authorized environment; quarterly cadence and final human
G5 acceptance cannot be attested by a script. This story delivers the contract, evaluator, runbooks
and a synthetic rehearsal of the check only.
