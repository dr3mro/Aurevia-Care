- [-] ORCA-CORE-MECHANISMS · Implement one offline deterministic ORCA core library
  - agent: orca
  - parent: 01300000-ORCA-INFRASTRUCTURE-REFACTOR
  - requirements: ORCA-015,016,017,018,021,070,080,093,128,145 (analysis helpers only; runtime capability still blocked where noted)
  - scope: scripts/orca_core.py, scripts/tests/test_orca_core.py, docs/orca-refactor/03-core-mechanisms.md
  - details: Owner chose the offline-core mode on 2026-11-24, recorded in `MEMORY.md` by the orchestrator. Build one standard-library Python analysis module with task-contract validation, context compilation under budgets with a manifest, preflight analysis, readiness scoring, a task-scoped blackboard, evidence freshness and checkpoint I/O. Routing, scheduling and lifecycle remain owned by `scripts/route-task.py`, `scripts/swarm.py` and native OpenCode. Never launch agents or write swarm state. Document analysis helper versus runtime capability per ORCA id. Every added line and branch needs a named test; no pragma, skip or threshold change.

## Loaded skills
- aurevia-delivery — mandatory story loop.
- aurevia-senior-python — standard-library Python module in `scripts/`.
- aurevia-test-engineer — red tests first, branch-to-test map.
- aurevia-plan-reviewer — plan before code.
- aurevia-code-reviewer, aurevia-coverage-auditor, aurevia-devsecops — verdicts after green tests.

## Plan (revised after plan-reviewer NO-GO)
1. Scope/authority: this module is an **offline analysis library only**. It never launches agents, never writes task state used by the swarm, and does not replace native OpenCode, `scripts/swarm.py`, `scripts/route-task.py` or `scripts/agent-run.sh` as the single runtime authority. Trackers are updated by the orchestrator outside this story's scope; the owner's offline-core mode is recorded in `MEMORY.md` with that explicit boundary.
2. Reuse before invention: drop `route_skills` and dependency resolution because `scripts/route-task.py:24-70` already owns scope-to-specialist routing; drop `schedule` and `can_transition` because dependency scheduling and lifecycle belong to `scripts/swarm.py` and native OpenCode. Keep only helpers with no current owner: `TaskContract` validation, `compile_context` (content budgets and manifest), `readiness` scoring, `preflight` analysis, `Blackboard`, `evidence_fresh`, `save_checkpoint`/`load_checkpoint`.
3. Honesty split in `docs/orca-refactor/03-core-mechanisms.md`: for each ORCA id, distinguish "analysis mechanism provided" from "runtime capability still blocked". ORCA-020, 021, 070, 105, 130 and 139 remain runtime-blocked even though the module offers analysis helpers. ORCA-028 is already `[x]` via generated config and is not claimed here.
4. Structural boundary test: assert no production script under `scripts/*.py` imports `orca_core`, and that the module source contains no `subprocess`, agent-launch or Git API usage. A reviewer can trace import → assertion without any runtime wiring.
5. Red first: write `scripts/tests/test_orca_core.py` before the module. Named tests cover invalid contract fields (each field), cyclic skill-free dependency data is not used, budget overrun and mandatory sections, readiness low/mandatory-fail/high, preflight missing and present inputs, illegal/legal blackboard entries, stale and fresh evidence, and missing/corrupt checkpoint files. Use synthetic data and injected `Path` boundaries only; no clock or network.
6. Gates: targeted `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` (red then green); full suite; `REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` with its real final line recorded (the gate diffs against `origin/main`, which this runtime cannot supply, so any skip/failure is reported honestly); `python3 -I scripts/check-constraints.py`, `python3 -I scripts/check-docs.py`, `python3 -I scripts/check-names.py`. Map every new line and branch to a named test under `## Coverage`. No pragma, skip, exclusion or threshold change.
7. After green, the orchestrator updates both checklists (`tasks/01300000-...` and `orca-refactor.md`) as separate tracker work and records the owner mode in `MEMORY.md`.

