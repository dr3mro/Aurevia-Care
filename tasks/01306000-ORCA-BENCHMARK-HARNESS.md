- [-] ORCA-BENCHMARK-HARNESS · Add a passive deterministic benchmark and evidence harness
  - agent: orca
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-002, 007, 028, 041, 044, 045, 051, 059, 081
  - scope: scripts/orca_benchmark.py, scripts/tests/test_orca_benchmark.py, tasks/01306000-ORCA-BENCHMARK-HARNESS.md
  - details: Implement `docs/orca-refactor/09-benchmark-design.md` as a passive measurement library only. No Git, tokeniser, wall-clock, agent launch, network or production import. Unavailable metrics stay textual; targets are never reported as results. Every added line and branch needs a named test; no pragma, skip, exclusion or threshold change.

## Loaded skills
- aurevia-delivery, aurevia-senior-python, aurevia-test-engineer, aurevia-plan-reviewer, aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops.

## Plan (revised after plan-reviewer NO-GO)
1. Scope/authority: `scripts/orca_benchmark.py` is a passive sibling of the single offline core (`scripts/orca_core.py`, owner ruling `MEMORY.md:68`), not a second framework. Mirror `test_orca_core.BoundaryTests`: no production script imports it, and an AST/source test rejects `subprocess`, Git, network, tokeniser and wall-clock APIs. Every public function gets a docstring with inputs, outputs, errors and the anti-fabrication invariant.
2. Red tests first in `scripts/tests/test_orca_benchmark.py` for the API in `09-benchmark-design.md`, then implement `scripts/orca_benchmark.py`. Reuse `scripts/tests/helpers.py:ROOT`, not a parallel fixture helper.
3. API: `count_files`, `count_bytes`, `doc_load_count`, `count_tests`, `count_agents`, `count_skills`, `measure`, `EvidenceSource` (five values including `UNAVAILABLE`), `record`, `first_attempt`, plus a ratio helper storing numerator, denominator and value together.
4. Branch map: empty and non-empty glob; empty and summed bytes; `doc_load_count` counts a synthetic profile of non-5 length and the generated config has exactly 5 native entries including `CONSTRAINTS.md`; empty, class-nested and non-`test_*` AST definitions; empty and populated agent/skill trees; measure shape and determinism; ratio with zero denominator; valid/unknown source, blank metric and denied-metric `record()` paths; each missing-field path of `first_attempt()` separately (acceptance missing, repair cycles missing, acceptance false, repairs greater than zero, all present).
5. Anti-fabrication tests: numeric values for tokens, duration, agent invocations and live first-attempt become textual `"unavailable"`; the 40/30/25/50/90 figures are labelled targets and are never returned as measurements; evidence rows are never averaged across source types; no wall-clock claim appears.
6. Gates: targeted red→green, module coverage 100%, full Python suite, `check-constraints.py`, `check-docs.py`, `check-names.py`, `check-pack.py`, `gen-opencode.py --check`. Record the exact `COVERAGE_BASE` and the verbatim final line of `bash scripts/python-coverage.sh`; if red on the stale base, the task stays `[-]`. Module 100% never substitutes for the changed-line gate.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_benchmark*.py' -q` → `Ran 17 tests in 0.004s`, `FAILED (errors=16)` with `FileNotFoundError: scripts/orca_benchmark.py`. Green after implementation: `Ran 18 tests in 0.019s`, `OK`.
- (tool) Module coverage: `python3 -m coverage run --branch --source=scripts` with the targeted tests, then `coverage report --include='scripts/orca_benchmark.py' -m` → 57 statements, 0 missed; 14 branches, 0 partial; 100%.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1823 tests in 109.551s`, `OK`. Three planner tests initially failed because `waiting_waves()` made a live `gh run list` and found real queued waves in this environment; their fixtures now mock `waiting_waves` to 0 (test-only hermetic fix, no production change), after which `test_swarm_cli_coverage.py` is 59/59 and the full suite green.
- (tool) `bash scripts/python-coverage.sh` → exit 0, verbatim final line `python coverage: every changed script line is covered`; all policy checks exit 0.
- (reviewer) Plan GO after five Required gaps were closed: passive-sibling boundary, counted doc loads, complete branch list, explicit anti-fabrication tests and stale-base reporting.

## Coverage (branch-to-test map)
- `count_files`: empty and non-empty glob → `CountTests.test_count_files_handles_empty_and_matching_globs`.
- `count_bytes`: empty and summed → `test_count_bytes_sums_only_given_paths`.
- `doc_load_count`: synthetic non-5 profile, real 5-entry config, missing and malformed → `test_doc_load_count_*`.
- `count_tests`: non-test definitions ignored, class-nested tests counted, empty file, missing file → `test_count_tests_*`.
- `count_agents` / `count_skills`: real 39/40 and empty tree → `test_count_agents_and_skills`.
- `measure`: shape, determinism, unavailable list and no numeric duration → `test_measure_is_deterministic_and_lists_unavailable_metrics`.
- `ratio`: normal and zero denominator → `test_ratio_stores_parts_and_handles_zero_denominator`.
- `record`: unknown source, blank metric, four denied metrics, target labelling and no cross-source averaging → `EvidenceTests.test_record_*`, `test_unavailable_metrics_never_become_numeric`, `test_target_values_*`, `test_evidence_rows_*`.
- `first_attempt`: five separate success and fail-closed cases → `test_first_attempt_is_fail_closed`.
- Boundary: no production import and no process/network/timing/tokeniser source API → `BoundaryTests.test_*`.
No pragma, omit, skip, exclusion or threshold change.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with five Required gaps; revised plan re-reviewed GO).
- (pending) aurevia-code-reviewer: —
- (pending) aurevia-coverage-auditor: —
- (pending) aurevia-devsecops: —
