- [x] ORCA-TASK-CLASSIFICATION · Add task classification and parsed-contract mapping to the offline core
  - evidence: Red `Ran 35 tests in 0.005s`, `FAILED (errors=10)` → green `Ran 41 tests in 0.005s`, `OK`; module coverage 133 statements/64 branches 100%; full suite `Ran 1842 tests in 106.640s`, `OK`; `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; plan/code/coverage/security reviewers GO. Evidence source: tool commands and read-only reviewers.
  - pr: none — no Git or `gh` operation is permitted in this session; scoped patch handed to the trusted integrator.
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-014, ORCA-015
  - scope: scripts/orca_core.py, scripts/tests/test_orca_core.py, tasks/01307000-ORCA-TASK-CLASSIFICATION.md
  - details: Implement step 1 of `docs/orca-refactor/11-task-intelligence-design.md`. Extend `TaskContract` with default-empty skills, constraints, tests, definition_of_done and components; add `contract_from_task(task, risk)` consuming one `parse_tasks()` dict and `classify(contract, scope, routed, validation)` consuming injected scope and route-task mappings. Never copy `ROUTES`, reimplement `parse_tasks`, write trackers or launch agents. Every new line and branch needs a named test; existing tests stay unchanged.

## Loaded skills
- aurevia-delivery, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan (revised after plan-reviewer NO-GO)
1. Red tests first in `scripts/tests/test_orca_core.py` for new fields, `contract_from_task` and `classify`; then implement in `scripts/orca_core.py`. `TaskContract` gains default-empty `skills`, `constraints`, `tests`, `definition_of_done` and `components`; positional construction remains valid.
2. `contract_from_task(task, risk)` is a thin adapter over one `parse_tasks()` dict: `id` → task_id (blank id raises ValueError), `task` or `objective` → objective, `requirements` (a comma/newline-separated string) → sorted tuple, `depends`/`scope` lists → tuples, and the five new fields stay empty. Unknown `risk` raises ValueError; no second parser and no filesystem scan.
3. `classify(contract, scope, routed, validation)` takes injected `scope` (tuple of path strings), `routed` (mapping path → tuple of specialist names from `route-task.py`) and `validation` (tuple of strings). Deterministic rules: domains from path fragments (`apps/api`→backend, `apps/staff-web`→staff-web, `apps/patient-mobile`→patient-mobile, `db/migrations`→database, `.github`→workflow, `scripts`→tooling, otherwise unknown); type from the same fragments (migration/api/ui/tooling); components = sorted scope; expertise = sorted unique routed names across scope entries; validation = sorted input; complexity = len(scope) + len(acceptance) + len(requirements); risk = contract.risk. `clinical` is true only for a validation containing "clinical" or a routed name containing "physician"/"clinical"; `security` is true only for a routed name containing "security" or a scope path containing "auth"/".github". ValueError on non-empty `problems()`, empty scope or empty routed. No copied `ROUTES`, no repo scan, no tracker write.
4. Branch map with named tests: new fields default/populated; `contract_from_task` full dict, absent optional fields, blank id, unknown risk; `classify` incomplete contract, empty scope, empty routed, duplicate/unordered input producing sorted output, known and unknown path fragments, expertise from routed names, validation sorted, clinical true/false, security true/false. Existing readiness scores 30/65/100/0 are re-asserted with new fields default and populated, proving the new fields do not feed readiness (ORCA-080 remains separate).
5. Boundary test extended: `orca_core.py` source neither imports `route-task.py`/`swarm.py` nor scans the filesystem for classification.
6. Gates: targeted `test_orca_core*.py`, module coverage 100%, full Python suite, `bash scripts/python-coverage.sh`, `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`, `check-ownership.py`. No pragma, skip, exclusion or threshold change.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` → `Ran 35 tests in 0.005s`, `FAILED (errors=10)` (new functions absent). Green after implementation: `Ran 41 tests in 0.005s`, `OK`.
- (tool) Module coverage: 133 statements, 0 missed; 64 branches, 0 partial; 100%.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1842 tests in 106.640s`, `OK`.
- (tool) `bash scripts/python-coverage.sh` → `python coverage: every changed script line is covered`; all policy checks exit 0.
- (reviewer) Plan GO after five Required gaps were closed: exact injected shapes and deterministic rules, thin adapter mapping, readiness preservation, precise failure tests and clinical/security triggers.

## Coverage (branch-to-test map)
- New dataclass fields default and populated → `ContractMappingTests.test_new_fields_default_empty_and_remain_valid`, `ClassificationTests.test_new_fields_do_not_change_readiness_scores`.
- `contract_from_task`: full dict, objective-only key, filtered empty requirements, absent optional fields, blank id, unknown risk, list and non-list requirements → `test_contract_from_task_*`.
- `classify`: field derivation, sorted determinism, unknown domain, staff-web/patient-mobile/migration mappings, unrouted entries, auth-path security, clinical/security true and false through validation and routed names, failure paths → `ClassificationTests.test_classify_*`.
- Existing readiness scores 30/65/100/0 remain asserted; 65 is asserted both with default and populated new fields, and 100 with populated fields, proving they do not feed readiness.
- Boundary: no runtime imports or filesystem scanning in the analysis module → `BoundaryTests.test_classification_does_not_import_runtime_scripts_or_scan_files`.
No pragma, omit, skip, exclusion or threshold change.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO; revised plan re-reviewed GO).
- aurevia-code-reviewer: GO after Required tests were added and rechecked.
- aurevia-coverage-auditor: GO after all five branch gaps were covered.
- aurevia-devsecops: GO (no process/network/tracker-write/filesystem-scan path).