## Evidence log
- (tool) Red: `python3 -I -m unittest discover -s scripts/tests -p 'test_orca_core*.py' -q` → `Ran 1 test`, `FAILED (errors=1)` (module absent). Green after implementation: `Ran 23 tests in 0.002s`, `OK`.
- (tool) Full suite: `python3 -I -m unittest discover -s scripts/tests -q` → `Ran 1768 tests in 109.949s`, `OK`.
- (tool) Module coverage: `python3 -m coverage run --branch --source=scripts` with the targeted tests, then `coverage report --include='scripts/orca_core.py' -m` → 96 statements, 0 missed, 48 branches, 0 partial, 100%.
- (tool) `bash scripts/python-coverage.sh` exit 1 with verbatim final line `python coverage: 9 uncovered; add tests for them in scripts/tests`; `REQUIRE_PY_COVERAGE=1 bash scripts/python-coverage.sh` exit 1 with verbatim final line `python coverage: 16 uncovered; add tests for them in scripts/tests`. All findings were pre-existing changed lines in `scripts/swarm.py` relative to a resolved but stale `origin/main` ref; this session cannot fetch or verify that ref, so the descriptions "base unavailable" would be inaccurate. No `scripts/orca_core.py` line or branch was reported. Trusted-integrator hand-off required; no gate was weakened.
- (tool) `check-constraints.py`, `check-docs.py`, `check-names.py` exit 0.
- (tool) Two unreachable branches in `readiness()` were deleted rather than covered: acceptance and risk are guaranteed by the mandatory gate above them, so their points are now unconditional.

## Coverage (branch-to-test map)
`python3 -m coverage run --branch --source=scripts` with `test_orca_core.py`, then `coverage report --include='scripts/orca_core.py' -m`: 96 statements, 0 missed; 48 branches, 0 partial; 100%. Every conditional below is taken both ways by the named tests.

| Production branch | Test(s) taking both sides |
|---|---|
| `TaskContract.problems`: task_id, objective, acceptance, scope, risk checks | `ContractTests.test_a_valid_contract_has_no_problems`, `test_each_required_field_is_reported`, `test_an_unknown_risk_is_reported` |
| `compile_context`: mandatory source missing / present | `ContextTests.test_missing_sources_are_skipped`, `test_mandatory_sections_are_never_truncated` |
| `compile_context`: mandatory vs optional section | `ContextTests.test_mandatory_sections_are_never_truncated`, `test_optional_sections_respect_the_budget` |
| `compile_context`: `limit <= 0` true / false | `ContextTests.test_a_zero_budget_keeps_the_whole_optional_section`, `test_optional_sections_respect_the_budget` |
| `compile_context`: manifest and stable hash | `ContextTests.test_manifest_records_the_task_and_a_stable_hash` |
| `readiness`: mandatory gate true / false | `ReadinessTests.test_missing_acceptance_or_bad_risk_fails_the_gate` and all scoring tests |
| `readiness`: objective, scope, requirements, depends, assumptions each true / false | `test_a_full_contract_scores_100`, `test_objective_and_scope_are_scored_independently`, `test_missing_optional_fields_lower_the_score` |
| `preflight`: task id guard true / false | `PreflightTests.test_missing_inputs_are_reported`, `test_all_present_inputs_pass` |
| `preflight`: tool, instruction and skill loops each true / false | `PreflightTests.test_missing_inputs_are_reported`, `test_all_present_inputs_pass` |
| `Blackboard.publish`: blank evidence true / false | `BlackboardTests.test_blank_evidence_is_rejected`, `test_publish_and_filter_entries` |
| `Blackboard.publish`: confidence out of range true / false | `test_confidence_out_of_range_is_rejected`, `test_publish_and_filter_entries` |
| `Blackboard.entries`: no filter vs filter match/mismatch | `BlackboardTests.test_publish_and_filter_entries` |
| `evidence_fresh`: equal / different revisions | `FreshnessTests.test_fresh_and_stale_revisions` |
| `save_checkpoint` / `load_checkpoint`: parent creation and round trip | `CheckpointTests.test_round_trip_creates_parent_and_restores_state` |
| `load_checkpoint`: missing and corrupt files | `CheckpointTests.test_a_missing_checkpoint_raises`, `test_a_corrupt_checkpoint_raises` |
| Structural boundary: no production import and no launch/Git API | `BoundaryTests.test_no_production_script_imports_orca_core`, `test_module_has_no_launch_or_git_api` |

No pragma, omit, skip, exclusion or threshold change. Two branches in `readiness()` for acceptance and risk were deleted as unreachable after the mandatory guard rather than covered by synthetic tests.

## Review verdicts
- aurevia-plan-reviewer: GO (first NO-GO with seven Required gaps; revised plan re-reviewed GO).
- (pending) aurevia-code-reviewer: —
- (pending) aurevia-coverage-auditor: —
- (pending) aurevia-devsecops: —
