# ORCA core mechanisms — worker notes

## Summary
Offline analysis library `scripts/orca_core.py`; 24 tests green; full suite green on the final verbose rerun after two flaky TokenTests errors; module 100% line and branch coverage. Analysis only — no runtime orchestration.

## Test evidence
- `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` → `Ran 24 tests in 0.002s`, `OK`.
- `python3 -I -m unittest discover -s scripts/tests -q` → verbose rerun `Ran 1769 tests in 302.654s`, `OK`; two earlier runs failed on a flaky `TokenTests` patch error.
- `bash scripts/python-coverage.sh` → `python coverage: 9 uncovered; add tests for them in scripts/tests` (pre-existing `scripts/swarm.py` lines against a stale resolved `origin/main`; no `orca_core.py` finding).
- `check-constraints.py`, `check-docs.py`, `check-names.py` exit 0.

## Coverage
`coverage report --include='scripts/orca_core.py' -m`: 96 statements, 0 missed; 48 branches, 0 partial; 100%. Branch-to-test map:
- `TaskContract.problems` field checks → `test_a_valid_contract_has_no_problems`, `test_each_required_field_is_reported`, `test_an_unknown_risk_is_reported`.
- `compile_context` missing source, mandatory vs optional, zero/unlimited budget, stable hash → `test_missing_sources_are_skipped`, `test_mandatory_sections_are_never_truncated`, `test_a_zero_budget_keeps_the_whole_optional_section`, `test_optional_sections_respect_the_budget`, `test_manifest_records_the_task_and_a_stable_hash`.
- `readiness` mandatory gate and five scored fields → `test_missing_acceptance_or_bad_risk_fails_the_gate`, `test_objective_and_scope_are_scored_independently`, `test_a_full_contract_scores_100`, `test_missing_optional_fields_lower_the_score`.
- `preflight` task id, tool, instruction and skill checks → `test_missing_inputs_are_reported`, `test_all_present_inputs_pass`.
- `Blackboard.publish` blank evidence and confidence bounds; `entries` filter → `test_blank_evidence_is_rejected`, `test_confidence_out_of_range_is_rejected`, `test_publish_and_filter_entries`.
- `evidence_fresh`, checkpoint round trip, missing/corrupt files, structural boundary → `test_fresh_and_stale_revisions`, `test_round_trip_creates_parent_and_restores_state`, `test_a_missing_checkpoint_raises`, `test_a_corrupt_checkpoint_raises`, `test_no_production_script_imports_orca_core`, `test_module_has_no_launch_or_git_api`.

## Review verdicts
- aurevia-plan-reviewer: GO (after NO-GO and revision).
- aurevia-code-reviewer: pending recheck after Required coverage-map addition.
- aurevia-coverage-auditor: pending recheck after exact gate-line correction and notes file.
- aurevia-devsecops: GO.

## Findings
Runtime capabilities ORCA-020/021/070/105/130/139 remain blocked; the library does not replace native OpenCode, swarm.py or route-task.py.

## Lessons for workers
A "changed-line coverage gate" must be reported with its verbatim final line and a truthful description of whether the base ref resolved.
