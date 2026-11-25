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
